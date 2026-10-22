@import talita.ui.Icon
@param String id
@param String label
@param String value = ""
@param String type = "text"
@param String name = null
@param String hint = null
@param String error = null
@param String autocomplete = null
@param String autocapitalize = null
@param boolean plain = false
<%-- A field of a form (design guide 4.2): label always visible above, optional help, error below with the icon.
     `plain`: no correction and no spell check (an address, not a word). The form that holds it has `novalidate`:
     the errors are ours, the same for everyone, and not the bubble of the browser. --%>
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
  <input id="${id}" name="${name != null ? name : id}" type="${type}" value="${value}" autocomplete="${autocomplete}" autocapitalize="${autocapitalize}" autocorrect="${plain ? "off" : null}" spellcheck="${plain ? "false" : null}" aria-describedby="${describedBy}" aria-invalid="${error != null ? "true" : null}" class="block min-h-12 w-full rounded-xl border border-line-strong bg-surface px-4 text-base text-ink placeholder:text-ink-muted focus:border-brand aria-[invalid=true]:border-danger aria-[invalid=true]:bg-danger-soft">
  @if(error != null)
    <p id="${id}-errore" class="flex items-start gap-1.5 text-sm font-medium text-danger">
      @template.components.icon(icon = Icon.CIRCLE_ALERT, classes = "mt-0.5 size-4 shrink-0")
      ${error}
    </p>
  @endif
</div>
