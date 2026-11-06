@import org.springframework.security.web.csrf.CsrfToken
@import talita.room.BlockKind
@import talita.room.RoomView
@import talita.room.RoomsBlock
@import talita.ui.Icon
@param RoomsBlock block
@param CsrfToken csrf
<%-- (TALITA-20: the rooms are the venue's. With no venue, or with a venue that has no rooms, the block says where to go; the rooms are written, changed and taken away in the page of the venue, which the list
     leads to at its end.) The part of «Camere» that every action replaces as a whole (htmx: hx-target="#camere-contenuto", hx-swap="outerHTML"; design TALITA-11, part 1, 5): the notice that is there
     while a situation lasts (a draft, the end, the deletion), the message of the action when it is about the top, the summary, the rooms with who sleeps in them and who has none.
     It is always made from the database of the moment, so with two teachers at work each answer is true. The title, the introduction, the navigation and the scroll stay. --%>
<div id="camere-contenuto">
  @if(block.getStanding() != null)
    <div class="mb-3">
      @template.components.alert(variant = block.getStanding().getVariant(), lead = block.getStanding().getLead(), content = @`${block.getStanding().getText()}`)
    </div>
  @endif
  @if(block.getAlert() != null)
    <div class="mb-3">
      @template.components.alert(variant = block.getAlert().getVariant(), live = true, content = @`${block.getAlert().getText()}`)
    </div>
  @endif

  @if(block.getKind() == BlockKind.NO_VENUE)
    @template.components.emptyState(icon = Icon.BUILDING_2, title = "Scegli la struttura", text = block.getEmptyText(), href = block.getChooseHref(), label = "Scegli la struttura", buttonIcon = Icon.BUILDING_2)
  @elseif(block.getKind() == BlockKind.EMPTY)
    @if(block.getCanAct())
      @template.components.emptyState(icon = Icon.BED_DOUBLE, title = "Nessuna camera", text = block.getEmptyText(), href = block.getVenueHref(), label = "Scrivi le camere", buttonIcon = Icon.PENCIL)
    @else
      @template.components.emptyState(icon = Icon.BED_DOUBLE, title = "Nessuna camera", text = block.getEmptyText())
    @endif
  @else
    <div class="space-y-3">
      @template.room.summaryCard(summary = block.getSummary())
    </div>

    <section class="mt-8" aria-labelledby="camere-t">
      <h2 id="camere-t" class="mb-3 text-xl font-semibold tracking-tight">Le camere</h2>
      <div class="space-y-3">
        @for(RoomView room : block.getRooms())
          @template.room.roomCard(room = room, gathering = block.getGatheringId(), csrf = csrf)
        @endfor
      </div>
      @if(block.getCanAct())
        <div class="mt-3">
          <p class="mb-3 text-sm text-ink-muted wrap-anywhere">Per aggiungere, cambiare o togliere una camera apri ${block.getVenueName()}.</p>
          @template.components.linkButton(href = block.getVenueHref(), label = "Apri la struttura", variant = "secondary", icon = Icon.BUILDING_2, width = "phone")
        </div>
      @endif
    </section>

    @if(block.getFree() != null)
      @template.room.freeSection(free = block.getFree(), gathering = block.getGatheringId(), csrf = csrf)
    @endif
  @endif
</div>
