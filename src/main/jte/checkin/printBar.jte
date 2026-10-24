@import talita.ui.Icon
@param String help
@param gg.jte.Content before = null
<%-- The bar over a printed document (design part 3, 1): what is chosen before printing (the venue list has the choice of who is in it), the button that prints and a line of
     help. It is never printed. The button is hidden in the page and `app.js` shows it (it prints with `window.print()`): without JavaScript it is not there and the help
     says enough, as on the preview of the information page. --%>
<div class="mb-4 flex flex-col gap-3 print:hidden">
  @if(before != null)
    ${before}
  @endif
  <div>
    <button type="button" hidden data-azione="stampa" class="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-brand px-5 text-base font-semibold text-white transition-colors duration-150 select-none hover:bg-brand-strong active:bg-brand-strong sm:w-auto">
      @template.components.icon(icon = Icon.PRINTER, classes = "size-5")
      Stampa
    </button>
  </div>
  <p class="text-sm text-ink-muted">${help}</p>
</div>
