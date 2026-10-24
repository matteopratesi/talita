@import talita.ui.Icon
@param String id
@param String name
@param String label
@param boolean checked = false
@param String error = null
@param String hint = null
<%-- A box with a long text and its error (design guide 4.2): the whole row is the target (48 px at least), the box is 24 px. The error is under the text,
     not under the box. The boxes of a page are never ticked before the person ticks them. --%>
!{String describedBy = null;
if (hint != null && error != null) {
    describedBy = id + "-aiuto " + id + "-errore";
} else if (hint != null) {
    describedBy = id + "-aiuto";
} else if (error != null) {
    describedBy = id + "-errore";
}}
<div>
  <label class="flex min-h-12 items-start gap-3 py-2">
    <input type="checkbox" id="${id}" name="${name}" value="true" checked="${checked}" aria-invalid="${error != null ? "true" : null}" aria-describedby="${describedBy}" class="mt-0.5 size-6 shrink-0 accent-brand">
    <span class="min-w-0 font-semibold wrap-anywhere">${label}</span>
  </label>
  @if(hint != null)
    <p id="${id}-aiuto" class="ml-9 text-sm text-ink-muted">${hint}</p>
  @endif
  @if(error != null)
    <p id="${id}-errore" class="ml-9 flex items-start gap-1.5 text-sm font-medium text-danger">
      @template.components.icon(icon = Icon.CIRCLE_ALERT, classes = "mt-0.5 size-4 shrink-0")
      ${error}
    </p>
  @endif
</div>
