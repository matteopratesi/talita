@import talita.checkin.RegisterBlock
@import talita.checkin.RegisterCell
@import talita.checkin.RegisterDay
@import talita.checkin.RegisterPage
@import talita.checkin.RegisterRow
@import talita.ui.Shell
@param Shell shell
@param RegisterPage page
<%-- Registro arrivi (criteria 23, 24, 27; design part 3, 2): a row for every registration that is not withdrawn, with the days (G, P or –, and the box, with an X when the presence is
     marked in Talità), what is due, what was paid, the manleva and the notes of the app, then five blank rows and the totals. Every sheet of paper is a block with its table and
     its header. No contacts and no notes on health: the register is kept for a year. The box of the table scrolls sideways on a phone, so it is a region with a name that the
     keyboard reaches (`tabindex="0"`: without it a person who does not use the touch cannot scroll it). --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = page.getHead().getTitle(), lead = page.getHead().getLead())
  @if(page.getEmpty() != null)
    @template.components.emptyState(icon = page.getEmpty().getIcon(), title = page.getEmpty().getTitle(), text = page.getEmpty().getText(), href = page.getEmpty().getHref(), label = page.getEmpty().getLabel(), variant = "secondary", buttonIcon = page.getEmpty().getButtonIcon())
  @else
    @template.checkin.printBar(help = "Stampa su fogli A4, in verticale. Da telefono puoi anche salvarlo in PDF dal menu di stampa.")
    <p class="mb-3 text-sm text-ink-muted print:text-[8.5pt] print:text-black">${page.getHead().getPrintedLine()}</p>
    <p class="mb-3 text-sm text-ink-muted print:text-[8.5pt] print:text-black">${page.getLegend()}</p>
    <p class="mb-2 text-sm text-ink-muted lg:hidden print:hidden">Scorri la tabella di lato per vedere tutte le colonne.</p>
    <div tabindex="0" role="region" aria-label="Registro arrivi, tabella che scorre di lato" class="relative overflow-x-auto rounded-2xl border border-line bg-surface print:overflow-visible print:rounded-none print:border-0">
      @for(RegisterBlock block : page.getBlocks())
        <div class="blocco-stampa">
          <table class="tabella-stampa min-w-176 print:min-w-0${page.getSqueezed() ? " tabella-stampa--7" : ""}">
            <caption>${block.getCaption()}</caption>
            <thead>
              <tr>
                <th class="col-nome" scope="col">Bambino</th>
                @for(RegisterDay day : page.getDays())
                  <th class="col-giorno" scope="col"><span aria-hidden="true">${day.getShort()}</span><span class="sr-only">${day.getSpoken()}</span></th>
                @endfor
                <th class="col-dovuta" scope="col">Dovuta</th>
                <th class="col-pagato" scope="col">Pagato</th>
                <th class="col-manleva" scope="col">Manleva</th>
                <th class="col-note" scope="col">Note</th>
              </tr>
            </thead>
            <tbody>
              @for(RegisterRow row : block.getRows())
                @if(row.getBlank())
                  <tr class="riga-vuota">
                    <td></td>
                    @for(RegisterCell cell : row.getCells())
                      <td class="cella-giorno"><span class="casella" aria-hidden="true"></span></td>
                    @endfor
                    <td class="col-dovuta"></td>
                    <td class="col-pagato"></td>
                    <td class="col-manleva"><span class="casella" aria-hidden="true"></span></td>
                    <td class="col-note"></td>
                  </tr>
                @else
                  <tr>
                    <td class="nome">${row.getName()}</td>
                    @for(RegisterCell cell : row.getCells())
                      <td class="cella-giorno"><span class="lettera">${cell.getLetter()}</span>@if(cell.getPresent())<span class="casella" aria-hidden="true">X</span><span class="sr-only">presente</span>@else<span class="casella" aria-hidden="true"></span>@endif</td>
                    @endfor
                    <td class="col-dovuta">${row.getDue()}</td>
                    <td class="col-pagato">${row.getPaid()}</td>
                    <td class="col-manleva">@if(row.getManleva() != null)${row.getManleva()}@else<span class="casella" aria-hidden="true"></span><span class="sr-only">da ricevere</span>@endif</td>
                    <td class="col-note">${row.getNotes()}</td>
                  </tr>
                @endif
              @endfor
            </tbody>
          </table>
          @if(block.getTotals() != null)
            <table class="totali-stampa">
              <caption class="sr-only">Totali del registro</caption>
              <tbody>
                <tr><th scope="row">Totale dovuto dalle iscrizioni confermate</th><td>${block.getTotals().getConfirmed()}</td></tr>
                <tr><th scope="row">Dovuto da chi è in forse (a parte)</th><td>${block.getTotals().getMaybe()}</td></tr>
                <tr><th scope="row">Incassato registrato in Talità (anche delle iscrizioni ritirate)</th><td>${block.getTotals().getCollected()}</td></tr>
                <tr><th scope="row">Incassato a mano</th><td class="scrivi"><span class="sr-only">Da scrivere a mano</span></td></tr>
              </tbody>
            </table>
          @endif
        </div>
      @endfor
    </div>
    <p class="mt-3 text-sm text-ink-muted print:text-[8.5pt] print:text-black">${page.getFooter()}</p>
  @endif
`)
