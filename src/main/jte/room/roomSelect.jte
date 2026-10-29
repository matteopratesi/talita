@import talita.room.RoomOption
@import talita.ui.Icon
@param String id
@param String name
@param String placeholder
@param java.util.List<RoomOption> options
@param String error = null
<%-- The choice of a room or of a teacher (design TALITA-11, 3.4 and guide 4.2): a native `select`, with the arrow drawn on top (`aria-hidden`, it takes no touch). The first
     option has no value and says what is chosen («Scegli la camera»), so it is also the label that everybody sees; the real label of a row that repeats (a list of thirty
     children) is `sr-only` and is in the template that holds this one. The select never sends by itself at the change: two touches, the choice and the button, and a
     message that says what was done (WCAG 3.2.2: the arrows of the keyboard change the value at every press). An error is under it, with the icon, and the select is invalid. --%>
<div class="min-w-0 flex-1">
  <div class="relative">
    <select id="${id}" name="${name}" aria-invalid="${error != null ? "true" : null}" aria-describedby="${error != null ? id + "-errore" : null}" class="block min-h-12 w-full appearance-none rounded-xl border border-line-strong bg-surface pr-11 pl-4 text-base text-ink focus:border-brand aria-[invalid=true]:border-danger aria-[invalid=true]:bg-danger-soft">
      <option value="">${placeholder}</option>
      @for(RoomOption option : options)
        <option value="${option.getValue()}">${option.getLabel()}</option>
      @endfor
    </select>
    @template.components.icon(icon = Icon.CHEVRON_DOWN, classes = "pointer-events-none absolute top-1/2 right-3.5 size-5 -translate-y-1/2 text-ink-muted")
  </div>
  @if(error != null)
    <p id="${id}-errore" class="mt-1.5 flex items-start gap-1.5 text-sm font-medium text-danger">
      @template.components.icon(icon = Icon.CIRCLE_ALERT, classes = "mt-0.5 size-4 shrink-0")
      ${error}
    </p>
  @endif
</div>
