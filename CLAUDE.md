# Talità

Iscrizioni e organizzazione della convivenza dei bambini: la pagina informativa per i genitori, l'iscrizione che è anche la manleva (il modulo di affidamento), il check-in dal telefono e le stampe per il giorno dell'evento. La convivenza è una all'anno, a settembre, organizzata dai maestri dei bambini di due parrocchie, per 30-35 persone fra bambini e maestri. È un progetto di servizio: niente scopo di lucro e nessun costo, perché l'hosting è gratuito.

Nasce da Koinè (KOINE-29): stesso stack e stesso design system, ma è un'app separata, con repo, database e deploy suoi. Il titolare dei dati è Matteo. Il perché sta in `docs/05-decisioni.md`.

Nei testi per gli utenti si scrive Talità, con l'accento. Negli identificatori (repo, package, nomi tecnici, indirizzi, nomi dei file) si scrive `talita`, senza accento.

Il repo è pubblico (licenza AGPL-3.0): quello che scrivi qui lo può leggere chiunque.

## Chi c'è

- **Matteo**: product owner, titolare dei dati e maestro. Sviluppatore di mestiere, lavora al progetto di sera e nel weekend.
- **Claude Code**: orchestratore del flusso agentico (skill `/avanza`). Questo file è il contesto di sessione.
- **Agenti** (`.claude/agents/`), ognuno con un account Jira su un alias della mail di Matteo:

  | Agente | Ruolo | Account ID Jira |
  |---|---|---|
  | Edgar Case | architetto (`architect`) | `712020:75ae7c20-cfb1-4392-a92b-62934ecf7c58` |
  | Grace Kerning | designer (`designer`) | `712020:f3119d87-5232-4656-a6b9-d685cc9600cc` |
  | Rick Ursion | developer (`developer`) | `712020:5cd74b49-1b67-4d54-94eb-f393ab3e0b7b` |
  | Tess Ting | QA (`qa`) | `712020:c5d138eb-26a9-4169-aa3b-272776f79cb0` |

## Regole non negoziabili

1. **Nessun dato reale in locale, nel repo né nei ticket**: solo dati sintetici, generati. I documenti veri delle convivenze (manleve, elenchi, registri, schede per la struttura) non si aprono nelle sessioni: per i testi si chiedono modelli vuoti. Il database di produzione si tocca solo sul server. Se un file sembra contenere dati reali, fermati e chiedi.
2. **Separazione dal lavoro dipendente di Matteo.** Solo questo PC personale, account e servizi personali. Mai indirizzi di lavoro in `git config`, GitHub, domini o servizi, e nessun riuso di codice o informazioni del suo datore di lavoro. Il repo è pubblico: nei commit l'autore e il committer hanno l'indirizzo noreply dell'account GitHub di Matteo (`<id>+<utente>@users.noreply.github.com`, da `gh api user`), impostato con `git config` locale. Prima di ogni commit si controlla `git config user.email`: mai la mail personale né quella di lavoro.
3. **Minimizzazione.** Ogni dato personale ha uno scopo scritto in `docs/02-modello-dati.md`, e un campo senza scopo non si aggiunge. Mai sesso né luogo di nascita.
4. **Segnalazioni** (allergie, intolleranze, farmaci): stanno solo nella loro tabella e si raccolgono solo con il consenso esplicito del genitore. Le vedono solo i maestri della convivenza, ogni lettura viene registrata e un mese dopo la fine della convivenza si cancellano.
5. **La pagina informativa non mostra dati personali.**
6. **Nessun servizio di terze parti riceve dati personali**, tranne i fornitori elencati in `docs/03-privacy.md` (hosting, invio email, backup cifrati). Niente analytics, CDN, font o script esterni.
7. **Passi piccoli e reversibili.** Ogni ticket ha il suo branch e la sua PR: il diff si guarda nella PR, e su `main` si fa merge solo dopo il QA e l'ok di Matteo. Fuori dal flusso dei ticket, mostra il diff prima di ogni commit.

Repo pubblico: nel codice e nei documenti niente nomi di parrocchie o della struttura, persone vere, date reali della convivenza, indirizzi email. Si scrive «due parrocchie», «una casa per ritiri», e gli esempi usano nomi sintetici. Gli ID Jira e il cloudId sono ammessi. La build fallisce se fra i file tracciati c'è un PDF, un documento Office o OpenDocument, o un'immagine fuori da `src/main/resources/static/` e `docs/design/`; su GitHub sono attivi secret scanning e push protection.

## Principi di design

- **Due ruoli**: il maestro gestisce tutta la convivenza, il genitore vede solo i suoi figli. Nessun amministratore a parte.
- **Login per persona** con link via email (magic link): gli utenti non sono informatici.
- **L'iscrizione è anche la manleva**: accettazione dell'affidamento con data e ora, senza firme né documenti caricati.
- **Mobile first**: i genitori arrivano da un link condiviso e i maestri usano l'app dal telefono durante la convivenza.
- **Scala minima**: una convivenza all'anno, 30-35 persone. Niente multi-tenant né architetture da grandi numeri.
- **Stack portabile**: l'immagine del container più un dump del database bastano per cambiare macchina.
- **L'interfaccia non deve sembrare vecchia**: il design system è in `docs/design/README.md`.

## Stack

Il codice è generato dall'IA: gli errori devono emergere in build o nei test, non in produzione. È lo stack di Koinè, senza Spring Modulith, multi-centro né row-level security.

- Kotlin 2.3.21 (la versione gestita da Boot) + Spring Boot 4.1.1, JDK 25 LTS, Gradle 9.8 con Kotlin DSL e toolchain
- Un solo pacchetto radice `talita`: il guscio in `talita.ui`, il codice generato da jOOQ in `talita.jooq`, un pacchetto per area sotto `talita` nei ticket successivi. Niente test dei confini: l'ordine lo tiene la review.
- PostgreSQL 18 + Flyway, con la sola estensione `unaccent`. Nessun `centro_id` e nessuna RLS: l'isolamento fra genitori lo fa l'applicazione, con i suoi test.
- Accesso ai dati con jOOQ, codice generato dalle migrazioni Flyway. Niente JPA/Hibernate.
- UI server-side: template JTE 3.2.4 (`.jte`, compilati e tipizzati) + htmx 2.0.11 + Tailwind 4.3.3 con il binario standalone, senza Node né npm. Niente SPA. JS, CSS e font serviti dall'app, mai da CDN.
- Spring Security 7 con one-time token login (magic link): la mail la manda l'app, i token stanno in `JdbcOneTimeTokenService` (TALITA-3).
- Test: JUnit 6 + Testcontainers 2 su Postgres vero, e2e con Playwright 1.63.0 (Chromium, telefono e desktop) e axe-core 4.14.0.
- Docker Compose per lo sviluppo; destinazione del deploy Oracle Cloud Always Free (Oracle A1, ARM) in una regione UE, con backup cifrati fuori sede (TALITA-8). Il binario di Tailwind ha il checksum solo per `linux-x64`: la build per ARM si decide in TALITA-8.

Kotlin passa alla 2.4 insieme a Spring Boot 4.2. Prima di ogni aggiornamento, controlla le versioni gestite dal BOM di Boot.

## Documenti

- `docs/01-contesto.md`: chi organizza la convivenza, cosa sostituisce, chi usa l'app, il problema del giorno dell'evento, il rapporto con Koinè, cosa resta fuori dal primo rilascio
- `docs/02-modello-dati.md`: entità e scopo di ogni dato personale
- `docs/03-privacy.md`: titolare, natura dei dati, base giuridica, fornitori, misure, conservazione, informativa e consenso
- `docs/04-ambiente.md`: setup del PC di sviluppo
- `docs/05-decisioni.md`: registro delle decisioni e del loro perché
- `docs/design/README.md`: design system (e `docs/design/KOINE-11/`, il mockup di origine)

## Come lavorare

- **Il backlog è su Jira**, progetto TALITA su wecook.atlassian.net (connettore Atlassian, cloudId `7c631dfd-34f5-47cd-8883-fcde1c6f298d`). I ticket si prendono dall'alto nell'ordine del backlog.
- **Flusso di un ticket**, guidato da `/avanza`: Open → To Do → Analyzed → Designed → Ready to Develop → In Development → In QA → Closed. Le transizioni Jira hanno lo stesso nome dello stato d'arrivo, comprese "In Development" e "Closed". Matteo decide in tre punti: mette il ticket in To Do, approva analisi e design (Designed → Ready to Develop), dà l'ok al merge dopo il QA. I ritorni: difetto del QA → In Development; buco nella specifica → To Do.
- I criteri di accettazione dell'architetto sono il contratto fra i ruoli: il designer ci collega le schermate, il developer li copre con i test, il QA li verifica uno per uno.
- **La PR nasce in bozza** (`gh pr create --draft`) e viene collegata al ticket come web link su Jira. Si mette pronta solo dopo il QA, così CodeRabbit la rivede una volta sola, e le sue correzioni si applicano prima del merge (squash).
- **Modelli**: ricerche su codebase e web con Haiku, analisi e design system con Opus, implementazione, schermate e verifica con Sonnet.
- Etichetta `decisione`: lo decide Matteo, chiedi prima di procedere.
- Nei ticket mai dati personali: Jira è un servizio esterno.
- Ogni decisione di progetto va in `docs/05-decisioni.md`.
- **Docs e commit in italiano, codice e identificatori in inglese.** Conventional Commits (`feat(iscrizione): ... (TALITA-5)`).
- **La build** usa Docker (Testcontainers, generazione jOOQ): `sg docker -c "./gradlew build"` finché la sessione non vede il gruppo `docker`, poi `scripts/smoke-test-jar.sh`. Setup e porte di sviluppo in `docs/04-ambiente.md`.
- Se un task è troppo grande per una sera, spezzalo.
- La memoria di Claude Code dipende dalla cartella: quello che serve a ogni sessione di questo progetto sta in questo file, non in memoria.
