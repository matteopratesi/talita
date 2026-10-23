@import talita.registration.ChildView
@import talita.registration.FirstStepPage
@import talita.registration.KnownView
@import talita.ui.Icon
@param FirstStepPage page
<%-- The blocks of the first step (`#figli`, design A 1/2, 3 and 6): «I tuoi figli» of the year before that are not here yet, a card for each child, and the button
     that adds one. This is the piece that «Aggiungi un figlio», «Togli» and «Aggiungi» replace, and the whole answer without JavaScript. The buttons are
     buttons of the form (`name="azione"`) that work by themselves, and with htmx ask only for this piece. --%>
<div id="figli" class="space-y-3">
  @if(!page.getKnown().isEmpty())
    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="figli-noti">
      <h2 id="figli-noti" class="text-lg font-semibold">I tuoi figli</h2>
      <p class="mt-1.5 text-sm text-ink-muted">Hai già iscritto questi bambini a una convivenza. Tocca «Aggiungi» per ritrovare i loro dati: potrai correggerli.</p>
      <ul class="mt-3 divide-y divide-line">
        @for(KnownView known : page.getKnown())
          <li class="flex items-center gap-3 py-2 first:pt-0 last:pb-0">
            <span aria-hidden="true" class="grid size-10 shrink-0 place-items-center rounded-full bg-brand-soft text-sm font-semibold text-brand">${known.getInitials()}</span>
            <span class="min-w-0 flex-1">
              <span class="block font-semibold wrap-anywhere">${known.getName()}</span>
              <span class="block text-sm text-ink-muted">Data di nascita ${known.getBirth()}</span>
            </span>
            <button type="submit" name="azione" value="${"aggiungi:" + known.getId()}" formaction="${page.getChildrenAction()}" formnovalidate hx-post="${page.getChildrenAction()}" hx-include="closest form" hx-target="#figli" hx-swap="outerHTML" hx-disabled-elt="this" aria-label="${"Aggiungi " + known.getName()}" class="inline-flex min-h-11 shrink-0 items-center justify-center gap-2 rounded-xl px-3 text-base font-semibold text-brand transition-colors duration-150 select-none hover:bg-brand-soft active:bg-brand-soft disabled:cursor-wait disabled:opacity-70">
              @template.components.icon(icon = Icon.PLUS, classes = "size-5")
              Aggiungi
            </button>
          </li>
        @endfor
      </ul>
    </section>
  @endif
  @for(ChildView child : page.getChildren())
    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="${"b" + child.getIndex() + "-titolo"}">
      <div class="flex items-center justify-between gap-3">
        <h2 id="${"b" + child.getIndex() + "-titolo"}" class="text-lg font-semibold">Bambino ${child.getNumber()}</h2>
        @if(child.getCanRemove())
          <button type="submit" name="azione" value="${"togli:" + child.getIndex()}" formaction="${page.getChildrenAction()}" formnovalidate hx-post="${page.getChildrenAction()}" hx-include="closest form" hx-target="#figli" hx-swap="outerHTML" hx-disabled-elt="this" aria-label="${"Togli il bambino " + child.getNumber()}" class="-my-1 inline-flex min-h-11 items-center justify-center gap-2 rounded-xl px-3 text-base font-semibold text-brand transition-colors duration-150 select-none hover:bg-brand-soft active:bg-brand-soft disabled:cursor-wait disabled:opacity-70">
            @template.components.icon(icon = Icon.X, classes = "size-5")
            Togli
          </button>
        @endif
      </div>
      <div class="mt-4">
        @template.registration.childFields(child = child, offer = page.getOffer(), formId = "iscrizione", quotaAction = page.getQuotaAction())
      </div>
    </section>
  @endfor
  @if(page.getCanAdd())
    <button type="submit" name="azione" value="aggiungi" formaction="${page.getChildrenAction()}" formnovalidate hx-post="${page.getChildrenAction()}" hx-include="closest form" hx-target="#figli" hx-swap="outerHTML" hx-disabled-elt="this" class="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl border border-line-strong bg-surface px-5 text-base font-semibold text-ink transition-colors duration-150 select-none hover:bg-surface-muted active:bg-surface-muted disabled:cursor-wait disabled:opacity-70 sm:w-auto">
      @template.components.icon(icon = Icon.PLUS, classes = "size-5")
      Aggiungi un figlio
    </button>
  @else
    <p class="text-ink-muted">Hai aggiunto 10 bambini, il massimo per volta. Per iscriverne altri, finisci questa iscrizione e poi tocca «Iscrivi un altro figlio».</p>
  @endif
</div>
