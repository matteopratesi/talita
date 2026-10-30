@import talita.gathering.RoomsCard
@import talita.ui.Icon
@param RoomsCard card
<%-- The card «Camere» of the detail of a gathering (TALITA-11, criterion 16): a row that leads to the page of the rooms, with how many rooms, beds and children without a room, and never
     a name. It comes after «Iscrizioni». --%>
<section class="overflow-hidden rounded-2xl border border-line bg-surface" aria-labelledby="camere">
  <h2 id="camere" class="px-4 pt-4 text-lg font-semibold lg:px-6 lg:pt-6">Camere</h2>
  <ul class="mt-2 divide-y divide-line">
    <li>@template.components.listLink(href = card.getHref(), icon = Icon.BED_DOUBLE, title = card.getTitle(), meta = card.getSummary())</li>
  </ul>
</section>
