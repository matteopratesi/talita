@import talita.gathering.MenuLink
@import talita.ui.Icon
@param MenuLink menu
<%-- The card «Menù» of Arrivi (TALITA-12, criterion 9; design 5): one row, like those of «Da stampare», that downloads the file of the menu of the gathering, so that during the
     gathering it is a touch away. The arrow gives way to the icon that says that the row saves a file. The row is not a page: htmx leaves it to the browser (`hx-boost="false"`), which
     saves the file while Arrivi stays as it is. There is no card when the gathering has no menu. The address is of the teachers' gathering (`/convivenza/{id}/menu/file`). --%>
<section id="menu-arrivi" class="overflow-hidden rounded-2xl border border-line bg-surface" aria-labelledby="menu-arrivi-titolo">
  <h2 id="menu-arrivi-titolo" class="px-4 pt-4 text-lg font-semibold lg:px-6 lg:pt-6">Menù</h2>
  <ul class="mt-3 divide-y divide-line border-t border-line">
    <li>@template.components.listLink(href = menu.getHref(), icon = Icon.UTENSILS, title = "Menù", meta = menu.getMeta(), trailing = Icon.DOWNLOAD, boost = false)</li>
  </ul>
  <p class="border-t border-line px-4 py-3 text-sm text-ink-muted lg:px-6">Una volta scaricato, lo apri anche senza rete.</p>
</section>
