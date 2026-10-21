# Talità

Iscrizioni e organizzazione della convivenza dei bambini: pagina informativa per i genitori, iscrizione che è anche la manleva, check-in dal telefono e stampe per il giorno dell'evento. Kotlin, Spring Boot, JTE, htmx e Tailwind, con Postgres. Una convivenza all'anno, 30-35 persone.

Nasce da Koinè, con cui condivide stack e design system, ma è un'app separata. Il contesto, le regole e il flusso di lavoro sono in [`CLAUDE.md`](CLAUDE.md); i documenti in [`docs/`](docs/).

## Prerequisiti

- Linux x64 (il binario di Tailwind ha il checksum solo per questa piattaforma).
- Un JDK 17 o più recente per avviare Gradle. Il JDK 25 con cui si compila lo scarica da sola la toolchain.
- Docker: la build genera il codice jOOQ da un Postgres usa e getta, i test usano Testcontainers e lo sviluppo parte da `compose.yaml`. L'utente deve poter usare Docker.
- La rete, alla prima build: Tailwind (circa 110 MB) e Chromium per gli e2e. Niente Node né npm.

## Build e test

```bash
./gradlew build            # migrazioni, jOOQ, template, CSS, test unitari ed e2e (telefono e desktop)
scripts/smoke-test-jar.sh  # avvia il jar come in produzione e controlla la home
```

Per i soli test unitari: `./gradlew test`; per i soli e2e: `./gradlew e2eTest`. Se la sessione non vede ancora il gruppo `docker`: `sg docker -c "./gradlew build"`.

## Sviluppo

In due terminali:

```bash
./gradlew tailwindWatch   # ricompila il CSS a ogni modifica di un template
./gradlew bootRun         # avvia Postgres da compose.yaml (porta 5433) e l'app sulla porta 8081
```

Poi <http://localhost:8081>. Il profilo `dev` ricarica i template senza riavviare. Solo dati sintetici.

## Licenza

[AGPL-3.0](LICENSE). Le licenze di Inter, Lucide e htmx sono accanto ai loro file in `src/main/resources/static/`.
