@import org.springframework.security.web.csrf.CsrfToken
@import talita.ui.Icon
@import talita.ui.Shell
@import talita.venue.VenueFormPage
@import talita.venue.VenueFormTexts
@param Shell shell
@param VenueFormPage page
@param CsrfToken csrf
<%-- «Nuovo modulo» and «Modifica il modulo» in the page of the venue (TALITA-20, part 3, criterion 20): the name of the form and four boxes in two groups, **never ticked at the start**: every column is a piece of data that
     leaves the application or is written on the sheet, and who writes the form chooses it. «Cognome e nome» is always on the sheet. The two boxes of the teachers say only that the sheet has a field for them: what a
     teacher writes there is not saved. No `required`, no `maxlength` and no `pattern`: the errors are ours, and the name comes back as it was written (422). --%>

@template.layout.shell(shell = shell, content = @`
  <div class="-mt-2 mb-2 -ml-3">
    @template.components.linkButton(href = "/strutture/" + page.getVenueId() + "#moduli", label = "Torna alla struttura", variant = "text", icon = Icon.CHEVRON_LEFT)
  </div>
  @template.components.pageHead(title = page.getTitle(), lead = page.getLead())

  @if(page.getAlert() != null)
    <div class="mb-3">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  <form method="post" action="${page.getAction()}" novalidate hx-disabled-elt="find button[type=submit]" class="rounded-2xl border border-line bg-surface p-4 lg:p-6">
    @template.components.csrf(csrf = csrf)
    <div class="space-y-5">
      @template.components.field(id = "nome-modulo", name = "name", label = "Nome del modulo", value = page.getName(), hint = VenueFormTexts.NAME_HELP, error = page.getNameError(), autocomplete = "off", autocapitalize = "sentences")
      <fieldset aria-describedby="bambini-aiuto">
        <legend class="font-semibold">${VenueFormTexts.CHILDREN_LEGEND}</legend>
        <p id="bambini-aiuto" class="mt-1.5 text-sm text-ink-muted">${VenueFormTexts.CHILDREN_HELP}</p>
        <div class="mt-2 divide-y divide-line overflow-hidden rounded-2xl border border-line bg-surface">
          <label class="flex min-h-14 items-start gap-3 px-4 py-3">
            <input type="checkbox" name="withAge" value="true" checked="${page.getChoices().getAge()}" class="mt-0.5 size-6 shrink-0 accent-brand">
            <span class="min-w-0 flex-1"><span class="block font-semibold">${VenueFormTexts.AGE_LABEL}</span><span class="block text-sm text-ink-muted">${VenueFormTexts.AGE_HELP}</span></span>
          </label>
          <label class="flex min-h-14 items-start gap-3 px-4 py-3">
            <input type="checkbox" name="withDays" value="true" checked="${page.getChoices().getDays()}" class="mt-0.5 size-6 shrink-0 accent-brand">
            <span class="min-w-0 flex-1"><span class="block font-semibold">${VenueFormTexts.DAYS_LABEL}</span><span class="block text-sm text-ink-muted">${VenueFormTexts.DAYS_HELP}</span></span>
          </label>
        </div>
      </fieldset>
      <fieldset aria-describedby="maestri-aiuto">
        <legend class="font-semibold">${VenueFormTexts.TEACHERS_LEGEND}</legend>
        <p id="maestri-aiuto" class="mt-1.5 text-sm text-ink-muted">${VenueFormTexts.TEACHERS_HELP}</p>
        <div class="mt-2 divide-y divide-line overflow-hidden rounded-2xl border border-line bg-surface">
          <label class="flex min-h-14 items-start gap-3 px-4 py-3">
            <input type="checkbox" name="withTeacherDocument" value="true" checked="${page.getChoices().getTeacherDocument()}" class="mt-0.5 size-6 shrink-0 accent-brand">
            <span class="min-w-0 flex-1"><span class="block font-semibold">${VenueFormTexts.DOCUMENT_LABEL}</span><span class="block text-sm text-ink-muted">${VenueFormTexts.TEACHER_FIELD_HELP}</span></span>
          </label>
          <label class="flex min-h-14 items-start gap-3 px-4 py-3">
            <input type="checkbox" name="withTeacherTaxCode" value="true" checked="${page.getChoices().getTeacherTaxCode()}" class="mt-0.5 size-6 shrink-0 accent-brand">
            <span class="min-w-0 flex-1"><span class="block font-semibold">${VenueFormTexts.TAX_CODE_LABEL}</span><span class="block text-sm text-ink-muted">${VenueFormTexts.TEACHER_FIELD_HELP}</span></span>
          </label>
        </div>
      </fieldset>
    </div>
    <div class="mt-6 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
      @template.components.button(label = "Salva il modulo", busyLabel = "Salvataggio…")
      @template.components.linkButton(href = "/strutture/" + page.getVenueId() + "#moduli", label = "Annulla", variant = "secondary", width = "phone")
    </div>
  </form>
`)
