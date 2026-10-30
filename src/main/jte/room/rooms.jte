@import org.springframework.security.web.csrf.CsrfToken
@import talita.room.RoomsPage
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param RoomsPage page
@param CsrfToken csrf
<%-- «Camere» (`/convivenza/{id}/camere`, only for the teachers; design TALITA-11, part 1): the way back to the gathering, the title with its dates, a line that says what to do, the
     region that a screen reader hears after an action, and the block `#camere-contenuto`, which every action answers with whole (roomsBlock.jte). --%>

@template.layout.shell(shell = shell, content = @`
  <div class="-mt-2 mb-2 -ml-3">
    @template.components.linkButton(href = page.getBackHref(), label = "Torna alla convivenza", variant = "text", icon = Icon.CHEVRON_LEFT)
  </div>
  @template.components.pageHead(title = "Camere", lead = page.getLead())
  @if(page.getIntro())
    <p class="mb-4">Metti ogni bambino e ogni maestro nella sua camera. Le camere le vedono solo i maestri.</p>
  @endif
  <p id="esito" role="status" class="sr-only"></p>
  @template.room.roomsBlock(block = page.getBlock(), csrf = csrf)
`)
