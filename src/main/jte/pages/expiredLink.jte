@import talita.account.ExpiredLinkPage
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param ExpiredLinkPage page

@template.layout.shell(shell = shell, content = @`
  @template.components.statePage(
    icon = Icon.LINK_2_OFF,
    title = "Il link non vale più",
    body = @`
      <p class="mt-2 max-w-sm text-ink-muted">Può essere già stato usato, oppure sono passati più di ${page.getValidityMinutes()} minuti.</p>
      <p class="mt-3 max-w-sm text-ink-muted">Succede: chiedine uno nuovo, ci vuole un momento. Se hai già usato il link su un altro telefono, sei già dentro lì.</p>
    `,
    actions = @`
      <div class="mt-6 w-full max-w-xs">
        @template.components.linkButton(href = "/accedi", label = "Chiedi un nuovo link", width = "full")
      </div>
    `)
`)
