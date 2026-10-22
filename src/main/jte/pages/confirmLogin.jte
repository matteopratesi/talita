@import org.springframework.security.web.csrf.CsrfToken
@import talita.account.ConfirmPage
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param ConfirmPage page
@param CsrfToken csrf

@template.layout.shell(shell = shell, content = @`
  @template.components.statePage(
    icon = Icon.LOG_IN,
    title = "Conferma l’accesso",
    body = @`
      @if(page.getAlert() != null)
        <div class="mt-4 w-full max-w-sm text-left">
          @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
        </div>
      @endif
      <p class="mt-2 max-w-sm text-ink-muted">Tocca «Entra» per accedere a Talità da qui.</p>
    `,
    actions = @`
      <form method="post" action="/accedi/conferma" hx-disabled-elt="find button[type=submit]" class="mt-6 w-full max-w-xs">
        @template.components.csrf(csrf = csrf)
        <input type="hidden" name="token" value="${page.getToken()}">
        @template.components.button(label = "Entra", busyLabel = "Accesso in corso…", width = "full")
      </form>
      <p class="mt-4 max-w-sm text-sm text-ink-muted">Non hai chiesto tu questo link? Chiudi la pagina: finché non tocchi «Entra», nessuno entra.</p>
    `)
`)
