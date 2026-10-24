@import talita.registration.ChildView
@import talita.ui.Icon
@param ChildView child
@param boolean paper = false
<%-- «Allergie, intolleranze, farmaci» (criterion 18; design A 1/2, 5): a block that opens, closed when there is nothing in it, so that the form stays short for
     who has nothing to write. The box of the consent is here, next to the notes, and apart from the custody: it is not ticked, and without it nothing is saved.
     `paper`: the form of a teacher who types in a paper form, where the consent is the one that the parent signed on the paper. --%>
!{String p = "children[" + child.getIndex() + "].";
String id = "b" + child.getIndex();
String consentError = child.getErrors().get("consent");}
<details class="group rounded-2xl border border-line-strong bg-surface" open="${child.getHealthOpen()}">
  <summary class="flex min-h-14 cursor-pointer list-none items-center gap-3 px-4 py-2 [&::-webkit-details-marker]:hidden">
    @template.components.icon(icon = Icon.HEART_PULSE, classes = "size-6 shrink-0 text-brand")
    <span class="min-w-0 flex-1"><span class="block font-semibold">Allergie, intolleranze, farmaci</span><span class="block text-sm text-ink-muted">${child.getHealthWritten() ? "Facoltativo · scritte" : "Facoltativo"}</span></span>
    @template.components.icon(icon = Icon.CHEVRON_DOWN, classes = "size-5 shrink-0 text-ink-muted transition-transform duration-150 group-open:rotate-180")
  </summary>
  <div class="space-y-5 border-t border-line px-4 pt-4 pb-4">
    @if(paper)
      <p class="text-sm text-ink-muted">Scrivi qui allergie, intolleranze e farmaci come sono sul modulo. Le leggono solo i maestri, e ogni lettura resta registrata.</p>
    @else
      <p class="text-sm text-ink-muted">Se il bambino ha allergie, intolleranze o prende farmaci, scrivilo qui. I maestri lo leggono solo se serve, e ogni lettura resta registrata. Le cancelliamo un mese dopo la fine della convivenza.</p>
    @endif
    @template.registration.noteField(id = id + "-allergie", name = p + "allergies", label = "Allergie", hint = "Per esempio: arachidi, polline.", value = child.getAllergies(), error = child.getErrors().get("allergies"))
    @template.registration.noteField(id = id + "-intolleranze", name = p + "intolerances", label = "Intolleranze", hint = "Per esempio: lattosio.", value = child.getIntolerances(), error = child.getErrors().get("intolerances"))
    @template.registration.noteField(id = id + "-farmaci", name = p + "medications", label = "Farmaci", hint = "Nome e quando serve, per esempio: antistaminico in caso di reazione.", value = child.getMedications(), error = child.getErrors().get("medications"))
    @template.registration.checkRow(
      id = id + "-consenso",
      name = p + "healthConsent",
      label = paper ? "Il genitore ha dato il consenso sul modulo cartaceo" : "Acconsento che i maestri della convivenza leggano queste informazioni sulla salute del bambino.",
      checked = child.getHealthConsent(),
      error = consentError,
      hint = paper ? "Spunta la casella solo se sul modulo il genitore ha firmato il consenso alle segnalazioni. Senza consenso non le salviamo." : "Sono facoltative. Se non le scrivi, l’iscrizione vale lo stesso, ma i maestri non sapranno di allergie, intolleranze o farmaci. Puoi toglierle quando vuoi dalla pagina «Iscrizioni».")
  </div>
</details>
