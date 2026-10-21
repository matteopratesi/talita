@import talita.ui.Icon
@import talita.ui.PlaceholderPage
@import talita.ui.Shell
@param Shell shell
@param PlaceholderPage page

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = page.getTitle())

  <div class="flex flex-col items-center rounded-2xl border border-line bg-surface px-6 py-12 text-center">
    <span class="grid size-16 place-items-center rounded-full bg-brand-soft text-brand">
      @template.components.icon(icon = Icon.HOURGLASS, classes = "size-8")
    </span>
    <h2 class="mt-4 text-xl font-semibold tracking-tight">In preparazione</h2>
    <p class="mt-2 max-w-sm text-ink-muted">${page.getText()}</p>
  </div>
`)
