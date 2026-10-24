@import org.springframework.security.web.csrf.CsrfToken
@import talita.registration.RecapChild
@import talita.registration.SecondStepPage
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param SecondStepPage page
@param CsrfToken csrf
<%-- The second step (criteria 6-10, 33, 35; design A 2/2): the recap, the short notice, the text of the custody, three boxes. Nothing has been saved: the data
     of the first step are in the hidden fields, and the server checks them all again when «Accetta e iscrivi» comes. The back button of the phone leaves the
     registration, and the page says so in the introduction. --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Controlla e accetta", lead = page.getTitle() + " · " + page.getDates())
  @template.registration.steps(current = 2, label = "Controlla e accetta")

  @if(page.getAlert() != null)
    <div class="mt-4">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  <p class="mt-4 max-w-prose">Rileggi i dati, leggi il testo dell’affidamento e spunta le caselle. Per correggere tocca «Modifica i dati»: il tasto indietro del telefono esce dall’iscrizione e quello che hai scritto si perde. Quando tocchi «Accetta e iscrivi» l’iscrizione è fatta.</p>

  <form id="conferma" method="post" action="${page.getConfirmAction()}" novalidate hx-disabled-elt="#conferma-invia" class="mt-5 space-y-3">
    <%-- The default button of the form (Enter on a box): «Accetta e iscrivi», not «Modifica i dati», which comes first in the page. See firstStep.jte. --%>
    <button type="submit" tabindex="-1" aria-hidden="true" class="sr-only"></button>
    @template.components.csrf(csrf = csrf)
    @for(kotlin.Pair<String, String> field : page.getHidden())
      <input type="hidden" name="${field.getFirst()}" value="${field.getSecond()}">
    @endfor
    <input type="hidden" name="textHash" value="${page.getHash()}">

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="riepilogo">
      <div class="flex items-center justify-between gap-3">
        <h2 id="riepilogo" class="text-lg font-semibold">Riepilogo</h2>
        <button type="submit" formaction="${page.getBackAction()}" formnovalidate class="-my-1 inline-flex min-h-11 items-center justify-center gap-2 rounded-xl px-3 text-base font-semibold text-brand transition-colors duration-150 select-none hover:bg-brand-soft active:bg-brand-soft disabled:cursor-wait disabled:opacity-70">
          @template.components.icon(icon = Icon.PENCIL, classes = "size-5")
          Modifica i dati
        </button>
      </div>
      <div class="mt-3 divide-y divide-line">
        @for(RecapChild child : page.getChildren())
          <div class="py-4 first:pt-0">
            <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
              <p class="text-lg font-semibold wrap-anywhere">${child.getName()}</p>
              @template.components.badge(label = child.getStatus(), tone = child.getStatusTone())
            </div>
            <dl class="mt-1 divide-y divide-line">
              <div class="flex flex-wrap items-baseline justify-between gap-x-4 py-2.5"><dt class="text-ink-muted">Data di nascita</dt><dd class="font-semibold">${child.getBirth()}</dd></div>
              <div class="flex flex-wrap items-baseline justify-between gap-x-4 py-2.5"><dt class="text-ink-muted">Quando</dt><dd class="basis-full text-left font-semibold wrap-anywhere">${child.getPresence()}</dd></div>
              <div class="flex flex-wrap items-baseline justify-between gap-x-4 py-2.5"><dt class="text-ink-muted">Segnalazioni</dt><dd class="font-semibold">${child.getHealth()}</dd></div>
              <div class="flex flex-wrap items-baseline justify-between gap-x-4 py-2.5 last:pb-0"><dt class="text-ink-muted">Quota</dt><dd class="font-semibold">${child.getQuota()}</dd></div>
            </dl>
          </div>
        @endfor
      </div>
      <dl class="mt-1 divide-y divide-line border-t border-line">
        <div class="flex flex-wrap items-baseline justify-between gap-x-4 py-2.5"><dt class="text-ink-muted">Telefono</dt><dd class="font-semibold">${page.getPhone()}</dd></div>
        <div class="flex flex-wrap items-baseline justify-between gap-x-4 pt-2.5"><dt class="font-semibold">Totale</dt><dd class="text-lg font-bold">${page.getTotal()}</dd></div>
      </dl>
      <p class="mt-3 text-sm text-ink-muted">In Talità non si paga: qui vedi solo l’importo. Per pagare, segui le indicazioni dei maestri.</p>
    </section>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="dati">
      <h2 id="dati" class="text-lg font-semibold">Come usiamo i tuoi dati</h2>
      <div class="mt-3 space-y-3">
        <p><span class="block font-semibold">Chi li gestisce</span>Il titolare è ${page.getNotice().getController()}. Per qualsiasi domanda scrivi a ${page.getNotice().getContact()}.</p>
        <p><span class="block font-semibold">A cosa servono</span>A iscrivere i bambini alla convivenza e a organizzarla: li usiamo perché ce lo chiedi con l’iscrizione. I dati dell’iscrizione sono necessari: senza non possiamo iscrivere i bambini. Durante la convivenza i maestri segnano giorno per giorno chi c’è e i pagamenti, ognuno con il nome del maestro che l’ha segnato: li vedono solo i maestri e servono a sapere chi è presente e quanto è stato pagato.</p>
        <p><span class="block font-semibold">Salute (facoltativa)</span>Allergie, intolleranze e farmaci li salviamo solo se ci dai il consenso. Se non li scrivi, l’iscrizione vale lo stesso, ma i maestri non li sapranno. Li leggono solo i maestri, quando serve, e ogni lettura resta registrata.</p>
        <p><span class="block font-semibold">Chi li riceve</span>I maestri della convivenza. La struttura che ospita la convivenza riceve su carta il nome e il cognome dei bambini e dei maestri affidatari, e nient’altro. Per far funzionare Talità ci appoggiamo a chi ospita il sito, a chi manda le email e a chi tiene le copie di sicurezza: usano i dati solo per questo.</p>
        <p><span class="block font-semibold">Per quanto tempo</span>${page.getNotice().getTime()}</p>
        <p><span class="block font-semibold">I tuoi diritti</span>${page.getNotice().getRights()}</p>
      </div>
      <a href="/informativa" target="_blank" rel="noopener" hx-boost="false" class="-mb-2 -ml-3 mt-3 inline-flex min-h-11 items-center justify-center gap-2 rounded-xl px-3 text-base font-semibold text-brand transition-colors duration-150 select-none hover:bg-brand-soft active:bg-brand-soft">
        @template.components.icon(icon = Icon.EXTERNAL_LINK, classes = "size-5")
        Leggi l’informativa completa
      </a>
      <p class="mt-3 text-sm text-ink-muted">Si apre in una nuova scheda: così non perdi quello che hai scritto.</p>
    </section>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="affidamento">
      <h2 id="affidamento" class="text-lg font-semibold">Testo dell’affidamento</h2>
      <p class="mt-3 max-w-prose whitespace-pre-line wrap-anywhere">${page.getCustodyBody()}</p>
    </section>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="caselle">
      <h2 id="caselle" class="text-lg font-semibold">Per iscrivere spunta tutte le caselle</h2>
      <div class="mt-2 space-y-1">
        @template.registration.checkRow(id = "accetto", name = "acceptCustody", label = "Ho letto il testo dell’affidamento e lo accetto.", checked = page.getAccept(), error = page.getErrors().get("accept"))
        @template.registration.checkRow(id = "altro-genitore", name = "otherParent", label = page.getDeclaration(), checked = page.getOther(), error = page.getErrors().get("other"))
        @template.registration.checkRow(id = "convinzioni", name = "religionConsent", label = page.getPlural() ? "Acconsento al trattamento dei miei dati e di quelli dei bambini, sapendo che partecipare a una convivenza organizzata da parrocchie può far capire le convinzioni religiose della famiglia." : "Acconsento al trattamento dei miei dati e di quelli del bambino, sapendo che partecipare a una convivenza organizzata da parrocchie può far capire le convinzioni religiose della famiglia.", checked = page.getReligion(), error = page.getErrors().get("religion"))
      </div>
    </section>

    <div class="flex flex-col gap-3 pt-3 sm:flex-row-reverse sm:justify-start">
      @template.components.button(label = "Accetta e iscrivi", busyLabel = "Iscrizione in corso…", id = "conferma-invia")
      @template.components.button(label = "Indietro", busyLabel = "Indietro…", variant = "secondary", formaction = page.getBackAction())
    </div>
  </form>
`)
