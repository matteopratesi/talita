@import org.springframework.security.web.csrf.CsrfToken
@import talita.registration.PaperPage
@import talita.ui.AlertVariant
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param PaperPage page
@param CsrfToken csrf
<%-- The paper form typed in by a teacher («Inserisci un'iscrizione») and the correction of a registration (criteria 25, 26; design C 7, 8). A correction never
     touches what a parent accepted online, nor the notes of an online registration: the notice says so. The notes of a paper registration have a page of their
     own, which shows them (and so writes a reading). --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = page.getTitle(), lead = page.getLead())

  @if(page.getIntro() != null)
    <p class="mb-4 max-w-prose">${page.getIntro()}</p>
  @endif
  @if(page.getNotice() != null)
    <div class="mb-4">
      @template.components.alert(variant = page.getNotice().getVariant(), content = @`${page.getNotice().getText()}`)
    </div>
  @endif
  @if(page.getAlert() != null)
    <div class="mb-4">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  <form id="modulo" method="post" action="${page.getFormAction()}" novalidate hx-disabled-elt="#modulo-invia" class="space-y-3">
    @template.components.csrf(csrf = csrf)
    @if(page.getInserting())
      <input type="hidden" name="convivenza" value="${page.getGatheringId()}" data-quota>
    @endif
    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="modulo-titolo">
      <h2 id="modulo-titolo" class="text-lg font-semibold">${page.getInserting() ? "Il modulo" : "I dati dell’iscrizione"}</h2>
      <div class="mt-4 space-y-5">
        <div class="space-y-1.5">
          <label for="telefono" class="block font-semibold">Telefono per i giorni della convivenza</label>
          <p id="telefono-aiuto" class="text-sm text-ink-muted">${page.getInserting() ? "Il numero scritto sul modulo. Lo vedono solo i maestri." : "Lo vedono solo i maestri."}</p>
          <input id="telefono" name="phone" type="tel" autocomplete="off" autocorrect="off" spellcheck="false" value="${page.getPhone()}" aria-describedby="${page.getPhoneError() != null ? "telefono-aiuto telefono-errore" : "telefono-aiuto"}" aria-invalid="${page.getPhoneError() != null ? "true" : null}" class="block min-h-12 w-full rounded-xl border border-line-strong bg-surface px-4 text-base text-ink focus:border-brand aria-[invalid=true]:border-danger aria-[invalid=true]:bg-danger-soft">
          @if(page.getPhoneError() != null)
            <p id="telefono-errore" class="flex items-start gap-1.5 text-sm font-medium text-danger">
              @template.components.icon(icon = Icon.CIRCLE_ALERT, classes = "mt-0.5 size-4 shrink-0")
              ${page.getPhoneError()}
            </p>
          @endif
        </div>
        @template.registration.childFields(child = page.getChild(), offer = page.getOffer(), quotaAction = page.getQuotaAction(), quotaFields = page.getQuotaFields(), withHealth = page.getInserting(), paper = true)
        @if(page.getPaper())
          <fieldset>
            <legend class="font-semibold">Manleva</legend>
            @template.registration.checkRow(id = "manleva", name = "paperReceived", label = "Manleva cartacea ricevuta", checked = page.getReceived(), hint = "Spunta quando hai in mano il modulo firmato. Se non c’è ancora, lascia vuoto: l’iscrizione resta «Da ricevere».")
          </fieldset>
        @endif
        @if(!page.getInserting())
          @if(!page.getPaper())
            @template.components.alert(variant = AlertVariant.INFO, content = @`L’affidamento accettato online non si cambia, e nemmeno le segnalazioni: le cambia solo il genitore.`)
          @elseif(page.getHasNotes())
            @template.components.alert(variant = AlertVariant.INFO, content = @`Ci sono segnalazioni. Per cambiarle mostrale prima dal dettaglio: la lettura resta registrata.`)
          @else
            <div>
              @template.components.linkButton(href = "/iscrizioni/" + page.getId() + "/correggi-segnalazioni", label = "Aggiungi le segnalazioni", variant = "text", icon = Icon.HEART_PULSE)
            </div>
          @endif
        @endif
      </div>
    </section>
    <div class="flex flex-col gap-3 pt-3 sm:flex-row-reverse sm:justify-start">
      @template.components.button(label = page.getSubmit(), busyLabel = page.getBusy(), id = "modulo-invia")
      @template.components.linkButton(href = page.getCancelHref(), label = "Annulla", variant = "secondary", width = "phone")
    </div>
  </form>
`)
