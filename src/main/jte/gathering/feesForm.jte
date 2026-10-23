@import org.springframework.security.web.csrf.CsrfToken
@import talita.gathering.FeesForm
@import talita.ui.Shell
@param Shell shell
@param FeesForm page
@param CsrfToken csrf
<%-- «Modifica le quote» (criterion 7): three amounts in euros, all optional but the full one that opens the registrations. Empty is «Non prevista», 0 is «Gratuita». --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Modifica le quote", lead = "Gli importi compaiono nella pagina per i genitori.")

  @if(page.getNotice() != null)
    <div class="mb-3">
      @template.components.alert(content = @`${page.getNotice()}`)
    </div>
  @endif
  @if(page.getAlert() != null)
    <div class="mb-3">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  <form method="post" action="${"/convivenza/" + page.getId() + "/quote"}" novalidate hx-disabled-elt="find button[type=submit]" class="rounded-2xl border border-line bg-surface p-4 lg:p-6">
    @template.components.csrf(csrf = csrf)
    <div class="space-y-5">
      @template.components.amountField(id = "quota-intera", name = "fullFee", label = "Quota intera", value = page.getFull(), hint = "Per chi viene tutti i giorni. Serve per aprire le iscrizioni: scrivi 0 se è gratuita.", error = page.getFullError())
      @template.components.amountField(id = "quota-giornaliera", name = "dailyFee", label = "Quota giornaliera", value = page.getDaily(), hint = "Per chi viene solo alcuni giorni: si paga per ogni giorno. Lascia vuoto se non è prevista; scrivi 0 se è gratuita.", error = page.getDailyError())
      @template.components.amountField(id = "quota-pranzo", name = "lunchOnlyFee", label = "Quota solo pranzo", value = page.getLunchOnly(), hint = "Per chi viene solo a pranzo: si paga per ogni pranzo. Lascia vuoto se non è prevista; scrivi 0 se è gratuita.", error = page.getLunchOnlyError())
    </div>
    <div class="mt-6 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
      @template.components.button(label = "Salva le modifiche", busyLabel = "Salvataggio…")
      @template.components.linkButton(href = "/convivenza/" + page.getId(), label = "Annulla", variant = "secondary", width = "phone")
    </div>
  </form>
`)
