@import org.springframework.security.web.csrf.CsrfToken
@import talita.gathering.CustodianLine
@import talita.gathering.DetailPage
@import talita.gathering.FeeLine
@import talita.gathering.MissingLink
@import talita.ui.AlertVariant
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param DetailPage page
@param CsrfToken csrf
<%-- The detail of a gathering (criteria 2, 4, 5, 8-12, 14, 17): the notice, the card «Stato» with its button, and the cards of the data, each with its
     «Modifica» that leads to the form of that part. A datum that is missing is written as such («Non indicato»), never a dash or an empty space. --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = page.getTitle())

  @if(page.getMissing() != null)
    <div role="alert" tabindex="-1" data-fuoco class="mb-3 flex items-start gap-3 rounded-xl bg-warning-soft p-4">
      @template.components.icon(icon = Icon.TRIANGLE_ALERT, classes = "size-6 shrink-0 text-warning")
      <div class="min-w-0">
        <p class="font-semibold">Prima di aprire le iscrizioni:</p>
        <ul class="mt-1 list-disc pl-5 marker:text-warning">
          @for(MissingLink item : page.getMissing())
            <li><a href="${item.getHref()}" class="inline-flex min-h-11 items-center font-semibold text-brand underline underline-offset-2">${item.getText()}</a></li>
          @endfor
        </ul>
      </div>
    </div>
  @elseif(page.getAlert() != null)
    <div class="mb-3">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @elseif(page.getStanding() != null)
    <div class="mb-3">
      @template.components.alert(variant = AlertVariant.WARNING, content = @`${page.getStanding()}`)
    </div>
  @endif

  <div class="space-y-3">
    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="stato">
      <h2 id="stato" class="text-lg font-semibold">Stato</h2>
      <div class="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1">
        @for(talita.gathering.Badge badge : page.getState().getBadges())
          @template.components.badge(label = badge.getLabel(), tone = badge.getTone())
        @endfor
      </div>
      <p class="mt-3 wrap-anywhere">${page.getState().getText()}@if(page.getState().getNote() != null) ${page.getState().getNote().getBefore()} <a href="${page.getState().getNote().getLinkHref()}" class="font-semibold text-brand underline underline-offset-2">${page.getState().getNote().getLinkText()}</a>${page.getState().getNote().getAfter()}@endif</p>
      @if(page.getState().getButton() != null)
        <div class="mt-4">
          @if(page.getState().getButton().getDirect())
            <form method="post" action="${page.getState().getButton().getHref()}" hx-disabled-elt="find button[type=submit]">
              @template.components.csrf(csrf = csrf)
              <input type="hidden" name="from" value="${page.getState().getFrom()}">
              @template.components.button(label = page.getState().getButton().getLabel(), busyLabel = "Apertura…", variant = page.getState().getButton().getPrimary() ? "primary" : "secondary")
            </form>
          @else
            @template.components.linkButton(href = page.getState().getButton().getHref(), label = page.getState().getButton().getLabel(), variant = page.getState().getButton().getPrimary() ? "primary" : "secondary", width = "phone", opens = page.getState().getButton().getOpens())
          @endif
        </div>
      @endif
    </section>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="quando">
      <div class="flex items-center justify-between gap-3">
        <h2 id="quando" class="text-lg font-semibold">Quando e dove</h2>
        @template.components.linkButton(href = "/convivenza/" + page.getId() + "/date", label = "Modifica", variant = "text", icon = Icon.PENCIL, ariaLabel = "Modifica titolo, date e luogo")
      </div>
      <dl class="mt-3 space-y-3">
        <div class="sm:grid sm:grid-cols-[11rem_1fr] sm:gap-6">
          <dt class="text-sm text-ink-muted">Quando</dt>
          <dd class="font-medium wrap-anywhere">${page.getDays()}</dd>
        </div>
        <div class="sm:grid sm:grid-cols-[11rem_1fr] sm:gap-6">
          <dt class="text-sm text-ink-muted">Dove</dt>
          <dd class="flex flex-wrap items-center gap-x-2 gap-y-1 font-medium">
            @if(page.getPlace() != null)
              <span class="wrap-anywhere">${page.getPlace()}</span>
            @else
              <span class="font-normal text-ink-muted">Non indicato</span>
              @if(page.getPlaceNeeded())
                @template.components.badge(label = "Da completare", tone = "warning")
              @endif
            @endif
          </dd>
        </div>
      </dl>
    </section>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="quote">
      <div class="flex items-center justify-between gap-3">
        <h2 id="quote" class="text-lg font-semibold">Quote</h2>
        @template.components.linkButton(href = "/convivenza/" + page.getId() + "/quote", label = "Modifica", variant = "text", icon = Icon.PENCIL, ariaLabel = "Modifica le quote")
      </div>
      <dl class="mt-3 divide-y divide-line">
        @for(FeeLine fee : page.getFees())
          <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5 first:pt-0 last:pb-0">
            <dt class="text-ink-muted">${fee.getLabel()}</dt>
            <dd class="flex flex-wrap items-center justify-end gap-x-2 gap-y-1 text-right font-semibold">
              @if(fee.getValue() != null)
                ${fee.getValue()}
              @else
                <span class="font-normal text-ink-muted">Non prevista</span>
                @if(fee.getNeeded())
                  @template.components.badge(label = "Da completare", tone = "warning")
                @endif
              @endif
            </dd>
          </div>
        @endfor
      </dl>
    </section>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="affidatari">
      <div class="flex items-center justify-between gap-3">
        <h2 id="affidatari" class="text-lg font-semibold">Maestri affidatari</h2>
        @template.components.linkButton(href = "/convivenza/" + page.getId() + "/affidatari", label = "Modifica", variant = "text", icon = Icon.PENCIL, ariaLabel = "Modifica i maestri affidatari")
      </div>
      @if(page.getCustodians().isEmpty())
        <p class="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1">
          <span class="text-ink-muted">Nessun maestro scelto</span>
          @if(page.getCustodiansNeeded())
            @template.components.badge(label = "Da completare", tone = "warning")
          @endif
        </p>
      @else
        <ul class="mt-3 divide-y divide-line">
          @for(CustodianLine custodian : page.getCustodians())
            <li class="flex flex-wrap items-center gap-x-2 gap-y-1 py-2 first:pt-0 last:pb-0">
              <span class="font-semibold wrap-anywhere">${custodian.getName()}</span>
              @if(custodian.getYou())
                @template.components.badge(label = "Sei tu")
              @endif
            </li>
          @endfor
        </ul>
      @endif
      <p class="mt-3 text-sm text-ink-muted">I loro nomi compaiono nel testo dell’affidamento che i genitori accettano con l’iscrizione.</p>
    </section>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="informazioni">
      <div class="flex items-center justify-between gap-3">
        <h2 id="informazioni" class="text-lg font-semibold">Informazioni per i genitori</h2>
        @template.components.linkButton(href = "/convivenza/" + page.getId() + "/informazioni", label = "Modifica", variant = "text", icon = Icon.PENCIL, ariaLabel = "Modifica le informazioni per i genitori")
      </div>
      <dl class="mt-3 space-y-3">
        <div class="sm:grid sm:grid-cols-[11rem_1fr] sm:gap-6">
          <dt class="text-sm text-ink-muted">Informazioni</dt>
          <dd class="font-medium">
            @if(page.getInfoParagraphs().isEmpty())
              <span class="font-normal text-ink-muted">Non indicate</span>
            @else
              <div class="max-w-prose space-y-3">
                @for(String paragraph : page.getInfoParagraphs())
                  <p class="font-normal whitespace-pre-line wrap-anywhere">${paragraph}</p>
                @endfor
              </div>
            @endif
          </dd>
        </div>
        <div class="sm:grid sm:grid-cols-[11rem_1fr] sm:gap-6">
          <dt class="text-sm text-ink-muted">Cosa portare</dt>
          <dd class="font-medium">
            @if(page.getWhatToBring().isEmpty())
              <span class="font-normal text-ink-muted">Non indicato</span>
            @else
              <ul class="list-disc space-y-1 pl-5 font-normal marker:text-ink-muted wrap-anywhere">
                @for(String thing : page.getWhatToBring())
                  <li class="pl-1">${thing}</li>
                @endfor
              </ul>
            @endif
          </dd>
        </div>
      </dl>
    </section>
  </div>

  @if(page.getDialog() != null)
    @template.components.confirmDialog(dialog = page.getDialog(), csrf = csrf)
  @endif
`)
