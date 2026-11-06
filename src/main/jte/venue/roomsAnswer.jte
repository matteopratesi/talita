@import org.springframework.security.web.csrf.CsrfToken
@import talita.venue.VenueRoomsBlock
@param VenueRoomsBlock block
@param CsrfToken csrf
<%-- The answer to «Togli» of a room that htmx sends (TALITA-20): the block whole, and the sentence that a screen reader says, in the region `#esito` that stays in the page. Nothing else of the page changes. --%>
@template.venue.roomsBlock(block = block, csrf = csrf)
@if(block.getAnnouncement() != null)
  <p hx-swap-oob="innerHTML:#esito">${block.getAnnouncement()}</p>
@endif
