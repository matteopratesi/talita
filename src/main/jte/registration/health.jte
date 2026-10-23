@import org.springframework.security.web.csrf.CsrfToken
@import talita.registration.HealthPage
@import talita.ui.AlertVariant
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param HealthPage page
@param CsrfToken csrf
<%-- The notes on health of a registration for the parent (criteria 18, 19; design B, 6): a page of its own, so that data on health are written and taken away in
     one place, with the registrations open and closed. «Togli le segnalazioni» is the button of danger: the text is really deleted. --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Segnalazioni", lead = page.getLead())

  @if(page.getAlert() != null)
    <div class="mb-4">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif
  @if(page.getClosedNotice())
    <div class="mb-4">
      @template.components.alert(variant = AlertVariant.INFO, content = @`Le iscrizioni sono chiuse, ma le segnalazioni puoi cambiarle fino alla fine della convivenza.`)
    </div>
  @endif

  <p class="max-w-prose">Allergie, intolleranze e farmaci. Sono facoltative: se non le scrivi, l’iscrizione vale lo stesso, ma i maestri non le sapranno.</p>
  <div class="mt-3">
    @template.components.alert(variant = AlertVariant.INFO, content = @`Le leggono solo i maestri, quando serve, e ogni lettura resta registrata. Le cancelliamo un mese dopo la fine della convivenza, e le puoi togliere quando vuoi.`)
  </div>

  <form id="segnalazioni" method="post" action="${"/iscrizioni/" + page.getId() + "/segnalazioni"}" novalidate hx-disabled-elt="#segnalazioni-salva" class="mt-4 space-y-3">
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
          label = "Acconsento che i maestri della convivenza leggano queste informazioni sulla salute del bambino.",
          checked = page.getChild().getHealthConsent(),
          error = page.getChild().getErrors().get("consent"),
          hint = "Sono facoltative. Se non le scrivi, l’iscrizione vale lo stesso, ma i maestri non sapranno di allergie, intolleranze o farmaci. Puoi toglierle quando vuoi dalla pagina «Iscrizioni».")
      </div>
    </section>
    @if(page.getConsentAt() != null)
      <p class="text-sm text-ink-muted">${page.getConsentAt()}</p>
    @endif
    <div class="flex flex-col gap-3 pt-3 sm:flex-row-reverse sm:justify-start">
      @template.components.button(label = "Salva le segnalazioni", busyLabel = "Salvataggio…", id = "segnalazioni-salva")
      @template.components.linkButton(href = "/iscrizioni", label = "Annulla", variant = "secondary", width = "phone")
      @if(page.getExists())
        @template.components.linkButton(href = "/iscrizioni/" + page.getId() + "/segnalazioni/togli", label = "Togli le segnalazioni", variant = "danger", icon = Icon.TRASH_2, opens = page.getTogli().getId())
      @endif
    </div>
  </form>

  @if(page.getExists())
    @template.components.confirmDialog(dialog = page.getTogli(), csrf = csrf)
  @endif
`)
