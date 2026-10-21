@import koine.ui.HomePage
@import koine.ui.Icon
@import koine.ui.Section
@import koine.ui.Shell
@param Shell shell
@param HomePage page

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Ti diamo il benvenuto", lead = "Koinè raccoglie l’anagrafe delle comunità e l’organizzazione delle convivenze.")

  @template.components.alert(content = @`<strong>Koinè è in costruzione.</strong> Le sezioni arriveranno una alla volta.`)

  <section class="mt-8" aria-labelledby="sezioni">
    <h2 id="sezioni" class="mb-3 text-xl font-semibold tracking-tight">Sezioni</h2>
    <ul class="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-surface">
      @for(Section section : page.getSections())
        <li>
          <a href="${section.getPath()}" class="flex min-h-16 items-center gap-3 px-4 py-3 transition-colors duration-150 hover:bg-surface-muted active:bg-surface-muted busy:bg-surface-muted">
            <span class="grid size-10 shrink-0 place-items-center rounded-xl bg-brand-soft text-brand">
              @template.components.icon(icon = section.getIcon(), classes = "size-6")
            </span>
            <span class="min-w-0 flex-1">
              <span class="block font-semibold wrap-break-word">${section.getLabel()}</span>
              <span class="block text-sm text-ink-muted">${section.getSummary()}</span>
            </span>
            @template.components.icon(icon = Icon.CHEVRON_RIGHT, classes = "size-5 shrink-0 text-ink-muted")
          </a>
        </li>
      @endfor
    </ul>
  </section>
`)
