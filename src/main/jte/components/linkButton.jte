@import talita.ui.Icon
@param String href
@param String label
@param String variant = "primary"
@param String width = "auto"
@param Icon icon = null
@param String opens = null
@param String ariaLabel = null
<%-- A link that looks like a button (design guide 4.1). Same variants and widths as button.jte.
     opens: the id of a dialog (confirmDialog.jte): app.js opens it at the tap; without JavaScript the link leads to the page of confirmation.
     ariaLabel: the name when the label alone does not say what it is about («Modifica» of a card). --%>
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
<a href="${href}" data-apri="${opens}" aria-label="${ariaLabel}" class="inline-flex ${variant.equals("text") ? "min-h-11 px-3" : "min-h-12 px-5"} items-center justify-center gap-2 rounded-xl text-base font-semibold transition-colors duration-150 select-none disabled:cursor-wait disabled:opacity-70 ${look}${span}">@if(icon != null)@template.components.icon(icon = icon, classes = "size-5")@endif${label}</a>
