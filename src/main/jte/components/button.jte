@import talita.ui.Icon
@param String label
@param String busyLabel
@param String variant = "primary"
@param Icon icon = null
@param String width = "phone"
@param String opens = null
<%-- The button of a form (design guide 4.1). While its request is on its way the form has the class htmx-request:
     the button shows the spinner and the busy label (busy:), and is disabled by hx-disabled-elt on the form.
     variant: primary (the one action of the screen), secondary (alternative), text (minor action in a card or a list).
     width: "phone" (the width of the screen on the phone, its own from 640 px, the default), "full" (always the width of its card), "auto".
     opens: the id of a dialog (confirmDialog.jte): app.js opens it at the tap; without JavaScript the button sends its form, which leads
     to a page with the same words. --%>
!{String look = switch (variant) {
    case "secondary" -> "border border-line-strong bg-surface text-ink hover:bg-surface-muted active:bg-surface-muted";
    case "text" -> "text-brand hover:bg-brand-soft active:bg-brand-soft";
    default -> "bg-brand text-white hover:bg-brand-strong active:bg-brand-strong";
};
String span = switch (width) {
    case "full" -> " w-full";
    case "phone" -> " w-full sm:w-auto";
    default -> "";
};}
<button type="submit" data-apri="${opens}" class="inline-flex ${variant.equals("text") ? "min-h-11 px-3" : "min-h-12 px-5"} items-center justify-center gap-2 rounded-xl text-base font-semibold transition-colors duration-150 select-none disabled:cursor-wait disabled:opacity-70 ${look}${span}">
  @template.components.icon(icon = Icon.LOADER_CIRCLE, classes = "hidden size-5 animate-spin busy:block")
  @if(icon != null)
    @template.components.icon(icon = icon, classes = "size-5 busy:hidden")
  @endif
  <span class="busy:hidden">${label}</span>
  <span class="hidden busy:inline">${busyLabel}</span>
</button>
