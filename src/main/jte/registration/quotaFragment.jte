@import talita.registration.QuotaLine
@import talita.registration.TotalView
@param java.util.List<QuotaLine> lines
@param TotalView total = null
<%-- The answer to a change of the days (design A 1/2, 10): nothing is swapped in place (`hx-swap="none"`) and every piece comes out of band. The line of a child
     is a live region, so it is not replaced but filled: the screen reader announces the change. The server worked every amount out with `Quota`. --%>
@for(QuotaLine line : lines)<div hx-swap-oob="${"innerHTML:#quota-" + line.getIndex()}">@if(line.getAmount() != null)<span>Quota</span><strong class="font-bold">${line.getAmount()}</strong>@endif</div>
@endfor<div hx-swap-oob="innerHTML:#quota-totale">@if(total != null)@template.registration.quotaTotal(total = total)@endif</div>
