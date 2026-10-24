@import talita.checkin.SheetBlock
@import talita.checkin.SheetPage
@import talita.checkin.SheetRow
@import talita.ui.Shell
@param Shell shell
@param SheetPage page
<%-- Foglio per i maestri (criteria 26, 27; design part 3, 4): the child and the contact of the parent, for every registration that is not withdrawn, with the sentence that says
     to keep it among the teachers and to destroy it at the end. It has no notes on health: the answer to question 6 is no. Every sheet of paper is a block with its table. --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = page.getHead().getTitle(), lead = page.getHead().getLead())
  @if(page.getEmpty() != null)
    @template.components.emptyState(icon = page.getEmpty().getIcon(), title = page.getEmpty().getTitle(), text = page.getEmpty().getText(), href = page.getEmpty().getHref(), label = page.getEmpty().getLabel(), variant = "secondary", buttonIcon = page.getEmpty().getButtonIcon())
  @else
    @template.checkin.printBar(help = "Contiene i recapiti dei genitori: stampalo solo se ti serve e tienilo con te. Stampa su A4, in verticale.")
    <p class="mb-3 text-sm text-ink-muted print:text-[8.5pt] print:text-ink">${page.getHead().getPrintedLine()}</p>
    <p class="riquadro-riservato">${page.getNotice()}</p>
    <div class="relative overflow-x-auto rounded-2xl border border-line bg-surface print:overflow-visible print:rounded-none print:border-0">
      @for(SheetBlock block : page.getBlocks())
        <div class="blocco-stampa">
          <table class="tabella-stampa tabella-stampa--foglio">
            <caption>${block.getCaption()}</caption>
            <thead>
              <tr><th scope="col">Bambino</th><th class="col-recapito" scope="col">Recapito del genitore</th></tr>
            </thead>
            <tbody>
              @for(SheetRow row : block.getRows())
                <tr><td class="nome">${row.getName()}</td><td class="tabular-nums">${row.getPhone()}</td></tr>
              @endfor
            </tbody>
          </table>
          @if(block.getClosing())
            <p class="mt-3 text-sm text-ink-muted print:text-[8.5pt] print:text-ink">${page.getClosing()}</p>
          @endif
        </div>
      @endfor
    </div>
  @endif
`)
