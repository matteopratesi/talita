# Contesto

Perché esiste Talità, per chi è e cosa deve fare nel giorno in cui serve davvero. Le regole di
lavoro stanno in `CLAUDE.md`, le scelte e i loro motivi in `docs/05-decisioni.md`.

## Chi organizza la convivenza, per quante persone e quando

La convivenza dei bambini la organizzano i **maestri dei bambini di due parrocchie**, una volta
l'anno, a **settembre**, in una casa per ritiri. Partecipano **30-35 persone** fra bambini e
maestri. Matteo è uno dei maestri.

Il primo rilascio deve essere pronto per giugno 2027. Fino ad allora si sviluppa e si prova con dati
sintetici.

È un progetto di servizio, senza scopo di lucro, e non deve costare niente: l'hosting è gratuito
e la scala è minima (una convivenza all'anno, qualche decina di persone).

## Cosa sostituisce

Oggi la convivenza si organizza con tre pezzi di carta (o l'equivalente in un messaggio):

- **la lettera ai genitori**, con date, luogo, quote e cosa portare, che i maestri fanno girare;
- **il modulo cartaceo di affidamento** (la manleva), che il genitore compila e firma per
  affidare i figli ai maestri affidatari;
- **il registro arrivi**, compilato a mano all'arrivo dei bambini, con chi è arrivato e chi ha
  pagato.

Talità li sostituisce con tre cose: una **pagina informativa** da condividere con un link (al posto
della lettera), l'**iscrizione**, che è anche la manleva (al posto del modulo), e il **check-in dal
telefono**, con le stampe per il registro e per la struttura (al posto del registro a mano).

## Chi la usa e come

- **Il genitore** arriva da un link condiviso su WhatsApp, entra con un link che riceve per email
  (magic link, senza password), iscrive i suoi figli e accetta l'affidamento. Può correggere
  l'iscrizione finché le iscrizioni sono aperte. Vede solo i suoi figli. Quasi sempre dal telefono,
  poco pratico con l'informatica.
- **Il maestro** crea e configura la convivenza (date, luogo, giorni, quote, maestri affidatari,
  testo informativo), invita gli altri maestri via email, inserisce le iscrizioni arrivate sul
  modulo cartaceo (per chi non usa l'app), e nei giorni dell'evento fa il check-in dal telefono: cerca
  il bambino, segna l'arrivo e la quota pagata, legge le segnalazioni e chiama il genitore. Vede
  tutta la convivenza.

Sono due ruoli, senza un amministratore a parte. L'interfaccia è pensata per il telefono (mobile
first) e per chi non è informatico: vedi `docs/design/README.md`.

## Il problema vero: il giorno dell'evento

Raccogliere le iscrizioni è la parte facile. Il problema è il giorno dell'evento: sapere **chi ha
consegnato la manleva**, chi è arrivato e chi ha pagato, e avere a portata di mano le
**segnalazioni** (allergie, intolleranze, farmaci) e il recapito del genitore quando serve. In più
bisogna compilare il **registro arrivi** e la **scheda per la struttura** (l'elenco dei presenti,
con solo nome e cognome).

Per questo il primo rilascio punta a un giorno dell'evento senza fogli da cercare: la ricerca del
bambino, il check-in, le segnalazioni e le stampe (registro arrivi precompilato, e una copia
stampata come riserva se manca la rete, perché in una casa isolata il campo può mancare).

## Il rapporto con Koinè

Talità nasce da **Koinè**, l'anagrafe delle comunità e gestione delle convivenze di un Centro, ma
è un'app **separata**, con repo, database e deploy suoi. Condivide con Koinè lo stack e il design
system (la copia è partita dal commit `23ea63d` di Koinè), non il codice di dominio né i dati.

Perché separata (esito di KOINE-29, 21/10/2026): il titolare dei dati è diverso (Matteo, non il
Centro), i tempi sono diversi (Talità serve per giugno 2027) e il modello è diverso (i bambini non
entrano nell'anagrafe delle comunità). Il dettaglio è in `docs/05-decisioni.md`.

In Koinè resta il modulo delle convivenze, comprese quelle dei bambini che un Centro organizza
per conto suo: è una questione di Koinè, non di Talità.

## Cosa resta fuori dal primo rilascio

**Camere, cucina e menù, solleciti via email sono nel primo rilascio.** Dall'esito di KOINE-29 ne erano fuori; il 24/10/2026 Matteo li ha messi dentro (`docs/05-decisioni.md`): camere (TALITA-11), cucina e menù (TALITA-12), solleciti via email (TALITA-13), tutti sotto l'epica TALITA-1 (M1 · Primo rilascio). Le analisi sono nei ticket, e i vincoli che vi sono già scritti restano.

**Cucina e menù entra solo come file** (TALITA-12, decisione di Matteo del 24/10/2026: «Keep it simple per ora mettiamolo solo come caricamento di un file per averlo sempre disponibile»). Un maestro carica il file del menù della convivenza (un PDF, o la foto del foglio), lo sostituisce o lo toglie, e i maestri lo scaricano, anche dal telefono durante la convivenza: la scheda «Menù» nel dettaglio e un collegamento in Arrivi. Lo vedono solo i maestri, non i genitori né la pagina informativa. Restano fuori, per un ticket futuro: pasti e menù per pasto, coperti calcolati dalle presenze, una vista per la cucina sulle segnalazioni (mostrando il minimo, mai nomi accanto ai dati sanitari) e le stampe per la cucina e per i tavoli.

Dall'esito di KOINE-29, gli altri punti non sono nel primo rilascio:

- rendiconto e raccolte;
- funzionamento senza rete (la stampa del registro è la riserva);
- firma con valore legale;
- sesso e luogo di nascita (mai: vedi `docs/02-modello-dati.md`);
- dati dei maestri per la struttura, oltre a nome e cognome;
- caricamento di documenti, tranne il file del menù (sopra): manleve, elenchi e registri si tengono fuori dall'app.

Fuori anche tutto ciò che è di Koinè: anagrafe delle comunità, appartenenze, fusioni, permessi per
ruolo e ambito, più centri.
