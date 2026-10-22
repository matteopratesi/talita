---
name: avanza
description: Porta avanti un ticket Jira di Talità nel flusso agentico (architetto, designer, developer, QA) fino al prossimo punto in cui serve Matteo. Usalo con "/avanza TALITA-n", con "/avanza" per prendere il primo ticket in To Do, o quando Matteo dà una nuova richiesta da trasformare in ticket.
---

# Avanza

Sei l'orchestratore. Matteo è il product owner: decide cosa si fa, approva la specifica e dà
l'ok al merge. Tu non fai il lavoro dei ruoli: per ogni passo prepari il contesto e lanci
l'agente giusto, poi controlli il risultato prima di andare avanti.

Jira: progetto TALITA, cloudId `7c631dfd-34f5-47cd-8883-fcde1c6f298d`. Ogni transizione ha lo
stesso nome dello stato d'arrivo, senza eccezioni: "In Development" porta a In Development e
"Closed" a Closed.

## 1. Scegli il ticket

- Con una chiave (`/avanza TALITA-5`): quel ticket.
- Senza chiave: il primo in To Do nell'ordine del backlog
  (`project = TALITA AND status = "To Do" ORDER BY rank`).
- Con una richiesta nuova di Matteo in chat: lancia `architect`, che crea il ticket e lo
  analizza.

Leggi ticket e commenti. Se ha l'etichetta `decisione` e la decisione non è ancora nei
commenti, fermati e chiedila a Matteo.

## 2. Fai il passo che corrisponde allo stato

| Stato | Cosa fai | Agente e modello |
|---|---|---|
| Open | Niente: è un'idea non ancora approvata. Chiedi a Matteo se va in To Do. | — |
| To Do | Ricerca, poi analisi. | Explore (haiku), poi `architect` (opus) |
| Analyzed | Design. Se `docs/design/` non esiste ancora, prima il design system. | `designer` (sonnet; opus per il design system) |
| Designed | Fermati: Matteo deve approvare analisi e design. Con il suo ok, porta il ticket in Ready to Develop. | — |
| Ready to Develop | Sviluppo. | `developer` (sonnet) |
| In Development | Riprendi lo sviluppo (per esempio dopo un difetto del QA). | `developer` (sonnet) |
| In QA | Verifica. | `qa` (sonnet) |
| Closed | Niente. | — |

La ricerca la fai tu prima dell'agente del ruolo, perché i subagenti non possono lanciarne
altri. Usa uno o più agenti Explore con modello haiku, in parallelo quando le domande sono
indipendenti: codice e documenti toccati, convenzioni già in uso, versioni e documentazione
delle librerie online. Passa all'agente del ruolo un riassunto dei risultati, non i file.

Nel prompt di ogni agente metti chiave e testo del ticket, i risultati della ricerca e cosa
deve consegnare. Prima di lanciarlo assegna il ticket all'account Jira dell'agente di turno,
se CLAUDE.md ne riporta l'account ID; altrimenti basta la firma nei commenti. Lancia il
developer con `isolation: "worktree"`, così lavora su un checkout suo.

Un worktree nuovo parte dal ramo di default, non dal branch della PR. Quando lanci il QA, o
riprendi il developer per un difetto o per i commenti di CodeRabbit, scrivi nel prompt il branch
della PR (`gh pr view <numero> --json headRefName --jq .headRefName`) e chiedi all'agente di
farne il checkout (`git fetch origin <branch>` e `git switch <branch>`) prima di toccare
qualcosa: si lavora su quel branch, mai su `main`, e il developer lo riusa invece di crearne uno
nuovo.

La PR nasce sempre in bozza (`gh pr create --draft`) e resta in bozza durante sviluppo, QA e
correzioni: la review di CodeRabbit si chiede solo dopo il QA (vedi sotto). Appena il developer la
apre, collegala al ticket come web link su Jira: prima `listJiraIssueRemoteIssueLinks` (lo stesso
URL aggiunto due volte diventa un doppione che non si può togliere), poi
`createJiraIssueRemoteIssueLink` con l'URL della PR e un titolo che inizia con `PR #<numero>`.

## 3. Controlla il risultato

Prima di andare avanti verifica che l'agente abbia consegnato davvero: `## Analisi` nella
descrizione e, se l'analisi dice "Interfaccia: sì", anche `## Design` (senza interfaccia il
designer scrive solo un commento); poi il commento firmato, lo stato giusto, la PR in bozza
collegata al ticket, la CI verde sull'ultimo commit (controlla tu il run: l'agente può citarne
uno vecchio). Se manca
qualcosa, rilancia lo stesso agente dicendo cosa manca. Dopo due tentativi falliti fermati e
chiedi a Matteo.

Puoi concatenare i passi finché non arrivi a un punto in cui serve Matteo: Designed (approva
la specifica) e In QA con QA superato e review di CodeRabbit chiusa (ok al merge). Se il QA
fallisce, rilancia il developer e poi di nuovo il QA, al massimo due giri, poi fermati e
chiedi.

### Review di CodeRabbit

Il repo è pubblico e ha meno di 10 stelle: CodeRabbit non rivede da solo, la review va chiesta a
mano a ogni giro. Finché la PR è in bozza lo status dice «Review skipped: draft pull request».

**Primo giro.** Quando il QA è superato e non ci sono altre modifiche in vista:

1. Metti la PR pronta: `gh pr ready <numero>`.
2. Leggi lo status (vedi sotto). Se dice «Review skipped: manual review required for this OSS
   repository», chiedi subito la review con un commento sulla PR:
   `gh pr comment <numero> --body "@coderabbitai review"`. Se dice ancora «Review skipped: draft
   pull request», aspetta qualche secondo e rileggi. Se dice già «Review in progress» o «Review
   completed» (per esempio se un giorno la review parte da sola), il commento non si scrive.
3. Aspetta che lo status dica «Review completed».

**Come si legge l'esito.** Il segnale è la descrizione dello status `CodeRabbit` sul commit di
testa della PR, non il suo stato: anche «Review skipped…» ha stato `success`, e `gh pr checks` lo
mostra come `pass`. Il `gh` installato (2.45) non ha `gh pr checks --json`: prendi lo SHA con
`gh pr view <numero> --json headRefOid --jq .headRefOid` e la descrizione con
`gh api repos/{owner}/{repo}/commits/<sha>/status --jq '.statuses[] | select(.context == "CodeRabbit") | .description'`.
Lo status è per commit: dopo un push il nuovo commit di testa torna a «Review skipped: manual
review required…», e la review va chiesta di nuovo.

**Se lascia commenti**, rilancia il developer: corregge quelli fondati sullo stesso branch e
risponde agli altri sulla PR spiegando perché. Se le correzioni toccano il codice, fai ripetere
al QA le verifiche dei criteri toccati. La CI deve tornare verde. Se la review è completa e non
ha commenti, vai avanti verso Matteo.

**Secondo giro.** Con la review completata e i commit di correzione spinti dopo, quando la CI è
di nuovo verde e il QA ha ripetuto i criteri toccati, chiedi un secondo giro con lo stesso
commento `@coderabbitai review` (rivede solo i commit nuovi) e aspetta «Review completed» sul
nuovo commit di testa. Niente push mentre la review è «in progress»: se capita, chiedi la review
sul commit nuovo. Se dopo il secondo giro servono altri commit, niente terzo giro automatico: lo
dici a Matteo.

**Rate limit e silenzio.** Se CodeRabbit è in rate limit lo scrive in un commento sulla PR (lo
status può non dirlo): aspetta il tempo indicato e chiedi di nuovo la review. Se entro 30 minuti
dalla richiesta non c'è né «Review completed» né un commento del bot che spieghi perché, lo dici
a Matteo invece di chiedergli l'ok al merge. Se rivede solo una parte di una PR grande, lo dici a
Matteo.

**Review conclusa.** Lo è solo se l'ultimo giro che hai chiesto ha «Review completed» e ogni
commento di CodeRabbit ha una correzione o una risposta. Nel messaggio a Matteo scrivi quanti giri
sono stati fatti e quali commit CodeRabbit non ha rivisto (quelli dopo l'ultimo «Review
completed»).

## 4. Merge e chiusura

Mai merge prima che la review di CodeRabbit sia conclusa (come dice la sezione sopra) e le sue
correzioni applicate. Solo
quando Matteo dà l'ok al merge: `gh pr merge --squash --delete-branch`. Se il branch locale è
ancora usato da un worktree, `gh` non cancella neanche quello remoto: cancellalo con
`git push origin --delete <branch>`. Controlla che la CI su `main` sia verde, porta il ticket in
Closed con un commento che riporta il commit.

## 5. Riferisci

Alla fine dici a Matteo in poche righe: ticket, stato raggiunto, cosa ha fatto ogni agente, e
la decisione che serve da lui, se ce n'è una.
