@import org.springframework.security.web.csrf.CsrfToken
@import talita.room.RoomsBlock
@param RoomsBlock block
@param CsrfToken csrf
<%-- The answer to an action of «Camere» that htmx sends (design TALITA-11, part 1, 5): the block whole, and the sentence that a screen reader says, in the region `#esito` that
     stays in the page (so that the announcement really happens). Nothing else of the page changes. --%>
@template.room.roomsBlock(block = block, csrf = csrf)
@if(block.getAnnouncement() != null)
  <p hx-swap-oob="innerHTML:#esito">${block.getAnnouncement()}</p>
@endif
