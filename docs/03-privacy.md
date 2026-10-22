# Privacy

**Questo documento non è consulenza legale.** È l'impostazione di lavoro di chi sviluppa l'app:
quello che si sa, quello che si prevede di fare e quello che resta da verificare con una persona
competente prima del rilascio. Le parti da verificare sono segnate come tali.

Gli scopi di ogni dato personale sono in `docs/02-modello-dati.md`, le regole di lavoro in
`CLAUDE.md`. TALITA-2 (il repo e il guscio) non tocca nessun dato personale; con TALITA-3 nascono gli
account (indirizzi email) e le misure dell'accesso descritte sotto. Le altre misure e i tempi diventano
veri con i ticket indicati.

## Titolare e interessati

- **Titolare del trattamento**: Matteo, persona fisica, non un ente. Decide perché e come si
  trattano i dati e risponde delle scelte. L'hosting e l'invio delle email sono affidati a fornitori
  (vedi sotto), che trattano i dati per suo conto.
- **Interessati**:
  - i **genitori** che iscrivono i figli: email, recapito telefonico, accettazione dell'affidamento
    con data e ora;
  - i **bambini**, che sono minori: nome e cognome, data di nascita (da confermare), giorni di
    presenza, segnalazioni facoltative su allergie, intolleranze e farmaci, arrivi e quota pagata;
  - i **maestri**: email dell'account, nome e cognome.

## Natura dei dati

- **Minori.** I bambini hanno bisogno di una tutela maggiore: il consenso e l'affidamento li dà chi
  esercita la responsabilità genitoriale, e l'iscrizione dichiara che anche l'altro genitore è
  d'accordo.
- **Dati sanitari** (art. 9 GDPR): le segnalazioni su allergie, intolleranze e farmaci. Sono
  facoltative, stanno in una tabella separata e le vedono solo i maestri della convivenza.
- **Convinzioni religiose che si possono dedurre.** Partecipare a una convivenza dei bambini
  organizzata da parrocchie può far dedurre le convinzioni religiose della famiglia (art. 9 GDPR),
  anche se l'app non chiede mai la religione di nessuno.

## Base giuridica (impostazione di lavoro, da verificare)

Non è una valutazione legale: è l'ipotesi con cui si scrivono informativa e consenso in TALITA-5, da
far verificare.

- **Iscrizione e organizzazione della convivenza** (dati comuni di genitori, bambini e maestri): è
  il servizio chiesto dal genitore con l'iscrizione. Ipotesi: esecuzione della richiesta
  dell'interessato (art. 6.1.b).
- **Segnalazioni** (dati sanitari): **consenso esplicito** del genitore (art. 9.2.a), distinto
  dall'iscrizione, facoltativo e revocabile. Senza consenso la segnalazione non si raccoglie.
- **Convinzioni religiose che si possono dedurre**: in Koinè la base è l'art. 9.2.d, che vale per un
  ente religioso e per i suoi membri. Talità ha come titolare una persona fisica, quindi **a quanto
  pare quella strada non c'è, da verificare**. L'ipotesi più semplice è chiedere al genitore il consenso esplicito anche per
  questo, insieme all'iscrizione e non solo per le segnalazioni. Si decide con il testo
  dell'informativa (TALITA-5).
- **Account dei genitori** (l'indirizzo email con cui si entra, che nasce al primo accesso riuscito e
  non ha altro dentro): è il servizio chiesto dal genitore, che senza un accesso non può iscrivere i
  figli. Ipotesi: esecuzione della richiesta dell'interessato (art. 6.1.b); da verificare. L'accesso non
  dipende dalla convivenza: il genitore entra sempre, e dal suo account esercita i diritti (sotto).
- **Account dei maestri** (inviti e accesso): ipotesi di legittimo interesse del titolare
  a organizzare la convivenza con i maestri che partecipano; da verificare.

## Fornitori che ricevono dati

Nessun servizio di terze parti riceve dati personali, **tranne** quelli elencati qui (regola 6 di
CLAUDE.md). Niente analytics, CDN, font o script esterni: lo garantisce anche un test sulle pagine.

| Fornitore | Cosa riceve | Note |
| --- | --- | --- |
| Hosting: Oracle Cloud Always Free, regione UE | Tutti i dati dell'app, nel database | Senza Pay As You Go: nessun costo possibile. Matteo, come titolare, accetta l'accordo sul trattamento dei dati (art. 28) quando apre l'account (TALITA-9). |
| Invio email: Mailjet (Parigi, piano gratuito) | Indirizzo email del destinatario e testo del messaggio (il magic link, gli inviti) | Stessa cosa per l'accordo (TALITA-9). **Senza tracciamento di aperture e clic**: ogni mail porta le intestazioni `X-Mailjet-TrackOpen: 0` e `X-Mailjet-TrackClick: 0`, e il tracciamento si spegne anche nell'account (TALITA-9). Solo testo, senza immagini né link rimandati da Mailjet. |
| Backup cifrati fuori sede | Una copia del database, **cifrata prima di lasciare la macchina**, con la chiave che resta al titolare | Fornitore da scegliere in TALITA-8. |

Non ricevono dati personali degli interessati: il dominio e il servizio DNS (conoscono solo il nome
del sito), GitHub e la CI (solo codice e dati sintetici) e Jira (nei ticket mai dati personali).
**Mailpit**, che in sviluppo e nei test prende la posta al posto di Mailjet, gira solo in locale e non
inoltra niente: non è un fornitore.

## Misure

Previste dal progetto, con il ticket in cui diventano vere:

- **Accesso per persona** con magic link, senza password condivise (TALITA-3). Due ruoli: il
  maestro vede tutta la convivenza, il genitore solo i suoi figli (TALITA-3 e TALITA-5, con test). Come
  funziona l'accesso, in concreto (TALITA-3):
  - **Il link** vale 15 minuti e si usa una sola volta. Aprirlo non fa entrare: la pagina chiede un tocco
    su «Entra» (un POST con codice CSRF), perché i programmi di posta aprono i link per guardarli. Un link
    usato sparisce dalla tabella subito, uno scaduto entro un'ora.
  - **La stessa risposta per ogni indirizzo**: chi scrive un indirizzo con un account e chi ne scrive uno
    senza vedono la stessa pagina, con lo stesso stato e lo stesso testo. Il link nasce in un solo punto,
    con la validazione dell'indirizzo e i limiti: 3 link ogni 15 minuti per indirizzo e 150 mail ogni 24
    ore in tutto (sotto la quota gratuita di Mailjet), contati in memoria.
  - **L'indirizzo del link** viene da `TALITA_PUBLIC_URL` e mai dall'intestazione `Host`, che chi attacca
    può falsificare.
  - **La sessione** sta in memoria (si perde al riavvio) e finisce dopo due ore senza usarla. Il cookie
    porta solo l'id di sessione, è `HttpOnly`, `SameSite=Lax` e `Secure` in HTTPS, e l'id cambia a ogni
    accesso. Le pagine di chi è dentro non si tengono nel browser (`Cache-Control: no-store`).
  - **Content-Security-Policy** su ogni risposta (solo risorse dell'app, niente script né stili in linea,
    nessun frame), `Referrer-Policy: same-origin`, `X-Content-Type-Options: nosniff`.
  - **Il log**, a livello INFO e oltre, non contiene indirizzi email, token né link: un errore di invio si
    scrive con il tipo dell'eccezione e basta.
  - **Il ruolo si legge dal database a ogni richiesta**: un maestro tolto, o un invito accettato, vale
    al tocco dopo, senza uscire e rientrare. Ogni account è un genitore; il maestro è un account con una riga
    in `teacher` (nome e cognome, scritti da lui). Gli indirizzi dei soli maestri rispondono 403 a un genitore,
    con una regola sola e un test su ognuno.
  - **Un invito** è un indirizzo e una scadenza (7 giorni), senza token: chi entra con quell'indirizzo, con il
    suo link, trova l'invito. Un invito ritirato o scaduto non apre niente, e chi entra vale come genitore.
  - **Non si raccoglie** ultimo accesso, indirizzo IP, browser, registro degli accessi, chi ha invitato chi: se
    servono (per esempio per gli account inattivi) li aggiunge il ticket che ne ha bisogno, con il suo scopo.
- **Minimizzazione**: ogni dato ha uno scopo scritto in `docs/02-modello-dati.md`; mai sesso né
  luogo di nascita; nessun documento caricato, nessuna firma.
- **Segnalazioni** in una tabella separata, visibili solo ai maestri, con ogni lettura registrata
  (TALITA-5 e TALITA-7).
- **La pagina informativa non mostra dati personali** (TALITA-4).
- **Collegamento cifrato** (HTTPS) e **backup cifrati**, con un restore provato (TALITA-8).
- **Cancellazione automatica** secondo i tempi di conservazione (TALITA-7).
- **Sviluppo con dati sintetici**, mai dati reali in locale né nel repo; il database di produzione si
  tocca solo sul server. Il repo è pubblico: una build fallisce se ci sono PDF, documenti Office o
  immagini fuori dai posti previsti, e su GitHub sono attivi secret scanning e push protection.

## Conservazione

Tempi di TALITA-7:

- **segnalazioni**: cancellate in automatico **un mese dopo la fine** della convivenza, con ogni
  lettura registrata;
- **manleva e registro**: tenuti **un anno**;
- **dati dei bambini**: tenuti per **l'anno dopo**, e il genitore li può cancellare quando vuole.

Ancora da fissare (TALITA-7): per quanto tempo si tengono email e recapito dei genitori, gli account
senza iscrizioni (nascono al primo accesso, anche di chi poi non iscrive nessuno), gli account dei
maestri, gli inviti scaduti e i dati di chi non torna l'anno dopo. I link di accesso non servono qui: si
cancellano quando si usano o entro un'ora dalla scadenza.

## Informativa e consenso: contenuto minimo

I testi che vede il genitore arrivano con TALITA-5. Qui si scrive solo cosa devono contenere:

- chi è il titolare e come contattarlo;
- quali dati si raccolgono e a quale scopo, in parole di tutti i giorni;
- la base su cui si trattano, e la distinzione fra l'iscrizione (necessaria) e le parti facoltative;
- **consenso esplicito e separato per le segnalazioni sanitarie**, facoltativo e revocabile, e il
  consenso per le convinzioni religiose deducibili (ipotesi sopra);
- chi li riceve (i fornitori della tabella) e dove stanno i dati;
- per quanto tempo si conservano;
- i diritti dell'interessato e come esercitarli;
- che l'accettazione dell'affidamento comprende la dichiarazione che anche l'altro genitore è
  d'accordo, e che data e ora si registrano.

Un'informativa **breve** sta dentro l'iscrizione; quella completa si apre da lì.

## Diritti degli interessati

Accesso, rettifica, cancellazione, limitazione, portabilità, opposizione, revoca del consenso e
reclamo all'autorità di controllo.

Il genitore li esercita **dal suo account**, per i dati suoi e dei suoi figli:

- corregge l'iscrizione finché le iscrizioni sono aperte; dopo, lo chiede a un maestro;
- cancella i dati dei figli quando vuole (TALITA-7);
- revoca il consenso alle segnalazioni, che vengono tolte.

Per quello che l'app non permette di fare da sé, scrive al titolare, che risponde entro un mese. Il
recapito di contatto lo fissa TALITA-4 insieme alla pagina informativa.

## DPIA: da valutare

Minori e dati sanitari, anche se su una scala piccola (30-35 persone, una volta l'anno), sono il
tipo di trattamento per cui una valutazione d'impatto va **almeno considerata**. Da valutare, con una
persona competente, prima del rilascio: se serve e, se non serve, perché. Il risultato va in
`docs/05-decisioni.md`.

## I documenti veri non si aprono

I documenti veri delle convivenze (manleve, elenchi, registri, schede per la struttura) hanno dati
reali di minori. **Non si aprono, non si copiano e non si leggono nelle sessioni di lavoro, né dai
programmi né dagli agenti.** Per scrivere un testo (un'informativa, un modulo) si chiede un
modello **vuoto**. Nel repo, nei test, nelle migrazioni, nei mockup e nei ticket solo dati
sintetici.
