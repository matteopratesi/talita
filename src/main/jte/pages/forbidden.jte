@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell

@template.layout.shell(shell = shell, content = @`
  @template.components.statePage(
    icon = Icon.LOCK,
    title = "Non puoi vedere questa pagina",
    body = @`<p class="mt-2 max-w-sm text-ink-muted">La pagina esiste, ma il tuo accesso non comprende questi dati. Se pensi di doverla vedere, chiedi a chi organizza la convivenza.</p>`,
    actions = @`<a href="/" class="mt-6 inline-flex min-h-12 items-center justify-center gap-2 rounded-xl bg-brand px-5 text-base font-semibold text-white transition-colors duration-150 select-none hover:bg-brand-strong active:bg-brand-strong disabled:cursor-wait disabled:opacity-70">Vai alla home</a>`)
`)
