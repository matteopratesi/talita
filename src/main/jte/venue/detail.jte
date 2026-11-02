@import org.springframework.security.web.csrf.CsrfToken
@import talita.ui.Icon
@import talita.ui.Shell
@import talita.venue.VenuePage
@param Shell shell
@param VenuePage page
@param CsrfToken csrf
<%-- The page of a venue (`/strutture/{id}`, only for the teachers; TALITA-20): the name as the title, the card «Nome e luogo» with its «Modifica» and, at the bottom, «Togli la struttura» for a venue that no
     gathering uses (a dialog: it removes nothing by itself) or the sentence that says why it is not taken away. The name and the place are public text (the parents read them): they wrap anywhere. --%>

@template.layout.shell(shell = shell, content = @`
  <div class="-mt-2 mb-2 -ml-3">
    @template.components.linkButton(href = "/strutture", label = "Torna alle strutture", variant = "text", icon = Icon.CHEVRON_LEFT)
  </div>
  @template.components.pageHead(title = page.getName())

  @if(page.getAlert() != null)
    <div class="mb-4">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, lead = page.getAlert().getLead(), actionLabel = page.getAlert().getActionLabel(), actionHref = page.getAlert().getActionHref(), content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  <div class="space-y-3">
    <section id="luogo" class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="luogo-t">
      <div class="flex items-center justify-between gap-3">
        <h2 id="luogo-t" class="text-lg font-semibold">Nome e luogo</h2>
        @template.components.linkButton(href = "/strutture/" + page.getId() + "/modifica", label = "Modifica", variant = "text", icon = Icon.PENCIL, ariaLabel = "Modifica nome e luogo di " + page.getName())
      </div>
      <dl class="mt-3 space-y-3">
        <div class="sm:grid sm:grid-cols-[11rem_1fr] sm:gap-6">
          <dt class="text-sm text-ink-muted">Nome</dt>
          <dd class="font-medium wrap-anywhere">${page.getCard().getName()}</dd>
        </div>
        <div class="sm:grid sm:grid-cols-[11rem_1fr] sm:gap-6">
          <dt class="text-sm text-ink-muted">Luogo</dt>
          <dd class="font-medium wrap-anywhere">${page.getCard().getPlace()}</dd>
        </div>
      </dl>
      <p class="mt-3 text-sm text-ink-muted">Nome e luogo li leggono i genitori, nella pagina informativa e nell’anteprima del link.</p>
      <p class="mt-1 text-sm text-ink-muted">${page.getCard().getUsed()}</p>
    </section>
  </div>

  @if(page.getRemoval() != null)
    <div class="mt-8">
      @template.components.linkButton(href = "/strutture/" + page.getId() + "/togli", label = "Togli la struttura", variant = "danger-outline", icon = Icon.TRASH_2, width = "phone", opens = page.getRemoval().getId())
    </div>
    @template.components.confirmDialog(dialog = page.getRemoval(), csrf = csrf)
  @elseif(page.getNotRemovable() != null)
    <p class="mt-8 text-sm text-ink-muted">${page.getNotRemovable()}</p>
  @endif
`)
