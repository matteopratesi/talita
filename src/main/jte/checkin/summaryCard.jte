@import talita.checkin.StatView
@import talita.checkin.SummaryView
@import talita.ui.Icon
@param SummaryView summary
@param boolean oob = false
<%-- The summary of the day (criterion 2; design 3.2): who is here out of who is expected, who is here and was not expected, how many signed forms are missing, how
     many are in doubt, and the money. The zero is in words. `id="riepilogo"` is what the answer to a touch replaces (`oob`); while the person searches for a name
     `app.js` hides it, so that the results come up. --%>
<section id="riepilogo" aria-labelledby="riepilogo-titolo" hx-swap-oob="${oob ? "true" : null}" class="rounded-2xl border border-line bg-surface p-4 lg:p-6">
  <div class="flex flex-wrap items-center justify-between gap-x-3">
    <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
      <h2 id="riepilogo-titolo" class="text-lg font-semibold">${summary.getTitle()}</h2>
      @template.components.badge(label = summary.getTag().getLabel(), tone = summary.getTag().getTone())
    </div>
    @template.components.linkButton(href = "#stampe", label = "Stampe", variant = "text", icon = Icon.PRINTER)
  </div>
  <dl class="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
    @for(StatView stat : summary.getStats())
      <div class="rounded-xl bg-canvas p-3">
        <dt class="text-sm text-ink-muted">${stat.getLabel()}</dt>
        <dd class="${stat.getWord() ? "text-lg" : "text-2xl tabular-nums"} font-semibold">${stat.getValue()}@if(stat.getUnit() != null)<small class="block text-sm font-normal text-ink-muted">${stat.getUnit()}</small>@endif</dd>
      </div>
    @endfor
  </dl>
  <dl class="mt-4 border-t border-line pt-4">
    <div class="flex flex-wrap items-baseline justify-between gap-x-4">
      <dt class="text-ink-muted">${summary.getCollected().getLabel()}</dt>
      <dd class="font-semibold tabular-nums">${summary.getCollected().getValue()}</dd>
      @if(summary.getCollected().getUnder() != null)
        <dd class="basis-full text-sm text-ink-muted wrap-anywhere">${summary.getCollected().getUnder()}</dd>
      @endif
    </div>
    <div class="mt-3 flex flex-wrap items-baseline justify-between gap-x-4 border-t border-line pt-3">
      <dt class="text-ink-muted">${summary.getOwed().getLabel()}</dt>
      <dd class="font-semibold tabular-nums">${summary.getOwed().getValue()}</dd>
      @if(summary.getOwed().getUnder() != null)
        <dd class="basis-full text-sm text-ink-muted">${summary.getOwed().getUnder()}</dd>
      @endif
    </div>
  </dl>
</section>
