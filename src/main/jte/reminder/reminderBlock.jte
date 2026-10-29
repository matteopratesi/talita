@import talita.gathering.ReminderBlock
@import talita.ui.Icon
@param ReminderBlock block
<%-- One kind of reminder in the card «Promemoria» (TALITA-13, design part 1, 4): the title, who it is for, the two rows, and **one of three things**: the button that opens the
     dialog, the wait until the next one can go, or the zero in words with why. Counts only: never a name or an address. The `id` is a word of the code (`maybe`, `notice`). --%>
<div id="${"promemoria-" + block.getType()}" class="mt-4 border-t border-line pt-4">
  <h3 class="font-semibold">${block.getTitle()}</h3>
  <p class="mt-1 text-ink-muted">${block.getWhat()}</p>
  <dl class="mt-2 divide-y divide-line">
    <div class="flex flex-wrap items-baseline justify-between gap-x-4 py-2.5"><dt class="text-ink-muted">Lo riceverebbero</dt><dd class="font-semibold">${block.getReceivers()}</dd></div>
    <div class="flex flex-wrap items-baseline justify-between gap-x-4 py-2.5"><dt class="text-ink-muted">Ultimo invio</dt><dd class="basis-full text-left font-semibold wrap-anywhere">${block.getLast()}</dd></div>
  </dl>
  @if(block.getButton() != null)
    <div class="mt-3">
      @template.components.linkButton(href = block.getButton().getHref(), label = block.getButton().getLabel(), variant = "secondary", icon = Icon.SEND, opens = block.getButton().getDialog().getId(), ariaLabel = block.getButton().getAriaLabel())
    </div>
  @elseif(block.getWait() != null)
    <p class="mt-3 rounded-xl bg-canvas px-4 py-3">${block.getWait()}</p>
  @elseif(block.getNobody() != null)
    <p class="mt-3 rounded-xl bg-canvas px-4 py-3">${block.getNobody()}</p>
  @endif
</div>
