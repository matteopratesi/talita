@import talita.ui.AlertVariant
@param gg.jte.Content content
@param AlertVariant variant = null
@param boolean live = false
@param gg.jte.Content action = null
@param String lead = null
@param String actionLabel = null
@param String actionHref = null
@param boolean leadOnly = false
<%-- A notice in the page (design guide 4.6). Colors by variant, written here because Tailwind reads the classes from the templates.
     `live`: the notice follows an action (a form sent again, a confirmation): it has the role that a screen reader announces
     and takes the focus after the page is replaced (data-fuoco, see app.js).
     `action`: a text button under the sentence (the reminder of an invitation has «Accetta l'invito»).
     `lead`, `actionLabel`, `actionHref`: a confirmation with two paragraphs (design TALITA-7, guide 6 «Conferme»): the first words in bold, then the sentence on
     what stays and until when, and a text button that leads to it. `leadOnly`: the notice is the bold words and nothing else («Dispositivo tolto.»). --%>
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
<div class="flex items-start gap-3 rounded-xl ${box} p-4 print:border print:border-ink" role="${live ? kind.getRole() : null}" tabindex="${live ? "-1" : null}" data-fuoco="${live ? "" : null}">
  @template.components.icon(icon = kind.getIcon(), classes = "size-6 shrink-0 " + tone)
  @if(lead != null || actionLabel != null)
    <div class="min-w-0 space-y-2">
      @if(lead != null)
        <p class="font-semibold wrap-anywhere">${lead}</p>
      @endif
      @if(!leadOnly)
        <p class="wrap-anywhere">${content}</p>
      @endif
      @if(actionLabel != null)
        <div class="-mb-2 -ml-3">@template.components.linkButton(href = actionHref, label = actionLabel, variant = "text")</div>
      @endif
    </div>
  @elseif(action != null)
    <div class="min-w-0">
      <p class="wrap-anywhere">${content}</p>
      <div class="-mb-2 -ml-3 mt-1">${action}</div>
    </div>
  @else
    <p class="min-w-0 wrap-anywhere">${content}</p>
  @endif
</div>
