@import talita.registration.ClosedPage
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param ClosedPage page
<%-- The registrations were closed, or the gathering ended, while a form was open (criterion 9; design A 2/2, 6): nothing was saved, and the page says to ask the
     teachers. The answer is 409. --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.statePage(
    icon = Icon.CALENDAR_X,
    title = page.getOver() ? "La convivenza è finita" : "Le iscrizioni sono chiuse",
    body = @`<p class="mt-2 max-w-sm text-ink-muted">${page.getOver() ? "La convivenza è finita: non abbiamo salvato niente. Per la prossima arriverà un link nuovo." : "Mentre compilavi, le iscrizioni si sono chiuse: non abbiamo salvato niente. Chiedi ai maestri se puoi ancora iscrivere i tuoi figli."}</p>`,
    actions = @`<div class="mt-6 w-full sm:w-auto">@template.components.linkButton(href = page.getInfoHref(), label = "Torna alle informazioni", width = "phone")</div>`)
`)
