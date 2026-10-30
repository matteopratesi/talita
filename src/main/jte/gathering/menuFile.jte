@import talita.gathering.MenuFileView
@import talita.ui.Icon
@param MenuFileView file
<%-- The menu file as a person reads it (TALITA-12, design 1): the tile with the icon of its kind, the name in bold and under it the kind, the size and the day of the
     upload. The name is the text of whoever wrote it, never markup: it wraps anywhere. There is no preview and no thumbnail: the file is saved, not opened here. --%>
<div class="mt-3 flex items-start gap-3">
  <span class="grid size-10 shrink-0 place-items-center rounded-xl bg-brand-soft text-brand">
    @template.components.icon(icon = file.getIcon(), classes = "size-6")
  </span>
  <div class="min-w-0 flex-1">
    <p class="font-semibold wrap-anywhere">${file.getName()}</p>
    <p class="text-sm text-ink-muted">${file.getMeta()}</p>
  </div>
</div>
