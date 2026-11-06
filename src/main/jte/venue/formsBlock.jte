@import org.springframework.security.web.csrf.CsrfToken
@import talita.ui.Icon
@import talita.venue.VenueFormRow
@import talita.venue.VenueFormTexts
@import talita.venue.VenueFormsBlock
@param VenueFormsBlock block
@param CsrfToken csrf
<%-- The forms in the page of a venue (TALITA-20, part 3, criterion 20): the block `#moduli-struttura`, which «Togli» answers with as a whole (htmx: hx-target="#moduli-struttura", hx-swap="outerHTML"), with the notice of the
     action at the top of the card (`role="status"`, the focus goes to it). The card is a list that goes to the edges (`overflow-hidden`), as «Camere»: the sentence on what the forms are, a row for each form (the name, what
     its sheet has, «Modifica» and «Togli», with the name in the accessible name of both) and «Aggiungi un modulo» at the end. Without forms, the empty state of a card. «Togli» always has the dialog (the sheets that were
     printed stay with who has them). --%>
<div id="moduli-struttura">
  <section id="moduli" class="overflow-hidden rounded-2xl border border-line bg-surface" aria-labelledby="moduli-t">
    <h2 id="moduli-t" class="px-4 pt-4 text-lg font-semibold lg:px-6 lg:pt-6">Moduli</h2>
    @if(block.getAlert() != null)
      <div class="px-4 pt-3 lg:px-6">
        @template.components.alert(variant = block.getAlert().getVariant(), live = true, content = @`${block.getAlert().getText()}`)
      </div>
    @endif
    @if(block.getRows().isEmpty())
      <p class="px-4 pt-3 font-semibold lg:px-6">${VenueFormTexts.EMPTY_TITLE}</p>
      <p class="px-4 pt-1 text-sm text-ink-muted lg:px-6">${VenueFormTexts.EMPTY_TEXT}</p>
      <div class="p-4 lg:p-6">
        @template.components.linkButton(href = block.getNewHref(), label = "Aggiungi un modulo", variant = "secondary", icon = Icon.PLUS, width = "phone")
      </div>
    @else
      <p class="px-4 pt-1 pb-3 text-sm text-ink-muted lg:px-6">${VenueFormTexts.LEAD}</p>
      <ul class="divide-y divide-line border-t border-line">
        @for(VenueFormRow row : block.getRows())
          <li class="flex min-h-16 flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3 lg:px-6">
            <span class="grid size-10 shrink-0 place-items-center rounded-xl bg-brand-soft text-brand">
              @template.components.icon(icon = Icon.LIST, classes = "size-6")
            </span>
            <span class="min-w-0 flex-[1_1_11rem]">
              <span class="block font-semibold wrap-anywhere">${row.getName()}</span>
              <span class="block text-sm text-ink-muted">${row.getColumns()}</span>
            </span>
            <div class="-ml-3 flex flex-wrap gap-1 sm:ml-0 sm:shrink-0">
              @template.components.linkButton(href = row.getEditHref(), label = "Modifica", variant = "text", icon = Icon.PENCIL, ariaLabel = "Modifica " + row.getName())
              @template.components.linkButton(href = row.getRemoveHref(), label = "Togli", variant = "text", icon = Icon.TRASH_2, opens = row.getRemoveDialog().getId(), ariaLabel = "Togli " + row.getName())
            </div>
          </li>
        @endfor
      </ul>
      <div class="border-t border-line p-4 lg:p-6">
        @template.components.linkButton(href = block.getNewHref(), label = "Aggiungi un modulo", variant = "secondary", icon = Icon.PLUS, width = "phone")
      </div>
    @endif
  </section>
  @for(VenueFormRow row : block.getRows())
    @template.components.confirmDialog(dialog = row.getRemoveDialog(), csrf = csrf)
  @endfor
</div>
