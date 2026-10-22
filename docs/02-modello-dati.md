# Modello dati

Entità e dati personali di Talità, con lo **scopo** di ognuno. Regola di CLAUDE.md: un dato
personale senza scopo scritto qui non si aggiunge, e un campo nuovo si aggiunge a questo documento
nello stesso ticket che lo crea.

Questo è il punto di partenza, preso dal perimetro di KOINE-29. Il dettaglio di ogni entità (colonne,
vincoli, tabelle) lo fissa il ticket che la crea. TALITA-2 non introduce dati personali: nessuna
tabella e nessun form. Lo schema comincia con TALITA-3 (account e link di accesso, maestri e inviti).

Nessun `centro_id` e nessuna row-level security: Talità ha un solo titolare, e l'isolamento fra
genitori (ognuno vede solo i suoi figli) lo fa l'applicazione, con i suoi test.

## Dati e scopi

| Entità | Dato | Scopo | Ticket |
| --- | --- | --- | --- |
| Account (`account.email`) | email | accesso con magic link; per i maestri anche gli inviti. In minuscolo, senza spazi ai lati (un vincolo della tabella lo garantisce). L'account nasce al primo accesso riuscito, e ogni account è anche un genitore. Quanto si tiene e come si cancella lo dice TALITA-7. | TALITA-3 |
| Link di accesso (`one_time_tokens`) | email, scadenza | far entrare chi ha chiesto il link. Vale 15 minuti e una volta sola: si cancella quando si usa, e uno scaduto si cancella entro un'ora | TALITA-3 |
| Invito a maestro (`teacher_invitation`) | email, scadenza | far entrare un nuovo maestro: chi entra con quell'indirizzo trova l'invito e lo accetta. Si cancella quando è accettato o ritirato; quanto resta uno scaduto lo dice TALITA-7 | TALITA-3 |
| Maestro (`teacher`) | nome e cognome | testo dell'affidamento (maestri affidatari); elenco dei presenti per la struttura. Li scrive il maestro quando accetta l'invito, anche il primo, e li corregge dal profilo; si cancellano quando il maestro è tolto | TALITA-3 (li raccoglie), 4 e 6 (li usano) |
| Iscrizione | recapito telefonico del genitore | chiamare il genitore durante i giorni della convivenza | TALITA-5 |
| Bambino | nome e cognome | riconoscere il bambino in iscrizione, check-in, registro ed elenco per la struttura | TALITA-5 |
| Bambino | data di nascita | **scopo proposto, da confermare in TALITA-5**: verificare che il bambino abbia l'età prevista e distinguere due bambini con lo stesso nome | TALITA-5 |
| Iscrizione | giorni di presenza, quota dovuta | calcolo della quota, registro arrivi | TALITA-5 |
| Iscrizione | accettazione dell'affidamento con data e ora; spunta "anche l'altro genitore è d'accordo" | prova dell'affidamento (manleva) | TALITA-5 |
| Iscrizione | "manleva cartacea ricevuta" | iscrizioni inserite da un maestro per chi non usa l'app | TALITA-5 |
| Iscrizione | stato (confermata, in forse, ritirata) | organizzazione della convivenza | TALITA-5 |
| Segnalazioni | allergie, intolleranze, farmaci, facoltative | sicurezza del bambino durante la convivenza. Sono dati sanitari: consenso esplicito, tabella separata, solo i maestri le vedono | TALITA-5, 7 |
| Lettura delle segnalazioni | chi le ha lette e quando | rendere conto di chi ha visto i dati sanitari | TALITA-7 |
| Check-in | arrivo per giorno, quota pagata | registro della convivenza | TALITA-6 |

Conservazione (TALITA-7): segnalazioni cancellate un mese dopo la fine della convivenza; manleva e
registro tenuti un anno; dati dei bambini tenuti per l'anno dopo, e il genitore li può cancellare
quando vuole. I tempi e le loro ragioni stanno in `docs/03-privacy.md`.

Di sessione, contatori dei limiti (3 link ogni 15 minuti per indirizzo, 150 mail al giorno) e
indirizzo mostrato in «Controlla la posta» resta traccia solo in memoria, e il cookie porta solo
l'id di sessione. **Non si raccolgono**: ultimo accesso, indirizzo IP, browser, registro degli accessi,
chi ha invitato chi. Se servono (per esempio a TALITA-7 per gli account inattivi) li aggiunge quel
ticket, con il suo scopo.

## Da decidere

Questi dati **non entrano** in questo documento finché un ticket non ne scrive lo scopo.

- Comunità del bambino e note del registro (TALITA-6). Le note libere rischiano di raccogliere dati
  sanitari fuori dalle segnalazioni.
- Nome del genitore che accetta l'affidamento (TALITA-5). Oggi chi accetta è l'account, con data e
  ora.
- Per quanto tempo si tengono email e recapito dei genitori, gli account dei maestri e i dati di
  chi non torna l'anno dopo (TALITA-7, che oggi fissa i tempi solo per segnalazioni, manleva,
  registro e dati dei bambini).

## Mai

Questi dati non si raccolgono:

- sesso;
- luogo di nascita;
- dati dei maestri per la struttura oltre a nome e cognome;
- dati sanitari fuori dalle segnalazioni;
- documenti caricati;
- firme.
