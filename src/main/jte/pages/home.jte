@import talita.ui.AlertVariant
@import talita.ui.HomeEntry
@import talita.ui.HomePage
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param HomePage page

@template.layout.shell(shell = shell, content = @`
  @if(page.getParent())
    @template.components.pageHead(title = "Ti diamo il benvenuto", lead = "Qui iscrivi i tuoi figli alla convivenza e controlli le loro iscrizioni.")
  @else
    @template.components.pageHead(title = "Ti diamo il benvenuto", lead = "Talità raccoglie le iscrizioni alla convivenza dei bambini e aiuta i maestri a organizzarla.")
  @endif

  @if(page.getAlert() != null)
    <div class="mb-4">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  @if(page.getInvitation() != null)
    <div class="mb-4">
      @template.components.alert(variant = AlertVariant.INFO, live = true, content = @`Ti hanno invitato come maestro. L’invito vale fino al ${page.getInvitation().getValidUntil()}.`, action = @`@template.components.linkButton(href = "/invito", label = "Accetta l’invito", variant = "text")`)
    </div>
  @endif

  @if(page.getParent())
    @template.components.alert(content = @`<strong>Talità è in costruzione.</strong> Le iscrizioni arriveranno presto.`)
  @else
    @template.components.alert(content = @`<strong>Talità è in costruzione.</strong> Le sezioni arriveranno una alla volta.`)
  @endif

  <section class="mt-8" aria-labelledby="sezioni">
    <h2 id="sezioni" class="mb-3 text-xl font-semibold tracking-tight">${page.getParent() ? "Cosa trovi qui" : "Sezioni"}</h2>
    <ul class="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-surface">
      @for(HomeEntry entry : page.getEntries())
        <li>
          <a href="${entry.getSection().getPath()}" class="flex min-h-16 items-center gap-3 px-4 py-3 transition-colors duration-150 hover:bg-surface-muted active:bg-surface-muted busy:bg-surface-muted">
            <span class="grid size-10 shrink-0 place-items-center rounded-xl bg-brand-soft text-brand">
              @template.components.icon(icon = entry.getSection().getIcon(), classes = "size-6")
            </span>
            <span class="min-w-0 flex-1">
              <span class="block font-semibold wrap-break-word">${entry.getSection().getLabel()}</span>
              <span class="block text-sm text-ink-muted">${entry.getSummary()}</span>
            </span>
            @template.components.icon(icon = Icon.CHEVRON_RIGHT, classes = "size-5 shrink-0 text-ink-muted")
          </a>
        </li>
      @endfor
    </ul>
  </section>
`)
