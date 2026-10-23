# Modello dati

Entità e dati personali di Talità, con lo **scopo** di ognuno. Regola di CLAUDE.md: un dato
personale senza scopo scritto qui non si aggiunge, e un campo nuovo si aggiunge a questo documento
nello stesso ticket che lo crea.

Questo è il punto di partenza, preso dal perimetro di KOINE-29. Il dettaglio di ogni entità (colonne,
vincoli, tabelle) lo fissa il ticket che la crea. TALITA-2 non introduce dati personali: nessuna
tabella e nessun form. Lo schema comincia con TALITA-3 (account e link di accesso, maestri e inviti); TALITA-4 aggiunge la convivenza e i suoi
affidatari; TALITA-5 i bambini (`child`), le iscrizioni (`registration` e `registration_day`), le segnalazioni (`health_note`) e il registro
delle letture (`health_note_access`), con la migrazione `V5__registration.sql`.

Nessun `centro_id` e nessuna row-level security: Talità ha un solo titolare, e l'isolamento fra
genitori (ognuno vede solo i suoi figli) lo fa l'applicazione, con i suoi test.

## Dati e scopi

| Entità | Dato | Scopo | Ticket |
| --- | --- | --- | --- |
| Account (`account.email`) | email | accesso con magic link; per i maestri anche gli inviti. In minuscolo, senza spazi ai lati (un vincolo della tabella lo garantisce). L'account nasce al primo accesso riuscito, e ogni account è anche un genitore. Quanto si tiene e come si cancella lo dice TALITA-7. | TALITA-3 |
| Link di accesso (`one_time_tokens`) | email, scadenza | far entrare chi ha chiesto il link. Vale 15 minuti e una volta sola: si cancella quando si usa, e uno scaduto si cancella entro un'ora | TALITA-3 |
| Invito a maestro (`teacher_invitation`) | email, scadenza | far entrare un nuovo maestro: chi entra con quell'indirizzo trova l'invito e lo accetta. Si cancella quando è accettato o ritirato; quanto resta uno scaduto lo dice TALITA-7 | TALITA-3 |
| Maestro (`teacher`) | nome e cognome | testo dell'affidamento (maestri affidatari); elenco dei presenti per la struttura. Li scrive il maestro quando accetta l'invito, anche il primo, e li corregge dal profilo; si cancellano quando il maestro è tolto | TALITA-3 (li raccoglie), 4 e 6 (li usano) |
| Convivenza (`gathering`) | titolo, data di inizio e di fine, luogo, tre quote (intera, giornaliera, solo pranzo), informazioni per i genitori, cosa portare, stato (bozza, iscrizioni aperte, iscrizioni chiuse), codice della pagina pubblica | **Nessun dato personale**: sono i dati di un evento, e il luogo è una struttura. Dicono però dove e quando ci saranno dei bambini: per questo la pagina informativa sta a un indirizzo con un codice che non si indovina (`/info/{codice}`, 96 bit casuali, che non cambia con le modifiche), non si indicizza, non porta nomi, email, telefoni né numeri sulle iscrizioni, e a convivenza finita non dice più dove era. Titolo, luogo e testi pubblici non possono contenere indirizzi email, telefoni né IBAN (controllo al salvataggio). I giorni di presenza sono tutti quelli dal primo all'ultimo, al massimo 7. Una convivenza non si cancella: le vecchie si consultano | TALITA-4 |
| Affidatari (`gathering_custodian`) | il legame fra una convivenza e un maestro (`teacher.account_id`) | testo dell'affidamento che il genitore accetta con l'iscrizione. Nessun dato in più: il nome e il cognome sono quelli che il maestro ha già scritto (riga «Maestro», con il suo scopo) e non si copiano. Li vedono i maestri e, con TALITA-5, il genitore nel testo dell'affidamento; mai la pagina informativa, l'anteprima del link né la stampa. Un maestro affidatario di una convivenza non finita non si toglie; se è affidatario solo di convivenze finite, togliendolo il legame si cancella con il suo nome (resta solo nelle accettazioni di TALITA-5) | TALITA-4 (il legame), 5 (il testo) |
| Bambino (`child`) | nome e cognome | riconoscere il bambino in iscrizione, check-in, registro ed elenco per la struttura. Il bambino è dell'account che l'ha iscritto e resta per l'anno dopo (si ritrova fra «I tuoi figli»); il bambino di un'iscrizione dal cartaceo non ha account e va via con la sua iscrizione. Due bambini dello stesso account con lo stesso nome, cognome e data di nascita (senza contare maiuscole, accenti e spazi ai bordi) sono lo stesso bambino | TALITA-5 |
| Bambino | data di nascita | **confermata** (era «proposta»): verificare l'età prevista (l'età al primo giorno si vede nell'elenco dei maestri), distinguere omonimi e doppioni, identificare il bambino nel testo dell'affidamento | TALITA-5 |
| Iscrizione (`registration`) | recapito telefonico | chiamare il genitore durante i giorni della convivenza. Uno per iscrizione (nell'iscrizione dal genitore è lo stesso per tutti i bambini dello stesso genitore alla stessa convivenza); lo vedono i maestri e il genitore titolare; nelle iscrizioni dal cartaceo viene dal modulo | TALITA-5 |
| Iscrizione | presenza (tutti i giorni, oppure giornata o solo pranzo per ogni giorno: `registration_day`) | calcolo della quota, registro arrivi. **La quota dovuta non si salva**: si calcola ogni volta dalle quote della convivenza e dai giorni, e un'iscrizione ritirata non deve niente | TALITA-5 |
| Iscrizione | accettazione dell'affidamento: il **testo esatto mostrato** (convivenza, date, luogo, nomi degli affidatari di quel momento, nome e data di nascita del figlio, dichiarazione sull'altro genitore), la versione del modello (`affidamento-1`), data e ora, e la casella sulle convinzioni deducibili | prova dell'affidamento (manleva). Si conserva il testo risultante, intero, non il modello: non cambia più, se poi cambiano affidatari, titolo, date o luogo | TALITA-5 |
| Iscrizione | **indirizzo email di chi accetta**, copiato nell'accettazione | **nuovo**: la prova regge anche se l'account cambia o si cancella (prova il controllo della casella, non l'identità) | TALITA-5 |
| Iscrizione | «manleva cartacea ricevuta» | iscrizioni inserite da un maestro per chi non usa l'app; il modulo firmato si conserva fuori dall'app (niente documenti caricati) | TALITA-5 |
| Iscrizione | **maestro che l'ha inserita dal cartaceo** (account) | **nuovo**: rendere conto di chi ha inserito i dati di un modulo cartaceo | TALITA-5 |
| Iscrizione | stato (confermata, in forse, ritirata) | organizzazione della convivenza | TALITA-5 |
| Segnalazioni (`health_note`) | allergie, intolleranze, farmaci, facoltative | sicurezza del bambino durante la convivenza. Sono dati sanitari: consenso esplicito, tabella separata, solo i maestri le vedono, a richiesta e con il registro. **Cancellate subito al ritiro dell'iscrizione e alla revoca del consenso**; un mese dopo la fine della convivenza le cancella TALITA-7. Le legge e le scrive una sola classe di codice (`HealthNotes`) | TALITA-5, 7 |
| Segnalazioni | **data e ora del consenso, account che lo dà, oppure «consenso sul modulo cartaceo»** | **nuovo**: dimostrare il consenso esplicito | TALITA-5 |
| Lettura delle segnalazioni (`health_note_access`) | maestro (account), iscrizione, data e ora | rendere conto di chi ha visto i dati sanitari. Non ha il testo e non dice se le segnalazioni c'erano. La crea TALITA-5 a ogni lettura e a ogni modifica fatta da un maestro; i tempi sono di TALITA-7 (un anno). Un maestro che non è più tra i maestri resta nel registro come account | TALITA-5, 7 |
| Check-in | arrivo per giorno, quota pagata | registro della convivenza | TALITA-6 |

**Chi vede cosa.** Il genitore titolare: le sue iscrizioni, con il testo accettato e le sue segnalazioni (senza che la lettura si registri). Un altro
genitore: niente, e nemmeno un messaggio che dica che un bambino c'è già (due genitori che iscrivono lo stesso bambino hanno due iscrizioni, e ognuno
vede solo la sua). I maestri: tutto, tranne le segnalazioni, che vedono a richiesta e con il registro. La pagina pubblica, le email e il log: niente.

Conservazione (TALITA-7): segnalazioni cancellate un mese dopo la fine della convivenza; iscrizioni (la manleva) e registro delle letture tenuti un
anno; dati dei bambini degli account tenuti per l'anno dopo, e il genitore li può cancellare quando vuole; i bambini delle iscrizioni dal cartaceo
non servono l'anno dopo e vanno via con l'iscrizione; il recapito segue l'iscrizione. I tempi e le loro ragioni stanno in `docs/03-privacy.md`.

Di sessione, contatori dei limiti (3 link ogni 15 minuti per indirizzo, 150 mail al giorno) e
indirizzo mostrato in «Controlla la posta» resta traccia solo in memoria, e il cookie porta solo
l'id di sessione. **Non si raccolgono**: ultimo accesso, indirizzo IP, browser, registro degli accessi,
chi ha invitato chi. Se servono (per esempio a TALITA-7 per gli account inattivi) li aggiunge quel
ticket, con il suo scopo.

## Da decidere

Questi dati **non entrano** in questo documento finché un ticket non ne scrive lo scopo.

- Comunità del bambino e note del registro (TALITA-6). Le note libere rischiano di raccogliere dati
  sanitari fuori dalle segnalazioni.
- Per quanto tempo si tengono email e recapito dei genitori, gli account dei maestri e i dati di
  chi non torna l'anno dopo (TALITA-7, che oggi fissa i tempi solo per segnalazioni, manleva,
  registro e dati dei bambini).

## Mai

Questi dati non si raccolgono (anche il **nome del genitore che accetta**: chi accetta è l'account, con l'indirizzo copiato nell'accettazione; TALITA-5):

- sesso;
- luogo di nascita;
- dati dei maestri per la struttura oltre a nome e cognome;
- dati sanitari fuori dalle segnalazioni;
- documenti caricati;
- firme.
