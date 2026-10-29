@import org.springframework.security.web.csrf.CsrfToken
@import talita.room.FreeRow
@import talita.ui.Icon
@param FreeRow row
@param long gathering
@param boolean canAct
@param CsrfToken csrf
<%-- A person who has no room (design TALITA-11, 3.5): the name and the label at the left, the line under it, and the choice of the room with «Metti», which is a secondary button
     (twenty primary buttons in a row would be noise, and the page has no single main action: the work is choosing). The select has no visible label: its first option says what it
     is, and the label that a screen reader reads is `sr-only` and carries the name of the person. A row that holds a message is the person who was just put in a room, or whose
     registration was withdrawn: it stays in place for this answer and has no choice. On the desktop the text is at the left and the choice at the right. In a finished
     gathering there is no choice, only the person. --%>
<li id="libero-${row.getKey()}" class="px-4 py-3 sm:flex sm:items-center sm:gap-4">
  <div class="min-w-0 sm:flex-1">
    <span class="flex flex-wrap items-center gap-x-2 gap-y-1">
      <span class="font-semibold wrap-anywhere">${row.getName()}</span>
      @if(row.getMaybe())
        @template.components.badge(label = "In forse", tone = "warning")
      @endif
    </span>
    <span class="block text-sm text-ink-muted">${row.getLine()}</span>
  </div>
  @if(row.getAlert() != null)
    <div class="mt-2 sm:mt-0 sm:w-80">
      @template.components.alert(variant = row.getAlert().getVariant(), live = true, content = @`${row.getAlert().getText()}`)
    </div>
  @elseif(canAct)
    <form method="post" action="${"/convivenza/" + gathering + "/camere/scegli"}" hx-post="${"/convivenza/" + gathering + "/camere/scegli"}" hx-target="#camere-contenuto" hx-swap="outerHTML" hx-disabled-elt="find button" novalidate class="mt-2 flex items-start gap-2 sm:mt-0 sm:w-80 sm:shrink-0">
      @template.components.csrf(csrf = csrf)
      <input type="hidden" name="persona" value="${row.getKey()}">
      <label for="${"camera-" + row.getKey()}" class="sr-only">Camera di ${row.getName()}</label>
      @template.room.roomSelect(id = "camera-" + row.getKey(), name = "camera", placeholder = "Scegli la camera", options = row.getOptions(), error = row.getError())
      <button type="submit" class="inline-flex min-h-12 shrink-0 items-center justify-center gap-2 rounded-xl border border-line-strong bg-surface px-4 text-base font-semibold text-ink transition-colors duration-150 select-none hover:bg-surface-muted active:bg-surface-muted disabled:cursor-wait disabled:opacity-70">
        @template.components.icon(icon = Icon.LOADER_CIRCLE, classes = "hidden size-5 animate-spin busy:block")
        <span class="busy:hidden">Metti<span class="sr-only"> ${row.getName()} in camera</span></span><span class="hidden busy:inline">Metto…</span>
      </button>
    </form>
  @endif
</li>
