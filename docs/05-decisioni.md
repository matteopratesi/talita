# Decisioni

Registro delle decisioni di progetto, dalla più vecchia alla più recente. Il lavoro da fare sta
su Jira (progetto TALITA); qui resta il perché delle scelte, versionato con il codice.

Le prime voci sono ereditate da Koinè, l'applicazione da cui Talità nasce: riportano la data e
l'origine (registro delle decisioni di Koinè, commit `23ea63d`). I rimandi «KOINE-N» sono ai
ticket di Koinè.

## 2026-10-19: design system (KOINE-11)

Origine: registro delle decisioni di Koinè, voce «design system (KOINE-11)». Guida completa in
`docs/design/README.md`, mockup di origine in `docs/design/KOINE-11/`.

- **Font Inter 4.1** (OFL 1.1), servito dall'app in due sottoinsiemi woff2 (latino circa 48 KB,
  latino esteso solo quando serve): aspetto da app, ottima leggibilità a schermo, cifre tabulari,
  copre i nomi stranieri. Variante `cv08` accesa per distinguere I da l. Scartati Atkinson
  Hyperlegible Next (aspetto istituzionale) e i font di sistema.
- **Icone Lucide** (ISC), SVG inline nei template: nessun file in più da scaricare.
- **Colori come token semantici** con la palette di Tailwind spenta: blu profondo per le azioni,
  neutri caldi, contrasti verificati (testo principale oltre AAA).
- **Due layout**: telefono con navigazione in basso, desktop da 1024 px con navigazione a sinistra.
  Testo base a 17 px, bottoni alti 48 px.
- **Navigazione**: in Koinè quattro voci approvate il 19/10/2026; in Talità le voci sono quelle di
  TALITA-2 (sopra).

## 2026-10-19: spike JTE (KOINE-11), si resta su JTE

Origine: registro delle decisioni di Koinè, voce «spike JTE (KOINE-11)». Esito con Spring Boot
4.1.1, Gradle 9.8.1, JDK 25 (toolchain) e Kotlin 2.3.21: **JTE 3.2.4 funziona, senza workaround**.
Thymeleaf non serve.

- **Versioni**: plugin `gg.jte.gradle` 3.2.4 e `gg.jte:jte-spring-boot-starter-4` 3.2.4 (stessa
  versione, richiesto da JTE). Lo starter è compilato contro Boot 4.0.2 ma gira su 4.1.1 senza
  toccare l'autoconfigurazione.
- **`generate()` al posto di `precompile()`**: il plugin scrive sorgenti Java in
  `build/generated-sources/jte` e li compila `compileJava` come tutto il resto, quindi le classi dei
  template finiscono nel `bootJar` (casid/jte#438 non si presenta). Lo verifica
  `scripts/smoke-test-jar.sh`: il jar avviato da una cartella fuori dal repo, senza profili,
  risponde 200 su `/` con la pagina del template. Lo stesso script gira in CI.
- **Template Java, modelli Kotlin**: i template sono `.jte` (non `.kte`, che fissa il compilatore
  Kotlin a 2.2.20) e leggono i modelli Kotlin dai getter (`page.getTitle()`). Una proprietà
  inesistente fa fallire `compileJava`.
- **Profili**: senza profili i template sono quelli precompilati; il profilo `dev`, impostato da
  `bootRun`, accende la modalità sviluppo e ricarica i `.jte` da `src/main/jte` a ogni richiesta.
  Le due modalità non convivono.
- **Pacchetto `ui`**: layout, navigazione, pagine di errore e supporto htmx. In Koinè era un modulo
  condiviso di Modulith; in Talità è un pacchetto (`talita.ui`) senza regole di confine.

## 2026-10-19: asset, e2e e guscio (KOINE-11)

Origine: registro delle decisioni di Koinè, voce «asset, e2e e guscio (KOINE-11)». Scelte fatte
sviluppando il guscio, oltre a quelle del design.

- **Tailwind 4.3.3 standalone, senza Node**: il binario si scarica una volta nella cache di Gradle,
  fuori da `build/`. Versione e SHA-256 stanno solo in `build.gradle.kts` (mai scaricati insieme al
  binario) e si verificano prima di ogni uso: se non corrispondono, la build si ferma senza
  eseguirlo. Solo `linux-x64` per ora.
- **htmx 2.0.11 vendorizzato**, con licenza (0BSD) e fonte accanto; `AssetTests` ne confronta
  l'hash con l'SRI pubblicato da htmx.org. Il WebJar è scartato: il suo `latest` è la 4.
- **Font Inter 4.1** in due sottoinsiemi woff2 prodotti con fonttools (comandi e hash in
  `static/fonts/README.txt`). La catena delle risorse di Spring calcola l'impronta del CSS *prima*
  di riscrivere gli indirizzi dei font: nel CSS c'è una riga `/*! fonts: … */` con l'inizio degli
  hash, e un test fallisce se non è aggiornata.
- **Cache di un anno** sui file con l'impronta (`max-age=31536000, public`).
- **Icone Lucide 1.59.0** in un solo template (`components/icon.jte`) scelto con un enum: ogni
  tracciato è scritto una volta, un test controlla che nessuna icona dell'enum manchi.
- **Playwright Java 1.63.0 + axe-core 4.14.0** (stessa versione, richiesta dal pacchetto). Ciclo di
  vita gestito in un'estensione JUnit propria (`@E2eTest` gira in telefono e desktop, traccia e
  screenshot su fallimento). Solo Chromium; il task `playwrightInstall` lo scarica e Playwright non
  ne scarica altri.
- **«Vale l'ultimo tocco» solo fra i cambi di pagina**, in `app.js`: un `hx-sync` sul `body`
  legherebbe fra loro tutte le richieste della pagina. Anche la richiesta di un indietro o di un
  avanti è un cambio di pagina.
- **Indietro**: se il ripristino dal server risponde 404 o 500, `app.js` ricarica la pagina; senza
  rete la pagina resta com'è, compare il messaggio e l'indirizzo torna a quello della pagina che si
  vede.
- **Barra di caricamento senza lampeggio** e **etichette della navigazione a 320 px**: margine
  laterale della navigazione `px-1` e nessun margine nell'etichetta, così le voci sono larghe 78 px
  e l'etichetta si legge intera.
- **htmx scrive in `sessionStorage`** il percorso corrente anche con `historyCacheSize: 0`: `app.js`
  toglie quella chiave dopo ogni cambio di pagina. Le copie delle pagine non vengono salvate.
- **Pagine di errore**: un `ErrorController` nostro sostituisce quello di Spring Boot. 5xx mostra
  «Qualcosa non ha funzionato», ogni altro errore del client «Pagina non trovata». «Riprova» c'è
  solo per una lettura (GET) e solo verso un indirizzo di quest'app (mai `//host`).
- **Errori htmx** in `app.js`: 404 e 500 di un cambio di pagina si mostrano come pagine; altri
  errori, rete assente e tempo scaduto (20 s) lasciano la pagina com'è e mostrano un messaggio.

## 2026-10-19: un solo ticket, sviluppo subito (KOINE-11)

Origine: registro delle decisioni di Koinè. Per il guscio Matteo ha deciso un solo ticket, con un
branch e una PR e le parti come passi in ordine, perché le parti servono solo insieme (il guscio si
verifica solo con gli e2e) e il QA controlla tutti i criteri in una volta. Per TALITA-2 vale la
stessa scelta.

## 2026-10-21: nascita di Talità (KOINE-29)

Esito dell'intervista a Matteo sulla convivenza dei bambini, approvato da lui il 21/10/2026.
La organizzano i maestri dei bambini di due parrocchie, una volta l'anno, a settembre, per
30-35 persone fra bambini e maestri.

- **App separata da Koinè**, con repo, database e deploy suoi. Il titolare dei dati è Matteo.
- **Perché**:
  - il titolare è diverso: in Koinè il Centro custodirebbe dati sanitari di minori di
    un'attività che non organizza;
  - i tempi sono diversi: serve per giugno 2027, mentre Koinè aspetta la call con il Centro, la
    nomina e la migrazione;
  - il modello è diverso: i bambini non entrano nell'anagrafe delle comunità, il ruolo di
    maestro in Koinè non c'è, comunità e appartenenze non servono.
- **Il problema vero è il giorno dell'evento**: sapere chi ha consegnato la manleva e compilare
  registro arrivi e scheda per la struttura.
- **Ruoli**: il maestro gestisce tutta la convivenza, e gli altri maestri li invita un maestro; il
  genitore entra con il magic link, iscrive i suoi figli e vede solo i suoi. Nessun amministratore
  a parte.
- **Hosting**: Oracle Cloud Always Free in una regione UE, senza Pay As You Go, quindi nessun
  costo possibile. Backup cifrati fuori sede. Stack portabile (immagine del container più dump
  del database) per passare più avanti a una macchina pagata dalla comunità. Dominio gratuito
  eu.org e invio delle email con Mailjet (piano gratuito). Dettagli e fornitori in
  `docs/03-privacy.md`.
- **Primo rilascio** (pronto per giugno 2027): login con magic link; convivenza con date, luogo,
  quote e maestri affidatari; pagina informativa al posto della lettera ai genitori; iscrizione,
  che è anche la manleva; iscrizione dal cartaceo inserita da un maestro; check-in dal telefono;
  stampe (registro arrivi ed elenco dei presenti per la struttura); conservazione e
  cancellazioni; informativa breve e consenso.
- **Fuori dal primo rilascio**: camere, cucina e menù, solleciti via email, rendiconto e raccolte,
  funzionamento senza rete, firma con valore legale, sesso, luogo di nascita, dati dei maestri per
  la struttura, caricamento di documenti.
- **Dopo il repo**: login e maestri (TALITA-3), convivenza e pagina informativa (TALITA-4),
  iscrizione (TALITA-5), check-in e stampe (TALITA-6), cancellazioni (TALITA-7), deploy, Dockerfile
  e backup (TALITA-8), account e dominio (TALITA-9).

Origine: commento di chiusura di KOINE-29 e voce del 21/10/2026 del registro delle decisioni di
Koinè.

## 2026-10-21: lo stack di Koinè, senza ciò che qui non serve (TALITA-2)

Lo stack è quello di Koinè, confermato il 19/10/2026 (voce «stack» di Koinè). Il codice è generato
dall'IA: gli errori devono emergere in build o nei test, non in produzione. Versioni in
`CLAUDE.md`.

**Resta**: Kotlin e Spring Boot (null-safety e controllo in compilazione), jOOQ al posto di JPA
(il codice è generato dallo schema, una query su una colonna che non c'è non compila), JTE al
posto di Thymeleaf (template compilati), htmx e Tailwind senza Node né npm, Postgres con Flyway,
Spring Security con il magic link (TALITA-3), Testcontainers su Postgres vero, Docker Compose,
Playwright per gli e2e.

**Si toglie, e perché**:

| Cosa | Perché non serve a Talità |
|---|---|
| Spring Modulith | I confini fra moduli servono a tenere ordinato un monolite che cresce. Qui l'app è piccola e ha un solo titolare: un pacchetto per area sotto `talita`, e l'ordine lo tiene la review. Se un giorno servirà, si deciderà allora. |
| Multi-centro e `centro_id` | C'è un solo titolare e una sola convivenza all'anno: niente multi-tenant, niente colonna su ogni tabella. |
| Row-level security | Serviva a impedire che una query dimenticata mostrasse dati di un altro Centro. Qui l'isolamento che conta è quello fra genitori (ognuno vede solo i suoi figli): lo fa l'applicazione, con i suoi test, in TALITA-3 e TALITA-5. |
| `btree_gist` | Serviva ai vincoli `EXCLUDE` sui periodi (`daterange`) delle appartenenze, che in Talità non esistono. |
| `pg_trgm` | Con 35 nomi basta una ricerca per sottostringa su `unaccent(lower(…))`. Se TALITA-6 vuole tollerare anche gli errori di battitura, si aggiunge con una migrazione nuova. |
| `jooq-postgres-extensions` e il tipo `daterange` | Senza periodi non servono. |

**Si aggiunge**: Oracle A1 (ARM) come destinazione del deploy. Il binario di Tailwind ha il
checksum solo per `linux-x64`: una build fatta sulla macchina ARM fallirebbe. Si decide in
TALITA-8 se compilare il jar in CI o aggiungere il checksum `linux-arm64`.

## 2026-10-21: scelte di TALITA-2

Il ticket crea il repo, copia lo scheletro di Koinè, adatta il guscio, scrive il flusso agentico e
i documenti. Una sola PR, con le tre parti come passi in ordine (decisione di Matteo: un ticket
unico invece dei tre proposti dall'analisi).

- **Repo pubblico, licenza AGPL-3.0.** Perché pubblico: la review completa di CodeRabbit e le
  Actions senza limiti. Perché AGPL-3.0: Matteo vuole tenersi aperta una doppia licenza, AGPL per
  tutti e una licenza commerciale per chi vuole tenere chiuse le modifiche. Le licenze di Inter,
  Lucide e htmx restano accanto ai loro file.
- **Un repo pubblico non ha dati né nomi veri.** Nel codice e nei documenti niente nomi di
  parrocchie o della struttura, persone vere, date reali della convivenza, indirizzi email (si
  scrive «due parrocchie», «una casa per ritiri», esempi con nomi sintetici). Nei commit l'autore e
  il committer hanno l'indirizzo noreply dell'account GitHub di Matteo (`git config` locale del
  repo, da controllare prima di ogni commit). Rete di sicurezza: la build fallisce se fra i file
  tracciati c'è un PDF, un documento Office o OpenDocument, o un'immagine fuori da
  `src/main/resources/static/` e `docs/design/` (`RepositoryRulesTests`); su GitHub sono attivi
  secret scanning e push protection.
- **Storia di `main`**: un primo commit con licenza, `.gitignore`, `.gitattributes` e un README di
  una riga, poi lo squash della PR. Il repo non ha la storia di Koinè: i file si sono presi con
  `git archive` dal commit `23ea63d`, mai copiando cartelle, così non passa niente che non fosse
  tracciato. I mockup di `docs/design/KOINE-11/` sono copiati com'erano, salvo due nomi di
  parrocchia di esempio e un indirizzo email di esempio, sostituiti con «Parrocchia di esempio» e
  `nome@example.org` per la regola precedente.
- **Porte di sviluppo**: Postgres di compose su `127.0.0.1:5433` e app con il profilo `dev` su
  8081, così Koinè e Talità girano insieme sullo stesso PC. Database, utente e password di
  sviluppo sono `talita` (solo dati sintetici). Il volume di compose è comunque diverso, perché il
  nome del progetto compose è quello della cartella.
- **Voci di navigazione**: Home, Convivenza, Iscrizioni, Arrivi (indirizzi `/`, `/convivenza`,
  `/iscrizioni`, `/arrivi`), nella vista del maestro; quelle del genitore le decide TALITA-3.
  L'ordine segue il lavoro dei maestri: si prepara la convivenza, si raccolgono le iscrizioni,
  nei giorni dell'evento si segnano gli arrivi. Quattro voci perché sono sotto il limite di cinque
  della guida e corrispondono, una a una, alle sezioni dei test e2e ereditati; «Convivenza» al
  singolare perché ce n'è una all'anno; «Arrivi» e non «Presenze» perché i «giorni di presenza»
  sono già un dato dell'iscrizione ed «Arrivi» è il nome del registro di carta che l'app
  sostituisce. A 320 px la più lunga, «Convivenza», misura 74 px su 78 disponibili.
- **Marchio**: quadrato blu con una T bianca, sullo stesso schema della K di Koinè (`favicon.svg` e
  intestazione). Palette, font e componenti restano quelli del design system di Koinè.
- **Cache di Tailwind**: `~/.gradle/caches/talita-tools/`, separata da quella di Koinè, con la
  stessa versione (4.3.3) e lo stesso SHA-256. La prima build su un PC che ha già Koinè scarica
  di nuovo il binario (circa 110 MB).
- **Solo `unaccent`**: la migrazione `V1__extensions.sql` installa solo questa estensione, per la
  ricerca per nome al check-in (TALITA-6): chi scrive dal telefono senza accenti trova lo stesso
  «Nicolò». Un test verifica che sia l'unica migrazione e che siano installate solo quelle
  previste.
- **Nessuna tabella** in TALITA-2: lo schema comincia con TALITA-3.
- **Test del repo**: oltre ai test ereditati, `NoLeftoversTests` controlla che il codice non porti
  parole di Koinè o delle funzioni tolte (l'unica ammessa è `KOINE-11` nei commenti che dicono da
  dove viene un test o una scelta), e un test del guscio controlla che nessuna pagina né messaggio
  a comparsa nomini Koinè, il Centro, le comunità o i fratelli.
- **Osservazione D7 di KOINE-11** (un indietro lento, un tocco e la rete che cade nello stesso
  momento): si porta così com'è, con il test disabilitato e la sua spiegazione. Se serve, diventa
  un ticket di Talità.
- **Test ereditati**: gli stessi e2e di Koinè (guscio, layout, responsive, cronologia, caricamento,
  messaggi, errori, accessibilità con axe), su telefono e desktop; cambiano etichette, percorsi e
  testi attesi.
