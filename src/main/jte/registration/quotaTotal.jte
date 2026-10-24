@import talita.registration.TotalLine
@import talita.registration.TotalView
@param TotalView total
<%-- The card «Quota» of two or more children (design A 1/2, 4): a line for each child that has a quota, then the total. The definitive quota is seen at step 2. --%>
<section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="quota-titolo">
  <h2 id="quota-titolo" class="text-lg font-semibold">Quota</h2>
  <dl class="mt-3 divide-y divide-line">
    @for(TotalLine line : total.getLines())
      <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5 first:pt-0">
        <dt class="min-w-0 text-ink-muted wrap-anywhere">${line.getName()}</dt>
        <dd class="font-semibold">${line.getAmount()}</dd>
      </div>
    @endfor
    <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 pt-2.5">
      <dt class="font-semibold">Totale</dt>
      <dd class="font-bold">${total.getTotal()}</dd>
    </div>
  </dl>
  <p class="mt-3 text-sm text-ink-muted">La quota definitiva la vedi al passo 2.</p>
</section>
