@import talita.ui.ErrorPage
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param ErrorPage page

@template.layout.shell(shell = shell, content = @`
  <div class="flex flex-col items-center py-10 text-center lg:py-16">
    <span class="grid size-16 place-items-center rounded-full bg-warning-soft text-warning">
      @template.components.icon(icon = Icon.TRIANGLE_ALERT, classes = "size-8")
    </span>
    <h1 tabindex="-1" class="mt-5 text-2xl font-semibold tracking-tight lg:text-3xl">Qualcosa non ha funzionato</h1>
    <p class="mt-2 max-w-sm text-ink-muted">Non è colpa tua: c’è stato un problema in Koinè. Riprova tra qualche minuto. Se il problema continua, avvisa il Centro.</p>
    <div class="mt-6 flex w-full max-w-xs flex-col gap-3">
      @if(page.getRetryHref() != null)
        <a href="${page.getRetryHref()}" class="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-brand px-5 text-base font-semibold text-white transition-colors duration-150 select-none hover:bg-brand-strong active:bg-brand-strong disabled:cursor-wait disabled:opacity-70">
          @template.components.icon(icon = Icon.ROTATE_CW, classes = "size-5")
          Riprova
        </a>
      @endif
      <a href="/" class="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl border border-line-strong bg-surface px-5 text-base font-semibold text-ink transition-colors duration-150 select-none hover:bg-surface-muted active:bg-surface-muted disabled:cursor-wait disabled:opacity-70">Vai alla home</a>
    </div>
  </div>
`)
