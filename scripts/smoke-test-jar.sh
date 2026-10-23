#!/usr/bin/env bash
# Smoke test of the production jar (from KOINE-11, criterion 3).
#
# Starts the jar produced by `./gradlew bootJar` with `java -jar`, from a folder outside the
# repository, without profiles, without a mail server and with a throwaway Postgres, then checks
# that `GET /` (which asks for a login) leads to `/accedi`, and that `/accedi` answers 200 with the
# page rendered by the precompiled template. It proves that the template classes (see
# https://github.com/casid/jte/issues/438) and the static files (CSS built by Tailwind, htmx, fonts)
# really end up in the jar, that the migrations ran, and that the application starts without mail.
# It also proves that the jar does not start without the controller of the data (TALITA_PRIVACY_CONTROLLER_NAME and
# TALITA_PRIVACY_CONTACT, which have no default: a production instance cannot show the placeholders of the notices), and
# that with them the complete notice names the controller.
#
# Needs Docker (Postgres) and curl. JAVA can point to a specific `java` binary.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="$(ls "$repo"/build/libs/talita-*-SNAPSHOT.jar 2>/dev/null | grep -v -- '-plain' | head -n 1 || true)"
java_bin="${JAVA:-java}"

if [[ -z "$jar" ]]; then
  echo "Jar not found in build/libs: run ./gradlew bootJar first." >&2
  exit 1
fi

workdir="$(mktemp -d)"
container=""
app_pid=""

cleanup() {
  if [[ -n "$app_pid" ]]; then kill "$app_pid" 2>/dev/null || true; fi
  if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null 2>&1 || true; fi
  rm -rf "$workdir"
}
trap cleanup EXIT

free_port() {
  python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'
}

db_port="$(free_port)"
app_port="$(free_port)"

echo "Starting Postgres on port $db_port"
container="$(docker run -d --rm \
  -e POSTGRES_DB=talita -e POSTGRES_USER=talita -e POSTGRES_PASSWORD=talita \
  -p "127.0.0.1:$db_port:5432" postgres:18)"

for _ in $(seq 1 60); do
  if docker exec "$container" pg_isready -U talita -d talita >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$container" pg_isready -U talita -d talita >/dev/null

cd "$workdir"

# Without the controller of the data the application must not start: the log names the two variables and nothing answers on the port.
# (The process may stay alive after a failed start, because a thread of the login links is not a daemon one: it is stopped here, not waited for.)
echo "Starting $jar without the controller of the data: it must not start"
# `env -u` removes the two variables even if the caller exported them in the shell: the check must not depend on the caller's environment.
env -u TALITA_PRIVACY_CONTROLLER_NAME -u TALITA_PRIVACY_CONTACT \
SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$db_port/talita" \
SPRING_DATASOURCE_USERNAME=talita \
SPRING_DATASOURCE_PASSWORD=talita \
SERVER_PORT="$app_port" \
  "$java_bin" -jar "$jar" >"$workdir/no-controller.log" 2>&1 &
app_pid=$!
for _ in $(seq 1 90); do
  if grep -q "APPLICATION FAILED TO START" "$workdir/no-controller.log" || ! kill -0 "$app_pid" 2>/dev/null; then break; fi
  sleep 1
done
sleep 2
if ! grep -q "APPLICATION FAILED TO START" "$workdir/no-controller.log" || ! grep -q "TALITA_PRIVACY_CONTROLLER_NAME" "$workdir/no-controller.log" \
  || curl -s -o /dev/null "http://127.0.0.1:$app_port/accedi"; then
  echo "The application did not refuse to start for the missing controller of the data:" >&2
  tail -n 40 "$workdir/no-controller.log" >&2
  exit 1
fi
kill "$app_pid" 2>/dev/null || true
wait "$app_pid" 2>/dev/null || true
app_pid=""

echo "Starting $jar from $workdir"
SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$db_port/talita" \
SPRING_DATASOURCE_USERNAME=talita \
SPRING_DATASOURCE_PASSWORD=talita \
SERVER_PORT="$app_port" \
TALITA_PRIVACY_CONTROLLER_NAME="Titolare della prova" \
TALITA_PRIVACY_CONTACT="nome@example.org" \
  "$java_bin" -jar "$jar" >"$workdir/app.log" 2>&1 &
app_pid=$!

# GET / asks for a login: it leads to the access page (a 302, which curl does not follow here).
status=""
for _ in $(seq 1 90); do
  if ! kill -0 "$app_pid" 2>/dev/null; then
    echo "The application stopped on its own:" >&2
    tail -n 40 "$workdir/app.log" >&2
    exit 1
  fi
  status="$(curl -s -o /dev/null -D "$workdir/root.headers" -w '%{http_code}' "http://127.0.0.1:$app_port/" || true)"
  if [[ "$status" == "302" ]]; then break; fi
  sleep 1
done

if [[ "$status" != "302" ]] || ! tr -d '\r' <"$workdir/root.headers" | grep -qi '^location: .*/accedi$'; then
  echo "GET / answered '$status' instead of a redirect to /accedi:" >&2
  cat "$workdir/root.headers" >&2 || true
  tail -n 40 "$workdir/app.log" >&2
  exit 1
fi

# The access page: 200, with its title and its heading (a jar of another project would not pass).
status="$(curl -s -o "$workdir/accedi.html" -w '%{http_code}' "http://127.0.0.1:$app_port/accedi" || true)"
if [[ "$status" != "200" ]]; then
  echo "GET /accedi answered '$status' instead of 200:" >&2
  tail -n 40 "$workdir/app.log" >&2
  exit 1
fi
if ! grep -q "<title>Accedi · Talità</title>" "$workdir/accedi.html" || ! grep -q "Accedi a Talità" "$workdir/accedi.html"; then
  echo "GET /accedi answered 200 but the page was not rendered by the template:" >&2
  head -c 600 "$workdir/accedi.html" >&2
  exit 1
fi

# The complete notice is public and names the controller that the environment gave (and never a placeholder).
status="$(curl -s -o "$workdir/informativa.html" -w '%{http_code}' "http://127.0.0.1:$app_port/informativa" || true)"
if [[ "$status" != "200" ]] || ! grep -q "Titolare della prova" "$workdir/informativa.html" || grep -q "Titolare di esempio" "$workdir/informativa.html"; then
  echo "GET /informativa answered '$status', and it does not name the controller of the environment:" >&2
  head -c 600 "$workdir/informativa.html" >&2
  exit 1
fi

# No profile and no spring.mail.host: the application starts without mail (health UP) and the log says so.
# Tomcat answers a little before the application has run its start-up tasks (the check of the mail, the first
# teacher): until then the readiness is still "refusing traffic" and the health is OUT_OF_SERVICE, so it is asked
# again for up to 30 seconds.
health=""
for _ in $(seq 1 30); do
  health="$(curl -s "http://127.0.0.1:$app_port/actuator/health" || true)"
  if grep -q '"status":"UP"' <<<"$health"; then break; fi
  sleep 1
done
if ! grep -q '"status":"UP"' <<<"$health"; then
  echo "GET /actuator/health did not answer UP: $health" >&2
  tail -n 40 "$workdir/app.log" >&2
  exit 1
fi
if ! grep -q "Mail is not configured" "$workdir/app.log"; then
  echo "The log does not warn that the mail is not configured:" >&2
  tail -n 40 "$workdir/app.log" >&2
  exit 1
fi

# The static files that the page points to are in the jar too, with their fingerprint in the URL.
for pattern in '/css/app-[0-9a-f]*\.css' '/js/htmx\.min-[0-9a-f]*\.js' '/js/app-[0-9a-f]*\.js' '/fonts/inter-latin-[0-9a-f]*\.woff2'; do
  asset="$(grep -o "$pattern" "$workdir/accedi.html" | head -n 1 || true)"
  if [[ -z "$asset" ]]; then
    echo "The access page does not reference a file like $pattern" >&2
    exit 1
  fi
  asset_status="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$app_port$asset")"
  if [[ "$asset_status" != "200" ]]; then
    echo "GET $asset answered '$asset_status' instead of 200" >&2
    exit 1
  fi
done

echo "OK: GET / leads to /accedi, which answers 200 with the page rendered by the precompiled template, and its static files are served."
