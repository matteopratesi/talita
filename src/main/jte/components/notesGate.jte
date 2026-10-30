@import talita.account.NotesGate
@import talita.ui.AlertVariant
@import talita.ui.Icon
@param NotesGate gate
@param boolean above = true
<%-- What a card of the notes on health says about the confirmation with the passkey (TALITA-15, criteria 37-46), in the two places where it says it. `above` (between the sentence «Sono dati
     sulla salute…» and the button): the notice «Serve la conferma.» after a tap that the server refused (409), with the button; the same without JavaScript, with no button; and, hidden, the
     two outcomes of the phone («Segnalazioni non aperte.», «La conferma non è riuscita.»), which `passkeys.js` shows (data-conferma-esito). Below the button (`above` false): the line that says
     that the phone will ask, or until when a confirmation holds, with the live region and the text of the waiting. The words are in the page; the script only shows and hides them.
     The card that holds this is `data-ambito data-esiti`. --%>
@if(above)
  @if(gate.getServe())
    <div class="mt-3">
      @template.components.alert(variant = AlertVariant.INFO, live = true, lead = "Serve la conferma.", content = @`Per aprire le segnalazioni il telefono ti chiede l’impronta, il volto o il codice di sblocco. Tocca di nuovo «Mostra le segnalazioni».`)
    </div>
  @elseif(gate.getServeNoJs())
    <div class="mt-3">
      @template.components.alert(variant = AlertVariant.INFO, live = true, lead = "Serve la conferma.", content = @`Per aprire le segnalazioni devi confermare con la passkey, e la conferma vuole JavaScript, che in questo browser è spento o non funziona. Le segnalazioni le può aprire un altro maestro.`)
    </div>
  @endif
  @if(gate.getConfirm())
    @template.components.outcomeAlert(variant = AlertVariant.INFO, lead = "Segnalazioni non aperte.", text = "La conferma non è arrivata: forse hai annullato, o su questo telefono non c’è una tua passkey. Puoi riprovare. Se non ci riesci, le può aprire un altro maestro.", confermaEsito = "segnalazioni-annullata")
    @template.components.outcomeAlert(variant = AlertVariant.WARNING, lead = "La conferma non è riuscita.", text = "Non è stato aperto niente. Riprova. Se non funziona, le segnalazioni le può aprire un altro maestro.", confermaEsito = "segnalazioni-rifiutata")
  @endif
@else
  @if(gate.getAsks())
    <p role="status" data-stato class="mt-3 flex items-start gap-2 text-sm text-ink-muted">
      @template.components.icon(icon = Icon.FINGERPRINT_PATTERN, classes = "mt-0.5 size-4 shrink-0")
      <span>Prima di mostrarle, il telefono ti chiede l’impronta, il volto o il codice di sblocco.</span>
    </p>
    <template data-attesa="">
      @template.components.icon(icon = Icon.FINGERPRINT_PATTERN, classes = "mt-0.5 size-4 shrink-0")
      <span>Conferma con l’impronta, il volto o il codice di sblocco.<span class="hidden [@media(pointer:fine)]:inline"> Dal computer, il browser può chiederti di usare il telefono.</span></span>
    </template>
  @elseif(gate.getValid())
    <p class="mt-3 flex items-start gap-2 text-sm text-ink-muted">
      @template.components.icon(icon = Icon.CIRCLE_CHECK, classes = "mt-0.5 size-4 shrink-0")
      <span>Hai già confermato con la passkey: fino alle ${gate.getUntil()} le apri senza confermare di nuovo.</span>
    </p>
  @elseif(gate.getServe())
    <p role="status" data-stato hidden class="mt-3 flex items-start gap-2 text-sm text-ink-muted"></p>
    <template data-attesa="">
      @template.components.icon(icon = Icon.FINGERPRINT_PATTERN, classes = "mt-0.5 size-4 shrink-0")
      <span>Conferma con l’impronta, il volto o il codice di sblocco.<span class="hidden [@media(pointer:fine)]:inline"> Dal computer, il browser può chiederti di usare il telefono.</span></span>
    </template>
  @endif
@endif
