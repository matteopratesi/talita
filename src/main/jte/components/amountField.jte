@import talita.ui.Icon
@param String id
@param String name
@param String label
@param String value = ""
@param String hint = null
@param String error = null
<%-- A field for an amount in euros (design guide 4.2): a text field with the keyboard of numbers and the sign «€» inside, at the right.
     The sign is only for the eyes (aria-hidden): the label says what it is. --%>
!{String describedBy = null;
if (hint != null && error != null) {
    describedBy = id + "-aiuto " + id + "-errore";
} else if (hint != null) {
    describedBy = id + "-aiuto";
} else if (error != null) {
    describedBy = id + "-errore";
}}
<div class="space-y-1.5">
  <label for="${id}" class="block font-semibold">${label}</label>
  @if(hint != null)
    <p id="${id}-aiuto" class="text-sm text-ink-muted">${hint}</p>
  @endif
  <div class="relative">
    <input id="${id}" name="${name}" type="text" inputmode="decimal" enterkeyhint="done" autocomplete="off" autocorrect="off" spellcheck="false" value="${value}" aria-describedby="${describedBy}" aria-invalid="${error != null ? "true" : null}" class="block min-h-12 w-full rounded-xl border border-line-strong bg-surface pr-12 pl-4 text-base text-ink focus:border-brand aria-[invalid=true]:border-danger aria-[invalid=true]:bg-danger-soft">
    <span aria-hidden="true" class="pointer-events-none absolute inset-y-0 right-4 flex items-center text-ink-muted">€</span>
  </div>
  @if(error != null)
    <p id="${id}-errore" class="flex items-start gap-1.5 text-sm font-medium text-danger">
      @template.components.icon(icon = Icon.CIRCLE_ALERT, classes = "mt-0.5 size-4 shrink-0")
      ${error}
    </p>
  @endif
</div>
