@import talita.room.OverfullLine
@import talita.room.RoomStat
@import talita.room.SummaryView
@import talita.ui.Icon
@param SummaryView summary
<%-- The summary of the rooms (design TALITA-11, 1.4): the four numbers (the zero in words), «Stampa» at the top right, and, when some room has more people than beds, a notice
     with a line for each of them (the name is a link to its card). «Vai a chi è senza camera» is only there when somebody is. The block is replaced as a whole at every action, so
     the numbers are always those of the moment. --%>
<section id="riepilogo" aria-labelledby="riepilogo-t" class="rounded-2xl border border-line bg-surface p-4 lg:p-6">
  <div class="flex flex-wrap items-center justify-between gap-x-3">
    <h2 id="riepilogo-t" class="text-lg font-semibold">Riepilogo</h2>
    @if(summary.getPrintHref() != null)
      @template.components.linkButton(href = summary.getPrintHref(), label = "Stampa", variant = "text", icon = Icon.PRINTER, ariaLabel = "Stampa le camere")
    @endif
  </div>
  <dl class="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
    @for(RoomStat stat : summary.getStats())
      <div class="rounded-xl bg-canvas p-3">
        <dt class="text-sm text-ink-muted">${stat.getLabel()}</dt>
        <dd class="${stat.getWord() ? "text-lg" : "text-2xl tabular-nums"} font-semibold">${stat.getValue()}@if(stat.getUnit() != null)<small class="block text-sm font-normal text-ink-muted">${stat.getUnit()}</small>@endif</dd>
      </div>
    @endfor
  </dl>
  @if(!summary.getOverfull().isEmpty())
    <div class="mt-3 flex items-start gap-3 rounded-xl bg-warning-soft p-4">
      @template.components.icon(icon = Icon.TRIANGLE_ALERT, classes = "size-6 shrink-0 text-warning")
      <div class="min-w-0">
        <p>Più persone dei posti. Puoi tenerle così, ma controlla.</p>
        <ul class="mt-1 space-y-0.5">
          @for(OverfullLine line : summary.getOverfull())
            <li class="wrap-anywhere"><a href="${line.getAnchor()}" class="font-semibold text-brand underline underline-offset-2">${line.getName()}</a>: ${line.getText()}</li>
          @endfor
        </ul>
      </div>
    </div>
  @endif
  @if(summary.getFreeLink())
    <div class="-mb-2 -ml-3 mt-2">
      @template.components.linkButton(href = "#senza-camera", label = "Vai a chi è senza camera", variant = "text")
    </div>
  @endif
</section>
