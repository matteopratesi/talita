@import org.springframework.security.web.csrf.CsrfToken
@import talita.gathering.ReminderBlock
@import talita.gathering.RemindersCard
@param RemindersCard card
@param CsrfToken csrf
<%-- The card «Promemoria» of the detail of a gathering (TALITA-13, design part 1), the whole `#promemoria` section: it is what the answer of a send replaces (htmx:
     hx-target="#promemoria", hx-swap="outerHTML"), with the notice of what happened in it, so the rest of the detail, the scroll and the address stay. The dialogs are inside it,
     so they go away with the answer. With the registrations not open (a draft, closed, over) it has only the sentence that says that reminders go with the registrations open. --%>
<section id="promemoria" class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="promemoria-t">
  <h2 id="promemoria-t" class="text-lg font-semibold">Promemoria</h2>
  @if(!card.getOpen())
    <p class="mt-2 text-ink-muted">I promemoria si mandano con le iscrizioni aperte.</p>
  @else
    <p class="mt-2 text-ink-muted">Due email che mandi tu, con un tocco. Il testo è fisso: non ci sono nomi né dati dei bambini, e in ogni mail c’è il modo per smettere.</p>
    @if(card.getAlert() != null)
      <div class="mt-3">
        @template.components.alert(variant = card.getAlert().getVariant(), live = true, lead = card.getAlert().getLead(), content = @`${card.getAlert().getText()}`)
      </div>
    @endif
    @for(ReminderBlock block : card.getBlocks())
      @template.reminder.reminderBlock(block = block)
    @endfor
    <p class="mt-4 text-sm text-ink-muted">Se la connessione cade durante l’invio, riapri la pagina per controllare l’ultimo invio.</p>
    @for(ReminderBlock block : card.getBlocks())
      @if(block.getButton() != null)
        @template.components.confirmDialog(dialog = block.getButton().getDialog(), csrf = csrf)
      @endif
    @endfor
  @endif
</section>
