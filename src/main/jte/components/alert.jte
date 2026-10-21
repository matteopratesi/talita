@import koine.ui.Icon
@param gg.jte.Content content
<div class="flex items-start gap-3 rounded-xl bg-brand-soft p-4">
  @template.components.icon(icon = Icon.INFO, classes = "size-6 shrink-0 text-brand")
  <p>${content}</p>
</div>
