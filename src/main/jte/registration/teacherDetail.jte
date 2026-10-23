@import org.springframework.security.web.csrf.CsrfToken
@import talita.gathering.Badge
@import talita.registration.OtherRegistration
@import talita.registration.TeacherDetailPage
@import talita.ui.AlertVariant
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param TeacherDetailPage page
@param CsrfToken csrf
<%-- The detail of a registration for the teachers (criteria 21-26; design C 4-6). The order is the one of an emergency, not the one of the paperwork: who the child
     is, how to call the parent, the notes (behind a button that writes who asked), the manleva, and who read the notes. The telephone is a link that calls. --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = page.getName(), lead = page.getLead())

  <div class="-mt-4 mb-4 flex flex-wrap items-center gap-2 lg:-mt-6">
    @for(Badge badge : page.getBadges())
      @template.components.badge(label = badge.getLabel(), tone = badge.getTone())
    @endfor
  </div>

  @if(page.getAlert() != null)
    <div class="mb-3">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif
  @if(!page.getDuplicates().isEmpty())
    <div class="mb-3">
      @template.components.alert(variant = AlertVariant.WARNING, content = @`<strong>Possibile doppione.</strong> C’è un’altra iscrizione con lo stesso nome e la stessa data di nascita. Se è lo stesso bambino, ritirane una.`, action = @`@for(OtherRegistration other : page.getDuplicates())@template.components.linkButton(href = other.getHref(), label = "Apri l’altra iscrizione", variant = "text", ariaLabel = other.getAriaLabel())@endfor`)
    </div>
  @endif

  <div class="space-y-3">
    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="bambino">
      <h2 id="bambino" class="text-lg font-semibold">Bambino</h2>
      <dl class="mt-3 divide-y divide-line">
        <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5 first:pt-0"><dt class="text-ink-muted">Data di nascita</dt><dd class="font-semibold">${page.getBirth()}</dd></div>
        <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5 last:pb-0"><dt class="text-ink-muted">Età</dt><dd class="font-semibold">${page.getAge()}</dd></div>
      </dl>
    </section>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="iscrizione">
      <div class="flex items-center justify-between gap-3">
        <h2 id="iscrizione" class="text-lg font-semibold">Iscrizione</h2>
        @template.components.linkButton(href = "/iscrizioni/" + page.getId() + "/correggi", label = "Correggi", variant = "text", icon = Icon.PENCIL, ariaLabel = "Correggi l’iscrizione")
      </div>
      <dl class="mt-3 divide-y divide-line">
        <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5 first:pt-0"><dt class="text-ink-muted">Quando</dt><dd class="basis-full text-left font-semibold wrap-anywhere">${page.getPresence()}</dd></div>
        <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5"><dt class="text-ink-muted">Quota</dt><dd class="font-semibold">${page.getQuota()}</dd></div>
        <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5 last:pb-0"><dt class="text-ink-muted">Telefono</dt><dd><a href="${"tel:" + page.getTelephone()}" class="inline-flex min-h-11 items-center font-semibold text-brand underline underline-offset-2">${page.getPhone()}</a></dd></div>
      </dl>
      <div class="-mb-2 -ml-3 mt-3 flex flex-wrap gap-1">
        @if(page.getWithdrawn())
          @template.components.linkButton(href = "/iscrizioni/" + page.getId() + "/correggi", label = "Rimetti l’iscrizione", variant = "text", icon = Icon.ROTATE_CCW)
        @else
          @template.components.linkButton(href = "/iscrizioni/" + page.getId() + "/ritira", label = "Ritira l’iscrizione", variant = "text", icon = Icon.CIRCLE_X, opens = page.getWithdrawDialog().getId())
        @endif
      </div>
    </section>

    @template.registration.notesCard(page = page, csrf = csrf)

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="manleva">
      <h2 id="manleva" class="text-lg font-semibold">Manleva</h2>
      @if(page.getOnPaper())
        <dl class="mt-3 divide-y divide-line">
          <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5 first:pt-0"><dt class="text-ink-muted">Modulo cartaceo</dt><dd>@template.components.badge(label = page.getPaperReceived() ? "Ricevuto" : "Da ricevere", tone = page.getPaperReceived() ? "positive" : "warning")</dd></div>
          <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5 last:pb-0"><dt class="text-ink-muted">Inserita da</dt><dd class="font-semibold wrap-anywhere">${page.getEnteredBy()}</dd></div>
        </dl>
        <form method="post" action="${"/iscrizioni/" + page.getId() + "/manleva"}" hx-disabled-elt="find button[type=submit]" class="-mb-2 -ml-3 mt-3">
          @template.components.csrf(csrf = csrf)
          <input type="hidden" name="received" value="${page.getPaperReceived() ? "false" : "true"}">
          @if(page.getPaperReceived())
            @template.components.button(label = "Segna come da ricevere", busyLabel = "Salvataggio…", variant = "text", icon = Icon.ROTATE_CCW, width = "auto")
          @else
            <div class="ml-3">
              @template.components.button(label = "Segna come ricevuta", busyLabel = "Salvataggio…", variant = "secondary", icon = Icon.CHECK, width = "phone")
            </div>
          @endif
        </form>
        <p class="mt-3 text-sm text-ink-muted">Il modulo firmato resta di carta: qui si segna solo se è arrivato.</p>
      @else
        <dl class="mt-3 divide-y divide-line">
          <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5 first:pt-0"><dt class="text-ink-muted">Affidamento</dt><dd class="font-semibold">${page.getAccepted()}</dd></div>
          <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5"><dt class="text-ink-muted">Accettato da</dt><dd class="font-semibold wrap-anywhere">${page.getAcceptedBy()}</dd></div>
          <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5 last:pb-0"><dt class="text-ink-muted">Versione del testo</dt><dd class="font-semibold">${page.getVersion()}</dd></div>
        </dl>
        <details class="group mt-3 rounded-2xl border border-line-strong bg-surface">
          <summary class="flex min-h-14 cursor-pointer list-none items-center gap-3 px-4 py-2 [&::-webkit-details-marker]:hidden">
            @template.components.icon(icon = Icon.FILE_TEXT, classes = "size-6 shrink-0 text-brand")
            <span class="min-w-0 flex-1"><span class="block font-semibold">Testo accettato</span><span class="block text-sm text-ink-muted">Come lo ha letto il genitore</span></span>
            @template.components.icon(icon = Icon.CHEVRON_DOWN, classes = "size-5 shrink-0 text-ink-muted transition-transform duration-150 group-open:rotate-180")
          </summary>
          <div class="border-t border-line px-4 pt-4 pb-4">
            <p class="max-w-prose whitespace-pre-line wrap-anywhere">${page.getAcceptedText()}</p>
          </div>
        </details>
      @endif
    </section>

    @template.registration.readingsCard(page = page)
  </div>

  @if(!page.getWithdrawn())
    @template.components.confirmDialog(dialog = page.getWithdrawDialog(), csrf = csrf)
  @endif
`)
