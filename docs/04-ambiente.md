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
| Mailpit di `compose.yaml`, SMTP (solo su `127.0.0.1`) | **1026** | 1025 |
| Mailpit di `compose.yaml`, pagina web (solo su `127.0.0.1`) | **8026** | 8025 |
| App con il profilo `dev` (`bootRun`) | **8081** | 8080 |

Database, utente e password di sviluppo sono `talita` (solo dati sintetici). Il volume di compose è
diverso da quello di Koinè, perché il nome del progetto compose è quello della cartella. Le porte
1025 e 8025 restano all'altro progetto: dentro il container Mailpit usa le sue solite, e `compose.yaml`
le pubblica come 1026 e 8026.

## Posta

Talità manda due tipi di mail (il link per entrare e l'invito a un maestro), solo testo, con un
server SMTP configurabile. Mai un servizio vero in sviluppo e nei test.

- **Sviluppo**: `./gradlew bootRun` avvia anche **Mailpit** (`compose.yaml`, immagine con la versione
  esatta, la stessa dei test). Il profilo `dev` gli manda tutta la posta e non esce niente verso
  server veri. Le mail si leggono su <http://localhost:8026>. Il profilo `dev` imposta anche il
  mittente, l'indirizzo pubblico (`http://localhost:8081`, da cui partono i link) e il primo maestro
  (`nome@example.org`): per entrare, si chiede il link a `/accedi` con quell'indirizzo e si apre la
  mail in Mailpit.
- **Test**: ogni test, unitario o e2e, che manda una mail la manda a un Mailpit di Testcontainers
  (`MailpitContainer`, stessa versione di `compose.yaml`) e ne legge destinatario, oggetto e testo
  dall'API HTTP (`Mailbox`). Gli indirizzi dei test li costruisce `Addresses`: nei file tracciati
  l'unico indirizzo ammesso è `nome@example.org`.
- **Produzione**: Mailjet (`in-v3.mailjet.com`, porta 587, STARTTLS obbligatorio), con mittente sul
  dominio di Talità e il tracciamento di aperture e clic spento (le intestazioni
  `X-Mailjet-TrackOpen: 0` e `X-Mailjet-TrackClick: 0` ci sono già su ogni mail). Account, dominio e
  valori veri arrivano con TALITA-9. Qui c'è solo il segnaposto: nel repo non c'è nessun host, utente,
  password o mittente.
- **Senza posta l'app parte lo stesso**: senza `SPRING_MAIL_HOST` (come nello smoke test) il log
  avvisa che la posta non è configurata e chi chiede un link vede che l'invio non è riuscito. La posta
  non fa parte di `/actuator/health`: un server di posta fermo non rende «giù» l'app.
- **Limiti** (valori nella configurazione, contatori solo in memoria): al massimo 150 mail ogni 24
  ore, link e inviti insieme (`talita.mail.daily-limit`: il piano gratuito di Mailjet ne manda 200).

## Variabili d'ambiente

Quelle che il server deve impostare (TALITA-8 le mette al loro posto; qui i nomi e un valore
d'esempio, mai quelli veri):

```
SPRING_MAIL_HOST=in-v3.mailjet.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=chiave-pubblica-di-mailjet
SPRING_MAIL_PASSWORD=chiave-segreta-di-mailjet
TALITA_MAIL_FROM=nome@example.org
TALITA_PUBLIC_URL=https://talita.example.org
TALITA_BOOTSTRAP_TEACHER=nome@example.org
TALITA_PRIVACY_CONTROLLER_NAME=Titolare di esempio
TALITA_PRIVACY_CONTACT=nome@example.org
```

- `SPRING_MAIL_*`: il server SMTP. Con STARTTLS obbligatorio e timeout di 10 secondi
  (`application.yaml`); nome e password sono quelli dell'account Mailjet.
- `TALITA_MAIL_FROM`: il mittente, sul dominio di Talità (il nome mostrato è «Talità»).
- `TALITA_PUBLIC_URL`: l'indirizzo del sito, senza barra finale. I link delle mail partono sempre da
  qui e mai dall'intestazione `Host` della richiesta, che chi attacca può falsificare. Senza, le mail
  con un link non partono.
- `TALITA_BOOTSTRAP_TEACHER`: l'indirizzo del primo maestro. Solo se non c'è nessun maestro, all'avvio
  l'app crea un invito per quell'indirizzo (senza mail): la persona entra da `/accedi`, trova «Accetta
  l'invito», scrive nome e cognome ed è maestro. L'invito vale 7 giorni, come ogni altro
  (`talita.teacher.invitation-validity`): a ogni avvio, finché non c'è nessun maestro, si rinnova. Con almeno
  un maestro la variabile non fa niente; senza variabile e senza maestri l'app parte lo stesso e il log
  avvisa che nessuno può essere invitato. In sviluppo il profilo `dev` la imposta a `nome@example.org`.
- `TALITA_PRIVACY_CONTROLLER_NAME` e `TALITA_PRIVACY_CONTACT` (`talita.privacy.controller-name` e `talita.privacy.contact`): chi è il titolare
  del trattamento e a quale indirizzo scrivergli, come li dicono l'informativa breve del passo 2 dell'iscrizione e quella completa a
  `/informativa`. **Non hanno un valore di default e sono obbligatorie: senza, l'app non parte** (l'errore nomina le due variabili, anche se ne manca una sola), così un'istanza di
  produzione non può dire ai genitori che il titolare è un segnaposto. I segnaposto «Titolare di esempio» e `nome@example.org` stanno solo nel profilo `dev`
  e nei test; i valori veri arrivano con TALITA-9 (`docs/03-privacy.md`). Lo smoke test del jar prova che senza le variabili l'app non parte e che con
  le variabili l'informativa completa nomina il titolare. Un altro test (`./gradlew build -Prelease`) non lascia uscire un rilascio con i testi ancora
  di prova.

## Il job della conservazione dei dati

L'applicazione cancella da sola i dati scaduti (TALITA-7, `docs/03-privacy.md`): un job gira **a ogni avvio** (quando l'applicazione è pronta) e **ogni notte alle 4:15 di Roma**, senza profili e senza variabili da impostare. Il log dice
solo le regole e i numeri (`Retention: rule notes deleted 12 rows.`), mai un nome o un indirizzo, e un errore dice il nome della regola e il tipo dell'eccezione. In sviluppo (profilo `dev`) gira allo stesso modo, sul database di sviluppo, con dati sintetici.
Nei test dell'applicazione è spento (`talita.retention.automatic=false`, impostato da `TestcontainersConfiguration`): ogni test lo chiama a mano con un `Clock` fisso, così non cancella da solo sotto gli altri test. Lo smoke test del jar controlla che il log dell'avvio
dica che il job ha girato. Il backup del giorno deve partire **dopo** le 4:15 (`docs/03-privacy.md`, «Backup: requisiti per TALITA-8»).

## Il file del menù e il limite delle richieste

Il menù della convivenza è un file (PDF, JPEG o PNG) che i maestri caricano (TALITA-12, `docs/03-privacy.md`). **Nessuna variabile nuova**: i valori sono in `application.yaml`.

- **Limite del file: 10 MB**, controllato dall'app (`MenuFile.MAX_BYTES`, una costante sola: la usano il controllo, l'aiuto sotto il campo e il vincolo della tabella).
- **Il contenitore** prende richieste fino a **20 MB** (`spring.servlet.multipart.max-file-size` e `max-request-size`; prima valevano quelli di Spring, 1 e 10 MB), così un file troppo grande per l'app riceve una risposta chiara dalla pagina. Una richiesta più grande non si legge: Tomcat lancia, e il filtro `TooLargeUploadFilter` riporta la persona alla pagina del menù. `server.tomcat.max-swallow-size` è **100 MB**: Tomcat legge e butta via il resto di una richiesta troppo grande, e il browser riceve la risposta invece di una connessione chiusa. Oltre i 100 MB il browser può vedere un errore suo (accettato).
- **Per il deploy (TALITA-8)**: il proxy davanti all'app deve lasciar passare richieste di **almeno 20 MB** (nginx, senza configurazione, si ferma a 1 MB: `client_max_body_size`); con un limite più basso un file da 15 MB non arriva all'app e la persona vede l'errore del proxy.
- **Sviluppo e test**: il file sta nella tabella `gathering_menu` come ogni altro dato; i file di prova dei test si costruiscono in memoria (`MenuSamples`), mai nel repo (la build rifiuta PDF e immagini tracciati), e i test dei file oltre i 20 MB usano un server vero (`MenuOversizeTests`, Testcontainers e un client HTTP) o il browser (`FilePayload`), perché MockMvc non ha Tomcat.

## Build, sviluppo e test

- **Build completa**: `./gradlew build` (migrazioni, generazione jOOQ, template JTE, CSS di Tailwind,
  test unitari ed e2e). Poi `scripts/smoke-test-jar.sh`: avvia il jar come in produzione, senza
  profili, senza posta e con un Postgres usa e getta, e controlla `GET /`, il CSS, htmx, `app.js` e i font.
  Avvia il jar con il `java` del PATH, che deve essere il JDK 25 (il jar è compilato per la 25):
  sul PC di Matteo il `java` di sistema è più vecchio, quindi
  `JAVA=~/.gradle/jdks/<jdk 25>/bin/java scripts/smoke-test-jar.sh`; in CI c'è già il 25.
- **Sviluppo**: `./gradlew bootRun` (profilo `dev`, Postgres e Mailpit di `compose.yaml`; i template `.jte`
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
