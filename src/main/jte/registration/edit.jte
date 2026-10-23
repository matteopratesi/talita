@import org.springframework.security.web.csrf.CsrfToken
@import talita.registration.EditPage
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param EditPage page
@param CsrfToken csrf
<%-- The modification of a registration, and «Rimetti l'iscrizione» for a withdrawn one (criteria 12-14; design B, 4): the block of the first step without the notes on
     health (they have their own page), and the phone. The acceptance does not change, and the page says so. --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = page.getTitle(), lead = page.getLead())

  <div class="mb-4">
    @template.components.alert(variant = page.getNotice().getVariant(), content = @`${page.getNotice().getText()}`)
  </div>
  @if(page.getAlert() != null)
    <div class="mb-4">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  <form id="modifica" method="post" action="${page.getFormAction()}" novalidate hx-disabled-elt="#modifica-salva" class="space-y-3">
    @template.components.csrf(csrf = csrf)
    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="dati-iscrizione">
      <h2 id="dati-iscrizione" class="text-lg font-semibold">I dati dell’iscrizione</h2>
      <div class="mt-4 space-y-5">
        <div class="space-y-1.5">
          <label for="telefono" class="block font-semibold">Telefono per i giorni della convivenza</label>
          <p id="telefono-aiuto" class="text-sm text-ink-muted">Il telefono vale per tutte le tue iscrizioni a questa convivenza: se lo cambi qui, cambia anche per gli altri bambini.</p>
          <input id="telefono" name="phone" type="tel" autocomplete="tel" autocorrect="off" spellcheck="false" value="${page.getPhone()}" aria-describedby="${page.getPhoneError() != null ? "telefono-aiuto telefono-errore" : "telefono-aiuto"}" aria-invalid="${page.getPhoneError() != null ? "true" : null}" class="block min-h-12 w-full rounded-xl border border-line-strong bg-surface px-4 text-base text-ink focus:border-brand aria-[invalid=true]:border-danger aria-[invalid=true]:bg-danger-soft">
          @if(page.getPhoneError() != null)
            <p id="telefono-errore" class="flex items-start gap-1.5 text-sm font-medium text-danger">
              @template.components.icon(icon = Icon.CIRCLE_ALERT, classes = "mt-0.5 size-4 shrink-0")
              ${page.getPhoneError()}
            </p>
          @endif
        </div>
        @template.registration.childFields(child = page.getChild(), offer = page.getOffer(), formId = "modifica", quotaAction = page.getQuotaAction(), withHealth = false)
      </div>
    </section>
    <div class="flex flex-col gap-3 pt-3 sm:flex-row-reverse sm:justify-start">
      @template.components.button(label = page.getSubmit(), busyLabel = page.getBusy(), id = "modifica-salva")
      @template.components.linkButton(href = "/iscrizioni", label = "Annulla", variant = "secondary", width = "phone")
    </div>
  </form>
`)
