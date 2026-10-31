@import org.springframework.security.web.csrf.CsrfToken
@import talita.gathering.InfoForm
@import talita.gathering.PublicText
@import talita.ui.Shell
@param Shell shell
@param InfoForm page
@param CsrfToken csrf
<%-- «Modifica le informazioni» (criteria 5, 6): the text for the parents and the list of what to bring (one thing per line). --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Modifica le informazioni")

  @if(page.getAlert() != null)
    <div class="mb-3">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  <form method="post" action="${"/convivenza/" + page.getId() + "/informazioni"}" novalidate hx-disabled-elt="find button[type=submit]" class="rounded-2xl border border-line bg-surface p-4 lg:p-6">
    @template.components.csrf(csrf = csrf)
    <div class="space-y-5">
      @template.components.textAreaField(id = "informazioni", name = "infoText", label = "Informazioni per i genitori", value = page.getInfo(), rows = 9, hint = PublicText.INFO_HELP, error = page.getInfoError())
      @template.components.textAreaField(id = "cosa-portare", name = "whatToBring", label = "Cosa portare", value = page.getWhatToBring(), rows = 6, hint = PublicText.BRING_HELP, error = page.getWhatToBringError())
    </div>
    <div class="mt-6 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
      @template.components.button(label = "Salva le modifiche", busyLabel = "Salvataggio…")
      @template.components.linkButton(href = "/convivenza/" + page.getId(), label = "Annulla", variant = "secondary", width = "phone")
    </div>
  </form>
`)
