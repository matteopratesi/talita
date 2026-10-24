@import org.springframework.security.web.csrf.CsrfToken
@import talita.checkin.RowKind
@import talita.checkin.RowLabel
@import talita.checkin.RowView
@import talita.ui.Icon
@param RowView row
@param CsrfToken csrf
<%-- A row of the list of the day (criteria 1, 4; design 1, 3.4). The name is a link to the card; for a child who is expected on a day that can be marked the button is
     at the right: «Segna presente» before, «Togli» after (smaller and without fill on purpose). The new button takes the place of the touched one, so the second touch
     of whoever taps twice would land on it and undo the first. So the row that comes in the answer to a touch has `animate-touch-pause`: for 700 ms it takes no touch
     (the button, and the link to the card, which a click of the mouse may reach when the new button is narrower; the focus, the name and the keyboard stay as they are).
     The other rows are only a link, with the arrow. The row does not change place after a touch: the order is made again at the next load. The id is the number of the
     registration, never a name; the name is in `data-filtro-testo`, which the search in `app.js` reads and which goes nowhere. In the answer to a touch the new button
     has `data-fuoco`; in the page that is drawn first no row has it (the focus goes to the title). --%>
@if(row.getKind() == RowKind.LINK)
  <li id="riga-${row.getId()}" data-filtro-riga>
    <a href="${row.getHref()}" class="flex min-h-16 items-center gap-3 px-4 py-3 transition-colors duration-150 hover:bg-surface-muted active:bg-surface-muted busy:bg-surface-muted">
      <span class="min-w-0 flex-1">
        <span class="block font-semibold wrap-anywhere" data-filtro-testo>${row.getName()}</span>
        @if(!row.getLabels().isEmpty())
          <span class="mt-1 flex flex-wrap gap-x-1.5 gap-y-1">
            @for(RowLabel label : row.getLabels())
              @template.components.badge(label = label.getText(), tone = label.getTone(), icon = label.getIcon())
            @endfor
          </span>
        @endif
      </span>
      @template.components.icon(icon = Icon.CHEVRON_RIGHT, classes = "size-5 shrink-0 text-ink-muted")
    </a>
    @if(row.getNote() != null)
      <p class="mx-4 mb-3 flex items-start gap-2 rounded-xl ${row.getNote().getWarning() ? "bg-warning-soft" : "bg-brand-soft"} px-3 py-2 text-sm">
        @template.components.icon(icon = row.getNote().getWarning() ? Icon.TRIANGLE_ALERT : Icon.INFO, classes = "size-5 shrink-0 " + (row.getNote().getWarning() ? "text-warning" : "text-brand"))
        <span class="min-w-0 wrap-anywhere">${row.getNote().getText()}</span>
      </p>
    @endif
  </li>
@else
  <li id="riga-${row.getId()}" data-filtro-riga class="flex flex-wrap items-center gap-x-3 py-2 pr-3 pl-4${row.getPresent() ? " bg-success-soft" : ""}${row.getFocus() ? " animate-touch-pause" : ""}">
    <a href="${row.getHref()}" class="flex min-h-14 min-w-0 flex-1 flex-col justify-center rounded-lg py-1.5">
      <span class="font-semibold wrap-anywhere" data-filtro-testo>${row.getName()}</span>
      @if(!row.getLabels().isEmpty())
        <span class="mt-1 flex flex-wrap gap-x-1.5 gap-y-1">
          @for(RowLabel label : row.getLabels())
            @template.components.badge(label = label.getText(), tone = label.getTone(), icon = label.getIcon())
          @endfor
        </span>
      @endif
    </a>
    @if(row.getKind() == RowKind.MARK)
      <form method="post" action="${"/arrivi/" + row.getId() + "/presenze"}" hx-post="${"/arrivi/" + row.getId() + "/presenze"}" hx-target="closest li" hx-swap="outerHTML" hx-disabled-elt="find button">
        @template.components.csrf(csrf = csrf)
        <input type="hidden" name="giorno" value="${row.getDay()}">
        <button type="submit" data-fuoco="${row.getFocus() ? "" : null}" class="inline-flex min-h-14 w-24 shrink-0 items-center justify-center rounded-xl bg-brand px-2 text-center text-sm leading-tight font-semibold text-white transition-colors duration-150 select-none hover:bg-brand-strong active:bg-brand-strong disabled:cursor-wait disabled:opacity-70 sm:w-auto sm:min-w-36 sm:px-5 sm:text-base sm:whitespace-nowrap">
          @template.components.icon(icon = Icon.LOADER_CIRCLE, classes = "mr-1.5 hidden size-5 animate-spin busy:block")
          <span class="busy:hidden">Segna presente</span><span class="hidden busy:inline">Segno…</span><span class="sr-only">${row.getMarkFor()}</span>
        </button>
      </form>
    @else
      <form method="post" action="${"/arrivi/" + row.getId() + "/presenze/togli"}" hx-post="${"/arrivi/" + row.getId() + "/presenze/togli"}" hx-target="closest li" hx-swap="outerHTML" hx-disabled-elt="find button">
        @template.components.csrf(csrf = csrf)
        <input type="hidden" name="giorno" value="${row.getDay()}">
        <button type="submit" data-fuoco="${row.getFocus() ? "" : null}" class="inline-flex min-h-11 shrink-0 items-center justify-center rounded-xl border border-line-strong bg-surface px-4 text-sm font-semibold text-ink transition-colors duration-150 select-none hover:bg-surface-muted active:bg-surface-muted disabled:cursor-wait disabled:opacity-70 sm:text-base">
          <span class="busy:hidden">Togli</span><span class="hidden busy:inline">Tolgo…</span><span class="sr-only">${row.getUnmarkFor()}</span>
        </button>
      </form>
    @endif
    @if(row.getNote() != null)
      <p class="mb-2 flex basis-full items-start gap-2 rounded-xl ${row.getNote().getWarning() ? "bg-warning-soft" : "bg-brand-soft"} px-3 py-2 text-sm">
        @template.components.icon(icon = row.getNote().getWarning() ? Icon.TRIANGLE_ALERT : Icon.INFO, classes = "size-5 shrink-0 " + (row.getNote().getWarning() ? "text-warning" : "text-brand"))
        <span class="min-w-0 wrap-anywhere">${row.getNote().getText()}</span>
      </p>
    @endif
  </li>
@endif
