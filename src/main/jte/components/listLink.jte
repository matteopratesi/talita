@import talita.gathering.Badge
@import talita.ui.Icon
@param String href
@param Icon icon
@param String title
@param String meta
@param java.util.List<Badge> badges = java.util.List.of()
<%-- A row of a list that is a link as a whole (design guide 4.3): the tile with the icon, the title with its labels of state next to it
     (they wrap, they do not take a column), the line of details, the arrow. The title is the text of whoever wrote it: it wraps anywhere. --%>
<a href="${href}" class="flex min-h-16 items-center gap-3 px-4 py-3 transition-colors duration-150 hover:bg-surface-muted active:bg-surface-muted busy:bg-surface-muted">
  <span class="grid size-10 shrink-0 place-items-center rounded-xl bg-brand-soft text-brand">
    @template.components.icon(icon = icon, classes = "size-6")
  </span>
  <span class="min-w-0 flex-1">
    <span class="flex flex-wrap items-center gap-x-2 gap-y-1">
      <span class="font-semibold wrap-anywhere">${title}</span>
      @for(Badge badge : badges)
        @template.components.badge(label = badge.getLabel(), tone = badge.getTone())
      @endfor
    </span>
    <span class="block text-sm text-ink-muted wrap-anywhere">${meta}</span>
  </span>
  @template.components.icon(icon = Icon.CHEVRON_RIGHT, classes = "size-5 shrink-0 text-ink-muted")
</a>
