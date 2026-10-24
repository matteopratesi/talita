@import talita.checkin.NameBlock
@import talita.checkin.VenuePage
@import talita.ui.Shell
@param Shell shell
@param VenuePage page
<%-- Elenco per la struttura (criteria 25, 27; design part 3, 3): the children (whose registration is confirmed, or who are present on the chosen day) and the teachers in
     custody, surname and name and nothing else, for whoever hosts the gathering. The choice of who is among the children is a normal GET with a day of the gathering and nothing
     personal in the address. --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = page.getHead().getTitle(), lead = page.getHead().getLead())
  @if(page.getEmpty() != null)
    @template.components.emptyState(icon = page.getEmpty().getIcon(), title = page.getEmpty().getTitle(), text = page.getEmpty().getText(), href = page.getEmpty().getHref(), label = page.getEmpty().getLabel(), variant = "secondary", buttonIcon = page.getEmpty().getButtonIcon())
  @else
    @template.checkin.printBar(help = "Contiene solo nome e cognome, per chi ospita la convivenza. Stampa su A4, in verticale.", before = @`
      <form method="get" action="/arrivi/elenco" class="space-y-3">
        @template.components.selectField(id = "chi", name = "giorno", label = "Chi mettere fra i bambini", options = page.getOptions())
        @template.components.button(label = "Aggiorna l’elenco", busyLabel = "Aggiorno…", variant = "secondary", width = "phone")
      </form>
    `)
    <p class="mb-3 text-sm text-ink-muted print:text-[8.5pt] print:text-ink">${page.getHead().getPrintedLine()}</p>
    <h2 class="mt-5 mb-2 text-lg font-semibold print:mt-[4mm] print:mb-[1.5mm] print:text-[11pt]">${page.getChildrenTitle()}</h2>
    <div class="relative overflow-x-auto rounded-2xl border border-line bg-surface print:overflow-visible print:rounded-none print:border-0">
      @for(NameBlock block : page.getChildren())
        <div class="blocco-stampa">
          @template.checkin.nameTable(block = block)
        </div>
      @endfor
    </div>
    <div class="${page.getTeachers().getNewSheet() ? "print:break-before-page" : null}">
      <h2 class="mt-5 mb-2 text-lg font-semibold print:mt-[4mm] print:mb-[1.5mm] print:text-[11pt]">${page.getTeachersTitle()}</h2>
      <div class="relative overflow-x-auto rounded-2xl border border-line bg-surface print:overflow-visible print:rounded-none print:border-0">
        <div class="blocco-stampa">
          @template.checkin.nameTable(block = page.getTeachers())
        </div>
      </div>
    </div>
    <p class="mt-3 text-sm text-ink-muted print:text-[8.5pt] print:text-ink">${page.getFooter()}</p>
  @endif
`)
