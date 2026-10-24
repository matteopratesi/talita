@import talita.registration.ReadingView
@import talita.registration.TeacherDetailPage
@param TeacherDetailPage page
@param boolean oob = false
<%-- «Letture delle segnalazioni» (criteria 21, 22; design C 5): who read the notes and when, the most recent first. It says who and when, never what was written
     and never whether there was anything. After «Mostra le segnalazioni» the card is replaced by the htmx answer, out of band, with the new row. --%>
<section id="letture" hx-swap-oob="${oob ? "true" : null}" class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="letture-titolo">
  <h2 id="letture-titolo" class="text-lg font-semibold">Letture delle segnalazioni</h2>
  @if(page.getReadings().isEmpty())
    <%-- «ancora» promises that someone can still read them: once the notes are deleted (TALITA-7), the sentence does not say it. --%>
    <p class="mt-3 text-ink-muted">${page.getNotes().getDeleted() != null ? "Nessuno le ha lette." : "Nessuno le ha ancora lette."}</p>
  @else
    <ul class="mt-3 divide-y divide-line">
      @for(ReadingView reading : page.getReadings())
        <li class="py-2.5 first:pt-0 last:pb-0">
          <span class="flex flex-wrap items-center gap-x-2 gap-y-1">
            <span class="font-semibold wrap-anywhere">${reading.getName()}</span>
            @if(reading.getMine())
              @template.components.badge(label = "Sei tu")
            @endif
          </span>
          <span class="block text-sm text-ink-muted">${reading.getAt()}</span>
        </li>
      @endfor
    </ul>
  @endif
  <p class="mt-3 text-sm text-ink-muted">Dal più recente. Il registro dice chi e quando, non cosa c’era scritto.</p>
</section>
