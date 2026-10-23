@import talita.gathering.PreviewPage
@import talita.ui.AlertVariant
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param PreviewPage page
<%-- The preview of the information page for the teachers (criterion 35): the notice on top, the buttons, and the page itself in a frame (on a desktop it shows that
     it is a preview and not the real page). The title of the page is an h2 and the cards h3: the only h1 is «Anteprima della pagina». In print only the page is printed:
     the notice, the buttons, the title of the preview and the frame stay out. --%>

@template.layout.shell(shell = shell, content = @`
  <div class="print:hidden">
    @template.components.pageHead(title = "Anteprima della pagina")
    <div id="avviso-anteprima">
      @template.components.alert(variant = AlertVariant.INFO, content = @`${page.getNotice()}`)
    </div>
    <div class="mt-3 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
      @if(page.getPrintable())
        <button type="button" data-azione="stampa" class="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl border border-line-strong bg-surface px-5 text-base font-semibold text-ink transition-colors duration-150 select-none hover:bg-surface-muted active:bg-surface-muted sm:w-auto">
          @template.components.icon(icon = Icon.PRINTER, classes = "size-5")
          Stampa la pagina
        </button>
      @endif
      @template.components.linkButton(href = "/convivenza/" + page.getId(), label = "Torna alla convivenza", variant = "secondary", width = "phone")
    </div>
  </div>

  <div class="mt-5 rounded-2xl border border-line bg-canvas p-4 lg:p-6 print:mt-0 print:border-0 print:bg-transparent print:p-0">
    <div class="mx-auto w-full max-w-2xl">
      @template.gathering.infoContent(info = page.getInfo(), level = 2, preview = true)
    </div>
  </div>
`)
