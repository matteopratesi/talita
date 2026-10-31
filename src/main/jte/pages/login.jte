@import org.springframework.security.web.csrf.CsrfToken
@import talita.account.LoginPage
@import talita.ui.AlertVariant
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param LoginPage page
@param CsrfToken csrf

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Accedi a Talità", lead = "Ti mandiamo un link per entrare, senza password. Se è la prima volta, va bene lo stesso: non devi creare niente.")

  @if(page.getAlert() != null)
    <div class="mb-4">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, lead = page.getAlert().getLead(), actionLabel = page.getAlert().getActionLabel(), actionHref = page.getAlert().getActionHref(), content = @`${page.getAlert().getText()}@if(page.getPasskeys() && page.getAlert().getPasskeyHint() != null) <span data-passkey hidden>${page.getAlert().getPasskeyHint()}</span>@endif`)
    </div>
  @endif

  <form method="post" action="/accedi" novalidate hx-disabled-elt="find button[type=submit]" class="rounded-2xl border border-line bg-surface p-4 lg:p-6">
    @template.components.csrf(csrf = csrf)
    @template.components.field(id = "email", label = "Indirizzo email", type = "email", value = page.getEmail(), hint = "Usa sempre lo stesso indirizzo: è il tuo accesso a Talità.", error = page.getEmailError(), autocomplete = "email", autocapitalize = "none", plain = true)
    <div class="mt-6">
      @template.components.button(label = "Ricevi il link", busyLabel = "Invio…", width = "full")
    </div>
  </form>

  @if(page.getPasskeys())
    <%-- «Entra con una passkey» (TALITA-15, criteria 22-24, 27, 28): under the form of the link, shown by passkeys.js only if the browser can use passkeys. The notices of what happens on the phone are
         here, hidden, with their words; the script shows one of them above the button, where the person touched. Without a public address of the site there is none of this. --%>
    <div data-passkey hidden data-ambito data-esiti class="mt-6">
      <p aria-hidden="true" class="flex items-center gap-3 text-sm text-ink-muted before:h-px before:flex-1 before:bg-line after:h-px after:flex-1 after:bg-line">oppure</p>
      @template.components.outcomeAlert(variant = AlertVariant.INFO, lead = "Nessuna passkey usata.", text = "Hai annullato, oppure su questo telefono non c’è una passkey di Talità. Puoi riprovare o chiedere un link qui sopra.", passkeyEsito = "annullata", classes = "mt-5")
      @template.components.outcomeAlert(variant = AlertVariant.WARNING, lead = "Questa passkey non funziona.", text = "Forse è stata tolta dal profilo, o il tuo account non c’è più. Entra con un link: poi, se vuoi, aggiungi una passkey nuova dal profilo.", passkeyEsito = "non-valida", classes = "mt-5")
      <div class="mt-5">
        @template.components.button(label = "Entra con una passkey", busyLabel = "In attesa della conferma…", variant = "secondary", icon = Icon.FINGERPRINT_PATTERN, width = "full", type = "button", azione = "entra-passkey")
      </div>
      <p aria-live="polite" aria-atomic="true" data-stato class="mt-3 text-sm text-ink-muted">Se hai aggiunto una passkey al tuo profilo, entri con l’impronta, il volto o il codice di sblocco, senza aspettare la mail.</p>
      <template data-attesa=""><strong>In attesa della conferma…</strong> Conferma con l’impronta, il volto o il codice di sblocco.</template>
    </div>
  @endif
`)
