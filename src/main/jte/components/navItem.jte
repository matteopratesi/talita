@import koine.ui.NavItem
@param NavItem item
<li class="min-w-0 flex-1">
  <a href="${item.getHref()}" aria-current="${item.getCurrent() ? "page" : null}" class="group flex h-16 flex-col items-center justify-center gap-1 rounded-xl text-xs font-medium text-ink-muted transition-colors duration-150 active:bg-surface-muted busy:bg-surface-muted aria-[current=page]:font-semibold aria-[current=page]:text-brand max-[20rem]:text-2xs sm:short:h-12 sm:short:flex-row sm:short:gap-2 sm:short:text-sm sm:short:aria-[current=page]:bg-brand-soft lg:h-12 lg:flex-row lg:justify-start lg:gap-3 lg:px-3 lg:text-base lg:hover:bg-surface-muted lg:aria-[current=page]:bg-brand-soft">
    <span class="flex h-8 w-14 items-center justify-center rounded-full transition-colors duration-150 group-aria-[current=page]:bg-brand-soft max-[20rem]:w-12 sm:short:h-auto sm:short:w-auto sm:short:group-aria-[current=page]:bg-transparent lg:h-auto lg:w-auto lg:group-aria-[current=page]:bg-transparent">
      @template.components.icon(icon = item.getIcon(), classes = "size-6")
    </span>
    <span class="max-w-full truncate">${item.getLabel()}</span>
  </a>
</li>
