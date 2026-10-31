@import org.springframework.security.web.csrf.CsrfToken
@import talita.gathering.NewPage
@import talita.gathering.PublicText
@import talita.ui.AlertVariant
@import talita.ui.Shell
@param Shell shell
@param NewPage page
@param CsrfToken csrf
<%-- «Nuova convivenza» (criteria 2, 3, 15): title and dates, nothing else. If a gathering is a draft or open already, the page says so and leads to it. --%>

@template.layout.shell(shell = shell, content = @`
  @if(page.getExisting() != null)
    @template.components.pageHead(title = "Nuova convivenza")
    @template.components.alert(variant = AlertVariant.WARNING, live = true, content = @`${page.getExisting().getText()}`)
    <div class="mt-4">
      @template.components.linkButton(href = page.getExisting().getHref(), label = "Vai alla convivenza", width = "phone")
    </div>
  @else
    @template.components.pageHead(title = "Nuova convivenza", lead = "Per cominciare bastano il titolo e le date. Il resto lo aggiungi dopo.")

    @if(page.getAlert() != null)
      <div class="mb-3">
        @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
      </div>
    @endif

    <form method="post" action="/convivenza/nuova" novalidate hx-disabled-elt="find button[type=submit]" class="rounded-2xl border border-line bg-surface p-4 lg:p-6">
      @template.components.csrf(csrf = csrf)
      <div class="space-y-5">
        @template.components.field(id = "titolo", name = "title", label = "Titolo", value = page.getTitle(), hint = PublicText.TITLE_HELP, error = page.getTitleError(), autocomplete = "off", autocapitalize = "sentences")
        @template.components.field(id = "primo-giorno", name = "startDate", label = "Primo giorno", type = "date", value = page.getStartDate(), error = page.getStartError())
        @template.components.field(id = "ultimo-giorno", name = "endDate", label = "Ultimo giorno", type = "date", value = page.getEndDate(), hint = "I giorni di presenza sono tutti quelli dal primo all’ultimo, al massimo 7.", error = page.getEndError())
      </div>
      <div class="mt-6 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
        @template.components.button(label = "Crea la convivenza", busyLabel = "Creazione…")
        @template.components.linkButton(href = "/convivenza", label = "Annulla", variant = "secondary", width = "phone")
      </div>
    </form>
  @endif
`)
