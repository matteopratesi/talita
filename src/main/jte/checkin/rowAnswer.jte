@import org.springframework.security.web.csrf.CsrfToken
@import talita.checkin.RowAnswer
@param RowAnswer answer
@param CsrfToken csrf
<%-- The answer to a touch on a row (criteria 4, 6, 20, 21; design 5): the row as it is now, the summary (out of band) and the sentence that a screen reader says (the
     region `#esito` stays in the page, so that the announcement really happens). Nothing else of the page changes: not the title, the days, the search, the scroll,
     the other children. --%>
@template.checkin.arrivalRow(row = answer.getRow(), csrf = csrf)
@template.checkin.summaryCard(summary = answer.getSummary(), oob = true)
<p hx-swap-oob="innerHTML:#esito">${answer.getAnnouncement()}</p>
