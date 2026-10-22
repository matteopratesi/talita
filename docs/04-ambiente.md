# Ambiente di sviluppo

Come si lavora su Talità da un PC Linux. Il PC di Matteo è lo stesso su cui gira Koinè (Ubuntu
24.04 LTS, utente separato, solo account e servizi personali): le differenze sono in fondo.

## Prerequisiti

- **Linux x64.** Il binario di Tailwind ha il checksum solo per `linux-x64`; la build per ARM si
  decide in TALITA-8.
- **Un JDK 17 o più recente** per avviare Gradle. Il JDK 25 con cui si compila lo scarica da sola la
  toolchain di Gradle (`foojay-resolver`), nella cache di Gradle, se manca.
- **Docker**, con l'utente che può usarlo (gruppo `docker`): la build genera il codice jOOQ da un
  Postgres usa e getta, i test usano Testcontainers e lo sviluppo parte da `compose.yaml`. Il gruppo
  `docker` equivale ai permessi di root sul PC.
- **La rete, alla prima build** (vedi le cache sotto). Niente Node né npm: non servono e un test
  controlla che non ci siano.
- `git`, e `gh` per le PR e per leggere l'identità dell'account GitHub.

## Docker e `sg docker`

Se la sessione è stata aperta prima che l'utente entrasse nel gruppo `docker`, non lo vede finché non
si esce e si rientra. Fino ad allora:

```bash
sg docker -c "./gradlew build"
```

Docker assente o senza permessi: `generateJooq` e i test falliscono, con un errore sul socket.

## Porte

Così Koinè e Talità girano insieme sullo stesso PC senza scontrarsi:

| Cosa | Talità | Koinè |
|---|---|---|
| Postgres di `compose.yaml` (solo su `127.0.0.1`) | **5433** | 5432 |
| App con il profilo `dev` (`bootRun`) | **8081** | 8080 |

Database, utente e password di sviluppo sono `talita` (solo dati sintetici). Il volume di compose è
diverso da quello di Koinè, perché il nome del progetto compose è quello della cartella.

## Build, sviluppo e test

- **Build completa**: `./gradlew build` (migrazioni, generazione jOOQ, template JTE, CSS di Tailwind,
  test unitari ed e2e). Poi `scripts/smoke-test-jar.sh`: avvia il jar come in produzione, senza
  profili e con un Postgres usa e getta, e controlla `GET /`, il CSS, htmx, `app.js` e i font.
  Avvia il jar con il `java` del PATH, che deve essere il JDK 25 (il jar è compilato per la 25):
  sul PC di Matteo il `java` di sistema è più vecchio, quindi
  `JAVA=~/.gradle/jdks/<jdk 25>/bin/java scripts/smoke-test-jar.sh`; in CI c'è già il 25.
- **Sviluppo**: `./gradlew bootRun` (profilo `dev`, Postgres di `compose.yaml`; i template `.jte`
  si ricaricano da `src/main/jte` a ogni richiesta, i file Kotlin richiedono il riavvio) e, in un
  secondo terminale, `./gradlew tailwindWatch` (rigenera il CSS quando cambia un template o
  `src/main/css/app.css`). Poi <http://localhost:8081>.
- **Classi Tailwind**: nei template si scrivono sempre intere; classi costruite in Kotlin non
  vengono viste e lo stile manca senza errori.
- **Font e htmx**: file fissi in `src/main/resources/static/`, con licenza e provenienza accanto. Non
  si scaricano in build.

## Cache

- **Binario di Tailwind** (circa 110 MB, da GitHub): la prima build lo scarica in
  `~/.gradle/caches/talita-tools/tailwindcss/v<versione>/`, fuori da `build/`, quindi `clean` non lo
  cancella. È **separato da quello di Koinè**: la prima build di Talità lo scarica di nuovo. A ogni
  build se ne confronta lo SHA-256 con quello scritto in `build.gradle.kts` (`tailwindChecksums`): se
  non corrisponde, la build si ferma senza eseguirlo. Per cambiare versione si aggiornano
  `tailwindVersion` e il checksum dalla pagina della release. Un download fermo (la connessione
  resta aperta ma non arriva più niente) si interrompe da solo dopo 15 minuti: la build fallisce
  con «Timeout has been exceeded» sul task `downloadTailwind`, senza lasciare file a metà, e basta
  rilanciarla. Con una connessione molto lenta (sotto circa 1 Mbit/s i 15 minuti non bastano) si
  può copiare il file `tailwindcss-linux-x64`, preso dalla pagina della release, nella cartella
  della cache: la build ne controlla comunque lo SHA-256. In CI il job `build` si ferma comunque
  dopo 30 minuti (`timeout-minutes`).
- **Chromium di Playwright** (circa 650 MB, in `~/.cache/ms-playwright`, condiviso con ogni progetto
  che usa la stessa versione di Playwright): `./gradlew playwrightInstall`, e `e2eTest` lo lancia da
  solo. Senza `--with-deps`, perché sul PC di Matteo `apt install` non funziona oggi (un pacchetto
  rimasto a metà, che non riguarda il progetto): Chromium parte lo stesso. In CI si usa
  `-PplaywrightWithDeps`, con la cache di `~/.cache/ms-playwright` per versione di Playwright.
- **Prima build senza rete**: Tailwind e Chromium non si scaricano e il messaggio dice di che cosa si
  tratta; con i due file già nella cache la build non usa la rete.

## Test end-to-end

`./gradlew e2eTest` (parte di `./gradlew build`) apre un Chromium vero, headless, in due profili:
telefono (360×800, touch) e desktop (1280×800). Le tracce e gli screenshot dei test falliti restano
sotto `build/e2e-artifacts/<Classe>/<test>-<profilo>/`; in CI sono nell'artifact `e2e-failures`
della run (7 giorni). Per guardarne uno:
`./gradlew playwrightShowTrace -Ptrace=build/e2e-artifacts/<…>/trace.zip`.

Il modello per un test nuovo è `PageBasicsE2eTest` in `src/e2eTest/kotlin/talita/e2e/`.

## Identità git

Il repo è pubblico: autore e committer dei commit hanno l'indirizzo noreply dell'account GitHub di
Matteo, impostato **solo nel repo** (`git config` locale, non globale):

```bash
git config user.name "Matteo Pratesi"
git config user.email "<id>+<utente>@users.noreply.github.com"   # id e utente da: gh api user
```

Prima di ogni commit: `git config user.email`. Mai la mail personale né quella di lavoro.

## Differenze dal PC configurato per Koinè

- **Identità git**: per Koinè è impostata a livello globale con la mail personale; in Talità la
  riga locale del repo prevale e usa l'indirizzo noreply, perché il repo è pubblico.
- **Porte, nome del database e cache di Tailwind** sono diversi (sopra), per poter avere i due progetti
  accesi insieme.
- **La memoria di Claude Code dipende dalla cartella**: in `~/dev/talita` non c'è niente di quello che
  per Koinè sta in memoria (identità git personale, flusso delle PR, UI moderna, documenti veri della
  convivenza da non aprire). Per questo sta tutto in `CLAUDE.md`.
- **Documenti veri**: il PC può avere documenti veri delle convivenze (manleve, elenchi), fuori dal
  repo. Non si aprono, non si cercano e non si copiano mai nelle sessioni (regola 1 di `CLAUDE.md`).
- **Repo pubblico**: nel repo non entrano indirizzi email, nomi di parrocchie o della struttura,
  persone vere, date reali; la build lo controlla in parte (PDF, documenti Office e immagini
  fuori posto).
- **Destinazione del deploy**: Oracle A1 (ARM): per una build fatta sulla macchina ARM serve il
  checksum `linux-arm64` di Tailwind, oppure un jar compilato in CI (TALITA-8).
