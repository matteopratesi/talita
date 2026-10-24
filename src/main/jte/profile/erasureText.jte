@import talita.retention.DialogSection
@import talita.retention.ErasureDialog
@param ErasureDialog dialog
@param String id
<%-- The words of a dialog that deletes data (guide 4.8, rule of TALITA-7), the same in the dialog and in the page without JavaScript: what is deleted at once, what stays
     (with the date and the reason) or «Non resta niente.», the copies of safety, and «Non si può annullare.». Short lists, never a wall of text. All of it is text. --%>
<div id="${id}" class="mt-3 space-y-4">
  @for(DialogSection section : dialog.getSections())
    <div>
      @if(section.getTitle() != null)
        <h3 class="text-base font-semibold">${section.getTitle()}</h3>
      @endif
      @if(!section.getItems().isEmpty())
        <ul class="mt-1 list-disc space-y-2 pl-5 text-ink-muted marker:text-ink-muted">
          @for(String item : section.getItems())
            <li class="pl-1 wrap-anywhere">${item}</li>
          @endfor
        </ul>
      @endif
      @if(section.getStrong() != null)
        <p class="font-semibold">${section.getStrong()}</p>
      @endif
    </div>
  @endfor
  <p class="text-sm text-ink-muted">${dialog.getSmall()}</p>
  <p class="font-semibold">${dialog.getStrong()}</p>
</div>
