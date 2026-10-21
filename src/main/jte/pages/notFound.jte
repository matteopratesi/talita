@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell

@template.layout.shell(shell = shell, content = @`
  <div class="flex flex-col items-center py-10 text-center lg:py-16">
    <span class="grid size-16 place-items-center rounded-full bg-brand-soft text-brand">
      @template.components.icon(icon = Icon.COMPASS, classes = "size-8")
    </span>
    <h1 tabindex="-1" class="mt-5 text-2xl font-semibold tracking-tight lg:text-3xl">Pagina non trovata</h1>
    <p class="mt-2 max-w-sm text-ink-muted">L’indirizzo potrebbe essere sbagliato, oppure la pagina non esiste più.</p>
    <a href="/" class="mt-6 inline-flex min-h-12 items-center justify-center gap-2 rounded-xl bg-brand px-5 text-base font-semibold text-white transition-colors duration-150 select-none hover:bg-brand-strong active:bg-brand-strong disabled:cursor-wait disabled:opacity-70">Vai alla home</a>
  </div>
`)
