@import org.springframework.security.web.csrf.CsrfToken
@import talita.gathering.DatesForm
@import talita.gathering.PublicText
@import talita.gathering.VenueChoice
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param DatesForm page
@param CsrfToken csrf
<%-- «Modifica titolo, date e luogo» (criteria 3, 5, 6, 17; TALITA-20, criteria 8 and 9). Once the gathering is over the dates are written as text, with no fields: they do not change any more.
     The place is the venue's: the form chooses the venue (a `select`, id `struttura`, the first choice without a value) and says what the parents read now; a link leads to «Nuova struttura». --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Modifica titolo, date e luogo")

  @if(page.getAlert() != null)
    <div class="mb-3">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  <form id="modifica-date" method="post" action="${"/convivenza/" + page.getId() + "/date"}" novalidate hx-disabled-elt="find button[type=submit]" data-valore-struttura="${page.getCurrentVenue()}" class="rounded-2xl border border-line bg-surface p-4 lg:p-6">
    @template.components.csrf(csrf = csrf)
    @if(page.getChange() != null)
      <input type="hidden" name="cambio" value="">
    @endif
    <div class="space-y-5">
      @template.components.field(id = "titolo", name = "title", label = "Titolo", value = page.getTitle(), hint = PublicText.TITLE_HELP, error = page.getTitleError(), autocomplete = "off", autocapitalize = "sentences")
      @if(page.getLockedDates() != null)
        <div class="space-y-1.5">
          <p class="font-semibold">Date</p>
          <p class="wrap-anywhere">${page.getLockedDates()}</p>
          <p class="text-sm text-ink-muted">Le date non si cambiano più, perché la convivenza è finita.</p>
        </div>
      @else
        @template.components.field(id = "primo-giorno", name = "startDate", label = "Primo giorno", type = "date", value = page.getStartDate(), error = page.getStartError())
        @template.components.field(id = "ultimo-giorno", name = "endDate", label = "Ultimo giorno", type = "date", value = page.getEndDate(), hint = page.getWithRegistrations() ? "I giorni di presenza sono tutti quelli dal primo all’ultimo, al massimo 7. Un giorno scelto da un’iscrizione non si può togliere. Se aggiungi un giorno, chi viene «tutti i giorni» viene anche quel giorno." : "I giorni di presenza sono tutti quelli dal primo all’ultimo, al massimo 7. Un giorno scelto da un’iscrizione non si può togliere.", error = page.getEndError())
      @endif
      @if(page.getVenues().size() > 1)
        <div class="space-y-1.5">
          <label for="struttura" class="block font-semibold">Struttura</label>
          <p id="struttura-aiuto" class="text-sm text-ink-muted">Nome e luogo della struttura li leggono i genitori.</p>
          <div class="relative">
            <select id="struttura" name="venue" aria-describedby="${page.getVenueError() != null ? "struttura-aiuto struttura-errore" : "struttura-aiuto"}" aria-invalid="${page.getVenueError() != null ? "true" : null}" class="block min-h-12 w-full appearance-none rounded-xl border border-line-strong bg-surface pr-11 pl-4 text-base text-ink focus:border-brand aria-[invalid=true]:border-danger aria-[invalid=true]:bg-danger-soft">
              @for(VenueChoice choice : page.getVenues())
                <option value="${choice.getValue()}" selected="${choice.getSelected()}" data-luogo="${choice.getPlace()}">${choice.getLabel()}</option>
              @endfor
            </select>
            @template.components.icon(icon = Icon.CHEVRON_DOWN, classes = "pointer-events-none absolute top-1/2 right-3.5 size-5 -translate-y-1/2 text-ink-muted")
          </div>
          @if(page.getVenueError() != null)
            <p id="struttura-errore" class="flex items-start gap-1.5 text-sm font-medium text-danger">
              @template.components.icon(icon = Icon.CIRCLE_ALERT, classes = "mt-0.5 size-4 shrink-0")
              ${page.getVenueError()}
            </p>
          @endif
          @if(page.getCurrentPlace() != null)
            <p id="struttura-luogo" class="mt-2 text-sm text-ink-muted wrap-anywhere">Luogo di adesso: ${page.getCurrentPlace()}.</p>
          @endif
          <p class="-ml-3 mt-1">@template.components.linkButton(href = "/strutture/nuova", label = "Nuova struttura", variant = "text", icon = Icon.PLUS)</p>
        </div>
      @else
        <div class="space-y-1.5">
          <p id="struttura" tabindex="-1" class="font-semibold">Struttura</p>
          <p class="text-ink-muted">Non c’è ancora nessuna struttura. Scrivi la casa dove fai la convivenza: poi la scegli qui.</p>
          <div class="pt-1.5">
            @template.components.linkButton(href = "/strutture/nuova", label = "Nuova struttura", variant = "secondary", icon = Icon.PLUS, width = "phone")
          </div>
        </div>
      @endif
    </div>
    <div class="mt-6 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
      @template.components.button(label = "Salva le modifiche", busyLabel = "Salvataggio…")
      @template.components.linkButton(href = "/convivenza/" + page.getId(), label = "Annulla", variant = "secondary", width = "phone")
    </div>
  </form>

  @if(page.getChange() != null)
    <%-- «Cambiare la struttura?» (TALITA-20, criterion 12): somebody is in a room of the venue of now, so another venue leaves them without a room. app.js opens this dialog when the form is sent with
         another venue chosen (the attribute data-valore-struttura), fills the second paragraph with the name and the place of the venue that is chosen (the data-luogo of its option) and, at the button, puts the number of people in
         the field cambio and sends the form: the dialog stays open while the answer is awaited, with the button that says «Cambio…» (the button takes the class of a request on its way, which the variant busy: reads). Without JavaScript,
         and for a page that was old, the server answers with a page that has the same words (gathering/changeVenue.jte). --%>
    <dialog id="cambia-struttura" aria-labelledby="cambia-struttura-titolo" aria-describedby="cambia-struttura-testo" class="mx-0 mt-auto mb-0 w-full max-w-none rounded-t-2xl bg-surface px-gutter pt-6 pb-[calc(1.5rem+env(safe-area-inset-bottom))] text-ink shadow-xl backdrop:bg-ink/50 motion-safe:animate-sheet-in sm:m-auto sm:max-w-md sm:rounded-2xl sm:p-6">
      <h2 id="cambia-struttura-titolo" class="text-xl font-semibold tracking-tight wrap-anywhere">Cambiare la struttura?</h2>
      <div id="cambia-struttura-testo" class="mt-2 space-y-2 text-ink-muted">
        <p class="wrap-anywhere">${page.getChange().getSentence()}</p>
        <p class="wrap-anywhere" data-nuova hidden>Per i genitori nome e luogo diventano «<span data-nuovo-nome></span>» e «<span data-nuovo-luogo></span>».</p>
      </div>
      <div class="mt-6 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
        <button type="button" data-conferma-cambio="${page.getChange().getPeople()}" class="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-danger px-5 text-base font-semibold text-white transition-colors duration-150 select-none hover:bg-danger-strong active:bg-danger-strong disabled:cursor-wait disabled:opacity-70 sm:w-auto">
          @template.components.icon(icon = Icon.LOADER_CIRCLE, classes = "hidden size-5 animate-spin busy:block")
          <span class="busy:hidden">Cambia la struttura</span>
          <span class="hidden busy:inline">Cambio…</span>
        </button>
        <form method="dialog" class="contents">
          <button autofocus class="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl border border-line-strong bg-surface px-5 text-base font-semibold text-ink transition-colors duration-150 select-none hover:bg-surface-muted active:bg-surface-muted sm:w-auto">Annulla</button>
        </form>
      </div>
    </dialog>
  @endif
`)
