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

  @if(page.getMaestriLink())
    <section class="mt-8" aria-labelledby="maestri">
      <h2 id="maestri" class="mb-3 text-xl font-semibold tracking-tight">Maestri</h2>
      <ul class="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-surface">
        <li>
          <a href="/maestri" class="flex min-h-16 items-center gap-3 px-4 py-3 transition-colors duration-150 hover:bg-surface-muted active:bg-surface-muted busy:bg-surface-muted">
            <span class="grid size-10 shrink-0 place-items-center rounded-xl bg-brand-soft text-brand">
              @template.components.icon(icon = Icon.USER_ROUND, classes = "size-6")
            </span>
            <span class="min-w-0 flex-1">
              <span class="block font-semibold wrap-break-word">Maestri</span>
              <span class="block text-sm text-ink-muted">Chi organizza la convivenza e chi puoi invitare.</span>
            </span>
            @template.components.icon(icon = Icon.CHEVRON_RIGHT, classes = "size-5 shrink-0 text-ink-muted")
          </a>
        </li>
      </ul>
    </section>
  @endif
`)
