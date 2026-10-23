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
    con data e ora (il testo esatto, e l'indirizzo email di quel momento); anche i genitori **senza account**, che hanno
    compilato il modulo cartaceo, per il recapito che i maestri copiano dal modulo (il consenso alle segnalazioni resta sulla carta);
  - i **bambini**, che sono minori: nome e cognome, data di nascita, giorni di
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

Non è una valutazione legale: è l'ipotesi con cui TALITA-5 ha scritto informativa e consenso, da far
verificare prima del rilascio (sotto).

- **Iscrizione e organizzazione della convivenza** (dati comuni di genitori, bambini e maestri): è
  il servizio chiesto dal genitore con l'iscrizione. Ipotesi: esecuzione della richiesta
  dell'interessato (art. 6.1.b).
- **Segnalazioni** (dati sanitari): **consenso esplicito** del genitore (art. 9.2.a), distinto
  dall'iscrizione, facoltativo e revocabile. Senza consenso la segnalazione non si raccoglie.
- **Convinzioni religiose che si possono dedurre**: in Koinè la base è l'art. 9.2.d, che vale per un
  ente religioso e per i suoi membri. Talità ha come titolare una persona fisica, quindi **a quanto
  pare quella strada non c'è, da verificare** (l'art. 9.2.d non si usa). TALITA-5 ha scelto l'ipotesi più semplice: una **terza casella** al
  passo 2 dell'iscrizione, separata dall'affidamento, con il consenso esplicito del genitore, conservata insieme all'accettazione (e,
  per il cartaceo, una frase sul modulo di carta, che l'app non registra). **Un consenso che condiziona l'iscrizione è proprio il punto da far
  verificare**, con la DPIA: se non serve, la casella si toglie (il resto non cambia).
- **Iscrizione, figli, recapito e accettazione dell'affidamento**: esecuzione della richiesta del genitore (art. 6.1.b); per le
  segnalazioni il consenso esplicito (art. 9.2.a), che non condiziona l'iscrizione (art. 7.4).
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
  - **L'elenco dei maestri** (pagina «Maestri», solo per i maestri) mostra nome e cognome, mai l'indirizzo email
    di un altro maestro; gli indirizzi si vedono solo negli inviti in attesa, che li hanno scritti i maestri
    stessi. Nome e id di una persona non stanno mai in un indirizzo web (non finiscono nei log né nella
    cronologia): i form li mandano nel corpo della richiesta.
  - **Togliere un maestro** cancella nome e cognome e il ruolo; l'account resta, come genitore, e con
    l'account i diritti di quella persona. Un invito si salva solo se la mail parte: la persona non si trova
    mai un invito di cui nessuno le ha detto niente. Il log non scrive mai l'indirizzo invitato.
  - **Non si raccoglie** ultimo accesso, indirizzo IP, browser, registro degli accessi, chi ha invitato chi: se
    servono (per esempio per gli account inattivi) li aggiunge il ticket che ne ha bisogno, con il suo scopo.
- **Minimizzazione**: ogni dato ha uno scopo scritto in `docs/02-modello-dati.md`; mai sesso né
  luogo di nascita; nessun documento caricato, nessuna firma.
- **Segnalazioni** in una tabella separata, visibili solo ai maestri, con ogni lettura registrata
  (TALITA-5 e TALITA-7). Come funziona (TALITA-5):
  - **una sola classe del codice** (`HealthNotes`) legge e scrive la tabella delle segnalazioni e quella delle letture: un test fallisce se
    un'altra classe usa le tabelle che jOOQ genera; il check-in e le cancellazioni passeranno di lì;
  - **il contenuto si vede solo a richiesta**: l'elenco e il dettaglio dei maestri non dicono nemmeno se ci sono segnalazioni; «Mostra le
    segnalazioni» scrive la lettura (maestro, iscrizione, data e ora) **prima** di rispondere, nella stessa transazione, e se non riesce a
    scriverla non si vede niente. Ogni risposta con segnalazioni e ogni modifica fatta da un maestro è una riga del registro; le letture del
    genitore titolare non si registrano. Il registro non ha il testo;
  - **cancellate subito** al ritiro dell'iscrizione e quando il genitore le toglie (revoca del consenso), anche a convivenza finita;
  - **nessuna mail con dati dei bambini** (la conferma dell'iscrizione è la pagina «Iscrizioni», che il genitore riapre quando vuole), e il
    **log** a livello INFO e oltre non ha nomi, date di nascita, recapiti, segnalazioni né indirizzi (solo identificativi e conteggi);
  - **l'isolamento fra genitori** lo fa l'applicazione, con un componente solo (`OwnRegistrations`): l'id di un'iscrizione di un altro è, per un
    altro genitore e per un maestro che non è il titolare, la pagina di un id che non esiste; un test prova ogni indirizzo con il titolare,
    con un altro genitore, con un maestro e senza accesso.
- **La pagina informativa non mostra dati personali** (TALITA-4): niente nomi dei maestri, né email o telefoni, né numeri sulle
  iscrizioni; a un indirizzo con un codice che non si indovina, mai indicizzata (`noindex` nella pagina e nell'intestazione), mai
  conservata (`Cache-Control`), senza cookie, senza script e senza nulla caricato da altri indirizzi; chi la riceve da un'app di
  messaggi vede un'anteprima con titolo, date e luogo e nessuna immagine. A convivenza finita dice solo titolo e date.
- **Collegamento cifrato** (HTTPS) e **backup cifrati**, con un restore provato (TALITA-8).
- **Cancellazione automatica** secondo i tempi di conservazione (TALITA-7).
- **Sviluppo con dati sintetici**, mai dati reali in locale né nel repo; il database di produzione si
  tocca solo sul server. Il repo è pubblico: una build fallisce se ci sono PDF, documenti Office o
  immagini fuori dai posti previsti, e su GitHub sono attivi secret scanning e push protection.

## Conservazione

Tempi di TALITA-7:

- **segnalazioni**: cancellate in automatico **un mese dopo la fine** della convivenza, con ogni
  lettura registrata;
- **iscrizione e manleva** (il testo accettato, con data, ora e indirizzo) e **registro delle letture**: tenuti **un anno** (il registro, un anno dalla
  fine: domanda approvata in TALITA-5);
- **dati dei bambini**: tenuti per **l'anno dopo**, e il genitore li può cancellare quando vuole (TALITA-7); i bambini delle iscrizioni dal
  cartaceo non servono l'anno dopo e vanno via con la loro iscrizione; il recapito segue l'iscrizione.

Ancora da fissare (TALITA-7): per quanto tempo si tengono email e recapito dei genitori, gli account
senza iscrizioni (nascono al primo accesso, anche di chi poi non iscrive nessuno), gli account dei
maestri, gli inviti scaduti e i dati di chi non torna l'anno dopo. I link di accesso non servono qui: si
cancellano quando si usano o entro un'ora dalla scadenza.

## Informativa e consenso

**Informativa a strati** (TALITA-5): una breve dentro l'iscrizione, al passo 2 «Controlla e accetta», e quella completa a `/informativa`, pubblica
(nel guscio pubblico, senza script né cookie, `noindex`), con un collegamento che si apre in una nuova scheda per non far perdere quello che il
genitore ha scritto. Il titolare e il recapito vengono dalla configurazione (`talita.privacy.controller-name` e `talita.privacy.contact`, in
`docs/04-ambiente.md`): nel repo ci sono i segnaposto «Titolare di esempio» e `nome@example.org`, e l'indirizzo vero arriva con TALITA-9.
Il recapito del titolare non è più assegnato a TALITA-4.

I testi devono contenere:

- chi è il titolare e come contattarlo;
- quali dati si raccolgono e a quale scopo, in parole di tutti i giorni;
- la base su cui si trattano, e la distinzione fra l'iscrizione (necessaria) e le parti facoltative (art. 13.2.e: cosa succede se mancano);
- **consenso esplicito e separato per le segnalazioni sanitarie**, facoltativo e revocabile, e il consenso per le convinzioni religiose deducibili;
- chi li riceve (i fornitori della tabella) e dove stanno i dati;
- per quanto tempo si conservano;
- i diritti dell'interessato e come esercitarli, e il reclamo al Garante;
- che l'accettazione dell'affidamento comprende la dichiarazione che anche l'altro genitore è d'accordo (o che la responsabilità genitoriale è
  di uno solo), che data e ora si registrano, e che la dichiarazione **non sostituisce** il loro accordo.

**Consenso alle segnalazioni**: una casella separata dall'affidamento, accanto ai campi, **non spuntata**, con il testo che dice che sono facoltative
e cosa succede se mancano; senza il consenso niente si salva; si salva con data e ora e account di chi lo dà (o «consenso sul modulo cartaceo»).
È revocabile dalla pagina «Iscrizioni» con un tocco e un dialogo, e la revoca cancella subito segnalazioni e consenso. L'iscrizione non dipende
dal consenso (art. 7.4).

**Manleva**: l'accettazione online è una **firma elettronica semplice**, valutata liberamente dal giudice (da verificare). Si conservano il testo
esatto che il genitore ha letto, la data e l'ora, l'account e l'indirizzo email di quel momento; l'indirizzo prova il controllo della casella,
non l'identità. Il testo del modello (`affidamento-1`) è generico (non nomina luoghi né parrocchie: viene dal modulo cartaceo di oggi, che non sta
nel repo), non esclude la responsabilità per dolo o colpa grave (art. 1229 c.c.) ed è fissato da un test, così non cambia senza una versione nuova.
L'iscrizione dal cartaceo non ha testo da accettare: il modulo firmato resta di carta, e l'app segna solo se è arrivato (e chi l'ha inserita).
Il modulo di carta di oggi chiede ancora il luogo di nascita e non ha una casella di consenso per le segnalazioni: Matteo valuta se allinearlo
all'app, così l'inserimento dal cartaceo raccoglie gli stessi dati.

## Prima del rilascio

Da fare con una persona competente, e da chiudere prima di dare il link ai genitori (la build con `-Prelease` non passa finché un testo porta
ancora un segnaposto):

- **leggere i testi**: l'affidamento (`affidamento-1`), l'informativa breve, l'informativa completa e il consenso, anche per la casella sulle
  convinzioni deducibili; l'informativa completa porta oggi la cornice «testo di prova» e un termine ancora aperto (quanto si tiene
  l'indirizzo email dell'account, con TALITA-7);
- **la DPIA**, o il motivo scritto per non farla (minori e dati sanitari, su scala piccola): il risultato va in `docs/05-decisioni.md`;
- **il registro dei trattamenti**, dovuto perché ci sono dati sanitari;
- l'indirizzo vero del titolare (TALITA-9).

## Diritti degli interessati

Accesso, rettifica, cancellazione, limitazione, portabilità, opposizione, revoca del consenso e
reclamo all'autorità di controllo.

Il genitore li esercita **dal suo account**, per i dati suoi e dei suoi figli:

- corregge l'iscrizione finché le iscrizioni sono aperte (e cambia le segnalazioni fino all'ultimo giorno); dopo, lo chiede a un maestro;
- cancella i dati dei figli quando vuole (TALITA-7);
- revoca il consenso alle segnalazioni, che vengono tolte.

Per quello che l'app non permette di fare da sé, scrive al titolare, che risponde entro un mese. Il
recapito di contatto del titolare **non sta sulla pagina informativa dei genitori** (`/info/{codice}`, che non mostra dati personali,
nemmeno quelli di chi organizza): sta nell'informativa dell'iscrizione e in quella completa (`/informativa`), da `talita.privacy.contact`.
L'indirizzo lo sceglie Matteo con TALITA-9: uno sul dominio di Talità richiede un servizio che riceva posta, oggi non elencato fra i fornitori.

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
