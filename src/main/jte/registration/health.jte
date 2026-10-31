@import org.springframework.security.web.csrf.CsrfToken
@import talita.registration.HealthPage
@import talita.ui.AlertVariant
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param HealthPage page
@param CsrfToken csrf
<%-- The notes on health of a registration, in a page of their own (criteria 18, 19, 26; design B 6 and C 8): the one of the parent («Segnalazioni», with the
     registrations open and closed, where the button of danger takes them away) and the one where a teacher corrects the notes of a paper registration, which
     shows them and says that the reading was written. The text is really deleted by «Togli le segnalazioni»: that is why it is the button of danger. --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = page.getTitle(), lead = page.getLead())

  @if(page.getAlert() != null)
    <div class="mb-4">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif
  @if(page.getWarning() != null)
    <div class="mb-4">
      @template.components.alert(variant = AlertVariant.WARNING, content = @`${page.getWarning()}`)
    </div>
  @endif
  @if(page.getClosedNotice())
    <div class="mb-4">
      @template.components.alert(variant = AlertVariant.INFO, content = @`Le iscrizioni sono chiuse, ma le segnalazioni puoi cambiarle fino alla fine della convivenza.`)
    </div>
  @endif

  @if(page.getIntro())
    <p class="max-w-prose">Allergie, intolleranze e farmaci. Sono facoltative: se non le scrivi, l’iscrizione vale lo stesso, ma i maestri non le sapranno.</p>
    <div class="mt-3">
      @template.components.alert(variant = AlertVariant.INFO, content = @`Le leggono solo i maestri, quando serve, e ogni lettura resta registrata. Le cancelliamo un mese dopo la fine della convivenza, e le puoi togliere quando vuoi.`)
    </div>
  @endif

  @if(page.getConfirm() != null)
    <%-- The correction of a paper registration that has notes, for a teacher who has a passkey and has not confirmed (TALITA-15, criteria 37, 39, 43): the page has no fields and no warning, because
         nothing was read and nothing is registered. The buttons are shown by passkeys.js where the browser can use passkeys; without JavaScript the sentence that says it wants it is shown. --%>
    <section id="segnalazioni" data-ambito data-esiti class="mt-4 rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="serve-titolo">
      <h2 id="serve-titolo" class="text-lg font-semibold wrap-anywhere">Serve la conferma</h2>
      <%-- The page has no form, and the script sends the CSRF code of the page with its two requests: the code is here, in a field that nobody sends. --%>
      @template.components.csrf(csrf = csrf)
      <div data-passkey hidden>
        <p class="mt-3">In questa pagina compaiono le segnalazioni di ${page.getFirstName()}, che sono dati sulla salute. Prima di aprirla, il telefono ti chiede l’impronta, il volto o il codice di sblocco.</p>
        @template.components.outcomeAlert(variant = AlertVariant.INFO, lead = "Pagina non aperta.", text = "La conferma non è arrivata: forse hai annullato, o su questo telefono non c’è una tua passkey. Puoi riprovare. Se non ci riesci, la pagina la può aprire un altro maestro.", confermaEsito = "pagina-annullata")
        @template.components.outcomeAlert(variant = AlertVariant.WARNING, lead = "La conferma non è riuscita.", text = "Non è stato aperto niente. Riprova. Se non funziona, la pagina la può aprire un altro maestro.", confermaEsito = "pagina-rifiutata")
        <div class="mt-4 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
          @template.components.button(label = "Conferma con la passkey", busyLabel = "In attesa della conferma…", icon = Icon.FINGERPRINT_PATTERN, type = "button", azione = "conferma-pagina", conferma = "pagina")
          @template.components.linkButton(href = page.getCancelHref(), label = "Annulla", variant = "secondary", width = "phone")
        </div>
        <p aria-live="polite" aria-atomic="true" data-stato hidden class="mt-3 flex items-start gap-2 text-sm text-ink-muted"></p>
        <template data-attesa="">
          @template.components.icon(icon = Icon.FINGERPRINT_PATTERN, classes = "mt-0.5 size-4 shrink-0")
          <span>Conferma con l’impronta, il volto o il codice di sblocco.<span class="hidden [@media(pointer:fine)]:inline"> Dal computer, il browser può chiederti di usare il telefono.</span></span>
        </template>
        <p class="mt-3 text-sm text-ink-muted">Dopo la conferma la pagina si apre con le segnalazioni. La lettura resta scritta a tuo nome.</p>
      </div>
      <div data-passkey-no>
        <div class="mt-3">
          @template.components.alert(variant = AlertVariant.INFO, content = @`<strong>Serve la conferma.</strong> Per aprire questa pagina devi confermare con la passkey, e la conferma vuole JavaScript, che in questo browser è spento o non funziona. Un altro maestro può aprire la pagina al posto tuo.`)
        </div>
        <div class="-ml-3 mt-3">
          @template.components.linkButton(href = page.getCancelHref(), label = "Torna all’iscrizione", variant = "text")
        </div>
      </div>
    </section>
  @else
  <form id="segnalazioni" method="post" action="${page.getFormAction()}" novalidate hx-disabled-elt="#segnalazioni-salva" class="mt-4 space-y-3">
    @template.components.csrf(csrf = csrf)
    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="segnalazioni-titolo">
      <h2 id="segnalazioni-titolo" class="text-lg font-semibold wrap-anywhere">Segnalazioni di ${page.getFirstName()}</h2>
      <div class="mt-4 space-y-5">
        @template.registration.noteField(id = "b0-allergie", name = "allergies", label = "Allergie", hint = "Per esempio: arachidi, polline.", value = page.getChild().getAllergies(), error = page.getChild().getErrors().get("allergies"))
        @template.registration.noteField(id = "b0-intolleranze", name = "intolerances", label = "Intolleranze", hint = "Per esempio: lattosio.", value = page.getChild().getIntolerances(), error = page.getChild().getErrors().get("intolerances"))
        @template.registration.noteField(id = "b0-farmaci", name = "medications", label = "Farmaci", hint = "Nome e quando serve, per esempio: antistaminico in caso di reazione.", value = page.getChild().getMedications(), error = page.getChild().getErrors().get("medications"))
        @template.registration.checkRow(
          id = "b0-consenso",
          name = "healthConsent",
          label = page.getPaper() ? "Il genitore ha dato il consenso sul modulo cartaceo" : "Acconsento che i maestri della convivenza leggano queste informazioni sulla salute del bambino.",
          checked = page.getChild().getHealthConsent(),
          error = page.getChild().getErrors().get("consent"),
          hint = page.getPaper() ? "Spunta la casella solo se sul modulo il genitore ha firmato il consenso alle segnalazioni. Senza consenso non le salviamo." : "Sono facoltative. Se non le scrivi, l’iscrizione vale lo stesso, ma i maestri non sapranno di allergie, intolleranze o farmaci. Puoi toglierle quando vuoi dalla pagina «Iscrizioni».")
      </div>
    </section>
    @if(page.getConsentAt() != null)
      <p class="text-sm text-ink-muted">${page.getConsentAt()}</p>
    @endif
    <div class="flex flex-col gap-3 pt-3 sm:flex-row-reverse sm:justify-start">
      @template.components.button(label = "Salva le segnalazioni", busyLabel = "Salvataggio…", id = "segnalazioni-salva")
      @template.components.linkButton(href = page.getCancelHref(), label = "Annulla", variant = "secondary", width = "phone")
      @if(page.getExists() && page.getTogli() != null)
        @template.components.linkButton(href = "/iscrizioni/" + page.getId() + "/segnalazioni/togli", label = "Togli le segnalazioni", variant = "danger", icon = Icon.TRASH_2, opens = page.getTogli().getId())
      @endif
    </div>
  </form>

  @endif

  @if(page.getConfirm() == null && page.getExists() && page.getTogli() != null)
    @template.components.confirmDialog(dialog = page.getTogli(), csrf = csrf)
  @endif
`)
