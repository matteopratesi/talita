@import org.springframework.security.web.csrf.CsrfToken
@import talita.account.ProfilePage
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param ProfilePage page
@param CsrfToken csrf

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Profilo")

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
    </section>
  </div>
`)
