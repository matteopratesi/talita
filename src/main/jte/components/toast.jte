@import talita.ui.Icon
@param String id
@param Icon icon
@param String title
@param String text
@param boolean retry = true
<%-- Model of a pop-up message: app.js clones it into #messaggi (testi nei template, non nel JS).
     `retry` false: the message never offers «Riprova» (to repeat a refused request changes nothing). --%>
<template id="${id}">
  <div role="alert" data-messaggio="${id}" class="pointer-events-auto grid w-full max-w-md grid-cols-[auto_1fr_auto] items-start gap-x-3 rounded-2xl bg-ink py-3.5 pr-1.5 pl-4 text-white shadow-lg motion-safe:animate-toast-in">
    @template.components.icon(icon = icon, classes = "mt-0.5 size-6")
    <div>
      <p class="font-semibold">${title}</p>
      <p class="text-sm text-white/80">${text}</p>
      @if(retry)
        <button type="button" data-azione="riprova" class="mt-2 inline-flex min-h-11 items-center gap-1.5 rounded-lg bg-white/15 px-3.5 font-semibold hover:bg-white/25 focus-visible:outline-white">
          @template.components.icon(icon = Icon.ROTATE_CW, classes = "size-4.5")
          Riprova
        </button>
      @endif
    </div>
    <button type="button" data-azione="chiudi" aria-label="Chiudi" class="-mt-2 grid size-11 place-items-center rounded-full text-white/80 hover:bg-white/15 focus-visible:outline-white">
      @template.components.icon(icon = Icon.X, classes = "size-5")
    </button>
  </div>
</template>
