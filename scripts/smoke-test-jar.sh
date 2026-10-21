#!/usr/bin/env bash
# Smoke test of the production jar (from KOINE-11, criterion 3).
#
# Starts the jar produced by `./gradlew bootJar` with `java -jar`, from a folder outside the
# repository, without profiles and with a throwaway Postgres, then checks that `GET /` answers
# 200 with the page rendered by the precompiled template. It proves that the template classes
# (see https://github.com/casid/jte/issues/438) and the static files (CSS built by Tailwind, htmx,
# fonts) really end up in the jar.
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

echo "Starting $jar from $workdir"
cd "$workdir"
SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$db_port/talita" \
SPRING_DATASOURCE_USERNAME=talita \
SPRING_DATASOURCE_PASSWORD=talita \
SERVER_PORT="$app_port" \
  "$java_bin" -jar "$jar" >"$workdir/app.log" 2>&1 &
app_pid=$!

status=""
for _ in $(seq 1 90); do
  if ! kill -0 "$app_pid" 2>/dev/null; then
    echo "The application stopped on its own:" >&2
    tail -n 40 "$workdir/app.log" >&2
    exit 1
  fi
  status="$(curl -s -o "$workdir/home.html" -w '%{http_code}' "http://127.0.0.1:$app_port/" || true)"
  if [[ "$status" == "200" ]]; then break; fi
  sleep 1
done

if [[ "$status" != "200" ]]; then
  echo "GET / answered '$status' instead of 200:" >&2
  tail -n 40 "$workdir/app.log" >&2
  exit 1
fi

# The heading of the home and its title: a jar of another project would not pass.
if ! grep -q "Ti diamo il benvenuto" "$workdir/home.html" || ! grep -q "<title>Talità</title>" "$workdir/home.html"; then
  echo "GET / answered 200 but the page was not rendered by the template:" >&2
  head -c 600 "$workdir/home.html" >&2
  exit 1
fi

# The static files that the page points to are in the jar too, with their fingerprint in the URL.
for pattern in '/css/app-[0-9a-f]*\.css' '/js/htmx\.min-[0-9a-f]*\.js' '/js/app-[0-9a-f]*\.js' '/fonts/inter-latin-[0-9a-f]*\.woff2'; do
  asset="$(grep -o "$pattern" "$workdir/home.html" | head -n 1 || true)"
  if [[ -z "$asset" ]]; then
    echo "The home page does not reference a file like $pattern" >&2
    exit 1
  fi
  asset_status="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$app_port$asset")"
  if [[ "$asset_status" != "200" ]]; then
    echo "GET $asset answered '$asset_status' instead of 200" >&2
    exit 1
  fi
done

echo "OK: GET / answers 200 with the page rendered by the precompiled template, and its static files are served."
