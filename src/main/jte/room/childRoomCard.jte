@import talita.room.ChildRoomCard
@import talita.ui.Icon
@param ChildRoomCard room
<%-- The card «Camera» of the child in Arrivi (criterion 15; design TALITA-11, part 2, 4), right under «Giorni»: the room is for the night and for an emergency, so it is not at the
     bottom of the page. In a room: its name, a link to the page «Camere», and the sentence that says where it is changed. Without a room: «Senza camera» and the way to choose one.
     After the end, once who slept where was deleted, only the sentence. The card has no button that changes anything: the room is changed from «Camere», in «Convivenza». --%>
<section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="camera-titolo">
  <h2 id="camera-titolo" class="text-lg font-semibold">Camera</h2>
  @if(room.getDeleted() != null)
    <p class="mt-2 text-ink-muted wrap-anywhere">${room.getDeleted()}</p>
  @elseif(room.getName() != null)
    <p class="mt-2"><a href="${room.getHref()}" class="-ml-3 inline-flex min-h-11 items-center rounded-xl px-3 font-semibold text-brand underline underline-offset-2 hover:bg-brand-soft active:bg-brand-soft wrap-anywhere">${room.getName()}</a></p>
    <p class="text-sm text-ink-muted">Si cambia dalla pagina «Camere», nella Convivenza.</p>
  @else
    <p class="mt-2 text-ink-muted">Senza camera</p>
    <div class="-mb-2 -ml-3 mt-1">
      @template.components.linkButton(href = room.getHref(), label = "Vai alle camere", variant = "text", icon = Icon.BED_DOUBLE)
    </div>
  @endif
</section>
