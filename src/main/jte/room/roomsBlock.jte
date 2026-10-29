@import org.springframework.security.web.csrf.CsrfToken
@import talita.room.BlockKind
@import talita.room.RoomView
@import talita.room.RoomsBlock
@import talita.ui.Icon
@param RoomsBlock block
@param CsrfToken csrf
<%-- The part of «Camere» that every action replaces as a whole (htmx: hx-target="#camere-contenuto", hx-swap="outerHTML"; design TALITA-11, part 1, 5): the notice that is there
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

  @if(block.getKind() == BlockKind.EMPTY)
    <div class="flex flex-col items-center rounded-2xl border border-line bg-surface px-6 py-12 text-center">
      <span class="grid size-16 place-items-center rounded-full bg-brand-soft text-brand">
        @template.components.icon(icon = Icon.BED_DOUBLE, classes = "size-8")
      </span>
      <h2 class="mt-4 text-xl font-semibold tracking-tight">Nessuna camera</h2>
      <p class="mt-2 max-w-sm text-ink-muted">Scrivi le camere della casa, poi metti ogni bambino e ogni maestro nella sua. Le camere le vedono solo i maestri.</p>
      @if(block.getCanAct())
        @if(block.getCopy() != null)
          <div class="mt-6 w-full max-w-sm rounded-xl bg-canvas p-4 text-left">
            <p>${block.getCopy().getText()}</p>
            <form method="post" action="${"/convivenza/" + block.getGatheringId() + "/camere/copia"}" hx-post="${"/convivenza/" + block.getGatheringId() + "/camere/copia"}" hx-target="#camere-contenuto" hx-swap="outerHTML" hx-disabled-elt="find button" class="mt-3">
              @template.components.csrf(csrf = csrf)
              @template.components.button(label = block.getCopy().getLabel(), busyLabel = "Copio…", icon = Icon.COPY, ariaLabel = block.getCopy().getLabel() + " di " + block.getCopy().getTitle())
            </form>
          </div>
          <div class="mt-3 w-full sm:w-auto">
            @template.components.linkButton(href = block.getNewHref(), label = "Aggiungi una camera", variant = "secondary", icon = Icon.PLUS, width = "phone")
          </div>
        @else
          <div class="mt-6 w-full sm:w-auto">
            @template.components.linkButton(href = block.getNewHref(), label = "Aggiungi una camera", icon = Icon.PLUS, width = "phone")
          </div>
        @endif
      @endif
    </div>
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
          @template.components.linkButton(href = block.getNewHref(), label = "Aggiungi una camera", variant = "secondary", icon = Icon.PLUS, width = "phone")
        </div>
      @endif
    </section>

    @if(block.getFree() != null)
      @template.room.freeSection(free = block.getFree(), gathering = block.getGatheringId(), csrf = csrf)
    @endif
  @endif

  @for(RoomView room : block.getRooms())
    @if(room.getRemoveDialog() != null)
      @template.components.confirmDialog(dialog = room.getRemoveDialog(), csrf = csrf)
    @endif
  @endfor
</div>
