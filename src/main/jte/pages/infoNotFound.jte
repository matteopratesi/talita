@import talita.ui.Icon
@import talita.ui.PublicShell
@import talita.ui.Shell
@param Shell shell = null
@param PublicShell publicShell = null
<%-- «Pagina non trovata» for the addresses of the information page (criterion 23): the same for a draft and for a code that does not exist, with the text of a link that
     arrived cut short. Without an account it is in the public shell and leads to the access page; with one, in the shell of the app and leads to the home. --%>
!{gg.jte.Content body = @`
  @template.components.statePage(
    icon = Icon.COMPASS,
    title = "Pagina non trovata",
    body = @`<p class="mt-2 max-w-sm text-ink-muted">L’indirizzo potrebbe essere sbagliato o incompleto. Se te l’ha mandato un maestro, chiedigli di mandartelo di nuovo.</p>`,
    actions = @`<a href="${shell != null ? shell.getHomeHref() : "/accedi"}" class="mt-6 inline-flex min-h-12 items-center justify-center gap-2 rounded-xl bg-brand px-5 text-base font-semibold text-white transition-colors duration-150 select-none hover:bg-brand-strong active:bg-brand-strong disabled:cursor-wait disabled:opacity-70">${shell != null ? shell.getHomeLabel() : "Vai all’accesso"}</a>`)
`;}

@if(shell != null)
  @template.layout.shell(shell = shell, content = body)
@else
  @template.layout.public(shell = publicShell, content = body)
@endif
