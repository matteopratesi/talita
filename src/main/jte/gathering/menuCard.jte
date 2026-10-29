@import org.springframework.security.web.csrf.CsrfToken
@import talita.gathering.MenuCard
@import talita.ui.Icon
@param MenuCard menu
@param CsrfToken csrf
<%-- The card «Menù» of the detail of the gathering (TALITA-12, criteria 1, 6-8; design 1): always there, in every state of the gathering. Without a file, a sentence and
     the way to upload it; with it, the file (name, kind, size, day: the content is never read for this page), «Scarica» (secondary: the detail has already one
     main button), «Sostituisci» and «Togli» (text), and a line on who sees it. «Scarica» is not a page: htmx leaves it to the browser, which saves the file and
     the page stays as it is. «Togli» opens its dialog (the page of confirmation without JavaScript); the dialog sits next to the card, not between the cards. --%>
<div>
  <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="menu">
    <h2 id="menu" class="text-lg font-semibold">Menù</h2>
    @if(menu.getFile() == null)
      <p class="mt-3 font-semibold">Nessun menù caricato.</p>
      <p class="mt-1 text-sm text-ink-muted">Carica il file del menù, per averlo sempre a portata di mano. Lo vedono solo i maestri.</p>
      <div class="mt-4">
        @template.components.linkButton(href = menu.getMenuHref(), label = "Carica il menù", variant = "secondary", icon = Icon.UPLOAD, width = "phone")
      </div>
    @else
      @template.gathering.menuFile(file = menu.getFile())
      <div class="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center">
        @template.components.linkButton(href = menu.getDownloadHref(), label = "Scarica", variant = "secondary", icon = Icon.DOWNLOAD, width = "phone", ariaLabel = "Scarica il menù", boost = false)
        <div class="-ml-3 flex flex-wrap gap-1 sm:ml-0">
          @template.components.linkButton(href = menu.getMenuHref(), label = "Sostituisci", variant = "text", icon = Icon.UPLOAD)
          @template.components.linkButton(href = menu.getRemoveHref(), label = "Togli", variant = "text", icon = Icon.TRASH_2, ariaLabel = "Togli il menù", opens = menu.getDialog().getId())
        </div>
      </div>
      <p class="mt-3 text-sm text-ink-muted">Lo vedono solo i maestri. Scaricato sul telefono, si apre anche senza rete.</p>
    @endif
  </section>
  @if(menu.getDialog() != null)
    @template.components.confirmDialog(dialog = menu.getDialog(), csrf = csrf)
  @endif
</div>
