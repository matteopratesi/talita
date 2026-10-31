@import talita.ui.Icon
@param boolean compact = false
<%-- The invitation to a teacher with no passkey (TALITA-15, criterion 46): a discreet line, with no background and no color of notice, with the neutral square and the icon. It does not block (the button
     above it does what it always did), it **does not close** (closing would be a datum more) and it is not there for a person who has a passkey, for a parent, or where the browser cannot use passkeys
     (`data-passkey` is hidden until `passkeys.js` sees the support; the `hidden` is on the container, because a `flex` on the same element would cancel it). In the card of a child of the arrivals
     (`compact`) it has a text button that leads to the card «Passkey» of the profile; in the profile the button is the one of the card, just below. --%>
<div data-passkey hidden class="mt-4 border-t border-line pt-4">
  <div class="flex items-start gap-3">
    <span aria-hidden="true" class="grid size-10 shrink-0 place-items-center rounded-xl bg-surface-muted text-ink-muted">
      @template.components.icon(icon = Icon.FINGERPRINT_PATTERN, classes = "size-6")
    </span>
    <div class="min-w-0">
      <p class="font-semibold wrap-anywhere">Proteggi le segnalazioni con una passkey.</p>
      @if(compact)
        <p class="mt-1 text-sm text-ink-muted">Prima di aprirle, il telefono ti chiede l’impronta, il volto o il codice di sblocco.</p>
        <div class="-ml-3 mt-1">
          @template.components.linkButton(href = "/profilo#passkey", label = "Aggiungi una passkey", variant = "text")
        </div>
      @else
        <p class="mt-1 text-sm text-ink-muted">Prima di aprirle, il telefono ti chiede l’impronta, il volto o il codice di sblocco. Per iniziare, aggiungi una passkey qui sotto.</p>
      @endif
    </div>
  </div>
</div>
