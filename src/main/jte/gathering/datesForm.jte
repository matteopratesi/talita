@import org.springframework.security.web.csrf.CsrfToken
@import talita.gathering.DatesForm
@import talita.gathering.PublicText
@import talita.ui.Shell
@param Shell shell
@param DatesForm page
@param CsrfToken csrf
<%-- «Modifica titolo, date e luogo» (criteria 3, 5, 6, 17). Once the gathering is over the dates are written as text, with no fields: they do not change any more. --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Modifica titolo, date e luogo")

  @if(page.getAlert() != null)
    <div class="mb-3">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  <form method="post" action="${"/convivenza/" + page.getId() + "/date"}" novalidate hx-disabled-elt="find button[type=submit]" class="rounded-2xl border border-line bg-surface p-4 lg:p-6">
    @template.components.csrf(csrf = csrf)
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
      @template.components.field(id = "luogo", name = "place", label = "Luogo", value = page.getPlace(), hint = PublicText.PLACE_HELP, error = page.getPlaceError(), autocomplete = "off", autocapitalize = "sentences")
    </div>
    <div class="mt-6 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
      @template.components.button(label = "Salva le modifiche", busyLabel = "Salvataggio…")
      @template.components.linkButton(href = "/convivenza/" + page.getId(), label = "Annulla", variant = "secondary", width = "phone")
    </div>
  </form>
`)
