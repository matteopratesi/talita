@import org.springframework.security.web.csrf.CsrfToken
@import talita.gathering.PublicText
@import talita.ui.AlertVariant
@import talita.ui.Icon
@import talita.ui.Shell
@import talita.venue.VenueForm
@param Shell shell
@param VenueForm page
@param CsrfToken csrf
<%-- «Nuova struttura» and «Modifica la struttura» (TALITA-20, criteria 2 and 3): a name and a place, one line each, nothing else. No `required`, no `maxlength` and no `pattern`: the errors are ours, the same for
     everyone, and the form comes back as it was written (422). Both texts are public, like the place of a gathering was: the help under each says where it appears and not to write the name of a person,
     a number of telephone or an email. A venue that a gathering uses says, above the fields, what changing it does. --%>

@template.layout.shell(shell = shell, content = @`
  <div class="-mt-2 mb-2 -ml-3">
    @template.components.linkButton(href = page.getBackHref(), label = page.getBackLabel(), variant = "text", icon = Icon.CHEVRON_LEFT)
  </div>
  @template.components.pageHead(title = page.getTitle(), lead = page.getLead())

  @if(page.getAlert() != null)
    <div class="mb-4">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif
  @if(page.getNotice() != null)
    <div class="mb-4">
      @template.components.alert(variant = AlertVariant.INFO, content = @`${page.getNotice()}`)
    </div>
  @endif

  <form method="post" action="${page.getAction()}" novalidate hx-disabled-elt="find button[type=submit]" class="rounded-2xl border border-line bg-surface p-4 lg:p-6">
    @template.components.csrf(csrf = csrf)
    <div class="space-y-5">
      @template.components.field(id = "nome", name = "name", label = "Nome della struttura", value = page.getName(), hint = PublicText.NAME_HELP, error = page.getNameError(), autocomplete = "off", autocapitalize = "sentences")
      @template.components.field(id = "luogo", name = "place", label = "Luogo", value = page.getPlace(), hint = PublicText.PLACE_HELP, error = page.getPlaceError(), autocomplete = "off", autocapitalize = "sentences")
    </div>
    <div class="mt-6 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
      @template.components.button(label = "Salva la struttura", busyLabel = "Salvataggio…")
      @template.components.linkButton(href = page.getBackHref(), label = "Annulla", variant = "secondary", width = "phone")
    </div>
  </form>
`)
