@import org.springframework.security.web.csrf.CsrfToken
@import talita.reminder.StopPage
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param StopPage page
@param CsrfToken csrf
<%-- The page that the link of the mail leads to (TALITA-13, design part 2, 3): `/promemoria/smetti?codice=…`, public, in the shell without access of `/accedi` (it has a POST with the CSRF
     code and a button that waits, which the shell of the information page, with no script, does not). **Opening it changes nothing** (the mail programs open links to look at them):
     it is the tap that stops. A code that does not exist, or none, has this same page. It never shows the address or says whose the code is. --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.statePage(
    icon = Icon.MAIL_X,
    title = "Promemoria di Talità",
    body = @`
      @if(page.getAlert() != null)
        <div class="mt-4 w-full max-w-sm text-left">
          @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
        </div>
      @endif
      <p class="mt-2 max-w-sm text-ink-muted">Ti scriviamo solo per ricordarti di confermare un’iscrizione in forse o, se l’hai chiesto, per avvisarti quando si aprono le iscrizioni di una nuova convivenza.</p>
      <p class="mt-3 max-w-sm text-ink-muted">Se non vuoi più riceverli, tocca «Non mandarmi più promemoria»: non ti scriveremo più per nessuno dei due motivi.</p>
    `,
    actions = @`
      <form method="post" action="/promemoria/smetti" hx-disabled-elt="find button[type=submit]" class="mt-6 w-full max-w-xs">
        @template.components.csrf(csrf = csrf)
        <input type="hidden" name="codice" value="${page.getCode()}">
        @template.components.button(label = "Non mandarmi più promemoria", busyLabel = "Salvataggio…", width = "full")
      </form>
      <p class="mt-4 max-w-sm text-sm text-ink-muted">Hai aperto questa pagina per sbaglio? Chiudila: finché non tocchi il bottone, non cambia niente. I link per entrare in Talità ti arrivano comunque, quando li chiedi.</p>
    `)
`)
