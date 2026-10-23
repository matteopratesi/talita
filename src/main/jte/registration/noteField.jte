@import talita.ui.Icon
@param String id
@param String name
@param String label
@param String hint
@param String value
@param String error = null
<%-- A note on health (design A 1/2, 5): two lines to start with (they grow), the example in the help under the label. What the person wrote is text, never HTML. --%>
<div class="space-y-1.5">
  <label for="${id}" class="block font-semibold">${label}</label>
  <p id="${id}-aiuto" class="text-sm text-ink-muted">${hint}</p>
  <textarea id="${id}" name="${name}" rows="2" aria-describedby="${error != null ? id + "-aiuto " + id + "-errore" : id + "-aiuto"}" aria-invalid="${error != null ? "true" : null}" class="block min-h-22 w-full resize-y rounded-xl border border-line-strong bg-surface px-4 py-3 text-base text-ink focus:border-brand aria-[invalid=true]:border-danger aria-[invalid=true]:bg-danger-soft">${value}</textarea>
  @if(error != null)
    <p id="${id}-errore" class="flex items-start gap-1.5 text-sm font-medium text-danger">
      @template.components.icon(icon = Icon.CIRCLE_ALERT, classes = "mt-0.5 size-4 shrink-0")
      ${error}
    </p>
  @endif
</div>
