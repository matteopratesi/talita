@import talita.registration.FirstStepPage
@param FirstStepPage page
<%-- The answer to «Aggiungi un figlio», «Togli» and «Aggiungi»: the blocks (they replace `#figli`) and, out of band, the card «Quota», which depends on how many
     children there are. The page does not move, and neither do the phone and the heading. --%>
@template.registration.children(page = page)
<div id="quota-totale" hx-swap-oob="true" class="empty:hidden">@if(page.getTotal() != null)@template.registration.quotaTotal(total = page.getTotal())@endif</div>
