@import org.springframework.security.web.csrf.CsrfToken
@import talita.checkin.CardPage
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param CardPage page
@param CsrfToken csrf
<%-- The page of the card of a child (`/arrivi/{id}`): the way back to the list of the same day, the name, the gathering, and the block `#scheda` (childCard.jte). --%>

@template.layout.shell(shell = shell, content = @`
  <div class="-mt-2 mb-2 -ml-3">
    @template.components.linkButton(href = page.getBackHref(), label = "Torna agli arrivi", variant = "text", icon = Icon.CHEVRON_LEFT)
  </div>
  @template.components.pageHead(title = page.getName(), lead = page.getLead())
  @template.checkin.childCard(page = page, csrf = csrf)
`)
