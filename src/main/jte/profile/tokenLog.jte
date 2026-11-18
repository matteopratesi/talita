@import talita.api.TokenLog
@import talita.api.TokenLogLine
@import talita.ui.Icon
@param TokenLog log
<%-- «Ultime richieste» (TALITA-21, criterion 12): the last 20 requests made with the token of a teacher, closed to start with, outside `#token-contenuto` so that the actions of the card do not draw it again. A line
     says the moment, the method, the route as a model and the code, with the meaning of the code in a sentence under it: never who made the request or what it held. The code is in the text, never only a color. --%>
<div id="token-registro">
  <details class="group mt-4 rounded-2xl border border-line-strong bg-surface">
    <summary class="flex min-h-14 cursor-pointer list-none items-center gap-3 px-4 py-2 [&::-webkit-details-marker]:hidden">
      @template.components.icon(icon = Icon.LIST, classes = "size-6 shrink-0 text-brand")
      <span class="min-w-0 flex-1"><span class="block font-semibold">Ultime richieste</span><span class="block text-sm text-ink-muted">Cosa è stato fatto con il token</span></span>
      @template.components.icon(icon = Icon.CHEVRON_DOWN, classes = "size-5 shrink-0 text-ink-muted transition-transform duration-150 group-open:rotate-180")
    </summary>
    <div class="space-y-2 border-t border-line px-4 pt-4 pb-4">
      @if(log.getLines().isEmpty())
        <p>Nessuna richiesta negli ultimi 30 giorni. Quando un programma userà il token, le vedi qui.</p>
        <p class="text-sm text-ink-muted">Qui compaiono le richieste fatte con un token di un maestro, senza dire di chi né cosa contenevano.</p>
      @else
        <p>Le ultime 20 richieste fatte con un token di un maestro, dalla più recente. Non dicono chi le ha fatte né cosa contenevano. Se ne vedi una che non riconosci, tocca «Revoca».</p>
        <ul>
          @for(TokenLogLine line : log.getLines())
            <li class="border-t border-line py-2.5 first:border-t-0">
              <span class="block font-medium wrap-anywhere">${line.getTitle()}</span>
              <span class="block text-sm text-ink-muted">${line.getNote()}</span>
            </li>
          @endfor
        </ul>
        <p class="text-sm text-ink-muted">Le richieste si tengono per 30 giorni.</p>
      @endif
    </div>
  </details>
</div>
