@import org.springframework.security.web.csrf.CsrfToken
@import talita.reminder.EmailCard
@import talita.ui.AlertVariant
@import talita.ui.Icon
@param EmailCard card
@param CsrfToken csrf
<%-- The card about the mail that is not a login link (TALITA-13, design part 2), in two places: «Iscrizioni» (short, `#avviso-card`) and the profile (complete, `#email`, with the
     stop). Every action answers with the card as a whole, which htmx puts in the place of the old one (hx-target = the id of the section, hx-swap="outerHTML"): the rest of the page, the
     scroll and the address stay. The box is never ticked before the person ticks it, and it is saved with a button (a consent is a gesture, art. 7), so it also works without JavaScript.
     Stop and resume have no dialog: a tap undoes them, and the text says what happens. --%>
<section id="${card.getId()}" class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="${card.getId() + "-t"}">
  <h2 id="${card.getId() + "-t"}" class="text-lg font-semibold">${card.getTitle()}</h2>
  @if(card.getIntro() != null)
    <p class="mt-2">${card.getIntro()}</p>
  @endif
  @if(card.getAlert() != null)
    <div class="mt-3">
      @template.components.alert(variant = card.getAlert().getVariant(), live = true, content = @`${card.getAlert().getText()}`)
    </div>
  @elseif(card.getStanding() != null)
    <div class="mt-3">
      @template.components.alert(variant = AlertVariant.INFO, content = @`${card.getStanding()}`)
    </div>
  @endif

  @if(card.getAsk())
    <form method="post" action="${card.getAskAction()}" hx-post="${card.getAskAction()}" hx-target="${"#" + card.getId()}" hx-swap="outerHTML" hx-disabled-elt="find button[type=submit]" novalidate class="mt-3">
      @template.components.csrf(csrf = csrf)
      @template.registration.checkRow(id = "avviso", name = "notice", label = card.getAskLabel(), checked = card.getChecked(), hint = card.getAskHint())
      @if(card.getSince() != null)
        <p class="mt-2 text-sm text-ink-muted">${card.getSince()}</p>
      @endif
      <div class="mt-3">
        @template.components.button(label = "Salva la scelta", busyLabel = "Salvataggio…", variant = "secondary")
      </div>
    </form>
  @endif

  @if(card.getStopped())
    @if(card.getFull())
      <p class="mt-3">Per riprenderli basta un tocco: poi potrai chiedere di nuovo l’avviso.</p>
      <form method="post" action="/profilo/promemoria/riprendi" hx-post="/profilo/promemoria/riprendi" hx-target="${"#" + card.getId()}" hx-swap="outerHTML" hx-disabled-elt="find button[type=submit]" class="mt-3">
        @template.components.csrf(csrf = csrf)
        @template.components.button(label = "Riprendi i promemoria", busyLabel = "Salvataggio…", icon = Icon.BELL)
      </form>
    @else
      <div class="mt-3">
        @template.components.linkButton(href = card.getProfileHref(), label = "Vai al profilo", variant = "secondary", icon = Icon.USER_ROUND, width = "phone")
      </div>
    @endif
  @elseif(card.getFull())
    @if(card.getStopText() != null)
      <div class="mt-5 border-t border-line pt-5">
        <h3 class="font-semibold">Non voglio più promemoria</h3>
        <p class="mt-1 text-ink-muted">${card.getStopText()}</p>
        <form method="post" action="/profilo/promemoria/stop" hx-post="/profilo/promemoria/stop" hx-target="${"#" + card.getId()}" hx-swap="outerHTML" hx-disabled-elt="find button[type=submit]" class="mt-3">
          @template.components.csrf(csrf = csrf)
          @template.components.button(label = "Non mandarmi più promemoria", busyLabel = "Salvataggio…", variant = "secondary", icon = Icon.BELL_OFF)
        </form>
      </div>
    @endif
  @elseif(card.getAsk())
    <div class="-mb-2 -ml-3 mt-3">
      @template.components.linkButton(href = card.getProfileHref(), label = "Gestisci le email", variant = "text", icon = Icon.MAIL)
    </div>
  @endif
</section>
