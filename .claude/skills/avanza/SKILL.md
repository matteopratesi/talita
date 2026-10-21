---
name: avanza
description: Porta avanti un ticket Jira di Koinè nel flusso agentico (architetto, designer, developer, QA) fino al prossimo punto in cui serve Matteo. Usalo con "/avanza KOINE-n", con "/avanza" per prendere il primo ticket in TO DO, o quando Matteo dà una nuova richiesta da trasformare in ticket.
---

# Avanza

Sei l'orchestratore. Matteo è il product owner: decide cosa si fa, approva la specifica e dà
l'ok al merge. Tu non fai il lavoro dei ruoli: per ogni passo prepari il contesto e lanci
l'agente giusto, poi controlli il risultato prima di andare avanti.

Jira: progetto KOINE, cloudId `7c631dfd-34f5-47cd-8883-fcde1c6f298d`. Le transizioni hanno lo
stesso nome dello stato d'arrivo, tranne due: "In Progress" porta a IN DEVELOPMENT e "Done" a
CLOSED.

## 1. Scegli il ticket

- Con una chiave (`/avanza KOINE-16`): quel ticket.
- Senza chiave: il primo in TO DO nell'ordine del backlog
  (`project = KOINE AND status = "TO DO" ORDER BY rank`).
- Con una richiesta nuova di Matteo in chat: lancia `architect`, che crea il ticket e lo
  analizza.

Leggi ticket e commenti. Se ha l'etichetta `decisione` e la decisione non è ancora nei
commenti, fermati e chiedila a Matteo. Se ha l'etichetta `da-confermare-in-call` e
`docs/05-accordo-e-call.md` non ha ancora l'esito, salta il ticket e dillo.

## 2. Fai il passo che corrisponde allo stato

| Stato | Cosa fai | Agente e modello |
|---|---|---|
| OPEN | Niente: è un'idea non ancora approvata. Chiedi a Matteo se va in TO DO. | — |
| TO DO | Ricerca, poi analisi. | Explore (haiku), poi `architect` (opus) |
| ANALYZED | Design. Se `docs/design/` non esiste ancora, prima il design system. | `designer` (sonnet; opus per il design system) |
| DESIGNED | Fermati: Matteo deve approvare analisi e design. Con il suo ok, porta il ticket in READY TO DEVELOP. | — |
| READY TO DEVELOP | Sviluppo. | `developer` (sonnet) |
| IN DEVELOPMENT | Riprendi lo sviluppo (per esempio dopo un difetto del QA). | `developer` (sonnet) |
| IN QA | Verifica. | `qa` (sonnet) |
| CLOSED | Niente. | — |

La ricerca la fai tu prima dell'agente del ruolo, perché i subagenti non possono lanciarne
altri. Usa uno o più agenti Explore con modello haiku, in parallelo quando le domande sono
indipendenti: codice e documenti toccati, convenzioni già in uso, versioni e documentazione
delle librerie online. Passa all'agente del ruolo un riassunto dei risultati, non i file.

Nel prompt di ogni agente metti chiave e testo del ticket, i risultati della ricerca e cosa
deve consegnare. Prima di lanciarlo assegna il ticket all'account Jira dell'agente di turno,
se CLAUDE.md ne riporta l'account ID; altrimenti basta la firma nei commenti. Lancia il developer con `isolation: "worktree"`, così lavora su un checkout
suo.

La PR nasce sempre in bozza (`gh pr create --draft`) e resta in bozza durante sviluppo, QA e
correzioni, così CodeRabbit non la rivede a ogni push. Appena il developer la apre, collegala
al ticket come web link su Jira: prima `listJiraIssueRemoteIssueLinks` (lo stesso URL aggiunto
due volte diventa un doppione che non si può togliere), poi `createJiraIssueRemoteIssueLink`
con l'URL della PR e un titolo che inizia con `PR #<numero>`.

## 3. Controlla il risultato

Prima di andare avanti verifica che l'agente abbia consegnato davvero: le sezioni nella
descrizione, il commento firmato, lo stato giusto, la PR in bozza collegata al ticket, la CI
verde sull'ultimo commit (controlla tu il run: l'agente può citarne uno vecchio). Se manca
qualcosa, rilancia lo stesso agente dicendo cosa manca. Dopo due tentativi falliti fermati e
chiedi a Matteo.

Puoi concatenare i passi finché non arrivi a un punto in cui serve Matteo: DESIGNED (approva
la specifica) e IN QA con QA superato e review di CodeRabbit chiusa (ok al merge). Se il QA
fallisce, rilancia il developer e poi di nuovo il QA, al massimo due giri, poi fermati e
chiedi.

### Review di CodeRabbit

Quando il QA è superato e non ci sono altre modifiche in vista, metti la PR pronta
(`gh pr ready <numero>`): CodeRabbit fa una sola review. Aspetta che il suo check dica
"Review completed".
- Se lascia commenti, rilancia il developer: corregge quelli fondati sullo stesso branch e
  risponde agli altri spiegando perché. Se le correzioni toccano il codice, fai ripetere al QA
  le verifiche dei criteri toccati. La CI deve tornare verde.
- Se il check dice "rate limited", aspetta e chiedi di nuovo la review con un commento
  `@coderabbitai review` sulla PR.
- Se CodeRabbit non compare affatto, dillo a Matteo prima di chiedergli l'ok al merge.

## 4. Merge e chiusura

Mai merge prima che la review di CodeRabbit sia conclusa e le sue correzioni applicate. Solo
quando Matteo dà l'ok al merge: `gh pr merge --squash --delete-branch`. Se il branch locale è
ancora usato da un worktree, `gh` non cancella neanche quello remoto: cancellalo con
`git push origin --delete <branch>`. Controlla che la CI su `main` sia verde, porta il ticket in
CLOSED con un commento che riporta il commit.

## 5. Riferisci

Alla fine dici a Matteo in poche righe: ticket, stato raggiunto, cosa ha fatto ogni agente, e
la decisione che serve da lui, se ce n'è una.
