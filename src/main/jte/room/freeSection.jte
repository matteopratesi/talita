@import org.springframework.security.web.csrf.CsrfToken
@import talita.room.FreeRow
@import talita.room.FreeView
@import talita.ui.Icon
@param FreeView free
@param long gathering
@param CsrfToken csrf
<%-- Who has no room (design TALITA-11, 1.6): the children that come for at least a whole day (who comes for lunch only is not here) and the teachers in custody, each with the
     choice of the room. The number in the title is the people in the list. When every child has a room the section says so; when there are no children to place (a draft, or no
     registration yet) it says that they come when the parents register. --%>
<section id="senza-camera" aria-labelledby="senza-t" class="mt-8">
  <h2 id="senza-t" class="mb-1 flex items-baseline justify-between gap-3 text-xl font-semibold tracking-tight"><span>Senza camera</span>@if(free.getTotal() > 0)<small class="text-sm font-normal text-ink-muted tabular-nums">${free.getTotal()}</small>@endif</h2>
  @if(free.getCanAct() && (!free.getChildren().isEmpty() || !free.getCustodians().isEmpty()))
    <p class="mb-3 text-ink-muted">Scegli la camera di ognuno. Chi viene solo a pranzo non è nell’elenco. L’età è quella del primo giorno.</p>
  @endif
  @if(free.getNoChildren())
    <p class="mb-3 text-ink-muted">I bambini compaiono qui quando i genitori li iscrivono.</p>
  @elseif(free.getAllDone())
    <p class="mb-3 flex items-center gap-2 font-semibold text-success">
      @template.components.icon(icon = Icon.CIRCLE_CHECK, classes = "size-5 shrink-0")
      Tutti i bambini hanno una camera.
    </p>
  @endif
  @if(free.getChildrenTitle() != null)
    <h3 class="mt-3 mb-2 font-semibold">${free.getChildrenTitle()}</h3>
    <ul class="overflow-hidden rounded-2xl border border-line bg-surface [&>li~li]:border-t [&>li~li]:border-line">
      @for(FreeRow row : free.getChildren())
        @template.room.freeRow(row = row, gathering = gathering, canAct = free.getCanAct(), csrf = csrf)
      @endfor
    </ul>
  @endif
  @if(free.getCustodiansTitle() != null)
    <h3 class="mt-4 mb-2 font-semibold">${free.getCustodiansTitle()}</h3>
    <ul class="overflow-hidden rounded-2xl border border-line bg-surface [&>li~li]:border-t [&>li~li]:border-line">
      @for(FreeRow row : free.getCustodians())
        @template.room.freeRow(row = row, gathering = gathering, canAct = free.getCanAct(), csrf = csrf)
      @endfor
    </ul>
  @endif
</section>
