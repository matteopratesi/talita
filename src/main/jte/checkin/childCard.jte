@import org.springframework.security.web.csrf.CsrfToken
@import talita.checkin.CardDay
@import talita.checkin.CardPage
@import talita.checkin.DayAction
@import talita.checkin.OtherCard
@import talita.checkin.SiblingView
@import talita.gathering.Badge
@import talita.ui.AlertVariant
@import talita.ui.Icon
@param CardPage page
@param CsrfToken csrf
<%-- The card of a child (criteria 5-9; design part 2), all in one block, `#scheda`: every action of the card (mark or take away a presence, mark the manleva as arrived, put
     the registration back) is a form with `hx-post` and `hx-target="#scheda"`, and the answer is always the whole block, so the labels under the title and the notices are
     never left old. The order is the one of the work: the day first (the most frequent thing), then, when there is hurry, the telephone and the notes, then the money, the
     manleva, the registration and the other children of the same parent. --%>
<div id="scheda">
  <div class="-mt-4 mb-4 flex flex-wrap items-center gap-2 lg:-mt-6">
    @for(Badge badge : page.getBadges())
      @template.components.badge(label = badge.getLabel(), tone = badge.getTone())
    @endfor
  </div>

  <div class="space-y-3">
    @if(page.getFlash() != null)
      @if(page.getFlash().getActionHref() != null)
        @template.components.alert(variant = page.getFlash().getVariant(), live = true, content = @`@if(page.getFlash().getLead() != null)<strong>${page.getFlash().getLead()}</strong> @endif${page.getFlash().getText()}`, action = @`@template.components.linkButton(href = page.getFlash().getActionHref(), label = page.getFlash().getActionLabel(), variant = "text", icon = Icon.PENCIL)`)
      @else
        @template.components.alert(variant = page.getFlash().getVariant(), live = true, content = @`@if(page.getFlash().getLead() != null)<strong>${page.getFlash().getLead()}</strong> @endif${page.getFlash().getText()}`)
      @endif
    @endif
    @if(page.getWithdrawn())
      @template.components.alert(
        variant = AlertVariant.WARNING,
        content = @`<strong>Iscrizione ritirata.</strong> Non si segnano presenze. Quelle già segnate e i pagamenti restano.`,
        action = @`<form method="post" action="${"/arrivi/" + page.getId() + "/rimetti"}" hx-post="${"/arrivi/" + page.getId() + "/rimetti"}" hx-target="#scheda" hx-swap="outerHTML" hx-disabled-elt="find button[type=submit]" class="ml-3 pt-1 pb-2">
            @template.components.csrf(csrf = csrf)
            <input type="hidden" name="giorno" value="${page.getDay()}">
            @template.components.button(label = "Rimetti confermata", busyLabel = "Ripristino…", variant = "secondary", icon = Icon.ROTATE_CCW, width = "phone")
          </form>`)
    @elseif(page.getMaybe())
      @template.components.alert(variant = AlertVariant.INFO, content = @`L’iscrizione è in forse. Se segni la presenza, diventa confermata.`)
    @endif
    @if(!page.getDuplicates().isEmpty())
      @template.components.alert(variant = AlertVariant.WARNING, content = @`<strong>Possibile doppione.</strong> C’è un’altra iscrizione con lo stesso nome e la stessa data di nascita. Se è lo stesso bambino, ritirane una.`, action = @`@for(OtherCard other : page.getDuplicates())@template.components.linkButton(href = other.getHref(), label = "Apri l’altra scheda", variant = "text", ariaLabel = other.getAriaLabel())@endfor`)
    @endif
    @if(page.getPhaseNotice() != null)
      @template.components.alert(variant = AlertVariant.INFO, content = @`${page.getPhaseNotice()}`)
    @endif

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="giorni-titolo">
      <h2 id="giorni-titolo" class="text-lg font-semibold">Giorni</h2>
      <ul class="mt-3 divide-y divide-line">
        @for(CardDay day : page.getDays())
          <li class="py-3.5 first:pt-1">
            <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
              <span class="font-semibold">${day.getLabel()}</span>
              @if(day.getToday())
                @template.components.badge(label = "Oggi", tone = "info")
              @endif
              <span class="ml-auto text-sm ${day.getNotExpected() ? "font-semibold text-warning" : "text-ink-muted"}">${day.getPlan()}</span>
            </div>
            @if(day.getAction() != DayAction.NONE)
              <div class="mt-2">
                @if(day.getAction() == DayAction.MARK)
                  <form method="post" action="${"/arrivi/" + page.getId() + "/presenze"}" hx-post="${"/arrivi/" + page.getId() + "/presenze"}" hx-target="#scheda" hx-swap="outerHTML" hx-disabled-elt="find button[type=submit]">
                    @template.components.csrf(csrf = csrf)
                    <input type="hidden" name="giorno" value="${day.getDay()}">
                    <input type="hidden" name="da" value="scheda">
                    @template.components.button(label = "Segna presente", busyLabel = "Segno…", variant = day.getPrimary() ? "primary" : "secondary", width = "full", ariaLabel = day.getMarkLabel())
                  </form>
                @elseif(day.getAction() == DayAction.UNMARK)
                  <div class="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
                    <p class="flex items-start gap-2 font-semibold text-success">
                      @template.components.icon(icon = Icon.CIRCLE_CHECK, classes = "mt-1 size-5 shrink-0")
                      <span>Presente<small class="block text-sm font-normal text-ink-muted wrap-anywhere">${day.getPresentBy()}</small></span>
                    </p>
                    <form method="post" action="${"/arrivi/" + page.getId() + "/presenze/togli"}" hx-post="${"/arrivi/" + page.getId() + "/presenze/togli"}" hx-target="#scheda" hx-swap="outerHTML" hx-disabled-elt="find button[type=submit]">
                      @template.components.csrf(csrf = csrf)
                      <input type="hidden" name="giorno" value="${day.getDay()}">
                      <input type="hidden" name="da" value="scheda">
                      <button type="submit" aria-label="${day.getUnmarkLabel()}" class="inline-flex min-h-11 shrink-0 items-center justify-center rounded-xl border border-line-strong bg-surface px-4 text-sm font-semibold text-ink transition-colors duration-150 select-none hover:bg-surface-muted active:bg-surface-muted disabled:cursor-wait disabled:opacity-70 sm:text-base">
                        <span class="busy:hidden">Togli</span><span class="hidden busy:inline">Tolgo…</span>
                      </button>
                    </form>
                  </div>
                @else
                  <p class="text-sm text-ink-muted">${day.getLater()}</p>
                @endif
              </div>
            @endif
          </li>
        @endfor
      </ul>
    </section>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="manleva-titolo">
      <h2 id="manleva-titolo" class="text-lg font-semibold">Manleva</h2>
      <div class="mt-2">
        @template.components.badge(label = page.getCustody().getBadge().getLabel(), tone = page.getCustody().getBadge().getTone())
      </div>
      <p class="mt-2">${page.getCustody().getText()}</p>
      @if(page.getCustody().getCanReceive())
        <form method="post" action="${"/arrivi/" + page.getId() + "/manleva"}" hx-post="${"/arrivi/" + page.getId() + "/manleva"}" hx-target="#scheda" hx-swap="outerHTML" hx-disabled-elt="find button[type=submit]" class="mt-3">
          @template.components.csrf(csrf = csrf)
          <input type="hidden" name="giorno" value="${page.getDay()}">
          @template.components.button(label = "Segna come ricevuta", busyLabel = "Salvataggio…", variant = "secondary", icon = Icon.CHECK, width = "phone")
        </form>
        <p class="mt-3 text-sm text-ink-muted">Il modulo firmato resta di carta: qui si segna solo se è arrivato.</p>
      @endif
    </section>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="iscrizione-titolo">
      <h2 id="iscrizione-titolo" class="text-lg font-semibold">Iscrizione</h2>
      <dl class="mt-3 divide-y divide-line">
        <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5 first:pt-0"><dt class="text-ink-muted">Stato</dt><dd>@template.components.badge(label = page.getRegistration().getStatus().getLabel(), tone = page.getRegistration().getStatus().getTone())</dd></div>
        <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5 last:pb-0"><dt class="text-ink-muted">Età</dt><dd class="font-semibold">${page.getRegistration().getAge()}</dd></div>
      </dl>
      <div class="-mb-2 -ml-3 mt-3 flex flex-wrap gap-1">
        @template.components.linkButton(href = page.getRegistration().getOpenHref(), label = "Apri l’iscrizione", variant = "text", icon = Icon.FILE_TEXT)
      </div>
    </section>

    @if(!page.getSiblings().isEmpty())
      <section class="overflow-hidden rounded-2xl border border-line bg-surface" aria-labelledby="altri-titolo">
        <h2 id="altri-titolo" class="px-4 pt-4 text-lg font-semibold lg:px-6 lg:pt-6">Altri figli dello stesso genitore</h2>
        <ul class="mt-3 divide-y divide-line border-t border-line">
          @for(SiblingView sibling : page.getSiblings())
            <li>
              <a href="${sibling.getHref()}" class="flex min-h-16 items-center gap-3 px-4 py-3 transition-colors duration-150 hover:bg-surface-muted active:bg-surface-muted busy:bg-surface-muted">
                <span class="min-w-0 flex-1">
                  <span class="block font-semibold wrap-anywhere">${sibling.getName()}</span>
                  <span class="mt-1 flex flex-wrap gap-x-1.5 gap-y-1">
                    @for(Badge badge : sibling.getLabels())
                      @template.components.badge(label = badge.getLabel(), tone = badge.getTone())
                    @endfor
                  </span>
                </span>
                @template.components.icon(icon = Icon.CHEVRON_RIGHT, classes = "size-5 shrink-0 text-ink-muted")
              </a>
            </li>
          @endfor
        </ul>
        <p class="border-t border-line px-4 py-3 text-sm text-ink-muted lg:px-6">Se il genitore ha pagato per tutti, registra a ognuno la sua parte.</p>
      </section>
    @endif
  </div>
</div>
