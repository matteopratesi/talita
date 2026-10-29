@import org.springframework.security.web.csrf.CsrfToken
@import talita.gathering.MenuPage
@import talita.ui.AlertVariant
@import talita.ui.Shell
@param Shell shell
@param MenuPage page
@param CsrfToken csrf
<%-- The page of the menu (TALITA-12, criteria 2-6; design 2 and 3): one page for uploading the menu and for replacing it. With a menu, «Menù attuale» above the form. The
     form is not a piece of a page: `hx-boost="false"` (the limit of 20 seconds of htmx would cut a slow upload from a phone), `multipart/form-data` with the CSRF code in
     the body, and it works without JavaScript. `data-carica` is for `app.js`: while the file leaves, the button says «Caricamento…» and the page dims. Every error is under
     the field, and the menu that was there stays. The notice on the data of the children is not a help line: it is the only defence of the app against what the file holds,
     which it does not read, so it is a notice (without a role: it is always there). --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = page.getHeading(), lead = page.getGatheringTitle())

  <div class="space-y-3">
    @if(page.getCurrent() != null)
      <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="attuale">
        <h2 id="attuale" class="text-lg font-semibold">Menù attuale</h2>
        @template.gathering.menuFile(file = page.getCurrent())
        <p class="mt-3 text-sm text-ink-muted">${page.getReplaces()}</p>
      </section>
    @endif

    <form method="post" action="${page.getAction()}" enctype="multipart/form-data" hx-boost="false" novalidate data-carica class="rounded-2xl border border-line bg-surface p-4 lg:p-6">
      @template.components.csrf(csrf = csrf)
      <div id="menu-file-avviso" class="mb-5">
        @template.components.alert(variant = AlertVariant.WARNING, content = @`${page.getWarning()}`)
      </div>
      @template.components.fileField(id = "menu-file", name = "file", label = "File del menù", hint = page.getHelp(), accept = "application/pdf,image/jpeg,image/png", also = "menu-file-avviso", error = page.getError())
      <div class="mt-6 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
        @template.components.button(label = page.getButton(), busyLabel = page.getBusyButton())
        @template.components.linkButton(href = page.getCancelHref(), label = "Annulla", variant = "secondary", width = "phone")
      </div>
      <p class="mt-4 text-sm text-ink-muted">${page.getSlowNote()}</p>
    </form>
  </div>
`)
