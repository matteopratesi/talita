@import org.springframework.security.web.csrf.CsrfToken
@import talita.ui.ConfirmModel
@import talita.ui.Shell
@param Shell shell
@param ConfirmModel page
@param CsrfToken csrf
<%-- The page that stands in for a dialog of confirmation when JavaScript is off: the same title, text and button, and «Annulla» as a link. --%>

@template.layout.shell(shell = shell, content = @`
  <div class="mx-auto w-full max-w-md">
    @template.components.pageHead(title = page.getTitle())
    <p class="text-ink-muted wrap-anywhere">${page.getText()}</p>
    <form method="post" action="${page.getAction()}" hx-disabled-elt="find button[type=submit]" class="mt-6 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
      @template.components.csrf(csrf = csrf)
      <input type="hidden" name="${page.getField()}" value="${page.getValue()}">
      @template.components.button(label = page.getConfirmLabel(), busyLabel = page.getBusyLabel(), variant = page.getDanger() ? "danger" : "primary")
      @template.components.linkButton(href = page.getCancelHref(), label = "Annulla", variant = "secondary", width = "phone")
    </form>
  </div>
`)
