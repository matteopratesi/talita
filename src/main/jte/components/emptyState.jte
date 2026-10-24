@import talita.ui.Icon
@param Icon icon
@param String title
@param String text
@param String href = null
@param String label = null
@param String variant = "primary"
@param Icon buttonIcon = null
@param boolean spaced = false
<%-- Something that has nothing to show yet, or nothing that fits (design guide 4.9): a circle with the icon, what is missing, a sentence on what will be here, and the
     button to make it if the person can. The title is an h2: the page has its own h1. `spaced`: the card follows another block of the page. --%>
<div class="flex flex-col items-center rounded-2xl border border-line bg-surface px-6 py-12 text-center${spaced ? " mt-4" : ""}">
  <span class="grid size-16 place-items-center rounded-full bg-brand-soft text-brand">
    @template.components.icon(icon = icon, classes = "size-8")
  </span>
  <h2 class="mt-4 text-xl font-semibold tracking-tight wrap-anywhere">${title}</h2>
  <p class="mt-2 max-w-sm text-ink-muted">${text}</p>
  @if(href != null)
    <div class="mt-6 w-full sm:w-auto">
      @template.components.linkButton(href = href, label = label, variant = variant, icon = buttonIcon, width = "phone")
    </div>
  @endif
</div>
