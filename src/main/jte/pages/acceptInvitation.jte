@import org.springframework.security.web.csrf.CsrfToken
@import talita.teacher.AcceptInvitationPage
@import talita.ui.Shell
@param Shell shell
@param AcceptInvitationPage page
@param CsrfToken csrf

@template.layout.shell(shell = shell, content = @`
  <div class="mx-auto w-full max-w-md">
    @template.components.pageHead(title = "Accetta l’invito", lead = "Ti hanno invitato a organizzare la convivenza come maestro. Il tuo nome compare nell’elenco dei maestri e nei documenti della convivenza.")

    @if(page.getAlert() != null)
      <div class="mb-4">
        @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
      </div>
    @endif

    <form method="post" action="/invito" novalidate hx-disabled-elt="find button[type=submit]" class="rounded-2xl border border-line bg-surface p-4 lg:p-6">
      @template.components.csrf(csrf = csrf)
      <div class="space-y-5">
        @template.components.field(id = "nome", label = "Nome", value = page.getFirst(), error = page.getFirstError(), autocomplete = "given-name", autocapitalize = "words")
        @template.components.field(id = "cognome", label = "Cognome", value = page.getLast(), error = page.getLastError(), autocomplete = "family-name", autocapitalize = "words")
      </div>
      <div class="mt-6 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
        @template.components.button(label = "Accetta l’invito", busyLabel = "Invio…")
        @template.components.linkButton(href = "/", label = "Più tardi", variant = "secondary", width = "phone")
      </div>
    </form>
    <p class="mt-4 text-sm text-ink-muted">Se non ti aspettavi questo invito, tocca «Più tardi»: non cambia niente.</p>
  </div>
`)
