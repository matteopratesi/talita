@import org.springframework.security.web.csrf.CsrfToken
@import talita.room.PersonView
@import talita.room.RoomView
@import talita.ui.AlertVariant
@import talita.ui.Icon
@param RoomView room
@param long gathering
@param CsrfToken csrf
<%-- A room (design TALITA-11, 3.1): the name with how many people and beds at the right, the notice when the people are more than the beds (it blocks nothing), who sleeps
     there (children first, then teachers), «Aggiungi un maestro», and «Modifica» and «Togli». The id is the number of the room, never a name: it is what the summary links to.
     The message of the last action, when it is about this room, is under the title. A room of a finished gathering has none of the actions. --%>
<section id="camera-${room.getId()}" class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="${"camera-" + room.getId() + "-t"}">
  <div class="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
    <h3 id="${"camera-" + room.getId() + "-t"}" class="text-lg font-semibold wrap-anywhere">${room.getName()}</h3>
    <p class="text-sm text-ink-muted tabular-nums">${room.getCount()}</p>
  </div>
  @if(room.getAlert() != null)
    <div class="mt-3">
      @template.components.alert(variant = room.getAlert().getVariant(), live = true, content = @`${room.getAlert().getText()}`)
    </div>
  @endif
  @if(room.getOverfull() != null)
    <div class="mt-3">
      @template.components.alert(variant = AlertVariant.WARNING, content = @`${room.getOverfull()}`)
    </div>
  @endif
  @if(room.getEmptyLine() != null)
    <p class="mt-2 text-ink-muted">${room.getEmptyLine()}</p>
  @endif
  @if(!room.getPeople().isEmpty())
    <ul class="mt-2 divide-y divide-line border-t border-line">
      @for(PersonView person : room.getPeople())
        @template.room.personInRoom(person = person, gathering = gathering, canAct = room.getCanAct(), csrf = csrf)
      @endfor
    </ul>
  @endif
  @if(room.getCanAct())
    @if(!room.getTeacherOptions().isEmpty() || room.getTeacherError() != null)
      <details class="group border-t border-line" open="${room.getTeacherError() != null}">
        <summary class="-ml-3 inline-flex min-h-11 cursor-pointer list-none items-center gap-2 rounded-xl px-3 font-semibold text-brand transition-colors duration-150 hover:bg-brand-soft active:bg-brand-soft [&::-webkit-details-marker]:hidden">
          <span class="group-open:hidden inline-flex items-center gap-2">
            @template.components.icon(icon = Icon.PLUS, classes = "size-5")
            <span>Aggiungi un maestro<span class="sr-only"> a ${room.getName()}</span></span>
          </span>
          <span class="hidden group-open:inline">Chiudi<span class="sr-only"> la scelta del maestro per ${room.getName()}</span></span>
        </summary>
        <form method="post" action="${"/convivenza/" + gathering + "/camere/maestri"}" hx-post="${"/convivenza/" + gathering + "/camere/maestri"}" hx-target="#camere-contenuto" hx-swap="outerHTML" hx-disabled-elt="find button" novalidate class="mt-1 mb-2 space-y-3 rounded-xl bg-canvas p-4">
          @template.components.csrf(csrf = csrf)
          <input type="hidden" name="camera" value="${room.getId()}">
          <div class="space-y-1.5">
            <label for="${"maestro-" + room.getId()}" class="block font-semibold">Maestro</label>
            @template.room.roomSelect(id = "maestro-" + room.getId(), name = "persona", placeholder = "Scegli il maestro", options = room.getTeacherOptions(), error = room.getTeacherError())
          </div>
          <button type="submit" class="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-brand px-5 text-base font-semibold text-white transition-colors duration-150 select-none hover:bg-brand-strong active:bg-brand-strong disabled:cursor-wait disabled:opacity-70 sm:w-auto">
            @template.components.icon(icon = Icon.LOADER_CIRCLE, classes = "hidden size-5 animate-spin busy:block")
            <span class="busy:hidden">Aggiungi</span><span class="hidden busy:inline">Aggiungo…</span>
          </button>
        </form>
      </details>
    @endif
    <div class="-mb-2 -ml-3 flex flex-wrap gap-1 border-t border-line pt-1">
      @template.components.linkButton(href = "/convivenza/" + gathering + "/camere/" + room.getId() + "/modifica", label = "Modifica", variant = "text", icon = Icon.PENCIL, ariaLabel = "Modifica " + room.getName())
      @if(room.getRemoveDialog() != null)
        @template.components.linkButton(href = "/convivenza/" + gathering + "/camere/" + room.getId() + "/togli", label = "Togli", variant = "text", icon = Icon.TRASH_2, opens = room.getRemoveDialog().getId(), ariaLabel = "Togli " + room.getName())
      @else
        <form method="post" action="${"/convivenza/" + gathering + "/camere/" + room.getId() + "/togli"}" hx-post="${"/convivenza/" + gathering + "/camere/" + room.getId() + "/togli"}" hx-target="#camere-contenuto" hx-swap="outerHTML" hx-disabled-elt="find button">
          @template.components.csrf(csrf = csrf)
          <input type="hidden" name="camera" value="${room.getId()}">
          @template.components.button(label = "Togli", busyLabel = "Tolgo…", variant = "text", width = "auto", icon = Icon.TRASH_2, ariaLabel = "Togli " + room.getName())
        </form>
      @endif
    </div>
  @endif
</section>
