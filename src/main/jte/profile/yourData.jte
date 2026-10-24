@import org.springframework.security.web.csrf.CsrfToken
@import talita.retention.BlockLine
@import talita.retention.DataChild
@import talita.retention.YourData
@import talita.ui.Icon
@param YourData data
@param CsrfToken csrf
<%-- «I tuoi dati» in the profile (TALITA-7, criteria 16, 17, 23, 24): the children of the person with the button that deletes their data, and the account. Deleting is
     never the main action of the page: the button is the one of danger in outline and opens a dialog (the one of danger, full, is inside it). When something has to be
     done first, the sentence takes the place of the button, in the same spot (not an error: no color of warning). Names are text, never HTML. --%>
<section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="dati-titolo">
  <h2 id="dati-titolo" class="text-lg font-semibold">I tuoi dati</h2>
  <p class="mt-3">${data.getIntro()}</p>
  <div class="-ml-3 mt-1">
    <a href="/informativa#per-quanto-tempo" hx-boost="false" class="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl px-3 text-base font-semibold text-brand transition-colors duration-150 select-none hover:bg-brand-soft active:bg-brand-soft">
      @template.components.icon(icon = Icon.SHIELD_CHECK, classes = "size-5")
      Per quanto tempo teniamo i dati
    </a>
  </div>

  @if(!data.getChildren().isEmpty())
    <h3 class="mt-6 text-base font-semibold">I tuoi figli</h3>
    <ul class="mt-2">
      @for(DataChild child : data.getChildren())
        <li class="border-t border-line py-4 first:border-t-0 first:pt-2">
          <div class="flex items-center gap-3">
            <span aria-hidden="true" class="grid size-10 shrink-0 place-items-center rounded-full bg-brand-soft text-sm font-semibold text-brand">${child.getInitials()}</span>
            <p class="min-w-0 font-semibold wrap-anywhere">${child.getFullName()}</p>
          </div>
          <div class="mt-3">
            @if(child.getDialog() != null)
              @template.components.linkButton(href = child.getConfirmHref(), label = child.getButtonLabel(), variant = "danger-outline", icon = Icon.TRASH_2, width = "phone", opens = child.getDialog().getId(), ariaLabel = child.getAriaLabel())
            @else
              @for(BlockLine line : child.getBlocks())
                <p class="text-sm text-ink-muted wrap-anywhere">${line.getSentence()}</p>
                @if(line.getHref() != null)
                  <div class="-ml-3">
                    @template.components.linkButton(href = line.getHref(), label = line.getLabel(), variant = "text", ariaLabel = line.getAriaLabel())
                  </div>
                @endif
              @endfor
            @endif
          </div>
        </li>
      @endfor
    </ul>
  @endif

  <h3 class="mt-6 text-base font-semibold">Il tuo account</h3>
  @if(data.getAccount().getBlocks().isEmpty() && data.getAccount().getDialog() != null)
    <p class="mt-2">${data.getAccount().getText()}</p>
    <div class="mt-3">
      @template.components.linkButton(href = data.getAccount().getConfirmHref(), label = "Cancella il mio account", variant = "danger-outline", icon = Icon.TRASH_2, width = "phone", opens = data.getAccount().getDialog().getId())
    </div>
  @elseif(!data.getAccount().getBlocks().isEmpty())
    <p class="mt-2 font-semibold">Per cancellare il tuo account, prima:</p>
    <ul class="mt-1 list-disc space-y-2 pl-5 marker:text-ink-muted">
      @for(BlockLine line : data.getAccount().getBlocks())
        <li class="pl-1">
          <span class="block wrap-anywhere">${line.getSentence()}</span>
          @if(line.getHref() != null)
            <div class="-ml-3">
              @template.components.linkButton(href = line.getHref(), label = line.getLabel(), variant = "text", ariaLabel = line.getAriaLabel())
            </div>
          @endif
        </li>
      @endfor
    </ul>
  @endif
</section>

@for(DataChild child : data.getChildren())
  @if(child.getDialog() != null)
    @template.profile.erasureDialog(dialog = child.getDialog(), csrf = csrf)
  @endif
@endfor
@if(data.getAccount().getDialog() != null)
  @template.profile.erasureDialog(dialog = data.getAccount().getDialog(), csrf = csrf)
@endif
