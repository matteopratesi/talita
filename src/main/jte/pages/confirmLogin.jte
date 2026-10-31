@import org.springframework.security.web.csrf.CsrfToken
@import talita.account.ConfirmPage
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param ConfirmPage page
@param CsrfToken csrf
<%-- The page of the link (TALITA-3, with the box of TALITA-15): opening it does nothing, the tap on «Entra» is the way in. The box «Ricordami su questo telefono» is never checked
     (no `checked`, no memory of the browser) and opening the page writes nothing; checked, the tap leaves the cookie that lets this browser in again without a link. The box is
     all one tap target (the label), and when checked it takes the border and the background of the brand: the mark in the box says it too, color is not the only sign. --%>

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
      <form method="post" action="/accedi/conferma" hx-disabled-elt="find button[type=submit]" class="mt-6 w-full max-w-sm">
        @template.components.csrf(csrf = csrf)
        <input type="hidden" name="token" value="${page.getToken()}">
        <label class="flex min-h-12 items-start gap-3 rounded-2xl border border-line bg-surface p-4 text-left transition-colors duration-150 has-checked:border-brand has-checked:bg-brand-soft">
          <input type="checkbox" name="ricordami" value="true" autocomplete="off" class="mt-0.5 size-6 shrink-0 accent-brand">
          <span class="min-w-0">
            <span class="block font-semibold">${page.getRemember().getLabel()}</span>
            <span class="mt-1 block text-sm text-ink-muted">${page.getRemember().getLasts()}</span>
            <span class="mt-2 block text-sm text-ink-muted">${page.getRemember().getAdvice()}</span>
          </span>
        </label>
        <div class="mt-4">
          @template.components.button(label = "Entra", busyLabel = "Accesso in corso…", width = "full")
        </div>
      </form>
      <p class="mt-4 max-w-sm text-sm text-ink-muted">Non hai chiesto tu questo link? Chiudi la pagina: finché non tocchi «Entra», nessuno entra.</p>
    `)
`)
