# Talità

Talità aiuta a organizzare i ritiri spirituali dei bambini della parrocchia. Le famiglie trovano le informazioni e iscrivono i figli online, con l'autorizzazione dei genitori già compresa. Chi organizza segue iscrizioni e arrivi dal telefono e stampa il registro per il giorno del ritiro.

*An app for organizing spiritual retreats for parish children. The app and its documentation are in Italian.*

Kotlin, Spring Boot, JTE, htmx e Tailwind, con Postgres.

Il contesto, le regole e il flusso di lavoro sono in [`CLAUDE.md`](CLAUDE.md); i documenti in [`docs/`](docs/).

## Prerequisiti

- Linux x64 (il binario di Tailwind ha il checksum solo per questa piattaforma).
- Un JDK 17 o più recente per avviare Gradle. Il JDK 25 con cui si compila lo scarica da sola la toolchain.
- Docker: la build genera il codice jOOQ da un Postgres usa e getta, i test usano Testcontainers e lo sviluppo parte da `compose.yaml`. L'utente deve poter usare Docker.
- La rete, alla prima build: Tailwind (circa 110 MB) e Chromium per gli e2e. Niente Node né npm.

## Build e test

```bash
./gradlew build            # migrazioni, jOOQ, template, CSS, test unitari ed e2e (telefono e desktop)
scripts/smoke-test-jar.sh  # avvia il jar come in produzione e controlla la pagina di accesso
```

Lo smoke test avvia il jar con il `java` del PATH, che deve essere il JDK 25: se non lo è, `JAVA=<percorso di un java 25> scripts/smoke-test-jar.sh` (la toolchain di Gradle ne tiene uno in `~/.gradle/jdks/`).

Per i soli test unitari: `./gradlew test`; per i soli e2e: `./gradlew e2eTest`. Se la sessione non vede ancora il gruppo `docker`: `sg docker -c "./gradlew build"`.

## Sviluppo

In due terminali:

```bash
./gradlew tailwindWatch   # ricompila il CSS a ogni modifica di un template
./gradlew bootRun         # avvia Postgres e Mailpit da compose.yaml (porte 5433, 1026 e 8026) e l'app sulla porta 8081
```

Poi <http://localhost:8081>. Il profilo `dev` ricarica i template senza riavviare. Solo dati sintetici.
Si entra con un link che arriva per mail: in sviluppo la posta la prende Mailpit, che si legge su <http://localhost:8026> (il primo maestro è `nome@example.org`, vedi `docs/04-ambiente.md`).

## Licenza

[AGPL-3.0](LICENSE). Le licenze di Inter, Lucide e htmx sono accanto ai loro file in `src/main/resources/static/`.
