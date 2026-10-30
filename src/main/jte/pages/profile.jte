@import org.springframework.security.web.csrf.CsrfToken
@import talita.account.ProfilePage
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param ProfilePage page
@param CsrfToken csrf

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Profilo")

  @if(page.getAlert() != null)
    <div class="mb-4">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, lead = page.getAlert().getLead(), actionLabel = page.getAlert().getActionLabel(), actionHref = page.getAlert().getActionHref(), content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  <div class="space-y-3">
    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="accesso">
      <h2 id="accesso" class="text-lg font-semibold">Il tuo accesso</h2>
      <dl class="mt-3 space-y-3">
        <div class="sm:grid sm:grid-cols-[11rem_1fr] sm:gap-6">
          <dt class="text-sm text-ink-muted">Indirizzo email</dt>
          <dd class="font-medium wrap-anywhere">${page.getEmail()}</dd>
        </div>
        @if(page.getRole() != null)
          <div class="sm:grid sm:grid-cols-[11rem_1fr] sm:gap-6">
            <dt class="text-sm text-ink-muted">Ruolo</dt>
            <dd class="font-medium">${page.getRole()}</dd>
          </div>
        @endif
      </dl>
      <form method="post" action="/esci" hx-disabled-elt="find button[type=submit]" class="mt-6">
        @template.components.csrf(csrf = csrf)
        @template.components.button(label = "Esci", busyLabel = "Uscita…", variant = "secondary", icon = Icon.LOG_OUT)
      </form>
      <p class="mt-3 text-sm text-ink-muted">Se usi un telefono o un computer di altri, esci quando hai finito.</p>
      @if(page.getAccess() != null)
        @template.profile.devicesBlock(block = page.getAccess(), csrf = csrf)
      @endif
    </section>

    @if(page.getNameForm() != null)
      <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="nome-titolo">
        <h2 id="nome-titolo" class="text-lg font-semibold">Il tuo nome</h2>
        <form method="post" action="/profilo/nome" novalidate hx-disabled-elt="find button[type=submit]" class="mt-3">
          @template.components.csrf(csrf = csrf)
          <div class="space-y-5">
            @template.components.field(id = "nome", label = "Nome", value = page.getNameForm().getFirst(), error = page.getNameForm().getFirstError(), autocomplete = "given-name", autocapitalize = "words")
            @template.components.field(id = "cognome", label = "Cognome", value = page.getNameForm().getLast(), error = page.getNameForm().getLastError(), autocomplete = "family-name", autocapitalize = "words")
          </div>
          <div class="mt-6">
            @template.components.button(label = "Salva il nome", busyLabel = "Salvataggio…")
          </div>
        </form>
      </section>
    @endif

    @if(page.getEmailCard() != null)
      @template.reminder.emailCard(card = page.getEmailCard(), csrf = csrf)
    @endif

    @template.profile.yourData(data = page.getData(), csrf = csrf)
  </div>
`)
