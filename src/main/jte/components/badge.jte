@import talita.ui.Icon
@param String label
@param String tone = "neutral"
@param Icon icon = null
<%-- A label of state (design guide 4.5): a word or two that says in words what the color says. tone: neutral, info, positive, warning, danger.
     icon: a small mark before the word («Presente» has a check). The classes are written here because Tailwind reads them from the templates. --%>
!{String look = switch (tone) {
    case "info" -> "bg-brand-soft text-brand";
    case "positive" -> "bg-success-soft text-success";
    case "warning" -> "bg-warning-soft text-warning";
    case "danger" -> "bg-danger-soft text-danger";
    default -> "bg-surface-muted text-ink-muted";
};}
<span class="inline-flex shrink-0 items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold whitespace-nowrap ${look}">@if(icon != null)@template.components.icon(icon = icon, classes = "size-4")@endif${label}</span>
