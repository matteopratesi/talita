# API di configurazione

API REST in JSON per cambiare la **configurazione** di Talità senza passare dalle pagine dell'app: la convivenza (titolo, date, quote, informazioni, «cosa portare»), le strutture, le camere e i moduli. Le può usare **qualunque client** che abbia il token personale di un maestro: uno script, `curl`, un altro programma. Un paragrafo, in fondo, dice come le usa uno strumento in particolare dal PC di Matteo.

Le API passano dagli **stessi controlli, frasi e lock dei form** dell'app (un punto solo per area, che form e API chiamano): quello che un form rifiuta, le API lo rifiutano con la stessa frase. Non danno e non ricevono **nessun dato personale**: niente bambini, genitori, account, iscrizioni, segnalazioni, presenze, pagamenti, chi dorme dove, nomi e indirizzi dei maestri, inviti, e nemmeno il codice della pagina informativa (`docs/03-privacy.md`, «Le API di configurazione»).

Questo documento è tenuto allineato al codice da un test (`ApiDocsTests`): ogni rotta di `/api/**` e ogni campo delle richieste sono qui, e qui non c'è una rotta o un campo che non esiste.

## Come si entra

Ogni richiesta porta l'intestazione `Authorization: Bearer <token>`. Il token si crea dal **profilo di un maestro**, nella scheda «Token per le API» (`docs/04-ambiente.md` dice dove tenerlo sul computer e come si scrive senza lasciarlo nella cronologia):

- vale **30 giorni**, ce n'è **uno solo** per maestro (uno nuovo sostituisce subito quello di prima) e si revoca dal profilo; ha la forma `talita_` e 43 caratteri;
- vale quanto il maestro **solo per la configurazione**: non apre nessuna pagina dell'app, e una sessione dell'app non apre le API;
- niente sessione, cookie, CSRF né CORS: un browser su un altro sito non le può usare.

Un token che manca, ha un'altra forma, non esiste, è revocato, è scaduto o è di chi non è più maestro riceve **sempre la stessa risposta**: `401`, `WWW-Authenticate: Bearer` e `{"error":"Token mancante o non valido."}`.

Ogni risposta, errori compresi, è JSON (`Content-Type: application/json`) con `Cache-Control: no-store` e `X-Content-Type-Options: nosniff`. Mai una pagina HTML, nemmeno per una rotta che non esiste. Le date sono `2027-09-17`; gli importi sono testi come nel form: `"12,50"`, `"0"` (gratuita) o `null` (non prevista).

## Gli errori

Il corpo di un errore è `{"error": "frase"}`, e con `422` anche `{"fields": {"campo": "frase"}}` con le frasi dei form.

| Codice | Quando |
| --- | --- |
| `400` | il corpo non è JSON, un campo ha il tipo sbagliato, un campo **non esiste** (la frase lo nomina) o, in un `PUT`, **manca** un campo |
| `401` | token mancante o non valido |
| `404` | una risorsa o una rotta che non c'è, o di un'altra struttura |
| `405` | il metodo non è quello della rotta |
| `409` | lo stato non lo permette (una struttura usata che si toglie, una camera in cui dorme qualcuno, la bozza che c'è già, le date di una convivenza finita) |
| `413` | il corpo supera 64 KB |
| `415` | il corpo non è `application/json` |
| `422` | campi sbagliati, con le frasi dei form |
| `429` | più di 60 richieste al minuto con lo stesso token; `Retry-After` dice quanti secondi aspettare |
| `500` | un errore di Talità: «Qualcosa non ha funzionato.», senza dettagli |

Ogni richiesta è una transazione: un errore lascia il database com'era.

## Le regole per chi le usa

Valgono per **chiunque** usi le API, una persona o un programma:

1. **Leggi prima di scrivere.** Un `PUT` scrive **ogni campo** (anche vuoto: `null` o `""`) e **un campo che manca è un `400`**: così un valore non si svuota perché ci si è dimenticati di scriverlo. Per cambiare una cosa sola, prima si fa il `GET`, poi si rimanda tutto com'era, con quella cosa cambiata.
2. **Mai nomi di persone nei testi.** I testi (titolo, informazioni, «cosa portare», nomi di strutture, camere e moduli) sono pubblici o sono stampati: non ci vanno nomi di bambini, genitori o maestri, né email, telefoni, IBAN. Talità rifiuta un testo con il nome e il cognome di una persona che conosce, e il rifiuto dice che quella persona c'è: non usare le API per cercarlo.
3. **Mai aprire il file del token**, né scriverne il contenuto in un comando, una risposta, un commento o un documento. Il programma legge il file da sé (`curl -H @file`).
4. **Una modifica alla volta**, e ogni modifica si rimedia con un'altra. Il registro «Ultime richieste» nel profilo del maestro mostra cosa è stato fatto (metodo, rotta e codice).
5. **Cosa resta nell'app** (le API rispondono `409` o non hanno la rotta): aprire, chiudere e riaprire le iscrizioni, mandare i promemoria, scegliere gli affidatari (sono nomi di maestri), togliere una camera in cui dorme qualcuno, cambiare la struttura di una convivenza con persone nelle camere (l'app chiede la conferma), il menù, chi dorme dove, Arrivi, le stampe, i maestri e gli inviti.

## Le rotte

Un `GET` risponde `200`. Le risposte che cambiano qualcosa hanno `200` (o `201` se nasce una cosa) con la cosa com'è dopo, e `notice` quando il form avrebbe un avviso in più.

### GET /api/convivenza

L'elenco delle convivenze, dalla più recente. Risposta: una lista (vuota se non ce n'è nessuna) di oggetti con `id`, `title`, `startDate`, `endDate`, `state` (`draft`, `open`, `closed`), `over` (finita: non è una bozza e l'ultimo giorno è passato) e `venueId` (o `null`). Niente affidatari, codice pubblico né conteggi.

### GET /api/strutture

L'elenco delle strutture per nome. Risposta: una lista di oggetti con `id`, `name`, `place`, `gatherings` (da quante convivenze è usata, mai quali), `rooms`, `beds` e `forms`. Niente persone.

## Come le usa Claude Code dal PC di Matteo

Claude Code chiama le API con `curl`, una modifica alla volta, quando Matteo gli chiede una cosa come «aggiungi questa camera» o «la quota ora è 0 per ogni bambino». Legge il token con `curl -H @"$HOME/.config/talita/dev.header"`, **senza mai aprire quel file**, e l'indirizzo da `~/.config/talita/dev.url`. L'ambiente è `dev` finché Matteo non dice «in produzione». Fa un `GET` prima di ogni `PUT`, non scrive nomi di persone nei testi, e dopo ogni modifica riferisce cosa ha cambiato. Sono le regole di sopra: valgono per lui come per chiunque.
