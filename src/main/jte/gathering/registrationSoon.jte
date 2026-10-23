@import talita.gathering.RegistrationSoonPage
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param RegistrationSoonPage page
<%-- Where «Iscrivi i tuoi figli» leads for someone who has entered (criterion 25), until the registration of TALITA-5 takes its place. --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Iscrizione", lead = page.getTitle())

  <div class="flex flex-col items-center rounded-2xl border border-line bg-surface px-6 py-12 text-center">
    <span class="grid size-16 place-items-center rounded-full bg-brand-soft text-brand">
      @template.components.icon(icon = Icon.HOURGLASS, classes = "size-8")
    </span>
    <h2 class="mt-4 text-xl font-semibold tracking-tight">In preparazione</h2>
    <p class="mt-2 max-w-sm text-ink-muted">Qui potrai iscrivere i tuoi figli: ci stiamo lavorando. Intanto puoi rileggere le informazioni sulla convivenza.</p>
    <div class="mt-6 w-full sm:w-auto">
      @template.components.linkButton(href = page.getInfoHref(), label = "Torna alle informazioni", width = "phone")
    </div>
  </div>
`)
