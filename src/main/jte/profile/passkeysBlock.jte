@import org.springframework.security.web.csrf.CsrfToken
@import talita.account.PasskeyLine
@import talita.account.PasskeysBlock
@import talita.ui.AlertVariant
@import talita.ui.Icon
@param PasskeysBlock block
@param CsrfToken csrf
<%-- The inside of the card «Passkey» (TALITA-15, criteria 19, 20, 25, 28, 45, 46), which every action of it replaces as a whole (htmx: hx-target="#passkey-contenuto", hx-swap="outerHTML"): the notice,
     the explanation for who is not used to them, the rows, the button, and what to do if the phone changes or is lost. The outcomes of what happens on the phone (cancelled, refused, there is a
     passkey on this phone already) are in the block, hidden, with their words: `passkeys.js` only shows one of them. Without JavaScript, or in a browser that cannot use passkeys, the button is
     not there and a sentence says that the link works always (`data-passkey` is shown by the script, `data-passkey-no` is hidden by it). A row says only the day and «Sincronizzata» or «Solo su un
     dispositivo»: never a name, a model of phone or a last use. --%>
<div id="passkey-contenuto" data-esiti>
  @if(block.getAlert() != null)
    <div class="mt-3">
      @template.components.alert(variant = block.getAlert().getVariant(), live = true, lead = block.getAlert().getLead(), leadOnly = block.getAlert().getText().isEmpty(), actionLabel = block.getAlert().getActionLabel(), actionHref = block.getAlert().getActionHref(), content = @`${block.getAlert().getText()}`)
    </div>
  @endif

  <div class="mt-3 space-y-2">
    <p>Con una passkey entri in Talità con l’impronta, il volto o il codice di sblocco del telefono, senza aspettare la mail.</p>
    <p class="text-sm text-ink-muted">Non la scrivi e non la ricordi, e non la può usare nessun altro. Il link resta sempre: serve per il primo accesso, per un telefono nuovo e se la passkey non c’è più.</p>
    @if(block.getRememberLine() != null)
      <p class="text-sm text-ink-muted">${block.getRememberLine()}</p>
    @endif
  </div>

  @if(block.getInvite())
    @template.components.passkeyInvite()
  @endif

  @template.components.outcomeAlert(variant = AlertVariant.INFO, lead = "Nessuna passkey aggiunta.", text = "Hai annullato, quindi non è cambiato niente. Puoi riprovare quando vuoi.", passkeyEsito = "annullata")
  @template.components.outcomeAlert(variant = AlertVariant.INFO, lead = "Su questo dispositivo c’è già una passkey di Talità.", text = "Non serve aggiungerne un’altra: la usi da «Accedi».", passkeyEsito = "gia-presente")
  @template.components.outcomeAlert(variant = AlertVariant.WARNING, lead = "Non è stata aggiunta nessuna passkey.", text = "Ne hai già 10, il massimo. Togline una e riprova.", passkeyEsito = "piena")
  @if(block.getTeacher())
    @template.components.outcomeAlert(variant = AlertVariant.INFO, lead = "Serve la conferma.", text = "Per aggiungere una passkey il telefono ti chiede prima di confermare con una che hai già. Tocca di nuovo «Aggiungi una passkey su questo telefono».", passkeyEsito = "serve-conferma")
    @template.components.outcomeAlert(variant = AlertVariant.INFO, lead = "Nessuna passkey aggiunta.", text = "La conferma non è arrivata, quindi non è cambiato niente. Puoi riprovare. Se non hai più con te nessuna passkey, scrivi al titolare: le può togliere lui.", confermaEsito = "aggiungi-annullata")
    @template.components.outcomeAlert(variant = AlertVariant.INFO, lead = "Passkey non tolta.", text = "La conferma non è arrivata, quindi non è cambiato niente. Puoi riprovare. Se non hai più con te nessuna passkey, scrivi al titolare: la può togliere lui.", confermaEsito = "togli-annullata")
    @template.components.outcomeAlert(variant = AlertVariant.WARNING, lead = "La conferma non è riuscita.", text = "Non è cambiato niente. Riprova. Se continua a non funzionare, scrivi al titolare.", confermaEsito = "aggiungi-rifiutata")
    @template.components.outcomeAlert(variant = AlertVariant.WARNING, lead = "La conferma non è riuscita.", text = "Non è cambiato niente. Riprova. Se continua a non funzionare, scrivi al titolare.", confermaEsito = "togli-rifiutata")
  @endif

  @if(block.getLines().isEmpty())
    <p class="mt-4 text-ink-muted">Non hai ancora aggiunto nessuna passkey.</p>
  @else
    <ul class="mt-2">
      @for(PasskeyLine line : block.getLines())
        <li class="border-t border-line py-3.5 first:border-t-0 first:pt-1">
          <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
            <span aria-hidden="true" class="grid size-10 shrink-0 place-items-center rounded-xl bg-brand-soft text-brand">
              @template.components.icon(icon = Icon.KEY_ROUND, classes = "size-6")
            </span>
            <span class="min-w-0 flex-[1_1_11rem]">
              <span class="flex flex-wrap items-center gap-x-2 gap-y-1">
                <span class="font-semibold wrap-anywhere">${line.getTitle()}</span>
                @template.components.badge(label = line.getLabel(), tone = line.getSynced() ? "info" : "neutral")
              </span>
              <span class="block text-sm text-ink-muted">${line.getMeta()}</span>
            </span>
            <form method="post" action="/profilo/passkey/togli/conferma" class="-ml-3 sm:ml-0 sm:shrink-0">
              @template.components.csrf(csrf = csrf)
              <input type="hidden" name="${line.getRemoval().getField()}" value="${line.getRemoval().getValue()}">
              @template.components.button(label = "Togli", busyLabel = "Rimozione…", variant = "text", width = "auto", opens = line.getRemoval().getId(), ariaLabel = line.getAriaLabel())
            </form>
          </div>
        </li>
      @endfor
    </ul>
  @endif

  @if(block.getFull())
    <p class="mt-4 text-sm text-ink-muted">Hai già 10 passkey, il massimo. Per aggiungerne un’altra, togline una.</p>
  @else
    <div data-passkey hidden data-ambito class="mt-4">
      @template.components.button(label = "Aggiungi una passkey su questo telefono", busyLabel = "In attesa della conferma…", variant = "secondary", icon = Icon.FINGERPRINT_PATTERN, width = "full", type = "button", azione = "aggiungi-passkey", conferma = block.getConfirmFirst() ? "aggiungi" : null)
      @if(block.getConfirmFirst())
        <p class="mt-2 text-sm text-ink-muted">Prima il telefono ti chiede di confermare con una passkey che hai già. Poi crea quella nuova.</p>
      @endif
      <p aria-live="polite" aria-atomic="true" data-stato hidden class="mt-3 text-sm text-ink-muted"></p>
      <template data-attesa=""><strong>In attesa della conferma…</strong> Conferma con l’impronta, il volto o il codice di sblocco.</template>
      <template data-attesa="primo"><strong>In attesa della conferma…</strong> Prima conferma con una passkey che hai già: impronta, volto o codice di sblocco.</template>
      <template data-attesa="secondo"><strong>In attesa della conferma…</strong> Ora conferma per creare la passkey nuova.</template>
    </div>
    <p data-passkey-no class="mt-4 text-ink-muted">Questo browser non sa usare le passkey. Per entrare, continua con il link: funziona sempre.</p>
  @endif

  <details class="group mt-4 rounded-2xl border border-line-strong bg-surface">
    <summary class="flex min-h-14 cursor-pointer list-none items-center gap-3 px-4 py-2 [&::-webkit-details-marker]:hidden">
      @template.components.icon(icon = Icon.KEY_ROUND, classes = "size-6 shrink-0 text-brand")
      <span class="min-w-0 flex-1"><span class="block font-semibold">Se cambi o perdi il telefono</span><span class="block text-sm text-ink-muted">Cosa succede alle passkey</span></span>
      @template.components.icon(icon = Icon.CHEVRON_DOWN, classes = "size-5 shrink-0 text-ink-muted transition-transform duration-150 group-open:rotate-180")
    </summary>
    <div class="space-y-2 border-t border-line px-4 pt-4 pb-4">
      <p>Se il tuo telefono salva le passkey nel tuo account Apple o Google, le ritrovi sul telefono nuovo. Talità non manda niente ad Apple o Google: le passkey le sincronizza il tuo telefono, in modo cifrato.</p>
      <p>Se la passkey sta solo su un dispositivo, sul telefono nuovo entri con un link e ne aggiungi una nuova.</p>
      <p>Se perdi il telefono, da un altro dispositivo entra con un link, tocca «Esci da tutti gli altri dispositivi» e togli qui la passkey del telefono perso.@if(block.getProtects()) Il telefono ti chiede di confermare con un’altra passkey che hai ancora.@endif</p>
      @if(block.getProtects())
        <p>Se la passkey persa era l’unica che avevi, non puoi toglierla da solo: scrivi al <a href="/informativa#titolare" hx-boost="false" class="font-semibold text-brand underline underline-offset-2">titolare</a> (l’indirizzo è nell’informativa), che la toglie per te. Intanto le segnalazioni le può aprire un altro maestro.</p>
      @endif
    </div>
  </details>

  @for(PasskeyLine line : block.getLines())
    @template.components.confirmDialog(dialog = line.getRemoval(), csrf = csrf)
  @endfor
</div>
