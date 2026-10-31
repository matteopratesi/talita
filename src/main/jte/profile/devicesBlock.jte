@import org.springframework.security.web.csrf.CsrfToken
@import talita.account.DeviceLine
@import talita.account.DevicesBlock
@import talita.ui.Icon
@param DevicesBlock block
@param CsrfToken csrf
<%-- «Dispositivi collegati» (TALITA-15, criteria 14-18), the part of the card «Il tuo accesso» that every action of it replaces as a whole (htmx: hx-target="#dispositivi-contenuto",
     hx-swap="outerHTML"): the title, the notice, the list with its dialogs and «Esci da tutti gli altri dispositivi». The rest of the page, and the scroll, stay. A row says only the day of
     the touch and the last day that it enters: never a name of the device, a browser, a place or a last use. The device that the person is on has the label «Questo dispositivo» and no
     button, because «Esci» above it does the same. --%>
<div id="dispositivi-contenuto">
  <h3 id="dispositivi-titolo" class="mt-6 text-base font-semibold">Dispositivi collegati</h3>
  @if(block.getAlert() != null)
    <div class="mt-2">
      @template.components.alert(variant = block.getAlert().getVariant(), live = true, lead = block.getAlert().getLead(), leadOnly = block.getAlert().getText().isEmpty(), actionLabel = block.getAlert().getActionLabel(), actionHref = block.getAlert().getActionHref(), content = @`${block.getAlert().getText()}`)
    </div>
  @endif
  <p class="mt-2 text-ink-muted">${block.getIntro()}</p>
  @if(!block.getLines().isEmpty())
    <ul class="mt-2" aria-labelledby="dispositivi-titolo">
      @for(DeviceLine line : block.getLines())
        <li class="border-t border-line py-3.5 first:border-t-0 first:pt-1">
          <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
            <span aria-hidden="true" class="grid size-10 shrink-0 place-items-center rounded-xl bg-brand-soft text-brand">
              @template.components.icon(icon = Icon.MONITOR_SMARTPHONE, classes = "size-6")
            </span>
            <span class="min-w-0 flex-[1_1_11rem]">
              <span class="flex flex-wrap items-center gap-x-2 gap-y-1">
                <span class="font-semibold wrap-anywhere">${line.getTitle()}</span>
                @if(line.getCurrent())
                  @template.components.badge(label = "Questo dispositivo")
                @endif
              </span>
              <span class="block text-sm text-ink-muted">${line.getMeta()}</span>
            </span>
            @if(line.getRemoval() != null)
              <form method="post" action="/profilo/dispositivi/togli/conferma" class="-ml-3 sm:ml-0 sm:shrink-0">
                @template.components.csrf(csrf = csrf)
                <input type="hidden" name="${line.getRemoval().getField()}" value="${line.getRemoval().getValue()}">
                @template.components.button(label = "Togli", busyLabel = "Rimozione…", variant = "text", width = "auto", opens = line.getRemoval().getId(), ariaLabel = line.getAriaLabel())
              </form>
            @endif
          </div>
        </li>
      @endfor
    </ul>
  @endif
  @if(block.getFull() != null)
    <p class="mt-1 text-sm text-ink-muted">${block.getFull()}</p>
  @endif
  <form method="post" action="/profilo/dispositivi/esci-altri/conferma" class="mt-4">
    @template.components.csrf(csrf = csrf)
    <input type="hidden" name="${block.getExitOthers().getField()}" value="${block.getExitOthers().getValue()}">
    @template.components.button(label = "Esci da tutti gli altri dispositivi", busyLabel = "Uscita…", variant = "secondary", icon = Icon.LOG_OUT, opens = block.getExitOthers().getId())
  </form>
  <p class="mt-3 text-sm text-ink-muted">Chiude Talità su tutti gli altri telefoni e computer, anche dove non hai scelto «Ricordami su questo telefono». Questo resta dentro.</p>

  @for(DeviceLine line : block.getLines())
    @if(line.getRemoval() != null)
      @template.components.confirmDialog(dialog = line.getRemoval(), csrf = csrf)
    @endif
  @endfor
  @template.components.confirmDialog(dialog = block.getExitOthers(), csrf = csrf)
</div>
