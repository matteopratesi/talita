@import talita.ui.Icon
@param String id
@param String name
@param String label
@param String value = ""
@param int rows = 6
@param String hint = null
@param String error = null
<%-- A field of several lines (design guide 4.2): the line breaks that the person writes are kept. 9 rows (min-h-48) for the long text, 6 (min-h-36) for a list. --%>
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
  <textarea id="${id}" name="${name}" rows="${rows}" aria-describedby="${describedBy}" aria-invalid="${error != null ? "true" : null}" class="block ${rows >= 9 ? "min-h-48" : "min-h-36"} w-full resize-y rounded-xl border border-line-strong bg-surface px-4 py-3 text-base text-ink focus:border-brand aria-[invalid=true]:border-danger aria-[invalid=true]:bg-danger-soft">${value}</textarea>
  @if(error != null)
    <p id="${id}-errore" class="flex items-start gap-1.5 text-sm font-medium text-danger">
      @template.components.icon(icon = Icon.CIRCLE_ALERT, classes = "mt-0.5 size-4 shrink-0")
      ${error}
    </p>
  @endif
</div>
