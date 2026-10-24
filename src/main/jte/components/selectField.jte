@import talita.ui.Icon
@import talita.ui.SelectOption
@param String id
@param String name
@param String label
@param java.util.List<SelectOption> options
<%-- A choice among many (design guide 4.22): more than five options, so a `select` and not radios. The label is always visible above, the arrow is only for the eyes
     (aria-hidden) and does not take the touch. The form that holds it is a GET: nothing that is chosen here is personal. --%>
<div class="space-y-1.5">
  <label for="${id}" class="block font-semibold">${label}</label>
  <div class="relative">
    <select id="${id}" name="${name}" class="block min-h-12 w-full appearance-none rounded-xl border border-line-strong bg-surface pr-10 pl-4 text-base text-ink focus:border-brand">
      @for(SelectOption option : options)
        <option value="${option.getValue()}" selected="${option.getSelected()}">${option.getLabel()}</option>
      @endfor
    </select>
    @template.components.icon(icon = Icon.CHEVRON_DOWN, classes = "pointer-events-none absolute top-1/2 right-3.5 size-5 -translate-y-1/2 text-ink-muted")
  </div>
</div>
