@import org.springframework.security.web.csrf.CsrfToken
@import talita.room.RoomForm
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param RoomForm page
@param CsrfToken csrf
<%-- «Nuova camera» and «Modifica la camera» (criteria 2, 3, 9; design TALITA-11, part 2, 1): a name and the beds, nothing else. No `required`, no `maxlength` and no `pattern`: the
     errors are ours, the same for everyone, and the field comes back as it was written (422). The help under the name says to write the name of the house, and not who sleeps in it:
     it is the only defence against the name of a person, which no rule can recognise. The help under the beds, in a change, says how many people sleep in the room now: lowering the
     beds under them is allowed, and the room then says «5 persone per 4 posti.». --%>

@template.layout.shell(shell = shell, content = @`
  <div class="-mt-2 mb-2 -ml-3">
    @template.components.linkButton(href = "/convivenza/" + page.getGatheringId() + "/camere", label = "Torna alle camere", variant = "text", icon = Icon.CHEVRON_LEFT)
  </div>
  @template.components.pageHead(title = page.getTitle(), lead = page.getLead())

  @if(page.getAlert() != null)
    <div class="mb-3">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  <form method="post" action="${page.getAction()}" novalidate hx-disabled-elt="find button[type=submit]" class="rounded-2xl border border-line bg-surface p-4 lg:p-6">
    @template.components.csrf(csrf = csrf)
    <div class="space-y-5">
      @template.components.field(id = "nome", name = "name", label = "Nome della camera", value = page.getName(), hint = "Scrivi il nome della camera nella casa, per esempio «Camera 3» o «Stanza azzurra». Non scrivere chi ci dorme.", error = page.getNameError(), autocomplete = "off", autocapitalize = "sentences")
      @template.components.field(id = "posti", name = "beds", label = "Posti letto", value = page.getBeds(), hint = page.getHint(), error = page.getBedsError(), autocomplete = "off", inputmode = "numeric", width = "w-24")
    </div>
    <div class="mt-6 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
      @template.components.button(label = "Salva la camera", busyLabel = "Salvataggio…")
      @template.components.linkButton(href = "/convivenza/" + page.getGatheringId() + "/camere", label = "Annulla", variant = "secondary", width = "phone")
    </div>
  </form>
`)
