@import org.springframework.security.web.csrf.CsrfToken
@import talita.retention.ErasureDialog
@import talita.ui.Shell
@param Shell shell
@param ErasureDialog page
@param CsrfToken csrf
<%-- The page that stands in for the dialog that deletes data when JavaScript is off (`GET /profilo/figli/{id}/cancella`, `GET /profilo/account/cancella`): the title
     is the h1, then the same words as the dialog, the button of danger and «Annulla» as a link to the profile. --%>

@template.layout.shell(shell = shell, content = @`
  <div class="mx-auto w-full max-w-md">
    @template.components.pageHead(title = page.getTitle())
    @template.profile.erasureText(dialog = page, id = "testo-cancellazione")
    <form method="post" action="${page.getAction()}" hx-disabled-elt="find button[type=submit]" class="mt-6 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
      @template.components.csrf(csrf = csrf)
      @template.components.button(label = page.getConfirmLabel(), busyLabel = page.getBusyLabel(), variant = "danger")
      @template.components.linkButton(href = page.getCancelHref(), label = "Annulla", variant = "secondary", width = "phone")
    </form>
  </div>
`)
