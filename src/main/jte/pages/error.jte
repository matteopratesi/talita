@import talita.ui.ErrorPage
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param ErrorPage page

@template.layout.shell(shell = shell, content = @`
  @template.components.statePage(
    icon = Icon.TRIANGLE_ALERT,
    tone = "warning",
    title = "Qualcosa non ha funzionato",
    body = @`<p class="mt-2 max-w-sm text-ink-muted">Non è colpa tua: c’è stato un problema in Talità. Riprova tra qualche minuto. Se il problema continua, avvisa chi organizza la convivenza.</p>`,
    actions = @`
      <div class="mt-6 flex w-full max-w-xs flex-col gap-3">
        @if(page.getRetryHref() != null)
          <a href="${page.getRetryHref()}" class="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-brand px-5 text-base font-semibold text-white transition-colors duration-150 select-none hover:bg-brand-strong active:bg-brand-strong disabled:cursor-wait disabled:opacity-70">
            @template.components.icon(icon = Icon.ROTATE_CW, classes = "size-5")
            Riprova
          </a>
        @endif
        <a href="${shell.getHomeHref()}" class="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl border border-line-strong bg-surface px-5 text-base font-semibold text-ink transition-colors duration-150 select-none hover:bg-surface-muted active:bg-surface-muted disabled:cursor-wait disabled:opacity-70">${shell.getHomeLabel()}</a>
      </div>
    `)
`)
