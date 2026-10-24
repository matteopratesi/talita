@import org.springframework.security.web.csrf.CsrfToken
@import talita.gathering.Badge
@import talita.registration.CardAction
@import talita.registration.MinePage
@import talita.registration.MineSection
@import talita.registration.RegistrationCard
@import talita.ui.ConfirmModel
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param MinePage page
@param CsrfToken csrf
<%-- «Iscrizioni» of the parent (criteria 11, 14, 15, 19; design B, 3): a section for each gathering with a registration, the most recent first, a card for
     each child (the card is the detail: there is no page of detail for the parent), the card of the total and «Iscrivi un altro figlio». Closed registrations
     and a gathering that is over only look: the notice says to ask the teachers, and the card has no buttons that the person cannot use. --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Iscrizioni")

  @if(page.getAlert() != null)
    <div class="mb-4">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  @if(page.getSections().isEmpty())
    <div class="flex flex-col items-center rounded-2xl border border-line bg-surface px-6 py-12 text-center">
      <span class="grid size-16 place-items-center rounded-full bg-brand-soft text-brand">
        @template.components.icon(icon = Icon.USERS_ROUND, classes = "size-8")
      </span>
      <h2 class="mt-4 text-xl font-semibold tracking-tight">Nessuna iscrizione</h2>
      <p class="mt-2 max-w-sm text-ink-muted">Quando iscrivi un bambino, lo trovi qui con i giorni, la quota e lo stato. Per iscriverlo apri il link che ti hanno mandato i maestri.</p>
    </div>
  @endif

  @for(MineSection section : page.getSections())
    <section class="mb-8" aria-labelledby="${"conv-" + section.getId()}">
      <div class="mb-3">
        <h2 id="${"conv-" + section.getId()}" class="text-xl font-semibold tracking-tight wrap-anywhere">${section.getTitle()}</h2>
        <p class="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-ink-muted"><span>${section.getDates()}</span>@for(Badge badge : section.getBadges())@template.components.badge(label = badge.getLabel(), tone = badge.getTone())@endfor</p>
      </div>

      @if(section.getNotice() != null)
        <div class="mb-3">
          @template.components.alert(variant = section.getNotice().getVariant(), content = @`${section.getNotice().getText()}`)
        </div>
      @endif

      <div class="space-y-3">
        @for(RegistrationCard card : section.getCards())
          <article class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="${"scheda-" + card.getId()}">
            <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
              <h3 id="${"scheda-" + card.getId()}" class="text-lg font-semibold wrap-anywhere">${card.getName()}</h3>
              @template.components.badge(label = card.getStatus(), tone = card.getStatusTone())
            </div>
            <dl class="mt-2 divide-y divide-line">
              <div class="flex flex-wrap items-baseline justify-between gap-x-4 py-2.5"><dt class="text-ink-muted">Quando</dt><dd class="basis-full text-left font-semibold wrap-anywhere">${card.getPresence()}</dd></div>
              <div class="flex flex-wrap items-baseline justify-between gap-x-4 py-2.5"><dt class="text-ink-muted">Quota</dt><dd class="font-semibold">${card.getQuota()}</dd></div>
              <div class="flex flex-wrap items-baseline justify-between gap-x-4 py-2.5"><dt class="text-ink-muted">Telefono</dt><dd class="font-semibold">${card.getPhone()}</dd></div>
              <div class="flex flex-wrap items-baseline justify-between gap-x-4 py-2.5"><dt class="text-ink-muted">Segnalazioni</dt><dd class="${card.getHealthWide() ? "basis-full text-left " : ""}font-semibold wrap-anywhere">${card.getHealth()}</dd></div>
            </dl>
            @if(card.getAccepted() != null)
              <p class="mt-3 flex items-start gap-2 text-sm text-ink-muted">
                @template.components.icon(icon = Icon.FILE_CHECK, classes = "mt-0.5 size-4 shrink-0")
                <span class="min-w-0">${card.getAccepted()}</span>
              </p>
            @endif
            @if(card.getKeptUntil() != null)
              <p class="mt-2 flex items-start gap-2 text-sm text-ink-muted">
                @template.components.icon(icon = Icon.ARCHIVE, classes = "mt-0.5 size-4 shrink-0")
                <span class="min-w-0">${card.getKeptUntil()}</span>
              </p>
            @endif
            @if(!card.getActions().isEmpty())
              <div class="-mb-2 -ml-3 mt-3 flex flex-wrap gap-1">
                @for(CardAction action : card.getActions())
                  @template.components.linkButton(href = action.getHref(), label = action.getLabel(), variant = "text", icon = action.getIcon(), opens = action.getOpens(), ariaLabel = action.getAriaLabel())
                @endfor
              </div>
            @endif
          </article>
        @endfor
        @if(section.getTotal() != null)
          <section class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="${"totale-" + section.getId()}">
            <h2 id="${"totale-" + section.getId()}" class="sr-only">Totale</h2>
            <p class="font-semibold" aria-hidden="true">Totale</p>
            <p class="text-lg font-bold">${section.getTotal()}</p>
          </section>
        @endif
      </div>

      @if(section.getAnotherHref() != null)
        <div class="mt-4">
          @template.components.linkButton(href = section.getAnotherHref(), label = "Iscrivi un altro figlio", icon = Icon.PLUS, width = "phone")
        </div>
      @endif

      @for(ConfirmModel dialog : section.getDialogs())
        @template.components.confirmDialog(dialog = dialog, csrf = csrf)
      @endfor
    </section>
  @endfor
`)
