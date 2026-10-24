@param int current
@param String label
<%-- The steps of the registration (design guide 4.15): two bars, the first current or both, and the sentence «Passo 1 di 2 · I dati». The bars are for the eyes only. --%>
<div class="mt-4">
  <div class="flex gap-1.5" aria-hidden="true">
    <span class="h-1.5 flex-1 rounded-full bg-brand"></span>
    <span class="h-1.5 flex-1 rounded-full ${current >= 2 ? "bg-brand" : "bg-line"}"></span>
  </div>
  <p class="mt-2 text-sm font-semibold text-ink-muted">Passo ${current} di 2 · ${label}</p>
</div>
