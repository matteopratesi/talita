@import org.springframework.security.web.csrf.CsrfToken
@import talita.registration.TeacherDetailPage
@import talita.ui.Icon
@param TeacherDetailPage page
@param CsrfToken csrf
@param boolean oob = false
<%-- «Segnalazioni» in the detail for the teachers (criteria 21, 22; design C 5): before the request only the button, and nothing about whether there is anything.
     «Mostra le segnalazioni» is a POST that writes the reading and then answers, so the notes are never in a page that nobody asked for. With htmx the answer is
     this card (the page is not replaced) and, out of band, the card of the readings (`oob`). --%>
!{talita.registration.NotesView notes = page.getNotes();}
<section id="segnalazioni" class="rounded-2xl border ${notes.getShown() ? "border-line-strong" : "border-line"} bg-surface p-4 lg:p-6" aria-labelledby="segnalazioni-titolo">
  <h2 id="segnalazioni-titolo" class="text-lg font-semibold">Segnalazioni</h2>
  @if(notes.getDeleted() != null)
    <div class="mt-3 flex items-start gap-3">
      <span class="grid size-10 shrink-0 place-items-center rounded-xl bg-surface-muted text-ink-muted">
        @template.components.icon(icon = Icon.TRASH_2, classes = "size-6")
      </span>
      <div class="min-w-0">
        <p class="font-semibold wrap-anywhere">${notes.getDeleted().getMain()}</p>
        <p class="mt-1 text-sm text-ink-muted">${notes.getDeleted().getSecond()}</p>
      </div>
    </div>
  @elseif(!notes.getShown())
    <p class="mt-3">Sono dati sulla salute. Ogni volta che le mostri, resta scritto chi le ha lette e quando.</p>
    <form method="post" action="${"/iscrizioni/" + notes.getId() + "/mostra-segnalazioni"}" hx-post="${"/iscrizioni/" + notes.getId() + "/mostra-segnalazioni"}" hx-target="#segnalazioni" hx-swap="outerHTML" hx-disabled-elt="find button[type=submit]" class="mt-3">
      @template.components.csrf(csrf = csrf)
      @template.components.button(label = "Mostra le segnalazioni", busyLabel = "Apertura…", variant = "secondary", icon = Icon.EYE, width = "full")
    </form>
    <p class="mt-2 text-sm text-ink-muted">Per vederle serve la rete: la lettura si registra prima di mostrarle.</p>
  @else
    <p class="mt-3 text-sm text-ink-muted" data-fuoco tabindex="-1">${notes.getReadAt()}</p>
    @if(notes.getRows().isEmpty())
      <p class="mt-3 font-semibold">Nessuna segnalazione.</p>
    @else
      <dl class="mt-3 divide-y divide-line">
        @for(kotlin.Pair<String, String> row : notes.getRows())
          <div class="flex flex-wrap items-baseline justify-between gap-x-4 py-2.5 first:pt-0"><dt class="text-ink-muted">${row.getFirst()}</dt><dd class="basis-full text-left font-semibold whitespace-pre-line wrap-anywhere">${row.getSecond()}</dd></div>
        @endfor
      </dl>
    @endif
    @if(notes.getConsent() != null)
      <p class="mt-3 text-sm text-ink-muted">${notes.getConsent()}</p>
    @endif
    <div class="-mb-2 -ml-3 mt-3 flex flex-wrap gap-1">
      @template.components.linkButton(href = "/iscrizioni/" + notes.getId(), label = "Nascondi le segnalazioni", variant = "text", icon = Icon.EYE)
      @if(notes.getCanCorrect())
        @template.components.linkButton(href = "/iscrizioni/" + notes.getId() + "/correggi-segnalazioni", label = "Correggi le segnalazioni", variant = "text", icon = Icon.PENCIL)
      @endif
    </div>
  @endif
</section>
@if(oob)
  @template.registration.readingsCard(page = page, oob = true)
@endif
