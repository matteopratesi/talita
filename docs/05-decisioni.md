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

## 2026-10-22: scelte di TALITA-14

Rifiniture dopo TALITA-2, nate dai commenti della QA. Il ticket non tocca l'app: cambia il flusso
agentico, la build, la CI e due test. Una PR sola.

- **Review di CodeRabbit a mano.** Sul repo pubblico, finché ha meno di 10 stelle, CodeRabbit non
  rivede da solo: la skill `/avanza` mette la PR pronta dopo il QA e chiede subito la review con un
  commento `@coderabbitai review`, e lo stesso per un secondo giro dopo le correzioni (rivede solo i
  commit nuovi). Mai un terzo giro senza Matteo. L'esito si legge dalla **descrizione** dello status
  `CodeRabbit` sul commit di testa («Review completed»), non dallo stato: anche «Review skipped…» ha
  stato `success` e `gh pr checks` lo mostra come `pass`. Lo status è per commit, quindi dopo un
  push la review va chiesta di nuovo. Rate limit: si aspetta e si richiede. Nessun esito in 30
  minuti: lo si dice a Matteo invece di chiedergli l'ok al merge. `CLAUDE.md`, developer e QA non
  dicono più che la review è una e automatica.
- **Prova di `/avanza` senza chiave: già fatta.** Da una sessione in `~/dev/talita` ha preso il
  primo ticket in To Do di TALITA e non quelli di Koinè né di altri progetti (commento 10849 del
  ticket). Il ticket di prova è TALITA-14 stesso.
- **Download di Tailwind fermo a metà.** I timeout del client HTTP coprono la connessione e l'attesa
  delle intestazioni, non un server che manda una parte del corpo e poi tace. Il task
  `downloadTailwind` ha un limite di 15 minuti (solo lui: `tailwindWatch` gira finché lo si ferma) e
  il job di CI ha `timeout-minutes: 30` per qualunque altro passo appeso. Il limite è sul tempo
  totale, non sull'inattività: un controllo «nessun byte da N secondi» vuol dire più codice nello
  script di build per un caso raro; se 15 minuti si rivelano stretti (110 MB sotto circa 1 Mbit/s
  non ci stanno), si apre un ticket, e intanto il file si copia a mano nella cache
  (`docs/04-ambiente.md`). Il file `.part` si toglie in un `finally` e prima di scaricare;
  l'interruzione del limite non si cattura, perché il task deve fallire; il client non si chiude,
  perché `close()` aspetta scambi che non finiscono.
- **Bot di GitHub ammessi nel controllo degli indirizzi** (decisione di Matteo, 22/10/2026).
  `scripts/check-commit-emails.sh` accetta anche `<id>+<login>[bot]@users.noreply.github.com`, solo
  in questa forma: con l'id, `[bot]` subito prima della `@`, dominio esatto. Il controllo serve a
  non far finire nella storia pubblica la mail personale o quella di lavoro (regola 2 di
  `CLAUDE.md`); l'indirizzo di un bot è pubblico e non è di nessuna persona, e rifiutarlo farebbe
  fallire la CI il giorno in cui un bot aggiunge un commit a una PR. Chi può scrivere sul branch lo
  decidono i permessi di GitHub. I fork non cambiano: un indirizzo che non sia un noreply si
  rifiuta, perché lo script non può distinguere la mail di Matteo da un'altra senza scriverla nel
  repo. Un test esegue lo script vero su repo temporanei.
- **Regola sul nome del ticket di origine** (`NoLeftoversTests`). Il caso di `Disabled(` con il nome
  in un'altra stringa della riga si chiude: l'eccezione vale solo dentro la stringa del motivo. Il
  caso di una stringa con `//` prima del nome resta aperto: chiuderlo bene vuol dire riconoscere le
  stringhe di Kotlin (anche raw e con template), YAML, shell, JTE, JS, CSS e SQL in una regola che
  lavora per righe, e contare le virgolette ne chiuderebbe solo una parte, con errori suoi. Sta
  scritto nel KDoc come limite noto e un test lo fissa, così chi un giorno lo chiude lo aggiorna
  sapendolo. La regola protegge da un residuo copiato per sbaglio, non da chi lo nasconde apposta:
  per quello c'è la review.
- **Registro in ordine di data.** Le quattro voci del 19/10 passano prima di quelle del 21/10, come
  dice la prima riga del registro, e un test controlla che le date delle intestazioni non scendano
  mai.

## 2026-10-22: scelte di TALITA-3

Login con magic link, ruoli e maestri. Analisi e design approvati da Matteo il 22/10/2026 con tutte le
raccomandazioni delle domande aperte. **Il ticket non si spezza**: un branch e una PR, con le parti
A (posta), B (accesso con il link), C (ruoli e primo maestro) e D (maestri e inviti) fatte come commit
in quest'ordine, ognuno con la build verde.

**Valori approvati**

- Il link vale **15 minuti** e si usa una volta sola (i 5 di Spring sono pochi per chi passa
  dall'app di posta con poco campo).
- La sessione sta **in memoria** e finisce dopo **2 ore** di inattività; si perde al riavvio. Se ai
  maestri non basta, si apre un ticket per tenerli dentro qualche giorno sul loro telefono.
- Limiti, in configurazione e contati in memoria: **3 link ogni 15 minuti per indirizzo** e **150 mail
  ogni 24 ore** in tutto (link e inviti; Mailjet gratuito ne manda 200). Per indirizzo e non per IP: alla
  convivenza i maestri escono dallo stesso Wi-Fi.
- **Il genitore entra sempre**, anche senza una convivenza aperta: è ogni account, e dal suo account
  esercita i diritti (`docs/03-privacy.md`). TALITA-3 non dipende da TALITA-4.
- La conservazione di account, inviti scaduti e link resta a TALITA-7.

**A. Posta**

- **Un solo punto di uscita, `Mailer`**, sincrono: chi chiede un link sa subito se la mail non è partita.
  Solo testo, mittente «Talità», intestazioni `X-Mailjet-TrackOpen: 0` e `X-Mailjet-TrackClick: 0`,
  timeout SMTP di 10 secondi (prima dei 20 di htmx), STARTTLS obbligatorio (lo spengono solo `dev` e i
  test). L'app parte anche senza `spring.mail.host` e lo dice nel log; la posta non fa parte di
  `/actuator/health`. Il log non ha mai indirizzo, testo o link di una mail: un errore si scrive con il tipo
  dell'eccezione, perché il messaggio di JavaMail nomina spesso il destinatario.
- **Mailpit** con tag esatto (`v1.31.4`, uscito il 3/10/2026, oltre le due settimane), su `127.0.0.1` con
  le porte 1026 e 8026 (1025 e 8025 restano all'altro progetto, come 5433 e 8081). Due variabili che
  servono davvero: `MP_DISABLE_VERSION_CHECK` (non chiama GitHub a ogni avvio) e `MP_SMTP_DISABLE_RDNS`:
  senza, Mailpit cerca nel DNS il nome di chi manda e, dove la rete non risponde per l'indirizzo del bridge
  di Docker, **ogni mail aspetta 5 secondi** (misurato: 5 secondi senza, 17 millisecondi con). Nei test
  gira un solo Mailpit di Testcontainers per tutta l'esecuzione (`MailpitContainer`); ogni test usa i suoi
  indirizzi (`Addresses`), quindi se ne condivide uno.
- **Le proprietà della posta nei test arrivano da un `ContextCustomizer`**, non da un bean
  (`DynamicPropertyRegistrar`): Spring Boot crea il sender solo se `spring.mail.host` esiste mentre si
  leggono le classi di configurazione, prima di qualunque bean. I test possono sostituire una proprietà con
  `@SpringBootTest(properties = ...)` per provare una mail che non parte.
- **Un solo orologio** (`Clock`), che i test spostano in avanti per far scadere un link o contare le 24 ore
  senza aspettare. Va solo avanti e non si rimette a posto: è condiviso da tutti i test del contesto, e
  quello che hanno scritto (una mail contata, un link) è datato da lui.
- **Indirizzi dei test**: nei file tracciati l'unico indirizzo ammesso è `nome@example.org`
  (`t2 c37`), quindi gli altri si costruiscono a pezzi in `Addresses`.

**B. Accesso con il link**

- **Aprire il link non fa entrare**: la pagina chiede un tocco su «Entra» (un `POST` con codice CSRF).
  I programmi di posta e le anteprime aprono i link per guardarli, e con un `GET` che consuma il link
  la persona lo troverebbe già usato.
- **Stessa risposta per ogni indirizzo valido**, con o senza account: 303 verso «Controlla la posta», che
  ripete l'indirizzo appena scritto (dalla sessione, letto una volta sola, mai nell'URL). L'account nasce
  al primo accesso riuscito, quando Spring ha già consumato il link: un link mai usato non crea niente.
- **I link nascono in un solo punto, `LoginLinks`** (validazione, limite per indirizzo, token, mail, e se
  la mail non parte il token si butta e la richiesta non conta). L'indirizzo predefinito di Spring
  (`/ott/generate`) è spento con un resolver che non risolve mai niente: non genera né manda. L'indirizzo
  del link viene da `talita.public-url` e mai da `Host` o `X-Forwarded-Host`.
- **Tabella dei link riscritta** (`V2__accounts.sql`): lo script di Spring usa `varchar_ignorecase`, che
  Postgres non ha, e un `username` da 50 caratteri. Qui 254, così gli indirizzi lunghi entrano. La pulizia
  dei link scaduti è quella di Spring, ogni ora; un link usato esce subito.
- **La sessione tiene solo id e indirizzo**; a ogni richiesta `CurrentAccountFilter` legge l'account dal
  database e costruisce l'autenticazione di quella richiesta (non si salva in sessione). Così un ruolo che
  cambia vale al tocco dopo, e un account cancellato esce. È l'unico modo per avere l'account corrente:
  `CurrentAccount` come parametro di un metodo del controller, o `CurrentAccounts` per il guscio.
- **Elenco unico degli indirizzi pubblici** (`PublicPaths`) e un test che lo controlla: `/accedi/**`,
  `/actuator/health`, la pagina d'errore e i file statici (con la cache di sempre). Chiunque apra un altro
  indirizzo, anche uno che non esiste, va a `/accedi`. Una richiesta di htmx senza accesso riceve
  `HX-Redirect`, non un 302 né un 401: la pagina di accesso non finisce dentro un pezzo di pagina.
- **Dopo l'accesso**: alla pagina chiesta prima, se il link si apre nello stesso browser (solo percorso e
  query, mai un indirizzo esterno, e non un pezzo di pagina che htmx chiede); altrimenti alla home. Niente
  `?continue` nell'URL.
- **CSRF scaduto**: un form delle pagine di accesso con il codice scaduto torna come la stessa pagina, con il
  campo compilato, un codice nuovo e «La pagina era aperta da troppo tempo. Riprova.»; mai la pagina «Non puoi
  vedere questa pagina». Per un form di chi non è più dentro: la pagina di accesso. Si fa con un **redirect**
  (l'avviso e quello che era nel campo restano nella sessione, in memoria, e si leggono una volta) e non con un
  `forward`: un forward rientra nei filtri di sicurezza con il controllo del CSRF già segnato come fatto, e il
  tocco su «Entra» con un codice scaduto avrebbe fatto entrare lo stesso (lo ha mostrato un test e2e).
- **Il tasto indietro dopo l'uscita**: htmx non legge `HX-Redirect` nella richiesta che rimette una pagina della
  cronologia. Lì la risposta è un 401 vuoto: htmx rinuncia (`historyCacheMissLoadError`), `app.js` ricarica
  l'indirizzo come pagina intera e la richiesta dopo va a `/accedi`.
- **Form rimandati con errori**: 422, o 429 per i limiti; `app.js` li mette al posto della pagina o del
  pezzo, il fuoco va al primo campo sbagliato o all'avviso, e non lasciano una seconda voce nella
  cronologia. Le conferme dopo un'azione sono avvisi nella pagina e non messaggi a comparsa (regola nuova
  di 4.7 della guida).
- **Content-Security-Policy** su ogni risposta, senza `unsafe-inline` né `unsafe-eval`; un test e2e fallisce
  se la console del browser segnala una violazione. Il cookie di sessione è `HttpOnly` e `SameSite=Lax`,
  `Secure` quando la richiesta arriva in HTTPS (dietro un proxy lo deciderà TALITA-8: un test lo prova con
  `X-Forwarded-Proto` e le due proprietà di Tomcat che serviranno), e la sessione non passa mai da un
  `;jsessionid=` nell'indirizzo.
- **Test che entrano con il flusso vero** (`MockSignIn`, `E2e.signIn`): token dal servizio, pagina di
  conferma, tocco su «Entra». Nessuna scorciatoia nel codice di produzione. Per non aspettare due ore, un
  endpoint solo dei test accorcia la sessione a un secondo: Tomcat la tratta come dopo le due ore vere.

**C. Ruoli e primo maestro**

- **Genitore è ogni account; maestro è un account con una riga in `teacher`** (`V3__teachers.sql`). I ruoli non
  si escludono: un maestro che è anche genitore ha un account solo, la navigazione del maestro, e l'account
  corrente è quello a cui TALITA-5 legherà le sue iscrizioni.
- **Il nome del maestro lo scrive lui** quando accetta l'invito, anche il primo (obbligatori, al massimo 100
  caratteri, accenti e apostrofi ammessi, nessun pattern: un nome che una regola non si aspetta è comunque un
  nome). L'invito ha solo l'indirizzo: il dato viene dalla persona e la strada è una per tutti. TALITA-4 lo
  legge, non lo chiede.
- **Inviti senza token**: la mail porta a `/accedi`, chi entra con quell'indirizzo trova l'invito. Un invito
  ritirato dopo l'invio non apre niente, uno scaduto nemmeno: chi entra vale come genitore, e `/invito` aperto a
  mano porta alla home senza messaggio. Se l'invito scade o viene ritirato mentre la pagina è aperta, il tocco su
  «Accetta l'invito» porta alla home con un avviso (non è nei criteri, ma l'ha proposto il design).
- **Dopo l'accesso, in quest'ordine**: «Accetta l'invito» se l'indirizzo ne ha uno valido (`PostLoginDestination`,
  in `talita.account`, realizzato da `talita.teacher`: l'accesso non conosce i maestri), poi la pagina chiesta
  prima, poi la home.
- **Primo maestro** da `TALITA_BOOTSTRAP_TEACHER`, all'avvio e solo se non c'è nessun maestro: un invito per
  quell'indirizzo, senza mail, valido 7 giorni e rinnovato a ogni avvio finché non c'è nessun maestro. Non
  scrive mai l'indirizzo nel log; senza variabile e senza maestri l'app parte e il log avvisa.
- **Un solo elenco degli indirizzi dei maestri** (`TeacherPaths`: `/convivenza`, `/arrivi`, `/maestri` e quello
  che c'è sotto), letto dalla configurazione di sicurezza, con un test che prova un genitore su ognuno. Per un
  genitore rispondono 403: pagina «Non puoi vedere questa pagina» per un link, messaggio «Accesso negato» per
  ogni altra richiesta (un form, un pezzo di pagina), mai «Riprova». `/iscrizioni` è di ogni account: il filtro
  «il genitore vede solo i suoi» è di TALITA-5.
- **Navigazione per ruolo** (`Section.visibleToParents`): il genitore ha Home e Iscrizioni, il maestro le
  quattro voci; la tabella della guida dice chi vede ogni voce e un test la confronta con `Section`.

**D. Maestri e inviti**

- **«Maestri»** (`/maestri`) sta sotto la Convivenza (voce corrente «Convivenza», non è una voce): ci si arriva da
  una sezione «Maestri» della pagina Convivenza (il design diceva «link dalla pagina Convivenza»; la pagina è
  ancora il segnaposto «in preparazione» e TALITA-4 la riempie, lasciando la sezione). Maestri per nome e
  cognome, mai per indirizzo; inviti per indirizzo, con scadenza o «Scaduto».
- **Un invito si salva solo se la mail parte** (`InviteTeacher`): invito e mail stanno in una transazione, e se la
  mail non parte (server giù, limite del giorno, niente `talita.public-url`) la transazione torna indietro, la
  scadenza di un invito che c'era non cambia e la pagina lo dice. Così «Invito mandato.» è vero, e nessuno ha un
  invito di cui nessuno gli ha detto niente. Per il limite del giorno il design non aveva un testo: si usa quello
  della pagina di accesso. L'indirizzo invitato non va mai nel log.
- **Togliere un maestro** (`Teachers.remove`) è una transazione che prima blocca tutte le righe di `teacher`
  (`SELECT … ORDER BY account_id FOR UPDATE`), poi conta, poi cancella. Due maestri che si tolgono a vicenda nello
  stesso istante sono in coda: il secondo trova un solo maestro e riceve «Serve almeno un maestro». Il test lo prova
  con due transazioni vere per 25 giri, e provato senza il blocco fallisce. **TALITA-4 aggiunge qui, nella stessa
  transazione e sotto lo stesso blocco, il controllo che il maestro non sia affidatario di una convivenza non
  finita** (e il testo del dialogo che lo dice). Chi si toglie da solo arriva alla home da genitore con l'avviso
  «Non sei più tra i maestri.» (`HX-Redirect`: cambia la navigazione, quindi si ricarica la pagina).
- **Nessun nome, id o indirizzo in un URL**: ogni azione è un `POST` con i dati nel corpo (`maestro` = id
  dell'account, `email`). I dialoghi hanno id `togli-0`, `ritira-0`. Un `GET` all'indirizzo di un'azione (un form senza
  JavaScript che è tornato con un errore, e la persona lo riapre) porta a `/maestri` invece di un 405.
- **Un blocco che ogni azione sostituisce** (`#maestri-contenuto`, `hx-swap="outerHTML"`): avviso, scheda, liste e
  dialoghi. La pagina non si muove e il fuoco va all'avviso o al campo sbagliato; senza JavaScript la stessa azione
  è un 303 verso la pagina con l'avviso in sessione (una volta sola) o, con errori, la pagina intera.
- **Dialoghi nativi** (`<dialog>` aperto da `app.js` con `showModal()`, un bottone con `data-apri`), con le pagine di
  conferma al posto loro senza JavaScript (`/maestri/togli/conferma`, `/maestri/inviti/ritira/conferma`: stesso titolo,
  testo e verbo). **Se una richiesta fallisce con un dialogo aperto, `app.js` lo chiude prima di mostrare il
  messaggio**: un dialogo aperto sta nel livello più alto della pagina e il messaggio, sotto lo sfondo, non si
  vedrebbe (c'è un test e2e con la rete spenta).
- **Il nome si corregge dal profilo** (`POST /profilo/nome`, scheda «Il tuo nome», solo maestri): stesse regole
  dell'invito. L'indirizzo è nell'elenco unico di `TeacherPaths` (un indirizzo esatto, non un ramo), e il test che
  prova un genitore su ogni indirizzo dei maestri lo include. Con htmx un form rimandato con errori tiene
  l'indirizzo `/profilo` (`HX-Replace-Url`).
- **Nessuna dipendenza nuova** in questa parte. Per i test: un `Person`/`MaestriSupport` che prepara i maestri
  (ogni test comincia con quelli che gli servono, senza inviti, perché il database è condiviso) e tre contesti
  piccoli con le proprietà della posta cambiate (server giù, nessun indirizzo pubblico, un solo invio al giorno).
- **Cose che i test e2e hanno insegnato**: Playwright scrolla un elemento solo se non si vede per niente, e un
  bottone che mostra la cima sopra la barra in basso del telefono è «in vista» ma coperto (negli e2e si centra
  prima, come fa chi scrolla); axe non gira in una pagina senza JavaScript e resta appeso, quindi le pagine di conferma
  si controllano con JavaScript acceso, inviando a mano il form del bottone.

**Dopo la QA di D (tre difetti di layout sul telefono)**

- **Il testo di una riga con azioni parte da 11 rem** (`min-w-0 flex-[1_1_11rem]`, come il mockup): con `flex-1` e basta,
  a 360 px un indirizzo da 34 caratteri restava largo 17 px (una lettera per riga) e i bottoni restavano a destra
  invece che sotto. Nella guida (4.3).
- **Indirizzi e nomi vanno a capo ovunque** (`wrap-anywhere`) nei dialoghi, nella pagina di conferma, negli avvisi e nel
  titolo di ogni pagina (`pageHead`): un nome di 100 lettere senza spazi faceva scorrere di lato il dialogo e tagliava
  proprio quello che si chiede di confermare. `wrap-break-word` non basta dentro un flex: non abbassa la larghezza minima.
- **`scroll-padding-top` e `scroll-padding-bottom` su `html`** (alti come intestazione e navigazione fisse, con la safe
  area; zero da `lg`): l'avviso «Invito mandato.» prendeva il fuoco sotto l'intestazione. Chiude anche il dubbio sul
  fuoco coperto dalla barra in basso (WCAG 2.2, 2.4.11), per tutte le pagine; nella guida (2.4 e 3.1).
- I test e2e di questi casi (`MaestriLayoutE2eTest`) li ha scritti la QA e riproducevano i difetti prima della correzione.
- **Anche il nome nella riga di un maestro** ha `wrap-anywhere` (la QA lo ha trovato dopo gli altri): con `wrap-break-word` una
  parola di 24 lettere passava sotto «Togli» sul telefono e una di 100 usciva dalla riga. Controllati gli altri nomi e
  indirizzi delle pagine «Maestri», profilo e «Controlla la posta»: avevano già `wrap-anywhere`.

## 2026-10-23: scelte di TALITA-4

Convivenza e pagina informativa. Analisi e design approvati da Matteo il 22/10/2026 con tutte le raccomandazioni delle
domande aperte. **Il ticket non si spezza**: un branch e una PR, con le parti A (convivenza per i maestri) e B (pagina
informativa) fatte come commit in quest'ordine, ognuna con la build verde.

**Valori approvati**

- **Indirizzo pubblico `/info/{codice}`** con un codice casuale per ogni convivenza: 16 caratteri base64url da 12 byte di
  `SecureRandom` (96 bit), che non cambia con le modifiche. Il repo è pubblico e i domini finiscono nei registri dei certificati:
  con un indirizzo fisso, luogo e date di un evento con bambini sarebbero leggibili da chiunque; col codice il link vale quanto la
  lettera su WhatsApp.
- **Al massimo una convivenza in bozza o aperta**, per volta. Lo garantiscono un indice unico parziale (`gathering_only_one_not_closed`)
  e un controllo nella transazione che crea o riapre, così due maestri che toccano insieme non ne fanno due.
- **Tre quote facoltative**, in centesimi: vuoto = non prevista, 0 = «Gratuita» (mai «0 €»), da 0 a 1.000 € con al massimo due decimali.
  L'intera serve per aprire. «Solo pranzo» è un importo per pranzo, in qualunque giorno. Niente sconti per più figli.
- **Giorni di presenza**: tutti quelli fra inizio e fine, al massimo 7, senza un campo per sceglierli.
- **Stati**: bozza → iscrizioni aperte → iscrizioni chiuse; da chiuse si riapre finché la fine non è passata, qualunque maestro; mai di
  nuovo in bozza. «Finita» non è uno stato: è una convivenza non in bozza con l'ultimo giorno passato, nel giorno di Roma (un solo
  `Clock`, `Europe/Rome`). Dopo la fine le date non cambiano.
- **Il recapito del titolare** non sta sulla pagina informativa (regola 5) ma nell'informativa di TALITA-5: `docs/03-privacy.md` non lo assegna più a
  TALITA-4.
- **Controllo su email, telefoni e IBAN** nei testi che vanno sulla pagina pubblica (titolo, luogo, informazioni, cosa portare).

**A. Convivenza per i maestri**

- **Pacchetto `talita.gathering`** (identificatori in inglese). `talita.teacher` dipende da lui (il controllo di «Togli»), lui non
  dipende da `talita.teacher`: i nomi dei maestri si leggono dalla tabella `teacher`.
- **Schema `V4__gathering.sql`**: enum `gathering_status`, tabella `gathering` con i controlli (titolo 1-100, date in ordine e al massimo
  7 giorni, importi 0-100000 centesimi, testi con il massimo, luogo e quota intera obbligatori fuori dalla bozza) e
  `gathering_custodian`. Le due chiavi di `gathering_custodian` sono `on delete cascade`: toccano solo le convivenze finite, perché per
  le altre «Togli» si ferma prima, e «finita» dipende da oggi, che un vincolo non può sapere.
- **«Non finita», per «Togli»**, è una bozza oppure una convivenza con l'ultimo giorno non passato. Una convivenza aperta con la fine
  passata è finita (come dice la sua pagina), quindi i suoi affidatari si possono togliere e il loro legame si cancella con il nome.
- **Il controllo su «Togli» sta in `Teachers.remove`**, nella stessa transazione e sotto lo stesso lock sulle righe di `teacher`
  (`FOR UPDATE`). Chi sceglie gli affidatari (`Gatherings.saveCustodians`) blocca prima la riga della convivenza (`FOR UPDATE`) e poi i
  maestri scelti (`FOR SHARE`): un maestro tolto nello stesso istante o è già sparito (non si salva) o aspetta e trova l'affidamento
  (viene rifiutato). Un test lo prova con due transazioni vere per 25 giri.
- **Ogni passo dello stato** (`Gatherings.change`) è una transazione che blocca la riga della convivenza: il secondo di due tocchi
  insieme trova lo stato già cambiato e riceve la stessa conferma del primo, mai un errore (`AlreadyDone`). Il campo `from` del form dice
  da che stato parte la persona. L'apertura controlla luogo, quota intera, almeno un affidatario e la fine non passata; la riapertura la
  fine non passata e nessun'altra convivenza in bozza o aperta (l'indice unico fa da rete).
- **Un modulo per parte, ognuno nella sua pagina** (`/date`, `/quote`, `/affidatari`, `/informazioni`), con il suo indirizzo: form corti,
  il tasto indietro del telefono, e i collegamenti di «Prima di aprire le iscrizioni» (`#luogo`, `#quota-intera`) arrivano al campo
  giusto anche senza JavaScript. `app.js` dà il fuoco all'elemento del frammento dopo un cambio di pagina.
- **Il bottone primario cambia con la situazione** (scostamento dall'analisi, approvato): «Apri le iscrizioni» in bozza; con le iscrizioni
  aperte o chiuse è «Copia il link» e l'azione di stato è secondaria; con la fine passata e le iscrizioni ancora aperte torna «Chiudi
  le iscrizioni».
- **Dialoghi dei passi dello stato**: un link con `data-apri` verso la pagina di conferma (`/convivenza/{id}/apri`...), che senza
  JavaScript è la pagina con gli stessi testi; il form del dialogo è un normale form della pagina (boosted), non un blocco che si
  sostituisce. Per una bozza non completa non c'è dialogo: il bottone manda il form e la risposta (422) elenca cosa manca, con un
  collegamento a ogni parte, e il bottone resta attivo.
- **`app.js`**: il listener dei dialoghi sta nella fase di cattura, perché un link con `data-apri` è anche un link che htmx fa
  navigare (htmx ascolta sul link stesso e partirebbe prima); **409 è una risposta**, come 422 e 429 (una richiesta che lo stato delle cose
  rifiuta sostituisce la pagina, non diventa «Qualcosa non ha funzionato»).
- **Importi e date con `Locale.ITALIAN`** in un solo formattatore (`GatheringFormat`): `17–19 settembre 2027`, `Venerdì 17, sabato 18 e
  domenica 19 settembre 2027`, `12,50 €`, `1.000 €`, «Gratuita».
- **Controlli dei campi** in un solo posto (`GatheringChecks`): un errore per campo, il primo che vale; le lunghezze si contano come le conta
  il database (un punto di codice, un'emoji vale uno). Il testo si salva com'è scritto (a capo conservati, spazi ai bordi tolti); più righe
  vuote valgono uno stacco quando si mostra.
- **Aggiunte al design, per un buco che il database aprirebbe**: fuori dalla bozza il luogo e la quota intera non si possono
  svuotare (lo vieta uno dei vincoli della tabella), quindi i moduli hanno due errori in più: «Scrivi il luogo: la pagina per i genitori lo
  mostra.» e «Scrivi la quota intera. Se è gratuita, scrivi 0.». Lo stesso per l'IBAN, che la domanda 7 approvata voleva bloccare insieme a
  email e telefoni: «Qui c’è un IBAN. Toglilo: la pagina la legge chiunque abbia il link.»
- **Test con date relative al giorno del `Clock` di prova**: con date fisse del 2027 i test cadrebbero il giorno in cui passano. Le frasi
  che chiedono date precise (giorni della settimana, intervalli) si provano con date fisse nei test del formattatore, che non guardano
  il calendario. Ogni test che tocca le convivenze comincia da `GatheringSupport.reset()`, perché il database è condiviso e ce ne può
  essere una sola non chiusa.

**B. Pagina informativa**

- **Una pagina senza script e senza cookie** (`layout/public.jte`): niente htmx, niente `app.js`, niente codice CSRF (leggerlo farebbe
  nascere la sessione e il cookie). La risposta non ha `Set-Cookie`, il browser non scarica nient'altro che CSS, font e icona dell'app,
  la prima visita pesa circa 85 KB. La Content-Security-Policy è quella di sempre.
- **Il template riceve `PublicInfo`, non la convivenza**: titolo, date, giorni, luogo, quote, testi e l'indirizzo. I nomi degli
  affidatari e i numeri sulle iscrizioni non possono arrivarci per distrazione, e un test fissa l'elenco dei campi.
- **Stessa risposta per una bozza e per un codice che non esiste** (anche per una sola maiuscola, anche per un maestro): 404 con lo
  stesso HTML e le stesse intestazioni, scritta dal controller e non dalla pagina d'errore, così non si capisce se una bozza esiste.
  Senza accesso la pagina sta nel guscio pubblico e porta all'accesso; con l'accesso nel guscio dell'app.
- **`X-Robots-Tag: noindex, nofollow`** e `<meta name="robots">` sulla pagina, sul 404 e sull'anteprima; `Cache-Control` è quello di
  Spring Security (`no-cache, no-store`), mai una pagina conservata.
- **L'anteprima del link** (`og:*`) ha titolo, date e luogo su una riga, mai un'immagine né un indirizzo esterno; quando la convivenza è
  finita dice solo che è finita. La query che le app aggiungono (`?fbclid=…`) non cambia niente.
- **`/info/*` è nell'elenco unico degli indirizzi pubblici** (con il suo test); `/info/{codice}/iscrizione` no: chiede un account e
  porta alla pagina chiesta dopo l'accesso, nello stesso browser (negli altri alla home, accettato). Finché TALITA-5 non c'è risponde
  «In preparazione»; con le iscrizioni chiuse o la convivenza finita torna alla pagina informativa con un 303.
- **L'indirizzo che si mostra e si stampa viene da `talita.public-url`**, mai dall'`Host` della richiesta. Se manca, la scheda lo dice
  invece di mostrare un indirizzo sbagliato.
- **Copia e condividi** sono bottoni nascosti nell'HTML che `app.js` mostra solo se il browser sa fare `navigator.clipboard` e
  `navigator.share`, e rifà dopo ogni cambio di pagina; la conferma sta nel bottone per 3 secondi e in una regione `role="status"`. Senza
  JavaScript restano l'indirizzo, selezionabile, e il collegamento all'anteprima.
- **Anteprima per i maestri**: lo stesso componente della pagina pubblica (`gathering/infoContent.jte`, con il livello dei titoli come
  parametro), il bottone «Iscrivi i tuoi figli» è un bottone che non fa niente e non porta mai all'iscrizione. In bozza non c'è «Stampa
  la pagina» né il blocco dell'indirizzo (non è ancora vero).
- **Stampa**: il CSS di `@media print` sta in `app.css` fuori da ogni `@layer`, quindi vince sulle utility senza `!important`. Nero su
  bianco, A4 verticale, l'indirizzo al posto del bottone, gli avvisi con il bordo nero (il significato non dipende da uno sfondo). I
  test e2e la provano con l'emulazione della stampa e con un PDF di Chromium (una pagina A4 con testi normali); nessun PDF nel repo.
  **Testi al massimo** (5.000 e 2.000 caratteri): la QA ha visto 5 fogli con buchi bianchi, perché le schede «Informazioni» e «Cosa portare» non
  si potevano spezzare fra le pagine. Ora si spezzano (le schede brevi restano intere), il titolo resta attaccato al testo, un paragrafo non lascia
  meno di tre righe da solo e una voce dell'elenco non si taglia: 4 fogli, pieni. Il design diceva «due fogli», ma a 11 pt quel contenuto è alto
  3,9 fogli anche con ogni foglio pieno (misurato), quindi due non si possono avere senza cambiare la grandezza del testo; la guida (4.12) ora dice
  quattro. Il test e2e controlla che i fogli non siano più di quelli che il contenuto riempie (il PDF sta in una cartella temporanea, mai nel repo).
- **Il recapito del titolare** non sta sulla pagina: `docs/03-privacy.md` lo assegna all'informativa di TALITA-5 (domanda 6 approvata).

## 2026-10-23: il repo resta pubblico (TALITA-5)

Talità resta un repo pubblico, con licenza AGPL-3.0, come deciso in TALITA-2. Matteo l'ha confermato dopo essersi chiesto se lo scheletro in comune con Koinè fosse un rischio: con Koinè Talità condivide solo lo scheletro tecnico (lo stack, il guscio e il design system), mentre le funzioni sono diverse. Le regole di un repo pubblico (dati sintetici, niente nomi veri né indirizzi, controlli di build sui file) restano quelle di `CLAUDE.md`.

## 2026-10-23: scelte di TALITA-5

Iscrizione dei figli, che è anche la manleva. Analisi e design approvati da Matteo il 22/10/2026 con le raccomandazioni delle domande aperte.
**Il ticket non si spezza**: un branch e una PR, con cinque passi fatti come commit in quest'ordine: schema e regole, iscrizione dal genitore,
iscrizioni del genitore, maestri, regole di TALITA-4 e chiusura.

**Valori approvati**

- **Testo dell'affidamento**: viene dal modulo cartaceo di oggi (dato da Matteo come modello vuoto) ma è **generico, senza luoghi né parrocchie**: il
  modulo non va nel repo. Versione `affidamento-1`; l'ultimo paragrafo è la dichiarazione sull'altro genitore («oppure di esercitare da solo/a la
  responsabilità genitoriale»), che prima del rilascio conferma una persona competente insieme al resto (`docs/03-privacy.md`). Il modulo di carta
  chiede ancora il luogo di nascita e non ha una casella di consenso per le segnalazioni: Matteo valuta se allinearlo all'app.
- **Registro delle letture** tenuto un anno; **niente mail di conferma** (la conferma è la pagina «Iscrizioni», e a Mailjet non arrivano nomi di
  bambini); **un solo recapito**; **due genitori con account diversi**: ognuno vede solo quello che ha iscritto lui, i maestri vedono il possibile doppione e
  ne ritirano una; **data di nascita confermata**; la **cartacea reclamata** non si collega all'account; la home non mostra la convivenza a chi non ha
  iscrizioni; **niente nome del genitore** (chi accetta è l'account, con l'indirizzo); la **casella sulle convinzioni deducibili** resta, da verificare con la DPIA.
- **Segnalazioni mai su un foglio stampato**, e la parrocchia del bambino non si raccoglie.
- **Un solo ticket e una PR** (il limite di CodeRabbit sono 100 file: i mockup di `docs/design/TALITA-5/` non contano, sono esclusi da `.coderabbit.yaml`).

**A. Iscrizione dal genitore**

- **Pacchetto `talita.registration`**, template in `src/main/jte/registration/`, modello del testo in `src/main/resources/registration/`. La migrazione
  `V5__registration.sql` ha figli, iscrizioni, giorni, segnalazioni e registro, con i vincoli provati sul database (accettazione tutta o niente, il cartaceo
  senza accettazione e con il maestro che l'ha inserito, i giorni solo per un'iscrizione «alcuni giorni» con una chiave composta, una sola iscrizione per
  bambino e convivenza). Nessuna cascata verso `child` e `account`: le cancellazioni sono di TALITA-7.
- **Due passi senza bozza sul server**: il passo 2 è la risposta al POST del passo 1 (200, non un redirect, `HX-Push-Url: false`), i dati viaggiano nei
  campi nascosti, e «Accetta e iscrivi» rifà tutto sul server (controlli, quota, testo, impronta): mai ci si fida dei campi nascosti. Salvare una bozza
  avrebbe tenuto nomi di bambini e segnalazioni fuori dalle loro tabelle. Il tasto indietro del telefono esce dall'iscrizione, e la pagina lo dice.
- **Impronta del testo mostrato** (SHA-256, nel campo `textHash`): se cambiano affidatari, titolo, date, luogo o il bambino, la conferma risponde 409 con il testo
  nuovo, le caselle da spuntare di nuovo e l'avviso. Si conserva il testo risultante, intero, non il modello; un test fissa l'impronta di ogni versione.
- **Chiusura e conferma insieme**: la conferma legge la convivenza con `select … for share` e la chiusura la prende `for update`, quindi o l'iscrizione esiste
  prima della chiusura o non esiste (due transazioni vere in un test, nei due versi). Le iscrizioni di un account si fanno una alla volta (`for update` sulla
  riga dell'account): il doppio tocco, anche nello stesso istante, fa un'iscrizione per figlio e la seconda risposta è la stessa conferma.
- **La quota dovuta non si salva** e la calcola una funzione sola (`Quota`, regola R5): tutti i giorni è la quota intera, alcuni giorni la giornaliera o la
  quota del pranzo per giorno, e i tre giorni «giornata» scelti uno per uno valgono tutti i giorni. Una quota non prevista non compare fra le scelte e il server la
  rifiuta. Con JavaScript si aggiorna con htmx (`hx-swap="none"` e pezzi fuori banda). La richiesta porta solo i campi della presenza (con `hx-params` e l'elenco esatto, perché htmx 2 aggiunge a ogni POST tutti
  i campi del form): non la data di nascita, il telefono né le segnalazioni, e il nome del bambino solo con due bambini o più, per le righe della card «Quota»; senza JavaScript si
  vede al passo 2.
- **Telefono** con `type="tel"` e senza `inputmode="numeric"` (la tastiera numerica di iOS non ha il «+»). Si salva con uno spazio per ogni separatore (solo le cifre e un «+»
  iniziale se non stanno in 20 caratteri).
- **Segnalazioni in un blocco che si apre** (`<details>`) dentro il blocco di ogni bambino, con il consenso lì accanto, e in una pagina a parte per cambiarle; presenza a
  segmenti, senza `select`; niente scelta preselezionata tranne «Confermata».
- **Informativa a strati**: breve al passo 2, completa a `/informativa` (nell'elenco unico delle pagine pubbliche, senza script né cookie, `noindex`).
  Titolare e recapito da `talita.privacy.controller-name` e `.contact` (`docs/04-ambiente.md`), **senza default: senza, l'app non parte**, così in produzione non escono i
  segnaposto (nel profilo `dev` e nei test sì). I testi sono quelli del design.
- **Test del rilascio** (`./gradlew build -Prelease`): fallisce finché un testo porta un segnaposto (`Informativa.markers`: la cornice «testo di prova», il termine
  ancora aperto di TALITA-7). Senza `-Prelease` il test dice che aspetta; i testi veri li legge prima una persona competente.

**B. Le iscrizioni del genitore**

- **Il genitore non ha una pagina di dettaglio**: la scheda nell'elenco è il dettaglio, e ogni azione ha la sua pagina o il suo dialogo; ogni azione finisce con un 303
  verso `/iscrizioni` e un avviso. «Ritirare» (si può rimediare: bottone primario) e «togliere le segnalazioni» (cancella: bottone di pericolo) sono due verbi e due dialoghi.
- **Il filtro «solo le sue»** è un componente solo, `OwnRegistrations`, per ogni indirizzo con un id (lettura, modifica, ritiro, segnalazioni; GET e POST): per un altro
  genitore e per un maestro che non è il titolare l'id è «inesistente» (404) e non cambia niente. Un test prova ogni indirizzo con il titolare, un altro genitore, un maestro e
  senza accesso, e un altro controlla che ogni indirizzo passi dal filtro. L'unica eccezione è il ritiro (`/iscrizioni/{id}/ritira`), che un maestro fa su qualunque iscrizione.
- **`/iscrizioni` è dei due ruoli**: per un maestro è l'elenco (C), per un genitore le sue schede. Gli indirizzi dei soli maestri sono nell'elenco unico di
  `TeacherPaths` (con una regola sul numero dell'id, `{id:[0-9]+}`), con il suo test; quelli del genitore con un id no, perché li usa il genitore.
- **Regole R1 in un posto solo** (`Rules`): bozza nessuna iscrizione; aperte e fine non passata, il genitore iscrive, modifica e ritira; chiuse, vede e cambia solo le
  segnalazioni (fino all'ultimo giorno); finita, vede e può solo togliere le segnalazioni; i maestri inseriscono e correggono in ogni stato tranne la bozza. «Finita» è
  il giorno di Roma, dal `Clock`. Una modifica chiesta a mano quando non si può risponde 409 con la stessa pagina e un avviso, e non cambia niente.
- **Il ritiro non toglie la scheda**: resta «Ritirata», con la quota non dovuta e i giorni che non contano più (R3); le segnalazioni si cancellano subito. «Rimetti l'iscrizione»
  la riporta «Confermata» o «In forse» con l'accettazione di allora; le segnalazioni si riscrivono.
- **Il recapito è uno per convivenza**: cambiarlo dalla modifica lo cambia per tutte le iscrizioni di quel genitore a quella convivenza.

**C. Maestri**

- **L'elenco è fatto per scorrere**: una riga per bambino con stato, manleva, doppione e quota, per cognome e nome, i conteggi in cima, e niente sulla salute, nemmeno «ha
  segnalazioni». **Nel dettaglio le segnalazioni vengono prima della manleva** (scostamento dall'analisi: in un'emergenza si cerca il telefono e le segnalazioni).
- **«Mostra le segnalazioni» è un POST** che scrive la lettura e poi risponde, nella stessa transazione: se non riesce a scriverla non si vede niente (500, mai il contenuto;
  un test lo prova con un vincolo che fa fallire la scrittura). Con htmx la risposta è la scheda e, fuori banda, la scheda delle letture; senza JavaScript la pagina
  intera. Il telefono è un link `tel:`. Un maestro che non è più tra i maestri compare come «un maestro che non è più tra i maestri».
- **Una classe sola** (`HealthNotes`) usa le tabelle delle segnalazioni e del registro, e un test fallisce se un'altra le usa (TALITA-6 passerà di lì).
- **Inserire e correggere sono due moduli**: il cartaceo ha «Manleva cartacea ricevuta» e il consenso «sul modulo cartaceo»; la correzione non tocca mai l'accettazione
  online né le segnalazioni delle iscrizioni online. Le segnalazioni di una cartacea si correggono da una pagina a parte che le mostra (e quindi scrive una lettura;
  salvare ne scrive un'altra). Per «Aggiungere» a una cartacea senza segnalazioni la pagina è vuota e non legge niente.
- **Possibile doppione**: stesso nome, cognome (`unaccent`, minuscole, senza spazi ai bordi) e data di nascita fra due iscrizioni non ritirate; è un aiuto, non un blocco, e i
  gemelli con nomi diversi no.
- **Un maestro che è anche genitore** iscrive i suoi figli dal modulo del genitore (account suo, accettazione online); li ritrova nell'elenco dei maestri, e le letture delle loro
  segnalazioni si registrano dalle pagine dei maestri, non da quella del genitore.

**D. Regole di TALITA-4 con le iscrizioni**

- **R2, quote**: il modulo delle quote dice quante iscrizioni non ritirate ricalcola; una quota usata (giornaliera, solo pranzo) non si svuota, e l'errore dice quante la usano e
  di scrivere 0. **R3, giorni**: aggiungere giorni si può (chi viene «tutti i giorni» viene anche quello); togliere un giorno scelto da un'iscrizione non ritirata, o compreso in
  «tutti i giorni», si rifiuta con una frase per giorno sotto il campo che lo perde. **R4, affidatari**: il testo accettato resta quello conservato, e il modulo lo dice. Il conteggio
  sta in `talita.registration` e lo chiede `talita.gathering` attraverso un'interfaccia (`RegistrationUsage`), senza dipendenze circolari; i conteggi si fanno nella transazione che
  cambia, sotto il lock della convivenza.
- **Scheda «Iscrizioni» nel dettaglio della convivenza**: solo conteggi, mai nomi, con il collegamento all'elenco (anche delle convivenze più vecchie); in bozza una frase che dice
  che non ce ne sono, nemmeno da carta.

**Scelte dove il design non diceva niente**

- **Le due regole d'accordo del testo** (`{b:suo|loro}` per i bambini, `{a:lo/la|li}` per gli affidatari) sono mie, per il modello di Matteo: «li/lo/la autorizzo» è riferito ai
  maestri, «loro/suo bene» ai bambini; il sesso non si chiede, quindi al singolare «lo/la» e «nato/a». I bambini sono elencati con `;` e «e» davanti all'ultimo. Per una convivenza
  di un giorno il testo dice «dal 18 settembre 2027 al 18 settembre 2027» (il modello non cambia).
- **La dichiarazione sull'altro genitore è l'ultimo paragrafo del modello**: nella pagina è la seconda casella (con le parole di Matteo) e nel testo conservato è l'ultima riga.
- **`childId` nascosto** nei blocchi che vengono da «I tuoi figli», così correggere un nome o una data non fa un altro bambino.
- **Presenza `lunch`** per una convivenza di un giorno («Solo pranzo»): è un'iscrizione «alcuni giorni» con quel giorno a pranzo. Con la sola quota intera non c'è niente da scegliere e il
  valore va in un campo nascosto.
- **I bottoni dei blocchi mandano il loro `name="azione"` e il valore** (htmx lo include da solo): niente `hx-vals`. Gli importi di queste pagine hanno lo spazio non separabile
  prima di «€» (il formattatore di TALITA-4 usa uno spazio normale, e le sue pagine non cambiano).
- **Stato non valido** (a mano): l'errore sotto lo stato è «Scegli «Confermata» o «In forse».» (il design non aveva il testo).
- **Test**: `RegistrationTestKit` e `RegistrationRows` preparano maestri, convivenza e iscrizioni (anche direttamente in tabella, per i test che non riguardano il farle); la prima
  convivenza parte da un venerdì a un mese di distanza con i tre giorni nello stesso mese, così le frasi sui giorni non dipendono da quando gira il test. Il database è condiviso:
  `GatheringSupport.reset()` cancella prima le iscrizioni.

## 2026-10-24: camere, cucina e solleciti nel primo rilascio (TALITA-1)

Decisione di Matteo, 24/10/2026: **camere** (TALITA-11), **cucina e menù** (TALITA-12) e **solleciti via email** (TALITA-13) entrano nel primo rilascio. I tre ticket sono sotto l'epica TALITA-1 (M1 · Primo rilascio). **Questa voce supera la parte della voce del 21/10/2026 («nascita di Talità») che metteva «camere, cucina e menù, solleciti via email» fuori dal primo rilascio**: quella voce è storia e non si riscrive; le altre cose che elenca fuori dal primo rilascio (rendiconto e raccolte, funzionamento senza rete, firma con valore legale, sesso e luogo di nascita, dati dei maestri per la struttura, documenti caricati) restano fuori.

Restano i vincoli già scritti nei ticket:

- **camere**: assegnate a mano dai maestri, senza registrare il sesso e senza mandare dati dei bambini a servizi esterni o a un'IA;
- **cucina**: il minimo indispensabile, mai nomi accanto ai dati sanitari;
- **solleciti**: prima si decide a chi si scrive e con quale scopo per l'indirizzo email.

Le analisi sono nei ticket.

## 2026-10-24: scelte di TALITA-6

Check-in dal telefono e stampe. Analisi e design approvati da Matteo il 23/10/2026. **Il ticket non si spezza**: un branch e una PR, con cinque passi fatti come commit in
quest'ordine: schema e servizi, Arrivi e scheda, quota recapito e segnalazioni nella scheda, stampe, chiusura. Cosa non c'è in questo ticket: rendiconto e restituzioni,
pagamenti online e ricevute, uscite e orari; **la cucina e il menù non sono in questo ticket: sono in TALITA-12**, e camere e solleciti in TALITA-11 e TALITA-13 (voce del
24/10/2026).

**Valori approvati**

- **Segnalazioni mai su un foglio stampato** (domanda 6: no): il foglio per i maestri ha solo bambino e recapito; non c'è la colonna `from_sheet` né letture dal foglio, e non c'è
  nessuna deroga alla regola 4. Le segnalazioni si vedono solo dalla scheda, con una lettura registrata (`docs/03-privacy.md`).
- **La parrocchia del bambino non si raccoglie** (domanda 1: no). **Niente note libere nell'app** (domanda 3): la colonna «Note» del registro stampato ha le indicazioni
  dell'app (in forse, dal cartaceo, possibile doppione, giorno non previsto) e lo spazio per scrivere a mano.
- **Elenco per la struttura**: gli affidatari più righe vuote per gli altri adulti (domanda 2). **Più pagamenti per iscrizione**, con il saldo calcolato (domanda 4).
  **«Presente» ogni giorno**, anche per chi dorme lì (domanda 5). Il design è approvato così com'è. Per l'inserimento dal cartaceo e per «Manleva cartacea ricevuta» valgono i
  testi di TALITA-5.
- **Un solo ticket e una PR**: il limite di CodeRabbit sono 100 file, e i mockup di `docs/design/TALITA-6/` non contano (sono esclusi da `.coderabbit.yaml`).

**A. Dati e regole**

- **Pacchetto `talita.checkin`**, template in `src/main/jte/checkin/`. La migrazione `V6__checkin.sql` ha `checkin` (una riga per iscrizione e giorno: la chiave è la coppia, e
  si scrive con `on conflict do nothing`) e `payment` (importo da 1 centesimo a 1.000 euro, maestro, momento e un codice della richiesta unico), con i vincoli provati sul
  database. Le chiavi verso `account` non hanno cascata (come in V5); verso l'iscrizione sì: presenze e pagamenti vanno via con lei (TALITA-7).
- **Un solo ordine dei blocchi** (`Registrations.lock`): l'iscrizione `for update`, poi la convivenza `for share`. Lo usano il ritiro, la correzione, il segno e il «Togli» della
  presenza, così un segno e un ritiro fatti insieme vanno in fila: la presenza nasce prima del ritiro o non nasce (due transazioni vere in un test). **Differenza dall'analisi**: l'analisi
  diceva `for share` anche sull'iscrizione, ma «in forse → confermata» nella stessa transazione del segno chiede di scriverla, quindi la presenza prende `for update` come il ritiro;
  i pagamenti, che non cambiano l'iscrizione, la leggono `for share`.
- **R3 per le presenze**: `talita.gathering` ha un'interfaccia (`ArrivalUsage`) che implementa `talita.checkin`, e il cambio delle date dice quali giorni hanno presenze, anche di
  iscrizioni ritirate: un giorno con presenze non si toglie. La quota dovuta resta la funzione di TALITA-5 (`Quota`): non si salva, e il saldo (dovuta meno pagata) nemmeno
  (R2). Un segno su un giorno non previsto nasce con l'avviso, e la quota non cambia finché non si corregge l'iscrizione.
- **L'importo si legge come le quote** (`GatheringChecks.amount`, virgola o punto, al più due decimali) e va da 0,01 a 1.000 euro. **Il form porta un codice della richiesta** che
  scrive il server: lo stesso form mandato due volte (doppio tocco, rete che torna) è un pagamento solo e la seconda risposta mostra lo stato; lo stesso codice per un'altra
  iscrizione non scrive niente (409).
- **Il design diceva «già tolto da Paolo Verdi»**, ma il modello dei dati non tiene chi toglie un pagamento (un pagamento tolto sparisce con tutti i suoi dati): il testo è «Quel pagamento era
  già stato tolto.». **Zero in parole**: «Pagata Niente» e non «0 €» (la guida e il commento di Grace lo chiedono; il mockup della scheda aveva «0 €»).
- **Giorno scelto e convivenza di Arrivi**: Arrivi riguarda la convivenza non in bozza che comincia per ultima; il giorno è oggi (il giorno di Roma, con il `Clock`), il primo prima
  dell'inizio e l'ultimo dopo la fine. Le presenze si segnano fino a oggi, non oltre; dopo la fine si correggono ancora (la trascrizione del registro di carta) con un avviso.

**B. Arrivi e scheda**

- **Una risposta per ogni tocco, con quello che è vero adesso**: la riga con il riepilogo (e una frase per il lettore di schermo), o per la scheda l'intero blocco `#scheda`; senza
  JavaScript un redirect che si porta dietro la conferma (letta una volta sola). 200 per «fatto» e «già fatto» (un'altra persona ha segnato un momento prima: nessun errore),
  409 per ciò che non si può più fare (ritirata nel frattempo, giorno dopo oggi o fuori dalle date), 422 per un importo sbagliato. Niente aggiornamento a tempo: ogni risposta è il database di quel momento.
- **Il filtro gira nel browser** (`app.js`, sui nomi già nella pagina): senza lettere maiuscole, accenti e apostrofi, ogni parola nel nome o nel cognome; nessun nome negli indirizzi né nei
  log; senza JavaScript il campo non c'è e la lista è intera. **Nessun service worker**: una pagina già aperta si stampa e si filtra senza rete, ma per aprirla serve la rete.
- **Segnalazioni**: «Mostra le segnalazioni» è un POST che passa da `HealthNotes` (la lettura si scrive e poi le segnalazioni si leggono, in una transazione) e risponde con la scheda aperta
  **solo in quella risposta**: ogni altra azione la richiude, e se la lettura non si scrive non si vede niente. Il test di TALITA-5 che fissa una sola classe resta com'era.
  La scheda chiusa è uguale per ogni bambino (un test confronta il markup con e senza segnalazioni).
- **`app.js`**: un POST rifiutato con 4xx (tranne 409, 422 e 429, che sono la risposta) mostra «Qualcosa non ha funzionato»; un frammento chiesto con GET che risponde 4xx resta muto.
- **Un doppio tocco non annulla il primo** (difetto del QA, commento 10948; il design, parte 1, punto 3.4, lo voleva e non era vero): il bottone nuovo, «Togli» al posto di «Segna presente» o
  l'inverso, sta dove stava il vecchio, quindi il secondo tocco (anche 80 ms dopo il primo) ci cadeva sopra e toglieva la presenza, o la rimetteva, senza domanda. **La correzione è solo CSS**:
  nella risposta a un tocco la riga della lista (`RowView.focus`) e il blocco del giorno della scheda (c'è l'avviso) hanno la classe `animate-touch-pause`, un'animazione di 700 ms che tiene
  `pointer-events: none` (si eredita fino al bottone e al nome, che col mouse sta sotto il punto del primo clic) e poi torna `auto` da sola. Nessun `disabled` (il fuoco `data-fuoco` si perderebbe e il nome accessibile direbbe «non disponibile»), nessun
  `aria-disabled`, nessun codice in `app.js`: il fuoco e il nome non cambiano. Senza JavaScript la classe non c'è nella riga (la prima pagina non ha `focus`) e nella scheda c'è con l'avviso:
  la pagina intera si comporta come prima, con la stessa pausa. Il tocco singolo dopo la pausa toglie la presenza come prima, e il criterio 6 resta: nessuna conferma. **Il limite**: la pausa
  è per il dito e per il mouse; chi preme Invio due volte sulla tastiera non è protetto. Gli e2e con Playwright aspettano da soli (il bottone non è il bersaglio del tocco finché dura la pausa).
- **Differenze dal codice di TALITA-5**: `Registrations` ha in più `lock`, `confirmIn` (in forse → confermata dentro la transazione del segno) e `restoreConfirmed` («Rimetti
  confermata», con le regole di TALITA-5); il dettaglio dell'iscrizione ha «Apri in Arrivi» e il modulo dal cartaceo sa tornare ad Arrivi (`ritorno=arrivi`); la pagina segnaposto
  di Arrivi non c'è più. La nota «Chi li riceve» delle due informative dice che la struttura riceve su carta nome e cognome (`docs/03-privacy.md`).
- **Le informative dicono presenze e pagamenti** (commento di CodeRabbit sulla PR #7, fuori dal diff, Major): `docs/03-privacy.md` li tratta come dati del servizio chiesto dal
  genitore, ma né l'informativa completa né quella breve li nominavano. Ora la completa li ha fra i dati raccolti («Segnati dai maestri durante la convivenza»: presenze per
  giorno e pagamenti, con il maestro che li ha segnati), fra gli scopi, in «Chi vede i dati» (solo i maestri) e in «Per quanto tempo» (con l'iscrizione, un anno dopo la fine); la
  breve ne parla in «A cosa servono» e in «Per quanto tempo». I testi esistenti non cambiano, si aggiunge soltanto; un test (`t6 c31`) controlla entrambe le pagine.

**C. Stampe**

- **Pagine intere** (`/arrivi/registro`, `/arrivi/elenco`, `/arrivi/foglio`) con il bottone «Stampa», nascosto nella pagina e mostrato da `app.js`; l'ora è quella del server alla richiesta. La
  pagina è larga `max-w-5xl` (`Shell.wide`); nessuna stampa legge le segnalazioni, quindi nessuna scrive una lettura.
- **Un foglio di carta è un blocco** con la sua tabella e la sua intestazione (Safari non ripete il `thead`) e un salto di pagina prima di ogni blocco dopo il primo; le righe non si
  spezzano. **Il server divide le righe** (`Pagination`, con un test: nessuna riga persa, nessun foglio vuoto, l'ultimo con il posto per quello che segue) con le capacità misurate dal
  design con Chromium, e conta il «di M» con la stessa regola; se l'ultimo foglio non ha posto per i totali, i maestri o la frase finale, vanno in un foglio a parte. Un test dei
  fogli veri (il PDF di Chromium, fuori dal repo) confronta le pagine con il «di M» per il registro a tre e a sette giorni, per l'elenco e per il foglio, anche con nomi su due righe.
  **Safari su iPhone lo prova Matteo prima del rilascio** (`docs/03-privacy.md`).
- **Dove il design taceva**: l'elenco senza iscrizioni confermate ha uno stato vuoto («Nessuna iscrizione confermata») fatto come quello del giorno senza presenti; la riserva dei maestri
  nell'elenco è 6 righe più gli affidatari (8 con due, come dice il design); l'elenco di un giorno ha i bambini presenti e non ritirati; un giorno che non è della convivenza è una pagina che non
  esiste; il riquadro che scorre del registro è una regione con nome e `tabindex="0"` (senza, axe segnala che la tastiera non lo raggiunge).
- **Nella guida** (`docs/design/README.md`): tre componenti nuovi, il selettore di giorno (4.20), il riepilogo con numeri (4.21) e la ricerca in una lista (4.22); la riga con azione rapida in 4.3, «Chiama» e «Stampa» in 4.1, le tabelle stampabili in 4.12 e la `select` in 4.2. **I test di navigazione** dei ticket precedenti
  cercano la voce nella navigazione principale: il selettore dei giorni di Arrivi è una seconda `nav`.

**D. Test**

- Ogni criterio ha il suo test: unitari (regole, saldo, importi, divisione in fogli), pagine su Postgres vero (anche con due transazioni dove il criterio lo chiede: ritiro e segno, due maestri
  insieme, due pagamenti uguali), indirizzi provati con un genitore, un maestro e senza accesso, log, parole vietate e pagine senza stili né script nel markup, e e2e su telefono e desktop con axe
  (lista, scheda, pagamenti, segnalazioni, stampe e una giornata intera).

## 2026-10-24: scelte di TALITA-7

Conservazione dei dati e cancellazioni automatiche. Analisi e design approvati da Matteo il 22/10/2026, con le raccomandazioni delle nove domande aperte e il design
così com'è, comprese le proposte della parte 4. **Il ticket non si spezza**: un branch e una PR, con cinque passi fatti come commit in quest'ordine: schema e
regole, job, cancellazione su richiesta, pagine, chiusura. Il perimetro è quello dell'analisi; le camere (TALITA-11) aggiungeranno una regola al job e alla cancellazione su
richiesta, e il modo per farlo è già pronto (sotto).

**Valori approvati** (domande dell'analisi, risposta: la raccomandazione)

- **Account vuoto** (non maestro, non nel registro delle letture, senza figli né iscrizioni): si cancella 30 giorni dopo la creazione, con il solo giorno di creazione (`account.created_on`), non con l'ultimo accesso, che sarebbe un dato sull'attività.
  **Inviti scaduti**: 30 giorni dopo la scadenza. **Account dei maestri tolti**: nessuna regola a parte, dopo «Togli» valgono quelle di tutti gli account; niente rimozione automatica di chi non viene più (per dopo, un promemoria in «Maestri»).
- **Registro delle letture**: un anno dalla fine, con l'iscrizione; l'account di chi ha letto resta con il solo indirizzo finché restano le sue letture, anche se chiede di cancellarlo.
- **Manleva quando il genitore chiede di cancellare tutto**: resta fino a un anno dalla fine (art. 17.3.e) solo se serve (iscrizione non ritirata di una convivenza finita, o con presenze o pagamenti); il resto va via subito. **Prima del
  rilascio** la persona competente controlla se un anno basta (`docs/03-privacy.md`): cambiarlo vuol dire una costante.
- **Backup**: nessuno più vecchio di 30 giorni; i requisiti per TALITA-8 sono in `docs/03-privacy.md`. **Nessuna mail prima di cancellare un account** (porterebbe l'indirizzo a Mailjet e somiglierebbe a un phishing). **Famiglie del cartaceo**: nessuna
  azione nell'app, le richieste arrivano al titolare (risponde entro un mese). **Figli di chi non torna**: vanno via con l'ultima iscrizione.

**A. Dati e regole**

- **Pacchetto `talita.retention`**: `RetentionPolicy` (le costanti e le date, l'unico posto che fa conti con le date; è un `data class` con i valori del prodotto in `STANDARD`, così un test ne costruisce uno con altri periodi), `RetentionTexts` (le parole dei tempi: il
  formattatore italiano `P1M` → «un mese», `P30D` → «30 giorni», le informative, gli stati «cancellati»), `RetentionRule` e le sue regole, `RetentionJob`, `RetentionSchedule`, `RetentionLog`, `ErasureService`, `YourDataPresenter` e `ErasureController`.
- **`V7__retention.sql`**: `account.created_on` (senza default: lo scrive l'applicazione con il `Clock`; le righe che c'erano prendono il giorno della migrazione), `registration.entered_by_account_id`, `checkin.recorded_by_account_id` e `payment.recorded_by_account_id`
  nullable con `on delete set null`, e il registro `retention_log` con i suoi vincoli. **Differenza dall'analisi**: la migrazione cambia anche tre vincoli di `registration` (V5). Quelli di V5 distinguevano la cartacea dall'iscrizione online con `account_id`
  (nullo = cartacea) e chiedevano che la cartacea avesse sempre il maestro che l'ha inserita; ma un'iscrizione online il cui genitore cancella l'account resta senza account (con l'accettazione, fino alla sua data), e una cartacea perde il
  maestro quando il suo account va via. Adesso **la cartacea è quella senza accettazione** (`RegistrationData.onPaper = custodyText == null`): `registration_paper_has_no_consent`, `registration_online_not_typed_in` e `registration_paper_form_is_paper` al posto dei tre di
  V5; `registration_online_has_custody` (chi ha un account ha accettato) resta. Le altre chiavi verso `account` (`child`, `registration`, il consenso delle segnalazioni, il registro delle letture) **non hanno cascata**: le sistema `ErasureService` prima del `DELETE`,
  e uno schema test prova che ognuna rifiuta.
- **«Cancellate» dipende dalla data e non dalle righe**: `Registrations` e `HealthNotes` non leggono più un dato oltre la sua data, anche nelle ore fra la mezzanotte e il job, e le pagine dicono «Cancellate il …» dal giorno della data. Così un dato sulla salute scaduto non si
  mostra mai e la pagina non cambia a seconda dell'ora. Per le iscrizioni è la regola consigliata dal design; l'ho fatta una volta sola, in `Registrations.select`.
- **Chi segnò una presenza o un pagamento** può non esserci più: `Checkin.byAccountId`, `Payment.byAccountId` e `Collected.byAccountId` sono nulli, e le pagine dicono «un maestro che non è più tra i maestri» (che c'era già per un maestro tolto).

**B. Il job**

- **Un elenco ordinato di regole**: `RetentionRule` (`name` e `apply(tx, context)`), una classe per regola con `@Order`: segnalazioni, iscrizioni (con i legami con gli affidatari), figli, inviti, account, richieste da rifare, numeri scaduti. **Aggiungere una regola è aggiungere un bean**: il job
  non cambia (le camere lo faranno così). Una regola è una transazione sotto un lock consultivo di Postgres (`pg_advisory_xact_lock` con la chiave del suo nome); non c'è ShedLock, perché l'istanza è una. Una regola che fallisce torna indietro e le altre girano.
- **Quando gira**: a ogni avvio (`ApplicationReadyEvent`) e ogni notte alle 4:15 (`@Scheduled(cron = "0 15 4 * * *", zone = "Europe/Rome")`, con `@EnableScheduling` e lo scheduler di Spring Boot a un thread). Guarda il giorno di Roma dal `Clock` e mai l'ora. I test dell'applicazione
  impostano `talita.retention.automatic=false` (il job gira a mano con un `Clock` fisso, e non cancella da solo sotto gli altri test); senza quell'impostazione, e senza profili, avvio e cron sono accesi, e un test lo prova. Lo smoke test del jar controlla il log dell'avvio.
- **Il log dice solo numeri**: la regola e quante righe (`Retention: rule notes deleted 12 rows.`); un errore dice il nome della regola e il tipo dell'eccezione, mai il suo messaggio.
- **Registro**: `notes`, `registrations`, `children`, `invitations`, `accounts` per le regole a tempo (una riga se hanno cancellato qualcosa), `child-request` e `account-request` per le richieste (con l'id), `child-redo` e `account-redo` per quelle rifatte (senza id). Il numero di righe è quello delle
  tabelle dei dati personali (`registration`, `child`, `health_note`, `teacher`…), senza contare la cascata. I numeri scaduti non scrivono una riga: il log dice quanti ne ha tolti.

**C. Cancellazione su richiesta**

- **Una sola classe** per il piano dei dialoghi, la transazione e il rifacimento dopo un ripristino (`ErasureService`): le frasi non possono dire altro da quello che si fa. **Il controllo dei blocchi sta nella transazione**: l'account `for update`, le iscrizioni `for update` e le loro
  convivenze `for share`, nell'ordine di ogni altra modifica di un'iscrizione; per il maestro, `Teachers.lockAndCheck` (lo stesso lock e le stesse regole di «Togli»: l'ultimo maestro, e l'affidatario di una convivenza non finita). Un test prova una richiesta che aspetta un ritiro non ancora salvato e poi lo trova.
- **Differenze dall'analisi**: il piano si chiama `planChild` e `planAccount` (non `plan(child)`); il rifacimento dopo un ripristino scrive `child-redo` e `account-redo`, senza id, perché la richiesta originale ha ancora il suo. **Una protezione in più**: se un account è rimasto per le letture (solo l'indirizzo) e la persona è
  rientrata e ha iscritto un bambino dopo la richiesta, il rifacimento non cancella quello che ha fatto dopo, ma solo quello che è più vecchio della richiesta (si guarda il momento della richiesta: vedi «Il rifacimento cancella il vecchio e tiene il nuovo»); senza, la notte dopo avrebbe cancellato i dati nuovi. Resta una cosa che non ho chiuso: le altre sessioni di un account che è rimasto per le letture restano
  dentro come un account vuoto (non c'è un'ora di creazione delle sessioni da confrontare con la richiesta); nessun dato è visibile.
- **Il rifacimento non cancella l'attività da maestro venuta dopo** (review di CodeRabbit sulla PR #8): un account rimasto per le letture può ricevere un invito da maestro, o accettarlo, senza iscrivere figli; `startedAgain` guardava solo le iscrizioni, e il rifacimento notturno avrebbe cancellato l'invito e la riga di `teacher`.
  Per saperlo serve un momento che non c'era: `teacher` aveva solo l'account e il nome, `teacher_invitation` solo l'indirizzo e la scadenza, e nessuna data che c'è già lo dice (`account.created_on` è dell'account, che c'era prima della richiesta; la scadenza dell'invito dipende dalla durata impostata, e `teacher` non ha niente).
  **Scelta**: `V8__teacher_moments.sql` aggiunge `teacher.created_at` e `teacher_invitation.invited_at`, `timestamptz not null` senza default (li scrive l'applicazione con il `Clock`: `Invitations.invite`, che a un nuovo invito lo rinnova con la scadenza, `Invitations.accept` e `Teachers.create`); le righe che c'erano prendono il momento della migrazione.
  È la sola cosa in più, con l'ora e non solo il giorno di `account.created_on` (lo stesso giorno la richiesta può venire prima o dopo di una riga di un backup), senza altri dati; scopo in `docs/02-modello-dati.md`. Una V8 e non una modifica di V7, perché V7 può essere già applicata su un database di sviluppo (il checksum non tornerebbe).
  La prima versione di questa correzione guardava il momento in `startedAgain` e saltava tutto il rifacimento se dopo l'ultima richiesta c'era un'iscrizione, una riga di `teacher` o un invito: la voce che segue la sostituisce, e `startedAgain` non c'è più.
- **Il rifacimento cancella il vecchio e tiene il nuovo** (seconda review di CodeRabbit sulla PR #8): saltare tutta la richiesta quando c'era qualcosa di nuovo lasciava anche i dati di prima che un backup aveva riportato (figli, iscrizioni, segnalazioni, riga di `teacher`), e l'account poteva restare in piedi perché il registro delle letture ne tiene l'indirizzo.
  Adesso `redo` passa a `eraseAccountIn` il momento dell'ultima richiesta (`since`) e **la stessa funzione della richiesta**, senza una seconda copia della logica, cancella solo quello che è nato prima:
  - le iscrizioni con `created_at` dopo la richiesta non si leggono nemmeno (non si classificano, non bloccano, non si cancellano), e un figlio che ne ha una resta attaccato all'account; le più vecchie vanno come nella richiesta (quelle che restano come prova perdono il legame con l'account);
  - le segnalazioni delle iscrizioni vecchie vanno sempre; il consenso dato dall'account si cancella solo se è di prima (`HealthNotes.removeConsentedBy(..., until)`, con `health_note.consent_at`);
  - la riga di `teacher` nata dopo la richiesta resta, e non si chiama `lockAndCheck` (non si toglie nessuno: né l'ultimo maestro né un affidatario); una più vecchia va via come con la richiesta, e quindi può anche bloccare (un `WARN` senza dati, decide il titolare); l'invito a quell'indirizzo si cancella solo se è più vecchio;
  - **l'account non si cancella se ha ancora qualcosa**: le letture, ma anche un'iscrizione o un figlio a suo nome, una riga di `teacher` (`on delete cascade` la porterebbe via in silenzio), un invito o un consenso. Per la richiesta della persona (`since` assente) non cambia niente.
  Le iscrizioni nate dopo la richiesta si trattano come il resto perché il ragionamento è lo stesso (nate dopo: della persona; nate prima: di un backup), e così non c'è più una condizione a parte per gli account con letture: il momento si guarda per ogni account. Per uno senza letture non cambia niente, perché un backup più vecchio della richiesta non ha righe nate dopo di lei. Se c'è solo il nuovo e nessun vecchio da cancellare, il rifacimento non scrive nel registro. **Il figlio non ha questo caso** (`child-redo` resta com'è): dopo la sua richiesta il figlio non c'è più o è staccato per sempre, quindi uno che è di nuovo di un account è tornato con un backup, intero.
- **Il profilo dice tutti i blocchi dell'account** (review di CodeRabbit sulla PR #8): un'iscrizione ritirata con presenze resta anche quando il figlio è staccato; se un maestro la rimette («Rimetti confermata») prima della fine della convivenza, la richiesta di cancellare l'account la trova come blocco (409), ma il piano contava solo i figli ancora attaccati e il profilo
  non diceva niente e metteva il bottone. Adesso `AccountPlan.blockers` è l'elenco intero di `classify`, lo stesso che la richiesta mette in `AccountErasure.Blocked`. **Una frase in più, da far vedere a chi cura il design**: «Iscrizioni» mostra in sola lettura l'iscrizione di un figlio staccato, quindi «ritira l'iscrizione» con il link sarebbe un vicolo cieco
  e «le iscrizioni sono chiuse» sarebbe falso; il blocco (`Blocker.detached`) dice «chiedi ai maestri di ritirare l’iscrizione di Luca alla «…»: un maestro l’ha rimessa dopo che avevi cancellato i dati di Luca.», senza link e anche a iscrizioni aperte. Un caso raro: un figlio staccato con un'iscrizione ritirata con presenze, rimessa durante la convivenza.
- **Un figlio che resta è staccato dall'account** (`child.account_id = null`) e l'iscrizione resta a nome del genitore, in sola lettura; dopo la cancellazione dell'account l'iscrizione perde anche il legame (`registration.account_id = null`) e mantiene l'accettazione con l'indirizzo copiato. Lo
  stesso `{id}` che non c'è più ma è nel registro risponde con il profilo e «Questi dati erano già stati cancellati.» (criterio 22); gli altri sono la 404.
- **Le proposte del design fuori dai criteri, approvate, ci sono**: la riga «Su carta» della scheda «Conservazione dei dati»; «Account cancellato.» anche per l'altro telefono e la seconda frase «Se ti serve, puoi rientrare…»; e le tre cose «dopo la data» (parte 3, sezione 5): dopo un anno dalla fine «Inserisci un’iscrizione» (anche da Arrivi) è la 404,
  e Iscrizioni e Arrivi hanno lo stato vuoto; dopo un mese dalla fine il modulo dal cartaceo non ha i campi delle segnalazioni, ma un avviso informativo, e non le scrive nemmeno se arrivano a mano; `…/correggi-segnalazioni` (GET e POST) risponde 303 al dettaglio con un avviso di attenzione, e «Aggiungi le segnalazioni» non c'è.

**D. Pagine**

- **«Cancella» e «Togli»** (vocabolario): «Cancella» è per sempre e riguarda dati personali; «Togli» si può rimediare. **«Account cancellato.» non ripete cosa resta**: l'ha detto il dialogo un attimo prima, e un avviso con le date su una pagina senza accesso porterebbe dati dell'iscrizione dove non c'è nessuno a leggerli; vale anche per l'altro telefono
  (la sessione si chiude e `/accedi` dice la stessa cosa, e non «Dopo un po’ che non usi Talità…»).
- **Il dialogo e le pagine** sono quelli del design: scheda «I tuoi dati» nel Profilo, bottone di pericolo in contorno, `erasureDialog` con le sezioni «Cancelliamo subito» e «Resta», avvisi con due paragrafi (`Alert` ha un `lead`, un'etichetta e un indirizzo opzionali), sola lettura con «Conservata fino al …» in «Iscrizioni», scheda «Conservazione dei dati»,
  segnalazioni cancellate (uguale per tutte, nessuna lettura), stati vuoti neutri di «Iscrizioni», «Arrivi» e delle tre stampe. Le due icone nuove sono `archive` e `shield-check`. Nella guida (`docs/design/README.md`): il pericolo in contorno (4.1), il dialogo con elenchi (4.8), le schede (4.4), la riga di un figlio (4.3), lo stato vuoto neutro
  (4.9), «Cancella» e «Togli», il tempo del verbo e le conferme con due frasi (6).
- **Un errore del server su un form del dialogo** è la pagina di errore dell'app, come per ogni form di una pagina (`app.js`: un 500 su una richiesta che cambia pagina la sostituisce): non è un messaggio a comparsa. Il messaggio a comparsa («Nessuna connessione», senza «Riprova») c'è per la rete assente, e un test e2e lo prova.
- **Dopo il QA (D1)**: i dialoghi si aprono dall'inizio, anche su un telefono piccolo. Il fuoco resta su «Annulla», in fondo, ma `app.js` rimette il pannello in cima (`scrollTop = 0` dopo `showModal()`, nel punto comune dei dialoghi), così titolo e «Cancelliamo subito» si leggono prima di «Resta» e dei bottoni; il lettore di schermo legge lo stesso il titolo, che è il nome del dialogo.
  Il motivo per cui un'iscrizione resta lo scrive un solo punto (`RetentionTexts.keptAs`): un'iscrizione ritirata resta «come registro della convivenza» nel dialogo, nell'avviso e nella riga della scheda di «Iscrizioni»; le altre «come prova dell'affidamento». Dopo la cancellazione delle segnalazioni, la scheda delle letture dice «Nessuno le ha lette.» (senza «ancora»).
- **Informativa**: i tempi della breve e della completa vengono da `RetentionTexts` (i periodi della policy), e il termine aperto di TALITA-5 («Da fissare con TALITA-7») non c'è più; il collegamento dal Profilo porta alla scheda `#per-quanto-tempo` nella stessa scheda del browser.

**E. Test**

- Ogni criterio ha il suo test: unitari (le date: 31 gennaio + un mese, 29 febbraio + un anno), Postgres vero con un `Clock` fisso (23:59 del 19 ottobre contro 00:30 del 20, il job con due thread, un lock tenuto da un'altra transazione, una regola che fallisce con un messaggio che porta un nome, un
  ripristino simulato con id esplicito), pagine (indirizzi provati con il genitore, un altro genitore, un maestro e senza accesso; le frasi dei dialoghi parola per parola; il log), schema (vincoli e chiavi) ed e2e su telefono e desktop con axe e a 320 px.
- **Test dei vecchi ticket cambiati**: gli schemi di V5 e V6 (il maestro può mancare, la cartacea senza accettazione), la scheda di una convivenza finita in «Iscrizioni» (finisce con «Conservata fino al …»), l'elenco delle migrazioni e l'informativa (il termine aperto non c'è più).
- **Axe non gira con JavaScript spento** (non può iniettarsi nella pagina): la pagina di conferma senza JavaScript si prova con le parole e con il form, non con axe.

**F. Cosa resta fuori (per dopo, con un ticket se serve)**: cancellazione su richiesta delle cartacee dalla pagina del maestro, promemoria per togliere i maestri che non partecipano più, esportazione dei dati, cambio d'indirizzo, eliminare una convivenza. Le camere (TALITA-11): una regola del job (un bean) e, se serve, un passo in
`ErasureService.deleteRegistrations`, l'unico punto che cancella le iscrizioni.

## 2026-10-29: scelte di TALITA-13

Solleciti via email a chi non si è ancora iscritto. Nel codice e nei documenti si chiamano **promemoria** (`talita.reminder`; glossario: promemoria `reminder`, tipi `maybe` e `notice`, invio `reminder_batch`, stop, avviso `notice`). Un branch e una PR, cinque passi fatti come commit in quest'ordine: schema e posta, destinatari e testi, invio dei maestri, consenso e stop, chiusura (permessi, cancellazione, documenti, guida, e2e). I mockup sono in `docs/design/TALITA-13/`.

**Le decisioni di Matteo (24/10/2026)**

- **Approvazione** (commento 10953, «ok»): analisi e design vanno bene con la modifica che segue; valgono le raccomandazioni delle quattro domande. **Domanda 1**: due tipi, «in forse» e «avviso»; mai account vuoti (un account lo crea chiunque, e la mail porterebbe il titolo e le date che il codice della pagina informativa protegge), mai indirizzi da fuori. **Domanda 2**: scopo nuovo dell'email «promemoria delle iscrizioni»; base 6.1.b per «in forse» e consenso (6.1.a) per l'avviso, da far verificare prima del rilascio (`docs/03-privacy.md`). **Domanda 3**: invio manuale, un bottone per tipo, al più uno per tipo ogni 7 giorni; niente invii a tempo né all'apertura delle iscrizioni, perché una mail partita per un tocco sbagliato non si ritira. **Domanda 4**: quota separata di 40 promemoria ogni 24 ore. Il ticket va dopo TALITA-7; in produzione serve TALITA-9 (dominio verificato in Mailjet).
- **Il promemoria «in forse» invita solo a confermare** (commento 10950, vincolante per sviluppo e QA). Le sue parole: «l'obiettivo è di far partecipare più bambini possibili, quindi le opzioni sono "iscrivilo" o "ci penso ancora un po'"». La mail ha due opzioni: **«Conferma l'iscrizione»**, il collegamento a `/iscrizioni` (dove dopo l'accesso si porta l'iscrizione a «Confermata»), e **«Ci penso ancora un po'»**, una frase senza collegamento che dice che non serve fare niente (l'iscrizione resta in forse finché le iscrizioni sono aperte). **Niente invito a ritirare e nessun collegamento per farlo**: il ritiro resta nelle pagine di TALITA-5, come prima, e un test (`t13 c08`) controlla che il testo non contenga «ritira» né «ritirare». «Iscrivilo» è diventato «Conferma l'iscrizione» perché la mail non dice il numero dei figli né il loro sesso. Il dialogo dei maestri dice «riceveranno un invito a confermare l'iscrizione» e la scheda dice «riceve un invito a confermarla». Dove analisi, design e mockup dicevano «confermarla o ritirarla», vale questa decisione: i mockup (la mail, il blocco «in forse», il dialogo, l'informativa) sono stati aggiornati.

**Schema e posta (passo 1)**

- **`V11__reminders.sql`** (e non la V10 dell'analisi: V9 e V10 sono di TALITA-11 e 12, sviluppati in parallelo; Flyway accetta il buco, e quando i tre ticket sono su `main` i numeri sono V9, V10 e V11). In `account`: `notice_consent_at` (il consenso all'avviso, con data e ora), `reminders_off_at` (lo stop) e `reminder_code` (16 caratteri base64url da 96 bit, unico, con il vincolo del formato), più il vincolo che consenso e stop **non ci siano insieme**: chi smette perde il consenso nella stessa istruzione, e chi vuole l'avviso dopo aver ripreso lo chiede di nuovo. Il tipo `reminder_kind` e la tabella `reminder_batch` (convivenza con `on delete cascade`, come le camere e il menù: una convivenza non si cancella, la cascata è solo l'ordine dei test; tipo, momento, `recipients >= 1`, `failed` fra 0 e `recipients`) con l'indice `(gathering_id, kind, sent_at desc)`. Il registro non ha destinatari né maestro: niente dati personali. L'applicazione non scrive mai una riga se non è partita almeno una mail, ma il vincolo è quello dell'analisi.
- **`Mailer.sendAll`** manda un elenco in **una sola connessione SMTP** (`JavaMailSender.send(vararg)`: 40 mail restano sotto i 20 secondi di htmx), con un **esito per ogni mail**: la libreria dice quali messaggi sono falliti (`MailSendException.failedMessages`), e le altre sono partite. Un indirizzo che non si può costruire è un fallimento di quella mail sola. `OutgoingMail` porta l'indirizzo per smettere (`unsubscribe`), che `Mailer` scrive in `List-Unsubscribe: <…>`; non è il «one click» dell'RFC 8058, perché la pagina chiede il tocco (stessa ragione di «Conferma l'accesso»). I timeout restano quelli di TALITA-3. Il log dice quante mail non sono partite e il tipo dell'eccezione, mai l'indirizzo.
- **Due conti** (`MailKind`: `ACCESS` 150, `REMINDER` 40): `tryAcquire(kind, count)` è tutto o niente, `remaining(kind)` dice quanti ne restano, e le mail che non partono restituiscono i posti. Le firme senza tipo restano quelle di prima (il conto dei link). Insieme 190, sotto le 200 al giorno di Mailjet gratuito.

**Chi riceve (passo 2)**

- **Una classe sola** (`Reminders.recipients`) decide i destinatari, e i conteggi dei maestri sono le stesse persone. «In forse»: gli account con almeno un'iscrizione `maybe` a quella convivenza (una mail anche con due figli in forse; il cartaceo non ha un account). «Avviso»: gli account con un figlio in `child.account_id`, con il consenso e **senza nessuna iscrizione a quella convivenza, nemmeno ritirata**. Mai chi ha smesso, mai account vuoti, mai inviti. Chi si ritira o conferma dopo il calcolo riceve la mail già partita: «dall'invio dopo la persona non c'è più».
- **I testi** (`ReminderMails`) sono nel codice, solo testo, con le parole del design e la decisione del 24/10. L'oggetto normalizza gli spazi del titolo (un titolo con un a capo non rompe l'intestazione).

**L'invio dei maestri (passo 3)**

- **`/convivenza/{id}/promemoria`** (GET: pagina di conferma senza JavaScript; POST con il tipo nel corpo) sta sotto `/convivenza/**`, quindi è dei maestri (`TeacherPaths`): 403 a un genitore, anche a mano, e `/accedi` per chi non ha accesso. Un tipo che non è `maybe` né `notice` o una convivenza che non c'è è la pagina che non esiste e non parte niente.
- **La transazione dell'invio** prende `pg_advisory_xact_lock(13, convivenza × 2 + tipo)` e **dopo** il lock controlla, in quest'ordine: la convivenza (letta `for share`: chiudere le iscrizioni aspetta), le iscrizioni aperte e la fine non passata, i 7 giorni dall'ultima riga dello stesso tipo, i destinatari (calcolati lì), la quota che resta, poi scrive i codici per smettere mancanti, manda le mail e scrive la riga del registro **solo se è partita almeno una mail**. Due tocchi, o due maestri insieme, mandano una volta: la seconda richiesta aspetta il lock, trova la riga e risponde 200 con «Questo promemoria è già stato mandato il …». Un test tiene il lock da un'altra transazione e prova che la seconda aspetta e poi non manda niente. La transazione tiene una connessione del pool finché le mail partono (al più una ventina di secondi, e solo la prima volta di un account scrive il codice): accettato a questa scala.
- **Esiti** (tutti nella scheda `#promemoria`, sostituita come un blocco): mandato (`status`), mandato in parte con la data da cui si può rimandare (`alert`: la riga c'è, i 7 giorni valgono lo stesso, non c'è modo di rimandare solo a chi manca), nessuna mail partita (422: nessuna riga, il bottone resta), quota che non basta (429) o finita, già mandato (200). Il numero della conferma è quello vero al momento dell'invio, che può non essere quello del dialogo.
- **Differenza dall'analisi: l'attesa è di 168 ore esatte**, non di sette giorni di calendario alla stessa ora. La pagina e il controllo usano la stessa regola (`LastBatch.again`), quindi quello che la pagina dice è quando il bottone funziona; nella settimana del cambio d'ora la pagina dice l'ora vera (22:30 invece di 21:30). Un test lo prova a cavallo del 28 marzo.
- **Il pacchetto della convivenza non dipende da quello dei promemoria**: il dettaglio chiede la scheda a `ReminderCards` (interfaccia in `talita.gathering`, come `RoomSummaries` per le camere), che `ReminderPresenter` realizza. Il dialogo è in un solo paragrafo (`ConfirmModel` ha un testo solo): «…Non ci sono nomi né dati dei bambini. Una mail partita non si ritira, e non potrai mandare un altro promemoria di questo tipo per 7 giorni.» L'avviso dopo un invio in parte ha il «Mandato a 2 persone su 3: …» come riga in grassetto e la data sotto (`Alert` ha un `lead`).

**Consenso, stop e pagina per smettere (passo 4)**

- **La scheda dell'avviso** (`reminder/emailCard.jte`): in «Iscrizioni» (corta, in fondo, `#avviso-card`) e nel profilo (completa, fra «Il tuo accesso» e «I tuoi dati», `#email`, con lo stop). La casella non è mai spuntata prima e si salva con un bottone («Salva la scelta»): un consenso è un gesto (art. 7) e così funziona senza JavaScript. Spuntata si salva il momento (salvare di nuovo lo tiene), tolta il consenso si cancella. **Chi non ha figli non vede la scheda**, e una richiesta a mano è la pagina che non esiste; **chi ha smesso** la trova nel profilo (anche senza figli: è l'unico modo per riprendere) e in «Iscrizioni» solo se ha figli, con «Vai al profilo». Una richiesta dell'avviso di chi ha smesso è 409 e non scrive.
- **Tre indirizzi dell'account** (`/profilo/avviso`, `/iscrizioni/avviso`, `/profilo/promemoria/stop` e `/riprendi`): di ogni account, non sotto `TeacherPaths`, **non prendono nessun id** e cambiano solo l'account della sessione (un test prova A e B anche con l'id dell'altro nella richiesta). Lo stop toglie il consenso nella stessa istruzione e tiene il momento del primo; «Riprendi» non fa tornare il consenso. Nessun dialogo: si rimedia con un tocco.
- **`/promemoria/smetti?codice=…`** è in `PublicPaths` (con il suo test), nel **guscio senza accesso di `/accedi`** (non in quello pubblico della pagina informativa, che non ha né script né CSRF, e qui servono tutte e due le cose: scelta di Grace). **Aprirla non cambia niente**, il tocco è un POST con il codice CSRF. Un codice che non esiste, o che non ha la forma di un codice, o assente, ha la **stessa pagina e la stessa risposta byte per byte**, e il codice che non ha la forma non si ripete nel campo. Il codice sta solo nella query del collegamento e nel corpo del POST: mai nel log, mai in un messaggio. Il CSRF scaduto riporta alla stessa pagina (`AccessHandler`, come i form di `/accedi`) con «La pagina era aperta da troppo tempo. Riprova.». Chi la apre dentro un account ferma l'account del codice e nient'altro. La pagina ha `noindex` (meta e `X-Robots-Tag`), `Cache-Control: no-store` e nessun collegamento esterno.
- **Dove il design taceva**: una richiesta a mano senza destinatari risponde 200 con «Nessuno da avvisare.»; le iscrizioni chiuse mentre la pagina era aperta rispondono 409 con la scheda e la sua sola frase; la pagina di conferma senza niente da confermare torna al dettaglio con un 303; con più mail non partite l'avviso dice «per 2 le mail non sono partite»; per chi ha smesso e non ha figli il profilo ha la scheda, «Iscrizioni» no.

**Informativa e documenti (passo 5)**

- **Informativa breve e completa**: le frasi del design (dati raccolti, scopo e base, chi li riceve con il fornitore come titolare autonomo per l'antispam, per quanto tempo, diritti); le frasi della breve sono in `secondStep.jte` e `RetentionTexts`, quelle della completa in `informativa.jte` e `RetentionTexts` (una riga in «Per quanto tempo» e il secondo paragrafo dei diritti). La cornice «testo di prova» resta.
- **`docs/02-modello-dati.md`** (le righe del consenso, dello stop, del codice e del registro, e «Chi vede cosa»), **`docs/03-privacy.md`** (base, **la riga di Mailjet corretta**: gruppo Sinch, dati in UE a Francoforte e Saint-Ghislain **come dichiara il fornitore** e non «solo UE» finché il DPA non è verificato, titolare autonomo per antispam e sicurezza, sub-responsabili, SCC e DPF, 90 giorni; misure; conservazione; diritti; **«Prima del rilascio»**), **`docs/04-ambiente.md`** (la nuova quota e come provare i promemoria con Mailpit) e la guida del design (icone `send`, `bell`, `bell-off` e `mail-x`, la pagina per smettere nel guscio senza accesso, scheda con più blocchi, dialogo di invio, casella di consenso, htmx, i testi e le mail).
- **Cancellazione (TALITA-7)**: nessuna regola nuova. Consenso, stop e codice sono colonne dell'account e vanno via con lui; un account cancellato che rientra è nuovo, senza stop né avviso; il registro degli invii resta con la convivenza, senza dati personali.

**Test e cosa resta fuori**

- Ogni criterio ha il suo (`t13 cNN`): unitari (i testi, la quota, i vincoli), Postgres vero (destinatari, schema, due transazioni sul lock), MockMvc con le mail lette da Mailpit (pagine, permessi, log, il consenso, la pagina per smettere, un server SMTP finto che rifiuta un destinatario con un 550 vero, il server giù, senza indirizzo e senza mittente, la quota di 3 e 2), ed e2e su telefono e desktop con axe e a 320 px.
- **Test dei vecchi ticket cambiati**: l'elenco delle migrazioni (V1-V8 e V11) e l'elenco degli indirizzi pubblici; per le nuove frasi dell'informativa nessuno, perché i test confrontano le pagine con le costanti.
- **Fuori** (per dopo, con un ticket se serve): la data di chiusura delle iscrizioni nella mail, un promemoria prima della partenza («cosa portare»), SMS o WhatsApp, un elenco di indirizzi che resterebbe dopo la cancellazione di un account, chi ha cominciato l'iscrizione senza finirla (l'iscrizione non ha bozze sul server).
- **Prima del rilascio**: il parere sulla base e sulla natura di servizio della mail «in forse», il testo del consenso, la verifica dei trasferimenti nel DPA di Mailjet, la DPIA, il dominio verificato (TALITA-9); l'elenco è in `docs/03-privacy.md`.
