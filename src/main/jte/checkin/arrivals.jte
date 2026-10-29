@import org.springframework.security.web.csrf.CsrfToken
@import talita.checkin.ArrivalsPage
@import talita.checkin.ArrivalsState
@import talita.checkin.DayLink
@import talita.checkin.GroupView
@import talita.checkin.RowView
@import talita.ui.AlertVariant
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param ArrivalsPage page
@param CsrfToken csrf
<%-- «Arrivi» for the teachers (criteria 1-4, 19-22; design parts 1 and 4): the days of the gathering, the summary of the one that is chosen, the search by name (which
     runs in the browser: nothing is asked of the server, no name goes in an address or in a log), and the children in groups, with «Segna presente» a touch away. What
     the page says is the database of the moment the page was made: no polling, «Aggiornato alle» and «Aggiorna» say so. The dividers of a list are drawn between the
     rows that are shown (not `divide-y`): the search hides rows, and a divider under the last one that is left would double the border of the card. --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Arrivi", lead = page.getLead())

  @if(page.getAlert() != null)
    <div class="mb-4">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  @if(page.getState() == ArrivalsState.NONE)
    @template.components.emptyState(icon = Icon.CALENDAR_DAYS, title = "Nessuna convivenza in programma", text = "Gli arrivi si segnano dopo che hai creato la convivenza e aperto le iscrizioni.", href = "/convivenza/nuova", label = "Crea la convivenza")
  @elseif(page.getState() == ArrivalsState.DELETED)
    @template.components.emptyState(icon = Icon.ARCHIVE, title = page.getDeleted().getTitle(), text = page.getDeleted().getText(), text2 = page.getDeleted().getText2(), href = page.getDeleted().getHref(), label = page.getDeleted().getLabel(), variant = "text", buttonIcon = Icon.CALENDAR_DAYS, neutral = true)
  @elseif(page.getState() == ArrivalsState.DRAFT)
    @template.components.alert(variant = AlertVariant.INFO, content = @`La convivenza «${page.getDraftTitle()}» è ancora in bozza: gli arrivi si segnano dopo l’apertura delle iscrizioni. Apri le iscrizioni dalla pagina della convivenza.`, action = @`@template.components.linkButton(href = page.getDraftHref(), label = "Vai alla convivenza", variant = "text")`)
  @else
    <nav aria-label="Giorni della convivenza">
      <ul class="grid auto-cols-fr grid-flow-col gap-1.5 rounded-2xl border border-line bg-surface p-1.5">
        @for(DayLink day : page.getDays())
          <li>
            <a href="${day.getHref()}" aria-current="${day.getCurrent() ? "date" : null}" class="flex min-h-16 flex-col items-center justify-center rounded-xl text-center text-ink-muted transition-colors duration-150 hover:bg-surface-muted active:bg-surface-muted busy:bg-surface-muted aria-[current=date]:bg-brand-soft aria-[current=date]:text-brand aria-[current=date]:ring-2 aria-[current=date]:ring-brand aria-[current=date]:ring-inset">
              <span class="text-xs font-medium"><span aria-hidden="true">${day.getAbbreviation()}</span><span class="sr-only">${day.getWeekday()}</span></span>
              <span class="text-lg font-semibold tabular-nums">${day.getNumber()}<span class="sr-only"> ${day.getMonth()}${day.getToday() ? " (oggi)" : ""}</span></span>
              @if(day.getToday())
                <span class="text-xs font-semibold" aria-hidden="true">oggi</span>
              @else
                <span class="invisible text-xs" aria-hidden="true">·</span>
              @endif
            </a>
          </li>
        @endfor
      </ul>
    </nav>

    @if(page.getNotice() != null)
      <div class="mt-3">
        @if(page.getNotice().getActionHref() != null)
          @template.components.alert(variant = page.getNotice().getAlert().getVariant(), content = @`${page.getNotice().getAlert().getText()}`, action = @`@template.components.linkButton(href = page.getNotice().getActionHref(), label = page.getNotice().getActionLabel(), variant = "text")`)
        @else
          @template.components.alert(variant = page.getNotice().getAlert().getVariant(), content = @`${page.getNotice().getAlert().getText()}`)
        @endif
      </div>
    @endif

    @if(page.getState() == ArrivalsState.EMPTY)
      @template.components.emptyState(icon = Icon.USERS_ROUND, title = "Nessun bambino iscritto", text = "Quando i genitori iscrivono i bambini, li trovi qui con gli arrivi. Se un genitore ha compilato il modulo di carta, puoi inserirlo tu.", href = page.getNewHref(), label = "Inserisci un’iscrizione", buttonIcon = Icon.PLUS, spaced = true)
      @if(page.getMenu() != null)
        <div class="mt-4">
          @template.checkin.menuCard(menu = page.getMenu())
        </div>
      @endif
    @else
      <div hidden data-filtro class="mt-4">
        <label for="cerca" class="sr-only">Cerca un bambino</label>
        <div class="relative">
          @template.components.icon(icon = Icon.SEARCH, classes = "pointer-events-none absolute top-1/2 left-3.5 size-5 -translate-y-1/2 text-ink-muted")
          <input id="cerca" type="search" autocomplete="off" autocapitalize="off" spellcheck="false" enterkeyhint="search" placeholder="Cerca per nome o cognome" aria-describedby="cerca-esito" class="block min-h-12 w-full rounded-xl border border-line-strong bg-surface pr-4 pl-11 text-base text-ink placeholder:text-ink-muted focus:border-brand">
        </div>
        <p id="cerca-esito" role="status" class="mt-1.5 min-h-5.5 text-sm text-ink-muted"></p>
      </div>
      <p class="flex flex-wrap items-center justify-between gap-x-3 text-sm text-ink-muted">
        <span>Aggiornato alle ${page.getUpdated()}</span>
        @template.components.linkButton(href = page.getRefreshHref(), label = "Aggiorna", variant = "text", icon = Icon.ROTATE_CW)
      </p>

      <div class="mt-2">
        @template.checkin.summaryCard(summary = page.getSummary())
      </div>

      <div id="elenco-bambini" class="mt-5 space-y-5">
        @for(GroupView group : page.getGroups())
          <div data-filtro-gruppo>
            <h2 class="mb-2 flex items-baseline justify-between gap-3 text-lg font-semibold"><span>${group.getTitle()}</span><small class="text-sm font-normal text-ink-muted">${group.getCount()}</small></h2>
            @if(group.getNote() != null)
              <p class="mb-2 text-sm text-ink-muted">${group.getNote()}</p>
            @endif
            <ul class="overflow-hidden rounded-2xl border border-line bg-surface [&>li:not([hidden])~li:not([hidden])]:border-t [&>li:not([hidden])~li:not([hidden])]:border-line">
              @for(RowView row : group.getRows())
                @template.checkin.arrivalRow(row = row, csrf = csrf)
              @endfor
            </ul>
          </div>
        @endfor
        <div hidden data-filtro-vuoto>
          @template.components.emptyState(icon = Icon.SEARCH, title = "Nessun bambino con questo nome", text = "Controlla come l’hai scritto. Se il bambino non è iscritto, puoi inserirlo dal modulo di carta.", href = page.getNewHref(), label = "Inserisci un’iscrizione", variant = "secondary", buttonIcon = Icon.PLUS)
        </div>
      </div>
      <p id="esito" role="status" class="sr-only"></p>

      <div class="mt-6 space-y-3">
        <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="carta-titolo">
          <h2 id="carta-titolo" class="text-lg font-semibold">Un bambino non è in elenco?</h2>
          <p class="mt-2">Se un genitore ha compilato il modulo di carta, inseriscilo tu: poi lo trovi qui.</p>
          <div class="mt-3">
            @template.components.linkButton(href = page.getNewHref(), label = "Inserisci un’iscrizione", variant = "secondary", icon = Icon.PLUS, width = "phone")
          </div>
        </section>
        <section id="stampe" class="overflow-hidden rounded-2xl border border-line bg-surface" aria-labelledby="stampe-titolo">
          <h2 id="stampe-titolo" class="px-4 pt-4 text-lg font-semibold lg:px-6 lg:pt-6">Da stampare</h2>
          <ul class="mt-3 divide-y divide-line border-t border-line">
            <li>@template.components.listLink(href = "/arrivi/registro", icon = Icon.CLIPBOARD_LIST, title = "Registro arrivi", meta = "Una riga per bambino, con giorni, quota e manleva. Per segnare a mano.")</li>
            <li>@template.components.listLink(href = "/arrivi/elenco", icon = Icon.LIST, title = "Elenco per la struttura", meta = "Solo nome e cognome, per chi ospita la convivenza.")</li>
            <li>@template.components.listLink(href = "/arrivi/foglio", icon = Icon.PHONE, title = "Foglio per i maestri", meta = "Bambini e recapiti dei genitori. Resta fra i maestri.")</li>
          </ul>
          <p class="border-t border-line px-4 py-3 text-sm text-ink-muted lg:px-6">Stampa il registro e il foglio prima di partire: se manca la rete, segni a mano e trascrivi dopo.</p>
        </section>
        @if(page.getMenu() != null)
          @template.checkin.menuCard(menu = page.getMenu())
        @endif
      </div>
    @endif
  @endif
`)
