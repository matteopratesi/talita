@import org.springframework.security.web.csrf.CsrfToken
@import talita.account.DevicesBlock
@import talita.api.TokenBlock
@import talita.api.TokenState
@import talita.ui.AlertVariant
@import talita.ui.Icon
@param TokenBlock block
@param boolean oob = false
@param DevicesBlock devices = null
@param CsrfToken csrf
<%-- The inside of the card «Token per le API» (TALITA-21), which every action of it replaces as a whole (htmx: hx-target="#token-contenuto", hx-swap="outerHTML"): the notice, what the token is for, the token or the
     way to make one, and the dialogs. The answer that makes a token (`CREATED`) is the only place where its value is: as text to select, never in an address or an attribute, with `hx-history="false"` so that the
     browser does not keep it (and the server says `Cache-Control: no-store`). The outcomes of what happens on the phone (cancelled, refused) are in the block, hidden, with their words: `passkeys.js` only shows one
     of them. `oob`: the block comes out of order with another one (after «Esci da tutti gli altri dispositivi»), and replaces this one by its id. --%>
<div id="token-contenuto" data-esiti hx-history="${block.getState() == TokenState.CREATED ? "false" : null}" hx-swap-oob="${oob ? "true" : null}">
  @if(block.getAlert() != null)
    <div class="mt-3">
      @template.components.alert(variant = block.getAlert().getVariant(), live = block.getFocus(), lead = block.getAlert().getLead(), leadOnly = block.getAlert().getText().isEmpty(), actionLabel = block.getAlert().getActionLabel(), actionHref = block.getAlert().getActionHref(), content = @`${block.getAlert().getText()}`)
    </div>
  @endif

  @if(block.getState() == TokenState.CREATED)
    <div class="mt-4">
      <p id="riga-etichetta" class="mb-1.5 text-sm text-ink-muted">La riga da salvare</p>
      <p id="token-riga" aria-labelledby="riga-etichetta" class="rounded-xl bg-surface-muted px-4 py-3 font-mono text-sm break-all select-all">${block.getLine()}</p>
      <div class="mt-3" data-copia-ambito>
        <button type="button" hidden data-azione="copia" data-copia="token-riga" class="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-brand px-5 text-base font-semibold text-white transition-colors duration-150 select-none hover:bg-brand-strong active:bg-brand-strong sm:w-auto">
          @template.components.icon(icon = Icon.COPY, classes = "size-5")
          <span data-copia-normale>Copia la riga</span><span hidden data-copia-fatto>Riga copiata</span>
        </button>
        <p role="status" class="sr-only" data-copia-stato data-copia-annuncio="Riga copiata."></p>
        <p hidden data-copia-errore class="mt-2 text-sm text-ink-muted">Non riesco a copiare la riga. Tienila premuta per copiarla.</p>
      </div>
      <div class="mt-4">
        @template.components.alert(variant = AlertVariant.WARNING, lead = "Non incollarla in chat, email o documenti.", content = @`Deve restare in un file solo tuo. Chi ha questa riga può fare le modifiche al posto tuo.`)
      </div>
      <h3 id="come-salvarla" class="mt-6 text-base font-semibold">Come salvarla sul tuo computer</h3>
      <ol aria-labelledby="come-salvarla" class="mt-3 list-decimal space-y-4 pl-6 marker:font-semibold">
        <li>
          <p>Apri il terminale, incolla questo comando e premi Invio. Prepara una cartella solo tua e aspetta il testo: vai avanti solo se il comando è partito senza errori.</p>
          <p id="token-comando" class="mt-2 rounded-xl bg-surface-muted px-4 py-3 font-mono text-sm break-all select-all">${block.getCommand()}</p>
          <div class="mt-3" data-copia-ambito>
            <button type="button" hidden data-azione="copia" data-copia="token-comando" class="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl border border-line-strong bg-surface px-5 text-base font-semibold text-ink transition-colors duration-150 select-none hover:bg-surface-muted active:bg-surface-muted sm:w-auto">
              @template.components.icon(icon = Icon.COPY, classes = "size-5")
              <span data-copia-normale>Copia il comando</span><span hidden data-copia-fatto>Comando copiato</span>
            </button>
            <p role="status" class="sr-only" data-copia-stato data-copia-annuncio="Comando copiato."></p>
            <p hidden data-copia-errore class="mt-2 text-sm text-ink-muted">Non riesco a copiare il comando. Tienilo premuto per copiarlo.</p>
          </div>
        </li>
        <li><p>Incolla la riga di prima, premi Invio e poi i tasti Ctrl e D. Il file è salvato e nella cronologia del terminale non resta niente.</p></li>
        <li><p>Torna qui e tocca «Fatto». Poi il programma può usare il file.</p></li>
      </ol>
      <p class="mt-4 text-sm text-ink-muted">Vale fino al ${block.getUntil()}. Se pensi che la riga sia finita dove non doveva, torna qui e tocca «Revoca». I passaggi sono anche nel file <code>docs/04-ambiente.md</code> del progetto.</p>
      <div class="mt-4">
        <a href="/profilo#token" hx-get="/profilo/token" hx-target="#token-contenuto" hx-swap="outerHTML" class="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-brand px-5 text-base font-semibold text-white transition-colors duration-150 select-none hover:bg-brand-strong active:bg-brand-strong sm:w-auto">Fatto</a>
      </div>
    </div>
  @else
    <div class="mt-3 space-y-2">
      <p>Il token è una chiave che lascia lavorare un programma sulla configurazione di Talità, senza passare dalle pagine dell’app: per esempio aggiungere una camera o cambiare una quota.</p>
    </div>
    <div class="mt-4 grid gap-4 sm:grid-cols-2">
      <div>
        <h3 class="text-base font-semibold">Cosa può fare</h3>
        <ul class="mt-1 list-disc space-y-1 pl-5 text-ink-muted">
          <li>Cambiare titolo, date, quote, informazioni e «cosa portare» della convivenza.</li>
          <li>Aggiungere, cambiare e togliere strutture, camere e moduli.</li>
        </ul>
      </div>
      <div>
        <h3 class="text-base font-semibold">Cosa non può fare, mai</h3>
        <ul class="mt-1 list-disc space-y-1 pl-5 text-ink-muted">
          <li>Leggere o scrivere i dati di bambini, genitori e maestri.</li>
          <li>Aprire o chiudere le iscrizioni, o mandare i promemoria: lo fai tu dall’app.</li>
        </ul>
      </div>
    </div>

    @template.components.outcomeAlert(variant = AlertVariant.INFO, lead = "Token non creato.", text = "Hai annullato la conferma, quindi non è stato creato nessun token. Puoi riprovare quando vuoi.", confermaEsito = "token-annullata")
    @template.components.outcomeAlert(variant = AlertVariant.WARNING, lead = "Token non creato.", text = "Il telefono non ha riconosciuto la passkey, quindi non è stato creato nessun token. Riprova. Se non hai più con te nessuna passkey, scrivi al titolare: la può togliere lui.", confermaEsito = "token-rifiutata")

    @if(block.getState() == TokenState.NONE || block.getState() == TokenState.EXPIRED)
      @if(block.getState() == TokenState.NONE)
        <p class="mt-4 text-sm text-ink-muted">Non hai nessun token. Se ne crei uno, vale 30 giorni e lo puoi revocare quando vuoi. Ne puoi avere uno solo.</p>
      @else
        <ul class="mt-4">
          <li class="py-1">
            <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
              <span aria-hidden="true" class="grid size-10 shrink-0 place-items-center rounded-xl bg-brand-soft text-brand">
                @template.components.icon(icon = Icon.BOT, classes = "size-6")
              </span>
              <span class="min-w-0 flex-[1_1_11rem]">
                <span class="flex flex-wrap items-center gap-x-2 gap-y-1">
                  <span class="font-semibold wrap-anywhere">${block.getTitle()}</span>
                  @template.components.badge(label = block.getBadge(), tone = block.getBadgeTone())
                </span>
                <span class="block text-sm text-ink-muted">${block.getMeta()}</span>
              </span>
            </div>
          </li>
        </ul>
      @endif
      <div data-ambito class="mt-4">
        <form method="post" action="/profilo/token" hx-post="/profilo/token" hx-target="#token-contenuto" hx-swap="outerHTML" hx-disabled-elt="find button[type=submit]" data-conferma="${block.getConfirmFirst() ? "token" : null}" class="contents">
          @template.components.csrf(csrf = csrf)
          <input type="hidden" name="azione" value="crea">
          @template.components.button(label = block.getState() == TokenState.NONE ? "Crea un token" : "Crea un token nuovo", busyLabel = "Creazione…", variant = "secondary", icon = Icon.BOT, width = "full")
        </form>
        @if(block.getConfirmFirst())
          <p aria-live="polite" aria-atomic="true" data-stato class="mt-2 flex items-start gap-2 text-sm text-ink-muted">
            @template.components.icon(icon = Icon.FINGERPRINT_PATTERN, classes = "mt-0.5 size-5 shrink-0")
            <span>Prima il telefono ti chiede l’impronta, il volto o il codice di sblocco.<span class="hidden [@media(pointer:fine)]:inline"> Dal computer, il browser può chiederti di usare il telefono.</span></span>
          </p>
          <template data-attesa=""><strong>In attesa della conferma…</strong> Conferma con la passkey: impronta, volto o codice di sblocco.<span class="hidden [@media(pointer:fine)]:inline"> Dal computer, il browser può chiederti di usare il telefono.</span></template>
        @endif
      </div>
    @else
      <ul class="mt-4">
        <li class="py-1">
          <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
            <span aria-hidden="true" class="grid size-10 shrink-0 place-items-center rounded-xl bg-brand-soft text-brand">
              @template.components.icon(icon = Icon.BOT, classes = "size-6")
            </span>
            <span class="min-w-0 flex-[1_1_11rem]">
              <span class="flex flex-wrap items-center gap-x-2 gap-y-1">
                <span class="font-semibold wrap-anywhere">${block.getTitle()}</span>
                @template.components.badge(label = block.getBadge(), tone = block.getBadgeTone())
              </span>
              <span class="block text-sm text-ink-muted">${block.getMeta()}</span>
            </span>
            <form method="post" action="/profilo/token/revoca/conferma" class="-ml-3 sm:ml-0 sm:shrink-0">
              @template.components.csrf(csrf = csrf)
              <input type="hidden" name="${block.getRevoke().getField()}" value="${block.getRevoke().getValue()}">
              @template.components.button(label = "Revoca", busyLabel = "Revoca…", variant = "text", width = "auto", opens = block.getRevoke().getId(), ariaLabel = block.getRevokeAria())
            </form>
          </div>
        </li>
      </ul>
      <form method="post" action="/profilo/token/nuovo/conferma" class="mt-4">
        @template.components.csrf(csrf = csrf)
        <input type="hidden" name="${block.getRenew().getField()}" value="${block.getRenew().getValue()}">
        @template.components.button(label = "Crea un token nuovo", busyLabel = "Creazione…", variant = "secondary", icon = Icon.BOT, width = "full", opens = block.getRenew().getId())
      </form>
      <p class="mt-2 text-sm text-ink-muted">Sostituisce questo: quello di adesso smette di funzionare subito.</p>
      @template.components.confirmDialog(dialog = block.getRevoke(), csrf = csrf)
      @template.components.confirmDialog(dialog = block.getRenew(), csrf = csrf)
    @endif
  @endif
</div>
@if(devices != null)
  <%-- The devices, out of order (hx-swap-oob): the dialog of «Esci da tutti gli altri dispositivi» and the line under it say of the token only if there is one now. No notice in it: one focus only. --%>
  @template.profile.devicesBlock(block = devices, oob = true, csrf = csrf)
@endif
