@import org.springframework.security.web.csrf.CsrfToken
@import talita.teacher.MaestriPage
@import talita.ui.Shell
@param Shell shell
@param MaestriPage page
@param CsrfToken csrf

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Maestri", lead = "Chi organizza la convivenza insieme a te. I maestri vedono tutta la convivenza, i bambini e le segnalazioni.")
  @template.pages.maestriBlock(page = page, csrf = csrf)
`)
