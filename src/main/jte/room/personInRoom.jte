@import org.springframework.security.web.csrf.CsrfToken
@import talita.room.PersonView
@import talita.ui.Icon
@param PersonView person
@param long gathering
@param boolean canAct
@param CsrfToken csrf
<%-- A person in a room (design TALITA-11, 3.2): the name and the label «In forse» at the left, the line under it (age and what the child comes for, or «Maestro»), and «Cambia» at
     the right, a native `details`. While it is closed its `summary` is out of the flow (absolute), so a row is never taller than its text; open, the form goes under the text, as wide as
     the card. The form has «Sposta» (the main button) and «Togli dalla camera», and the select of the other rooms. Nothing says the sex, the reason of a choice or a note: there is
     nothing to say it with. A room of a finished gathering has no «Cambia» (`canAct` false). --%>
<li id="persona-${person.getKey()}" class="relative py-2.5${canAct ? " pr-21" : ""}">
  <span class="flex flex-wrap items-center gap-x-2 gap-y-1">
    <span class="font-semibold wrap-anywhere">${person.getName()}</span>
    @if(person.getMaybe())
      @template.components.badge(label = "In forse", tone = "warning")
    @endif
  </span>
  <span class="block text-sm text-ink-muted">${person.getLine()}</span>
  @if(canAct)
    <details class="group" open="${person.getError() != null}">
      <summary class="absolute top-0.5 -right-3 inline-flex min-h-11 cursor-pointer list-none items-center rounded-xl px-3 font-semibold text-brand transition-colors duration-150 hover:bg-brand-soft active:bg-brand-soft [&::-webkit-details-marker]:hidden">
        <span class="group-open:hidden">Cambia<span class="sr-only"> la camera di ${person.getName()}</span></span>
        <span class="hidden group-open:inline">Chiudi<span class="sr-only"> le scelte per ${person.getName()}</span></span>
      </summary>
      <form method="post" action="${"/convivenza/" + gathering + "/camere/sposta"}" hx-post="${"/convivenza/" + gathering + "/camere/sposta"}" hx-target="#camere-contenuto" hx-swap="outerHTML" hx-disabled-elt="find button" novalidate class="mt-2.5 -mr-21 space-y-3 rounded-xl bg-canvas p-4">
        @template.components.csrf(csrf = csrf)
        <input type="hidden" name="persona" value="${person.getKey()}">
        <div class="space-y-1.5">
          <label for="${"sposta-" + person.getKey()}" class="block font-semibold">Camera di ${person.getName()}</label>
          @template.room.roomSelect(id = "sposta-" + person.getKey(), name = "camera", placeholder = "Scegli la camera", options = person.getOptions(), error = person.getError())
        </div>
        <div class="flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
          <button type="submit" name="azione" value="sposta" class="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-brand px-5 text-base font-semibold text-white transition-colors duration-150 select-none hover:bg-brand-strong active:bg-brand-strong disabled:cursor-wait disabled:opacity-70 sm:w-auto">
            @template.components.icon(icon = Icon.LOADER_CIRCLE, classes = "hidden size-5 animate-spin busy:block")
            <span class="busy:hidden">Sposta</span><span class="hidden busy:inline">Sposto…</span>
          </button>
          <button type="submit" name="azione" value="togli" class="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl border border-line-strong bg-surface px-5 text-base font-semibold text-ink transition-colors duration-150 select-none hover:bg-surface-muted active:bg-surface-muted disabled:cursor-wait disabled:opacity-70 sm:w-auto">
            <span class="busy:hidden">Togli dalla camera</span><span class="hidden busy:inline">Tolgo…</span>
          </button>
        </div>
      </form>
    </details>
  @endif
</li>
