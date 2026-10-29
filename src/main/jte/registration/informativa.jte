@import talita.registration.InformativaPage
@import talita.ui.PublicShell
@param PublicShell shell
@param InformativaPage page
<%-- The complete notice (criteria 33, 34; design D, 3): ten cards in the public shell, no script and nothing personal but the controller. The frame at the
     top says that the text is not final: a test of the release (-Prelease) does not let it go out. --%>

@template.layout.public(shell = shell, content = @`
  @template.components.pageHead(title = "Informativa sulla privacy", lead = "Come Talità usa i dati di chi iscrive i bambini a una convivenza.")

  <p class="mb-4 rounded-lg bg-warning-soft px-3 py-2 text-sm font-semibold wrap-anywhere">${page.getNotice()}</p>

  <div class="space-y-3">
    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="titolare">
      <h2 id="titolare" class="text-lg font-semibold">Chi è il titolare</h2>
      <p class="mt-3">Il titolare del trattamento è ${page.getController()}: decide perché e come si usano i dati e ne risponde. Puoi scrivergli a ${page.getContact()}.</p>
    </section>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="dati">
      <h2 id="dati" class="text-lg font-semibold">Quali dati raccogliamo</h2>
      <ul class="mt-3 list-disc space-y-2 pl-5 marker:text-ink-muted">
        <li class="pl-1"><span class="font-semibold">Di chi iscrive i bambini:</span> indirizzo email, numero di telefono, data e ora in cui accetta l’affidamento, e se l’ha chiesto la data e l’ora della richiesta di avviso o dello stop ai promemoria.</li>
        <li class="pl-1"><span class="font-semibold">Dei bambini:</span> nome, cognome, data di nascita, giorni di presenza e stato dell’iscrizione.</li>
        <li class="pl-1"><span class="font-semibold">Segnati dai maestri durante la convivenza:</span> le presenze, giorno per giorno (il giorno e l’ora in cui un maestro segna che un bambino c’è), e i pagamenti (l’importo e l’ora). Ogni presenza e ogni pagamento porta il nome del maestro che l’ha segnato.</li>
        <li class="pl-1"><span class="font-semibold">Facoltativi:</span> allergie, intolleranze e farmaci dei bambini, solo con il consenso del genitore.</li>
        <li class="pl-1"><span class="font-semibold">Dei maestri:</span> nome, cognome e indirizzo email.</li>
      </ul>
      <p class="mt-3">Non raccogliamo mai il sesso, il luogo di nascita, documenti o firme.</p>
    </section>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="scopi">
      <h2 id="scopi" class="text-lg font-semibold">A cosa servono e perché possiamo usarli</h2>
      <ul class="mt-3 list-disc space-y-2 pl-5 marker:text-ink-muted">
        <li class="pl-1">Iscrivere i bambini e organizzare la convivenza: li usiamo perché ce lo chiedi iscrivendo i bambini.</li>
        <li class="pl-1">Presenze e pagamenti: servono ai maestri per sapere chi c’è ogni giorno e quanto è stato pagato, e li usiamo perché fanno parte del servizio che ci chiedi iscrivendo i bambini.</li>
        <li class="pl-1">Allergie, intolleranze e farmaci: li usiamo solo con il tuo consenso esplicito, per la sicurezza dei bambini durante la convivenza.</li>
        <li class="pl-1">Partecipare a una convivenza organizzata da parrocchie può far capire le convinzioni religiose della famiglia: per questo, nell’iscrizione, ti chiediamo il consenso con una casella a parte.</li>
        <li class="pl-1">Il tuo indirizzo email serve anche a ricordarti di confermare un’iscrizione in forse: lo facciamo perché hai fatto l’iscrizione. L’avviso per una nuova convivenza ti arriva solo se lo chiedi: ci vuole il tuo consenso, e lo togli quando vuoi. In ogni mail c’è il modo per smettere.</li>
        <li class="pl-1">I dati dei maestri servono a organizzare la convivenza insieme.</li>
      </ul>
    </section>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="obbligo">
      <h2 id="obbligo" class="text-lg font-semibold">Cosa è obbligatorio e cosa no</h2>
      <p class="mt-3">I dati dell’iscrizione sono necessari: senza non possiamo iscrivere i bambini. Le segnalazioni sono facoltative: se non le scrivi, l’iscrizione vale lo stesso, ma i maestri non sapranno di allergie, intolleranze o farmaci. Il consenso alle segnalazioni non condiziona l’iscrizione.</p>
    </section>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="visibilita">
      <h2 id="visibilita" class="text-lg font-semibold">Chi vede i dati</h2>
      <p class="mt-3">I maestri della convivenza vedono le iscrizioni. Le presenze e i pagamenti li vedono solo i maestri, con il nome di chi li ha segnati. Le segnalazioni le vedono solo i maestri, solo quando le chiedono, e ogni lettura resta registrata: chi e quando, non cosa c’era scritto. Un altro genitore non vede niente dei tuoi figli. La struttura che ospita la convivenza riceve su carta un elenco con il nome e il cognome dei bambini e dei maestri affidatari, e nient’altro.</p>
    </section>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="fornitori">
      <h2 id="fornitori" class="text-lg font-semibold">Chi li riceve per far funzionare Talità</h2>
      <p class="mt-3">Chi ospita il sito, nell’Unione europea; il servizio che manda le email, che riceve l’indirizzo e il testo del messaggio (anche dei promemoria, mai con dati dei bambini) e che per proteggersi da spam e frodi usa l’indirizzo come titolare autonomo, secondo la sua informativa; chi tiene le copie di sicurezza, cifrate prima di lasciare il server. Nessun altro: niente pubblicità, niente statistiche, niente servizi esterni nelle pagine.</p>
    </section>

    <section id="per-quanto-tempo" class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="tempi">
      <h2 id="tempi" class="text-lg font-semibold">Per quanto tempo</h2>
      <p class="mt-3">${page.getTime()}</p>
      <dl class="mt-3 space-y-3">
        @for(talita.retention.TimeRow row : page.getTimes())
          <div class="sm:grid sm:grid-cols-[13rem_1fr] sm:gap-x-6">
            <dt class="font-semibold wrap-anywhere">${row.getWhat()}</dt>
            <dd class="wrap-anywhere">${row.getTime()}</dd>
          </div>
        @endfor
      </dl>
    </section>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="diritti">
      <h2 id="diritti" class="text-lg font-semibold">I tuoi diritti</h2>
      <div class="mt-3 space-y-3">
        @for(String paragraph : page.getRights())
          <p>${paragraph}</p>
        @endfor
      </div>
    </section>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="reclamo">
      <h2 id="reclamo" class="text-lg font-semibold">Reclamo</h2>
      <p class="mt-3">Se pensi che i dati non siano trattati bene, puoi rivolgerti al Garante per la protezione dei dati personali.</p>
    </section>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="affidamento">
      <h2 id="affidamento" class="text-lg font-semibold">L’accettazione dell’affidamento</h2>
      <p class="mt-3">Quando accetti l’affidamento registriamo il testo esatto che hai letto, la data e l’ora e il tuo indirizzo email. La dichiarazione sull’altro genitore non sostituisce il loro accordo.</p>
    </section>
  </div>

  <div class="mt-6">
    @template.components.linkButton(href = "/", label = "Torna a Talità", variant = "secondary", width = "phone")
  </div>
`)
