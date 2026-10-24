@import talita.gathering.Badge
@import talita.registration.TeacherListKind
@import talita.registration.TeacherListPage
@import talita.registration.TeacherRow
@import talita.ui.AlertVariant
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param TeacherListPage page
<%-- «Iscrizioni» of the teachers (criteria 20, 24; design C 2): the children of the gathering that starts last, for scrolling and recognizing (30-35 of them,
     also on the phone): one row each, with the state, the manleva and the fee, and nothing about health, not even whether there is anything. With a draft only,
     a notice; with no gathering, the empty state. --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Iscrizioni", lead = page.getLead())

  @if(page.getAlert() != null)
    <div class="mb-4">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  @if(page.getKind() == TeacherListKind.NONE)
    <div class="flex flex-col items-center rounded-2xl border border-line bg-surface px-6 py-12 text-center">
      <span class="grid size-16 place-items-center rounded-full bg-brand-soft text-brand">
        @template.components.icon(icon = Icon.CALENDAR_DAYS, classes = "size-8")
      </span>
      <h2 class="mt-4 text-xl font-semibold tracking-tight">Nessuna convivenza in programma</h2>
      <p class="mt-2 max-w-sm text-ink-muted">Le iscrizioni arrivano dopo che hai creato la convivenza e aperto le iscrizioni.</p>
      <div class="mt-6 w-full sm:w-auto">
        @template.components.linkButton(href = "/convivenza/nuova", label = "Crea la convivenza", width = "phone")
      </div>
    </div>
  @elseif(page.getKind() == TeacherListKind.DELETED)
    @template.components.emptyState(icon = Icon.ARCHIVE, title = page.getDeleted().getTitle(), text = page.getDeleted().getText(), text2 = page.getDeleted().getText2(), href = page.getDeleted().getHref(), label = page.getDeleted().getLabel(), variant = "text", buttonIcon = Icon.CALENDAR_DAYS, neutral = true)
  @elseif(page.getKind() == TeacherListKind.DRAFT)
    @template.components.alert(variant = AlertVariant.INFO, content = @`La convivenza «${page.getDraftTitle()}» è ancora in bozza: le iscrizioni si fanno dopo l’apertura, anche quelle di carta. Apri le iscrizioni dalla pagina della convivenza.`, action = @`@template.components.linkButton(href = page.getDraftHref(), label = "Vai alla convivenza", variant = "text")`)
  @elseif(page.getKind() == TeacherListKind.EMPTY)
    <div class="flex flex-col items-center rounded-2xl border border-line bg-surface px-6 py-12 text-center">
      <span class="grid size-16 place-items-center rounded-full bg-brand-soft text-brand">
        @template.components.icon(icon = Icon.USERS_ROUND, classes = "size-8")
      </span>
      <h2 class="mt-4 text-xl font-semibold tracking-tight">Nessuna iscrizione</h2>
      <p class="mt-2 max-w-sm text-ink-muted">Quando i genitori iscrivono i bambini, li trovi qui. Se un genitore ha compilato il modulo di carta, puoi inserirlo tu.</p>
      <div class="mt-6 w-full sm:w-auto">
        @template.components.linkButton(href = page.getNewHref(), label = "Inserisci un’iscrizione", icon = Icon.PLUS, width = "phone")
      </div>
    </div>
  @else
    <div class="mb-4">
      @template.components.linkButton(href = page.getNewHref(), label = "Inserisci un’iscrizione", icon = Icon.PLUS, width = "phone")
    </div>

    <section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="in-breve">
      <h2 id="in-breve" class="text-lg font-semibold">In breve</h2>
      <dl class="mt-3 divide-y divide-line">
        @for(kotlin.Pair<String, String> line : page.getSummary())
          <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5 first:pt-0 last:pb-0"><dt class="text-ink-muted">${line.getFirst()}</dt><dd class="font-semibold">${line.getSecond()}</dd></div>
        @endfor
      </dl>
    </section>

    <section class="mt-6" aria-labelledby="bambini">
      <h2 id="bambini" class="mb-3 text-xl font-semibold tracking-tight">I bambini</h2>
      <ul class="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-surface">
        @for(TeacherRow row : page.getRows())
          <li>
            <a href="${row.getHref()}" class="flex min-h-16 items-center gap-3 px-4 py-3 transition-colors duration-150 hover:bg-surface-muted active:bg-surface-muted busy:bg-surface-muted">
              <span aria-hidden="true" class="grid size-10 shrink-0 place-items-center rounded-full bg-brand-soft text-sm font-semibold text-brand">${row.getInitials()}</span>
              <span class="min-w-0 flex-1">
                <span class="flex flex-wrap items-center gap-x-2 gap-y-1">
                  <span class="font-semibold wrap-anywhere">${row.getName()}</span>
                  @template.components.badge(label = row.getStatus().getLabel(), tone = row.getStatus().getTone())
                  @if(row.getManleva() != null)
                    @template.components.badge(label = row.getManleva().getLabel(), tone = row.getManleva().getTone())
                  @endif
                  @if(row.getDuplicate())
                    @template.components.badge(label = "Possibile doppione", tone = "warning")
                  @endif
                </span>
                <span class="block text-sm text-ink-muted wrap-anywhere">${row.getLine()}</span>
              </span>
              @template.components.icon(icon = Icon.CHEVRON_RIGHT, classes = "size-5 shrink-0 text-ink-muted")
            </a>
          </li>
        @endfor
      </ul>
    </section>
  @endif
`)
