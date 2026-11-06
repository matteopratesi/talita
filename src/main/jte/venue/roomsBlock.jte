@import org.springframework.security.web.csrf.CsrfToken
@import talita.ui.Icon
@import talita.venue.VenueRoomRow
@import talita.venue.VenueRoomTexts
@import talita.venue.VenueRoomsBlock
@param VenueRoomsBlock block
@param CsrfToken csrf
<%-- The rooms in the page of a venue (TALITA-20, criteria 13 and 16): the block `#camere-struttura`, which «Togli» answers with as a whole (htmx: hx-target="#camere-struttura", hx-swap="outerHTML"), so the rooms are always
     those of the moment, with the notice of the action at the top of the card (`role="status"`, the focus goes to it). The card is a list that goes to the edges (`overflow-hidden`), as «Da stampare» of Arrivi: the sentence on
     what the rooms are for, a row for each room (the name, its beds, «Modifica» and «Togli», with the name in the accessible name of both) and «Aggiungi una camera» at the end. Without rooms, the empty state of a card.
     A room where somebody sleeps has the dialog «Togliere {camera}?» (the page of confirmation without JavaScript); one where nobody does is taken away at once, by the form that is the button. --%>
<div id="camere-struttura">
  <section id="camere" class="overflow-hidden rounded-2xl border border-line bg-surface" aria-labelledby="camere-t">
    <h2 id="camere-t" class="px-4 pt-4 text-lg font-semibold lg:px-6 lg:pt-6">Camere</h2>
    @if(block.getAlert() != null)
      <div class="px-4 pt-3 lg:px-6">
        @template.components.alert(variant = block.getAlert().getVariant(), live = true, content = @`${block.getAlert().getText()}`)
      </div>
    @endif
    @if(block.getRows().isEmpty())
      <p class="px-4 pt-3 font-semibold lg:px-6">${VenueRoomTexts.EMPTY_TITLE}</p>
      <p class="px-4 pt-1 text-sm text-ink-muted lg:px-6">${VenueRoomTexts.EMPTY_TEXT}</p>
      <div class="p-4 lg:p-6">
        @template.components.linkButton(href = block.getNewHref(), label = "Aggiungi una camera", variant = "secondary", icon = Icon.PLUS, width = "phone")
      </div>
    @else
      <p class="px-4 pt-1 pb-3 text-sm text-ink-muted lg:px-6">${block.getSummary()}</p>
      <ul class="divide-y divide-line border-t border-line">
        @for(VenueRoomRow row : block.getRows())
          <li class="flex min-h-16 flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3 lg:px-6">
            <span class="grid size-10 shrink-0 place-items-center rounded-xl bg-brand-soft text-brand">
              @template.components.icon(icon = Icon.BED_DOUBLE, classes = "size-6")
            </span>
            <span class="min-w-0 flex-[1_1_11rem]">
              <span class="block font-semibold wrap-anywhere">${row.getName()}</span>
              <span class="block text-sm text-ink-muted">${row.getBeds()}</span>
            </span>
            <div class="-ml-3 flex flex-wrap gap-1 sm:ml-0 sm:shrink-0">
              @template.components.linkButton(href = "/strutture/" + block.getVenueId() + "/camere/" + row.getId() + "/modifica", label = "Modifica", variant = "text", icon = Icon.PENCIL, ariaLabel = "Modifica " + row.getName())
              @if(row.getRemoveDialog() != null)
                @template.components.linkButton(href = row.getRemoveHref(), label = "Togli", variant = "text", icon = Icon.TRASH_2, opens = row.getRemoveDialog().getId(), ariaLabel = "Togli " + row.getName())
              @else
                <form method="post" action="${row.getRemoveHref()}" hx-post="${row.getRemoveHref()}" hx-target="#camere-struttura" hx-swap="outerHTML" hx-disabled-elt="find button">
                  @template.components.csrf(csrf = csrf)
                  <input type="hidden" name="camera" value="${row.getId()}">
                  @template.components.button(label = "Togli", busyLabel = "Tolgo…", variant = "text", width = "auto", icon = Icon.TRASH_2, ariaLabel = "Togli " + row.getName())
                </form>
              @endif
            </div>
          </li>
        @endfor
      </ul>
      <div class="border-t border-line p-4 lg:p-6">
        @template.components.linkButton(href = block.getNewHref(), label = "Aggiungi una camera", variant = "secondary", icon = Icon.PLUS, width = "phone")
      </div>
    @endif
  </section>
  @for(VenueRoomRow row : block.getRows())
    @if(row.getRemoveDialog() != null)
      @template.components.confirmDialog(dialog = row.getRemoveDialog(), csrf = csrf)
    @endif
  @endfor
</div>
