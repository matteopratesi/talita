@import talita.ui.AlertVariant
@param gg.jte.Content content
@param AlertVariant variant = null
@param boolean live = false
<%-- A notice in the page (design guide 4.6). Colors by variant, written here because Tailwind reads the classes from the templates.
     `live`: the notice follows an action (a form sent again, a confirmation): it has the role that a screen reader announces
     and takes the focus after the page is replaced (data-fuoco, see app.js). --%>
!{AlertVariant kind = variant == null ? AlertVariant.INFO : variant;
String box = switch (kind) {
    case INFO -> "bg-brand-soft";
    case SUCCESS -> "bg-success-soft";
    case WARNING -> "bg-warning-soft";
    case DANGER -> "bg-danger-soft";
};
String tone = switch (kind) {
    case INFO -> "text-brand";
    case SUCCESS -> "text-success";
    case WARNING -> "text-warning";
    case DANGER -> "text-danger";
};}
<div class="flex items-start gap-3 rounded-xl ${box} p-4" role="${live ? kind.getRole() : null}" tabindex="${live ? "-1" : null}" data-fuoco="${live ? "" : null}">
  @template.components.icon(icon = kind.getIcon(), classes = "size-6 shrink-0 " + tone)
  <p>${content}</p>
</div>
