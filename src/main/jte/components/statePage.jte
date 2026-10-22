@import talita.ui.Icon
@param Icon icon
@param String title
@param gg.jte.Content body
@param gg.jte.Content actions
@param String tone = "brand"
<%-- A page that is only a message (design guide 4.11): a circle with the icon, the title (the h1), the text and the buttons.
     Used by the pages of error and of access. tone: "brand" (it is not a fault) or "warning" (something went wrong). --%>
<div class="flex flex-col items-center py-10 text-center lg:py-16">
  <span class="grid size-16 place-items-center rounded-full ${tone.equals("warning") ? "bg-warning-soft text-warning" : "bg-brand-soft text-brand"}">
    @template.components.icon(icon = icon, classes = "size-8")
  </span>
  <h1 tabindex="-1" class="mt-5 text-2xl font-semibold tracking-tight lg:text-3xl">${title}</h1>
  ${body}
  ${actions}
</div>
