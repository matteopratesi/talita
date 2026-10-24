@import org.springframework.security.web.csrf.CsrfToken
@import talita.registration.FirstStepPage
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param FirstStepPage page
@param CsrfToken csrf
<%-- The first step of the registration (criteria 1-5, 18; design A 1/2): the phone, a card for each child, the card of the fee when there are two or more.
     «Controlla e accetta» sends the form (boosted: the answer is the second step, which adds no entry to the history); the other buttons are the ones of
     the blocks and ask for a piece. Without JavaScript the fee is not seen here: it is at step 2. --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Iscrizione", lead = page.getTitle() + " · " + page.getDates())
  @template.registration.steps(current = 1, label = "I dati")

  @if(page.getAlert() != null)
    <div class="mt-4">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  <p class="mt-4 max-w-prose">Scrivi i dati dei bambini che vuoi iscrivere. Al passo 2 ricontrolli tutto e accetti l’affidamento: finché non accetti, non salviamo niente.</p>

  <form id="iscrizione" method="post" action="${page.getFormAction()}" novalidate hx-disabled-elt="#iscrizione-avanti" class="mt-5 space-y-3">
    <%-- The default button of the form: Enter in a field clicks the first submit button in the order of the page, and that would be one of the blocks («Aggiungi», «Togli»).
         This one comes first and does what «Controlla e accetta» does. It is out of the tab order and of the accessibility tree, and not display:none, which some browsers skip. --%>
    <button type="submit" tabindex="-1" aria-hidden="true" class="sr-only"></button>
    @template.components.csrf(csrf = csrf)
    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="telefono-titolo">
      <h2 id="telefono-titolo" class="text-lg font-semibold">Il tuo telefono</h2>
      <div class="mt-3 space-y-1.5">
        <label for="telefono" class="block font-semibold">Telefono per i giorni della convivenza</label>
        <p id="telefono-aiuto" class="text-sm text-ink-muted">Un numero a cui i maestri possono chiamarti mentre i bambini sono con noi. Lo vedono solo i maestri. Per esempio 333 123 4567.@if(page.getPhoneKnown()) Vale per tutte le tue iscrizioni a questa convivenza.@endif</p>
        <input id="telefono" name="phone" type="tel" autocomplete="tel" autocorrect="off" spellcheck="false" value="${page.getPhone()}" aria-describedby="${page.getPhoneError() != null ? "telefono-aiuto telefono-errore" : "telefono-aiuto"}" aria-invalid="${page.getPhoneError() != null ? "true" : null}" class="block min-h-12 w-full rounded-xl border border-line-strong bg-surface px-4 text-base text-ink focus:border-brand aria-[invalid=true]:border-danger aria-[invalid=true]:bg-danger-soft">
        @if(page.getPhoneError() != null)
          <p id="telefono-errore" class="flex items-start gap-1.5 text-sm font-medium text-danger">
            @template.components.icon(icon = Icon.CIRCLE_ALERT, classes = "mt-0.5 size-4 shrink-0")
            ${page.getPhoneError()}
          </p>
        @endif
      </div>
    </section>

    @template.registration.children(page = page)

    <div id="quota-totale" class="empty:hidden">@if(page.getTotal() != null)@template.registration.quotaTotal(total = page.getTotal())@endif</div>

    <div class="flex flex-col gap-3 pt-3 sm:flex-row-reverse sm:justify-start">
      @template.components.button(label = "Controlla e accetta", busyLabel = "Controllo…", id = "iscrizione-avanti")
      @template.components.linkButton(href = page.getCancelHref(), label = "Annulla", variant = "secondary", width = "phone")
    </div>
    <noscript><p class="text-sm text-ink-muted">La quota la vedi al passo 2.</p></noscript>
  </form>
`)
