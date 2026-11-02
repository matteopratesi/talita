@import talita.ui.Icon
@import talita.ui.Shell
@import talita.venue.VenueListPage
@import talita.venue.VenueRow
@import talita.venue.VenueTexts
@param Shell shell
@param VenueListPage page
<%-- «Strutture» (`/strutture`, only for the teachers; TALITA-20, criteria 1 and 6): the houses where the gathering is held, each with its name, its place and what it has, a row that is a link as a whole.
     No search, no map and no photo: two or three venues in all. With none, the empty state with «Nuova struttura». The notice after «Togli la struttura» is on top. --%>

@template.layout.shell(shell = shell, content = @`
  <div class="-mt-2 mb-2 -ml-3">
    @template.components.linkButton(href = "/convivenza", label = "Torna alla convivenza", variant = "text", icon = Icon.CHEVRON_LEFT)
  </div>
  @template.components.pageHead(title = "Strutture", lead = VenueTexts.LIST_LEAD)

  @if(page.getAlert() != null)
    <div class="mb-4">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  @if(page.getRows().isEmpty())
    @template.components.emptyState(icon = Icon.BUILDING_2, title = VenueTexts.EMPTY_TITLE, text = VenueTexts.EMPTY_TEXT, href = "/strutture/nuova", label = "Nuova struttura", buttonIcon = Icon.PLUS)
  @else
    <div class="mb-6">
      @template.components.linkButton(href = "/strutture/nuova", label = "Nuova struttura", icon = Icon.PLUS, width = "phone")
    </div>
    <section aria-labelledby="elenco-strutture">
      <h2 id="elenco-strutture" class="sr-only">Le strutture</h2>
      <ul class="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-surface">
        @for(VenueRow row : page.getRows())
          <li>@template.components.listLink(href = row.getHref(), icon = Icon.BUILDING_2, title = row.getName(), meta = row.getPlace(), more = row.getDetails())</li>
        @endfor
      </ul>
    </section>
  @endif
`)
