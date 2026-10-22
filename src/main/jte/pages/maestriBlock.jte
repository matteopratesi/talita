@import org.springframework.security.web.csrf.CsrfToken
@import talita.teacher.InvitationLine
@import talita.teacher.MaestriPage
@import talita.teacher.TeacherLine
@import talita.ui.Icon
@param MaestriPage page
@param CsrfToken csrf
<%-- The part of «Maestri» that every action replaces as a whole (htmx: hx-target="#maestri-contenuto", hx-swap="outerHTML"):
     the notice, the card to invite, the two lists and their dialogs. The title, the introduction, the navigation and the header
     stay, and so does the scroll. --%>
<div id="maestri-contenuto">
  @if(page.getAlert() != null)
    <div class="mb-5">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  <form method="post" action="/maestri/invita" hx-post="/maestri/invita" hx-target="#maestri-contenuto" hx-swap="outerHTML" hx-disabled-elt="find button[type=submit]" novalidate aria-labelledby="invita" class="rounded-2xl border border-line bg-surface p-4 lg:p-6">
    @template.components.csrf(csrf = csrf)
    <h2 id="invita" class="text-lg font-semibold">Invita un maestro</h2>
    <div class="mt-3">
      @template.components.field(id = "email", label = "Indirizzo email", type = "email", value = page.getEmail(), hint = "Gli arriverà una mail con le istruzioni. L’invito vale " + page.getValidityDays() + " giorni. Chi accetta potrà vedere tutta la convivenza, bambini compresi.", error = page.getEmailError(), autocomplete = "off", autocapitalize = "none", plain = true)
    </div>
    <div class="mt-6">
      @template.components.button(label = "Manda l’invito", busyLabel = "Invio…")
    </div>
  </form>

  <section class="mt-8" aria-labelledby="maestri-titolo">
    <h2 id="maestri-titolo" class="mb-3 text-xl font-semibold tracking-tight">I maestri</h2>
    <ul class="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-surface">
      @for(TeacherLine teacher : page.getTeachers())
        <li>
          <div class="flex min-h-16 flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3">
            <span aria-hidden="true" class="grid size-10 shrink-0 place-items-center rounded-full bg-brand-soft text-sm font-semibold text-brand">${teacher.getInitials()}</span>
            <span class="min-w-0 flex-[1_1_11rem]">
              <span class="flex flex-wrap items-center gap-x-2 gap-y-1">
                <span class="font-semibold wrap-break-word">${teacher.getName()}</span>
                @if(teacher.getYou())
                  <span class="inline-flex shrink-0 items-center gap-1 rounded-full bg-surface-muted px-2.5 py-0.5 text-xs font-semibold whitespace-nowrap text-ink-muted">Sei tu</span>
                @endif
              </span>
              @if(teacher.getNote() != null)
                <span class="block text-sm text-ink-muted">${teacher.getNote()}</span>
              @endif
            </span>
            @if(teacher.getRemoval() != null)
              <form method="post" action="/maestri/togli/conferma" class="-ml-3 sm:ml-0 sm:shrink-0">
                @template.components.csrf(csrf = csrf)
                <input type="hidden" name="${teacher.getRemoval().getField()}" value="${teacher.getRemoval().getValue()}">
                @template.components.button(label = "Togli", busyLabel = "Togli", variant = "text", width = "auto", opens = teacher.getRemoval().getId())
              </form>
            @endif
          </div>
        </li>
      @endfor
    </ul>
  </section>

  <section class="mt-8" aria-labelledby="inviti-titolo">
    <h2 id="inviti-titolo" class="mb-3 text-xl font-semibold tracking-tight">Inviti in attesa</h2>
    @if(page.getInvitations().isEmpty())
      <p class="text-ink-muted">Nessun invito in attesa.</p>
    @else
      <ul class="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-surface">
        @for(InvitationLine invitation : page.getInvitations())
          <li>
            <div class="flex min-h-16 flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3">
              <span class="grid size-10 shrink-0 place-items-center rounded-xl bg-brand-soft text-brand">
                @template.components.icon(icon = Icon.MAIL, classes = "size-6")
              </span>
              <span class="min-w-0 flex-[1_1_11rem]">
                <span class="block font-semibold wrap-anywhere">${invitation.getEmail()}</span>
                <span class="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-ink-muted">
                  @if(invitation.getExpired())
                    <span class="inline-flex shrink-0 items-center gap-1 rounded-full bg-warning-soft px-2.5 py-0.5 text-xs font-semibold whitespace-nowrap text-warning">Scaduto</span>
                  @endif
                  ${invitation.getDate()}
                </span>
              </span>
              <span class="-ml-3 flex flex-wrap gap-1 sm:ml-0 sm:shrink-0">
                <form method="post" action="/maestri/inviti/rimanda" hx-post="/maestri/inviti/rimanda" hx-target="#maestri-contenuto" hx-swap="outerHTML" hx-disabled-elt="find button[type=submit]">
                  @template.components.csrf(csrf = csrf)
                  <input type="hidden" name="email" value="${invitation.getEmail()}">
                  @template.components.button(label = "Manda di nuovo", busyLabel = "Invio…", variant = "text", width = "auto")
                </form>
                <form method="post" action="/maestri/inviti/ritira/conferma">
                  @template.components.csrf(csrf = csrf)
                  <input type="hidden" name="email" value="${invitation.getEmail()}">
                  @template.components.button(label = "Ritira", busyLabel = "Ritira", variant = "text", width = "auto", opens = invitation.getWithdrawal().getId())
                </form>
              </span>
            </div>
          </li>
        @endfor
      </ul>
    @endif
  </section>

  @for(TeacherLine teacher : page.getTeachers())
    @if(teacher.getRemoval() != null)
      @template.components.confirmDialog(dialog = teacher.getRemoval(), csrf = csrf)
    @endif
  @endfor
  @for(InvitationLine invitation : page.getInvitations())
    @template.components.confirmDialog(dialog = invitation.getWithdrawal(), csrf = csrf)
  @endfor
</div>
