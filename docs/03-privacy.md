# Privacy

**Questo documento non è consulenza legale.** È l'impostazione di lavoro di chi sviluppa l'app:
quello che si sa, quello che si prevede di fare e quello che resta da verificare con una persona
competente prima del rilascio. Le parti da verificare sono segnate come tali.

Gli scopi di ogni dato personale sono in `docs/02-modello-dati.md`, le regole di lavoro in
`CLAUDE.md`. TALITA-2 (il repo e il guscio) non tocca nessun dato personale; con TALITA-3 nascono gli
account (indirizzi email) e le misure dell'accesso descritte sotto. Le altre misure diventano
vere con i ticket indicati; i tempi di conservazione con TALITA-7.

## Titolare e interessati

- **Titolare del trattamento**: Matteo, persona fisica, non un ente. Decide perché e come si
  trattano i dati e risponde delle scelte. L'hosting e l'invio delle email sono affidati a fornitori
  (vedi sotto), che trattano i dati per suo conto.
- **Interessati**:
  - i **genitori** che iscrivono i figli: email, recapito telefonico, accettazione dell'affidamento
    con data e ora (il testo esatto, e l'indirizzo email di quel momento); anche i genitori **senza account**, che hanno
    compilato il modulo cartaceo, per il recapito che i maestri copiano dal modulo (il consenso alle segnalazioni resta sulla carta);
  - i **bambini**, che sono minori: nome e cognome, data di nascita, giorni di
    presenza, segnalazioni facoltative su allergie, intolleranze e farmaci, presenze per giorno e pagamenti (TALITA-6);
  - i **maestri**: email dell'account, nome e cognome, e, come chi ha segnato una presenza o registrato un pagamento, l'account che lo ha fatto (TALITA-6).

## Natura dei dati

- **Minori.** I bambini hanno bisogno di una tutela maggiore: il consenso e l'affidamento li dà chi
  esercita la responsabilità genitoriale, e l'iscrizione dichiara che anche l'altro genitore è
  d'accordo.
- **Dati sanitari** (art. 9 GDPR): le segnalazioni su allergie, intolleranze e farmaci. Sono
  facoltative, stanno in una tabella separata e le vedono i maestri della convivenza, a richiesta e con ogni lettura registrata, e il genitore che le ha
  scritte: nessun altro.
- **Convinzioni religiose che si possono dedurre.** Partecipare a una convivenza dei bambini
  organizzata da parrocchie può far dedurre le convinzioni religiose della famiglia (art. 9 GDPR),
  anche se l'app non chiede mai la religione di nessuno.

## Base giuridica (impostazione di lavoro, da verificare)

Non è una valutazione legale: è l'ipotesi con cui TALITA-5 ha scritto informativa e consenso, da far
verificare prima del rilascio (sotto).

- **Iscrizione e organizzazione della convivenza** (dati comuni di genitori, bambini e maestri): è
  il servizio chiesto dal genitore con l'iscrizione. Ipotesi: esecuzione della richiesta
  dell'interessato (art. 6.1.b).
- **Presenze e pagamenti** (TALITA-6): sono il servizio chiesto dal genitore con l'iscrizione, che serve a sapere chi c'è ogni giorno e quanto è stato pagato.
  Ipotesi: esecuzione della richiesta dell'interessato (art. 6.1.b), come per l'iscrizione; da verificare.
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
| Backup cifrati fuori sede | Una copia del database, **cifrata prima di lasciare la macchina**, con la chiave che resta al titolare | Fornitore da scegliere in TALITA-8. Nessuna copia più vecchia di 30 giorni (sotto, «Backup»). |

**La struttura che ospita la convivenza** (TALITA-6) non è un fornitore dell'app: riceve **su carta** l'elenco per la struttura, con **solo nome e cognome**
dei bambini e dei maestri affidatari, e niente altro (né recapiti, né segnalazioni, né quote). È un destinatario dei dati e va detto nell'informativa breve e in
quella completa (`Chi li riceve`, TALITA-5): lo dicono entrambe. Quando la struttura avrà un suo modulo, si modella in un ticket a parte.

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
  - **Un invito** è un indirizzo, una scadenza (7 giorni) e il momento in cui è stato fatto, senza token: chi entra con quell'indirizzo, con il
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
- **Segnalazioni** in una tabella separata, visibili ai maestri della convivenza e al genitore che le ha scritte e a nessun altro, con ogni lettura dei maestri
  registrata (TALITA-5 e TALITA-7). Come funziona (TALITA-5):
  - **una sola classe del codice** (`HealthNotes`) legge e scrive la tabella delle segnalazioni e quella delle letture: un test fallisce se
    un'altra classe usa le tabelle che jOOQ genera; il check-in (TALITA-6) passa di lì e le cancellazioni (TALITA-7) passano di lì. Dalla data di cancellazione (un mese dopo la fine)
    la classe non le legge più, nemmeno nelle ore fra la mezzanotte e il job: una pagina non mostra mai un dato sulla salute scaduto;
  - **il contenuto si vede solo a richiesta**: l'elenco e il dettaglio dei maestri non dicono nemmeno se ci sono segnalazioni; «Mostra le
    segnalazioni» scrive la lettura (maestro, iscrizione, data e ora) **prima** di rispondere, nella stessa transazione, e se non riesce a
    scriverla non si vede niente. Ogni risposta con segnalazioni e ogni modifica fatta da un maestro è una riga del registro; le letture del
    genitore titolare non si registrano. Il registro non ha il testo;
  - **cancellate subito** al ritiro dell'iscrizione e quando il genitore le toglie (revoca del consenso), anche a convivenza finita;
  - **nessuna mail con dati dei bambini** (la conferma dell'iscrizione è la pagina «Iscrizioni», che il genitore riapre quando vuole), e il
    **log** a livello INFO e oltre non ha nomi, date di nascita, recapiti, segnalazioni né indirizzi (solo identificativi e conteggi);
  - **l'isolamento fra genitori** lo fa l'applicazione, con un componente solo (`OwnRegistrations`): l'id di un'iscrizione di un altro è, per un
    altro genitore e, sulle pagine del genitore, per un maestro che non è il titolare, la pagina di un id che non esiste; un test prova ogni indirizzo con il
    titolare, con un altro genitore, con un maestro e senza accesso. L'unica eccezione è il ritiro, che un maestro può fare su qualunque iscrizione
    (TALITA-5): le pagine dei maestri sono un'altra regola, solo per i maestri, con il suo test.
- **La pagina informativa non mostra dati personali** (TALITA-4): niente nomi dei maestri, né email o telefoni, né numeri sulle
  iscrizioni; a un indirizzo con un codice che non si indovina, mai indicizzata (`noindex` nella pagina e nell'intestazione), mai
  conservata (`Cache-Control`), senza cookie, senza script e senza nulla caricato da altri indirizzi; chi la riceve da un'app di
  messaggi vede un'anteprima con titolo, date e luogo e nessuna immagine. A convivenza finita dice solo titolo e date.
- **Check-in e stampe** (TALITA-6): Arrivi, la scheda del bambino e le tre stampe sono solo per i maestri (403 a un genitore, anche a mano, con un test su ogni
  indirizzo; un id che non esiste o che è di un'altra convivenza è una pagina che non esiste).
  - **il filtro per nome gira nel browser** (`app.js`, sui nomi che sono già nella pagina): nessun nome negli indirizzi web né nei log, che a livello INFO e oltre
    non hanno nomi, recapiti, importi né segnalazioni; l'elenco per la struttura prende nell'indirizzo solo un giorno della convivenza;
  - **le segnalazioni** si leggono solo dalla classe che registra la lettura: «Mostra le segnalazioni» scrive la lettura (maestro, iscrizione, data e ora) e poi le mostra
    **solo in quella risposta**; dopo qualunque altra azione la scheda è chiusa, e se la lettura non si scrive non si vede niente. Niente in lista, riepilogo, scheda
    chiusa e stampe dice se un bambino ha segnalazioni: le stampe non le leggono, e quindi non possono scrivere una lettura;
  - **ogni presenza e ogni pagamento ha il maestro che l'ha scritto e il momento**; i pagamenti non si riscrivono: uno sbagliato si toglie, e togliendolo sparisce con i
    suoi dati;
  - **le due informative lo dicono** (commento di CodeRabbit sulla PR di TALITA-6): quella completa elenca presenze e pagamenti fra i dati raccolti, fra gli scopi, fra
    le cose che vedono solo i maestri e fra i tempi di conservazione; quella breve, al passo 2, li nomina in «A cosa servono» e in «Per quanto tempo». Un test (`t6 c31`)
    lo verifica;
  - **niente copie dei dati nel telefono**: nessun service worker e nessuna lista che si tenga offline; una pagina già aperta si stampa senza rete, ma per aprirla serve la
    rete (la riserva è la carta, stampata prima di partire). Le pagine di Arrivi non si tengono nel browser (`Cache-Control: no-store`).
- **Collegamento cifrato** (HTTPS) e **backup cifrati**, con un restore provato (TALITA-8).
- **Cancellazione automatica** secondo i tempi di conservazione (TALITA-7). Come funziona:
  - **un job** ogni notte alle 4:15 (ora di Roma) e a ogni avvio dell'applicazione, così un giorno in cui l'applicazione era spenta si recupera al primo avvio. Guarda il **giorno** di Roma e mai l'ora:
    a mezzanotte e mezza del giorno della data i dati non ci sono più. Ogni regola (segnalazioni, iscrizioni, figli, inviti, account) è una transazione sotto un lock consultivo di Postgres:
    due esecuzioni insieme cancellano ogni riga una volta. Una regola che fallisce torna indietro, le altre girano, il log ha un ERROR senza dati e la notte dopo riprova;
  - **righe cancellate davvero** (`DELETE`), mai nascoste con un flag; niente `VACUUM FULL` (bloccherebbe le tabelle, e a questa scala non serve). Le righe cancellate restano nei file di Postgres finché lo spazio
    non si riusa: non si leggono più né dall'app né in SQL, ed è per questo che i backup sono logici (sotto);
  - **registro delle cancellazioni** (`retention_log`): momento, regola e numero di righe, mai un nome, un indirizzo o una data di nascita. L'unico dato che indica una persona è l'id interno del figlio o
    dell'account cancellato su richiesta, tenuto 37 giorni per rifare la richiesta dopo un ripristino; il log dell'applicazione dice solo le regole e i numeri;
  - **dopo un ripristino** le regole a tempo si riapplicano da sole al primo avvio; le richieste dei genitori si rifanno grazie all'id nel registro (requisiti per i backup, sotto).
- **Sviluppo con dati sintetici**, mai dati reali in locale né nel repo; il database di produzione si
  tocca solo sul server. Il repo è pubblico: una build fallisce se ci sono PDF, documenti Office o
  immagini fuori dai posti previsti, e su GitHub sono attivi secret scanning e push protection.

## Backup: requisiti per TALITA-8

Un dato cancellato resta nei backup al più 30 giorni, cifrato e non usato; un ripristino riapplica le cancellazioni. Per questo TALITA-8 deve rispettare cinque requisiti:

1. **Backup logici** (`pg_dump`), cifrati prima di uscire dalla macchina. Niente copie della cartella dei dati né del WAL: conterrebbero righe cancellate.
2. **Nessun backup più vecchio di 30 giorni**, in nessun posto, macchina compresa. La rotazione scelta (per esempio 7 giornalieri e 3 settimanali) si scrive qui.
3. **Il backup del giorno parte dopo il job delle 4:15** (per esempio alle 5:00), così non contiene quello che il job ha cancellato quella notte.
4. **Ripristino dal backup più recente**: all'avvio il job riapplica le regole a tempo. Se il database di prima è ancora leggibile, prima di avviare l'app si copiano le sue righe del registro con `target_id` più recenti del
   backup e si portano le sequenze ai suoi valori (un id non si riusa): il job rifà le richieste. Se non è leggibile, la finestra scoperta va dall'ultimo backup al guasto (al massimo un giorno), e si scrive in `docs/05-decisioni.md`.
5. **Il log del proxy senza query string** (TALITA-3).

## Carta

Le stampe di TALITA-6 portano **data e ora di stampa** su ogni documento e non hanno mai un testo libero. Che cosa contengono, e cosa se ne fa:

| Stampa | Contenuto | Dopo l'uso |
| --- | --- | --- |
| **Registro arrivi** | per ogni iscrizione non ritirata: nome del bambino, giorni (con le presenze già segnate), quota dovuta, pagato, manleva, indicazioni dell'app (in forse, dal cartaceo, possibile doppione, giorno non previsto), spazio per scrivere a mano, totali. **Niente recapiti, niente segnalazioni** | lo conserva il titolare fra i documenti della convivenza, come quello dell'app: un anno |
| **Elenco per la struttura** | solo cognome e nome dei bambini (confermati, o presenti in un giorno) e dei maestri affidatari, con righe vuote per gli altri adulti. **Nient'altro** | resta alla struttura |
| **Foglio per i maestri** | nome del bambino e recapito del genitore, con la frase che dice di tenerlo fra i maestri. **Niente segnalazioni** | si distrugge alla fine della convivenza |

**Segnalazioni su carta: no.** Matteo ha risposto no alla domanda 6 di TALITA-6: nessuna stampa le contiene (nemmeno il foglio per i maestri), quindi **non c'è
nessuna deroga** alla regola 4 e le segnalazioni non escono dalla loro tabella. Senza rete le segnalazioni non si leggono dall'app (per i bambini iscritti dal
cartaceo c'è il modulo di carta). La stampa non scrive nessuna lettura, perché non legge nessuna segnalazione.

## Conservazione

**La tabella è questa** (TALITA-7; Matteo ha approvato i tempi nell'intervista di apertura del progetto e le scelte il 22 ottobre 2026). «Finita» è il giorno dopo l'ultimo giorno della convivenza; tutte le date sono giorni
di Roma e le calcola un solo punto del codice (`RetentionPolicy`), le stesse per il job, per le pagine e per le informative. Il job cancella **dal giorno della data**, di notte. Esempio: la «Convivenza di settembre 2027»,
dal 17 al 19 settembre, è finita il 20; le segnalazioni vanno via il 20 ottobre 2027, il resto il 20 settembre 2028.

| Dato | Quando si cancella | Chi | Note |
| --- | --- | --- | --- |
| Segnalazioni (`health_note`) | **un mese dopo la fine**; prima: ritiro e revoca del consenso (TALITA-5), cancellazione del figlio o dell'account | job, genitore | ogni lettura registrata; il consenso va via con loro |
| Iscrizione: manleva (testo, versione, data e ora, indirizzo copiato), recapito, giorni, stato, cartacea, chi l'ha inserita | **un anno dopo la fine**; prima, su richiesta, solo se ritirata senza presenze né pagamenti | job, genitore | è la prova dell'affidamento (art. 17.3.e, da verificare) |
| Presenze, pagamenti, registro delle letture | con l'iscrizione (cascata) | job | il maestro che li ha segnati si svuota quando il suo account va via |
| Legami con gli affidatari | un anno dopo la fine; prima con «Togli» o con l'account cancellato (convivenze finite) | job, maestri | |
| Figlio (nome, cognome, data di nascita) | quando non ha più iscrizioni: un anno dopo la fine della sua ultima convivenza (cartaceo: con la sua iscrizione); prima su richiesta | job, genitore | il genitore lo stacca dal suo account quando vuole; resta solo l'iscrizione che serve come prova |
| Account (indirizzo, giorno di creazione) | vuoto (non maestro, senza figli né iscrizioni, non nel registro delle letture) **30 giorni dopo la creazione**; subito su richiesta | job, persona | chi ha letto segnalazioni resta con il solo indirizzo finché restano le sue letture |
| Maestro (nome e cognome, momento in cui lo è diventato) | con «Togli» o con l'account cancellato | maestri, persona | |
| Invito (indirizzo, scadenza, momento dell'invito) | **30 giorni dopo la scadenza**; prima: accettato o ritirato, o su richiesta | job, persona | per 30 giorni i maestri vedono «Scaduto» |
| Link di accesso, sessione, contatori | link usato subito, scaduto entro un'ora; il resto in memoria | TALITA-3 | |
| Id nel registro delle cancellazioni | dopo **37 giorni** (rotazione dei backup più 7) | job | il resto del registro non ha dati personali e resta |
| Backup | **entro 30 giorni** dalla cancellazione | TALITA-8 | requisiti sopra |
| Carta: manleve firmate e registro stampato | un anno dopo la fine | titolare | il foglio per i maestri alla fine della convivenza (TALITA-6); l'elenco resta alla struttura |

Il recapito telefonico sta nell'iscrizione e ne segue i tempi (un anno). Una convivenza in bozza non ha iscrizioni, e nessuna regola la tocca. Una convivenza finita con le iscrizioni ancora aperte segue la sua fine come le altre.

**Quando il genitore chiede di cancellare**: le segnalazioni vanno via subito; le iscrizioni ritirate senza presenze né pagamenti vanno via subito; **le altre restano fino alla loro data**, come prova dell'affidamento (o come registro
della convivenza, se sono ritirate ma con presenze o pagamenti), con il nome e la data di nascita del bambino dentro quell'iscrizione. Il dialogo e l'informativa lo dicono. Un'iscrizione non ritirata di una convivenza non finita blocca
la richiesta: prima si ritira (o si chiede ai maestri, se le iscrizioni sono chiuse). Chi cancella l'account perde anche il legame con quello che resta (l'accettazione, con l'indirizzo copiato, resta).

I link di accesso non servono qui: si cancellano quando si usano o entro un'ora dalla scadenza. **Nessuna mail** prima di cancellare un account: si cancellano da soli solo account vuoti, che non hanno niente da perdere, e una mail in più porterebbe
l'indirizzo a Mailjet e somiglierebbe a un phishing.

## Informativa e consenso

**Informativa a strati** (TALITA-5): una breve dentro l'iscrizione, al passo 2 «Controlla e accetta», e quella completa a `/informativa`, pubblica
(nel guscio pubblico, senza script né cookie, `noindex`), con un collegamento che si apre in una nuova scheda per non far perdere quello che il
genitore ha scritto. Il titolare e il recapito vengono dalla configurazione (`talita.privacy.controller-name` e `talita.privacy.contact`, in
`docs/04-ambiente.md`): non hanno un valore di default (senza, l'app non parte: un'istanza di produzione non può mostrare un titolare di esempio), i segnaposto «Titolare di esempio» e
`nome@example.org` stanno solo nel profilo `dev` e nei test, e l'indirizzo vero arriva con TALITA-9.
Il recapito del titolare non è più assegnato a TALITA-4.

I testi devono contenere:

- chi è il titolare e come contattarlo;
- quali dati si raccolgono e a quale scopo, in parole di tutti i giorni, **comprese le presenze segnate giorno per giorno e i pagamenti** (giorno o importo, ora e
  maestro che li ha segnati: TALITA-6), e che li vedono solo i maestri;
- la base su cui si trattano, e la distinzione fra l'iscrizione (necessaria) e le parti facoltative (art. 13.2.e: cosa succede se mancano);
- **consenso esplicito e separato per le segnalazioni sanitarie**, facoltativo e revocabile; e il consenso per le convinzioni religiose deducibili, che è
  un'**ipotesi di lavoro**: la casella c'è nell'iscrizione e nelle informative finché la verifica di «Prima del rilascio» (con la DPIA) non decide se
  serve; se non serve, si toglie dalla pagina, dal testo conservato e dalle informative;
- chi li riceve (i fornitori della tabella) e dove stanno i dati;
- per quanto tempo si conservano, **anche per presenze e pagamenti**: vanno via con l'iscrizione, un anno dopo la fine della convivenza (TALITA-7 ne prende il tempo da
  `RetentionPolicy`);
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
L'app non raccoglie mai il luogo di nascita, nemmeno dal cartaceo: il maestro copia solo i dati del passo 1. Il modulo di carta di oggi lo chiede
ancora e non ha una casella di consenso per le segnalazioni: è da allineare all'app prima del rilascio, e lo decide Matteo (voce in «Prima del
rilascio»).

## Prima del rilascio

Da fare con una persona competente, e da chiudere prima di dare il link ai genitori (la build con `-Prelease` non passa finché un testo porta
ancora un segnaposto):

- **leggere i testi**: l'affidamento (`affidamento-1`), l'informativa breve, l'informativa completa e il consenso, anche per la casella sulle
  convinzioni deducibili; l'informativa completa porta oggi la cornice «testo di prova»; i tempi sono quelli della tabella di «Conservazione», e **se un anno basta per la manleva**
  (un'azione di risarcimento può prescriversi più tardi) lo dice la persona competente che legge il testo dell'affidamento: cambiarlo vuol dire una costante di `RetentionPolicy`;
- **il modulo cartaceo** (lo decide Matteo): oggi chiede il luogo di nascita, che l'app non raccoglie, e non ha la casella di consenso alle
  segnalazioni; va allineato all'app, oppure l'eccezione va scritta in `docs/02-modello-dati.md` e qui, prima di usare l'inserimento dal cartaceo;
- **la DPIA**, o il motivo scritto per non farla (minori e dati sanitari, su scala piccola): il risultato va in `docs/05-decisioni.md`;
- **il registro dei trattamenti**, dovuto perché ci sono dati sanitari;
- **le stampe di Arrivi da Safari su iPhone** (lo prova Matteo, perché la prova automatica usa Chromium): registro, elenco e foglio su A4, con i titoli delle
  colonne su ogni foglio e senza righe spezzate (TALITA-6);
- l'indirizzo vero del titolare (TALITA-9).

## Diritti degli interessati

Accesso, rettifica, cancellazione, limitazione, portabilità, opposizione, revoca del consenso e
reclamo all'autorità di controllo.

Il genitore li esercita **dal suo account**, per i dati suoi e dei suoi figli:

- corregge l'iscrizione finché le iscrizioni sono aperte (e cambia le segnalazioni fino all'ultimo giorno); dopo, lo chiede a un maestro;
- **cancella i dati dei figli, o l'account, quando vuole** (TALITA-7), dal «Profilo»: cosa si cancella subito e cosa resta (la manleva e il registro, con la data in cui vanno via e il perché; l'indirizzo di chi ha letto segnalazioni,
  finché restano le letture) lo dice il dialogo prima di confermare;
- revoca il consenso alle segnalazioni, che vengono tolte.

Per quello che l'app non permette di fare da sé (anche per le famiglie del cartaceo, che non hanno un account), scrive al titolare, che risponde **entro un mese** (art. 12.3), con una proroga di due mesi solo motivata; anche
un rifiuto va motivato entro un mese. Il
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
