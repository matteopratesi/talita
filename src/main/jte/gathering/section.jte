@import talita.gathering.GatheringRow
@import talita.gathering.SectionPage
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param SectionPage page
<%-- The section «Convivenza» (criteria 1, 15, 16, 41): nothing yet, or the current gathering (a draft or open) above the closed ones, and always the way to «Maestri». --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Convivenza")

  @if(page.getEmpty())
    <div class="flex flex-col items-center rounded-2xl border border-line bg-surface px-6 py-12 text-center">
      <span class="grid size-16 place-items-center rounded-full bg-brand-soft text-brand">
        @template.components.icon(icon = Icon.CALENDAR_DAYS, classes = "size-8")
      </span>
      <h2 class="mt-4 text-xl font-semibold tracking-tight">Nessuna convivenza in programma</h2>
      <p class="mt-2 max-w-sm text-ink-muted">Quando la crei, la trovi qui con le date, i maestri e il link per i genitori.</p>
      <div class="mt-6 w-full sm:w-auto">
        @template.components.linkButton(href = "/convivenza/nuova", label = "Crea la convivenza", icon = Icon.PLUS, width = "phone")
      </div>
    </div>
  @elseif(page.getCurrent() != null)
    <section aria-labelledby="corrente">
      <h2 id="corrente" class="mb-3 text-xl font-semibold tracking-tight">Convivenza corrente</h2>
      <ul class="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-surface">
        <li>@template.components.listLink(href = page.getCurrent().getHref(), icon = Icon.CALENDAR_DAYS, title = page.getCurrent().getTitle(), meta = page.getCurrent().getDates(), badges = page.getCurrent().getBadges())</li>
      </ul>
    </section>
  @else
    <div class="mb-6">
      @template.components.linkButton(href = "/convivenza/nuova", label = "Crea la convivenza", icon = Icon.PLUS, width = "phone")
    </div>
  @endif

  @if(!page.getClosed().isEmpty())
    <section class="${page.getCurrent() != null ? "mt-8" : ""}" aria-labelledby="chiuse">
      <h2 id="chiuse" class="mb-3 text-xl font-semibold tracking-tight">Convivenze chiuse</h2>
      <ul class="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-surface">
        @for(GatheringRow row : page.getClosed())
          <li>@template.components.listLink(href = row.getHref(), icon = Icon.CALENDAR_DAYS, title = row.getTitle(), meta = row.getDates(), badges = row.getBadges())</li>
        @endfor
      </ul>
    </section>
  @endif

  <section class="mt-8" aria-labelledby="maestri">
    <h2 id="maestri" class="mb-3 text-xl font-semibold tracking-tight">Maestri</h2>
    <ul class="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-surface">
      <li>@template.components.listLink(href = "/maestri", icon = Icon.USER_ROUND, title = "Maestri", meta = "Chi organizza la convivenza e chi puoi invitare.")</li>
    </ul>
  </section>
`)
