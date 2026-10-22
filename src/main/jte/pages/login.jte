@import org.springframework.security.web.csrf.CsrfToken
@import talita.account.LoginPage
@import talita.ui.Shell
@param Shell shell
@param LoginPage page
@param CsrfToken csrf

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Accedi a Talità", lead = "Ti mandiamo un link per entrare, senza password. Se è la prima volta, va bene lo stesso: non devi creare niente.")

  @if(page.getAlert() != null)
    <div class="mb-4">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  <form method="post" action="/accedi" novalidate hx-disabled-elt="find button[type=submit]" class="rounded-2xl border border-line bg-surface p-4 lg:p-6">
    @template.components.csrf(csrf = csrf)
    @template.components.field(id = "email", label = "Indirizzo email", type = "email", value = page.getEmail(), hint = "Usa sempre lo stesso indirizzo: è il tuo accesso a Talità.", error = page.getEmailError(), autocomplete = "email", autocapitalize = "none", plain = true)
    <div class="mt-6">
      @template.components.button(label = "Ricevi il link", busyLabel = "Invio…", width = "full")
    </div>
  </form>
`)
