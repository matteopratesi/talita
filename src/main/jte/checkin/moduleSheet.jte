@import talita.checkin.ModuleChildBlock
@import talita.checkin.ModuleChildRow
@import talita.checkin.ModuleDay
@import talita.checkin.ModulePage
@import talita.checkin.ModuleTeacherRow
@import talita.ui.AlertVariant
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param ModulePage page
<%-- The sheet of a form of the venue (TALITA-20, part 3, criteria 21-27 and 29-31; design part 3): the name of the form, the gathering and the venue, the children who are present on at least one day (surname and name, and, if the form
     asks for them, the age on the first day and a cell for each day) and the table of the teachers (the custodians and three blank rows), with a field for each column of the teachers that the form has.

     **The fields of the teachers are in the page and nowhere else.** Each one is an `<input type="text">` outside every `<form>` and with no `name`: the browser does not send it, htmx does not send it, and the server never sees
     what is written. They keep the browser from helping (`autocomplete`, `autocorrect`, `autocapitalize`, `spellcheck`: the advanced spell check of Chrome sends the text to Google), the page is not kept for the
     back button (`hx-history="false"`, and `app.js` empties the fields marked `data-solo-carta` when the page comes back from the cache of the browser), and nothing copies the value anywhere: the browser prints
     it as the text of the cell (the field has no border in print), and an empty one is the cell to be written by hand. The fields exist only if the form asks for them; the rows of the children never have fields.
     Every sheet of paper is a block with its table and its header (Safari does not repeat the `thead`). The tables scroll sideways on a phone as a region with a name that the keyboard reaches. --%>

@template.layout.shell(shell = shell, content = @`
  <div hx-history="false">
    <div class="-mt-2 mb-2 -ml-3 print:hidden">
      @template.components.linkButton(href = "/arrivi#stampe", label = "Torna agli arrivi", variant = "text", icon = Icon.CHEVRON_LEFT)
    </div>
    @template.components.pageHead(title = page.getHead().getTitle(), lead = page.getHead().getLead())
    @if(page.getEmpty() != null)
      @template.components.emptyState(icon = page.getEmpty().getIcon(), title = page.getEmpty().getTitle(), text = page.getEmpty().getText(), href = page.getEmpty().getHref(), label = page.getEmpty().getLabel(), variant = "secondary", buttonIcon = page.getEmpty().getButtonIcon())
    @else
      @template.checkin.printBar(help = page.getHelp())
      <p class="mb-1 text-sm text-ink-muted print:text-[8.5pt] print:text-ink">${page.getPrinted()}</p>
      @if(page.getAgeLine() != null)
        <p class="mb-3 text-sm text-ink-muted print:text-[8.5pt] print:text-ink">${page.getAgeLine()}</p>
      @endif
      <h2 class="mt-5 mb-2 text-lg font-semibold print:mt-[4mm] print:mb-[1.5mm] print:text-[11pt]">${page.getChildrenTitle()}</h2>
      <p class="mb-3 text-sm text-ink-muted print:text-[8.5pt] print:text-ink">${page.getLegend()}</p>
      <p class="mb-2 text-sm text-ink-muted lg:hidden print:hidden">Scorri la tabella di lato per vedere tutte le colonne.</p>
      <div tabindex="0" role="region" aria-label="${page.getHead().getTitle() + ", bambini, tabella che scorre di lato"}" class="relative overflow-x-auto rounded-2xl border border-line bg-surface print:overflow-visible print:rounded-none print:border-0">
        @for(ModuleChildBlock block : page.getChildren())
          <div class="blocco-stampa">
            <table class="tabella-stampa tabella-stampa--modulo ${page.getSqueezed() ? "tabella-stampa--7 " : ""}${page.getColumns().getDays().isEmpty() ? "min-w-0" : (page.getSqueezed() ? "min-w-208 print:min-w-0" : "min-w-144 print:min-w-0")}">
              <caption>${block.getCaption()}</caption>
              <thead>
                <tr>
                  <th class="col-nome" scope="col">Cognome e nome</th>
                  @if(page.getColumns().getAge())
                    <th class="col-eta" scope="col">Età</th>
                  @endif
                  @for(ModuleDay day : page.getColumns().getDays())
                    <th class="col-giorno" scope="col"><span aria-hidden="true">${day.getShort()}</span><span class="sr-only">${day.getSpoken()}</span></th>
                  @endfor
                </tr>
              </thead>
              <tbody>
                @for(ModuleChildRow row : block.getRows())
                  <tr>
                    <td><span class="cognome">${row.getLast()}</span> ${row.getFirst()}</td>
                    @if(page.getColumns().getAge())
                      <td class="col-eta">${row.getAge()}</td>
                    @endif
                    @for(String cell : row.getCells())
                      <td class="cella-presenza">${cell}</td>
                    @endfor
                  </tr>
                @endfor
              </tbody>
            </table>
          </div>
        @endfor
      </div>

      <div class="${page.getTeachers().getNewSheet() ? "print:break-before-page" : null}">
        <h2 class="mt-5 mb-2 text-lg font-semibold print:mt-[4mm] print:mb-[1.5mm] print:text-[11pt]">${page.getTeachersTitle()}</h2>
        @if(page.getNotice() != null)
          <div class="mb-3 print:hidden">
            @template.components.alert(variant = AlertVariant.INFO, lead = page.getNotice().get(0), content = @`${page.getNotice().get(1)}`)
          </div>
        @endif
        <div tabindex="0" role="region" aria-label="${page.getHead().getTitle() + ", maestri, tabella che scorre di lato"}" class="relative overflow-x-auto rounded-2xl border border-line bg-surface print:overflow-visible print:rounded-none print:border-0">
          <div class="blocco-stampa">
            <table class="tabella-stampa tabella-stampa--modulo ${page.getColumns().getFields() ? "tabella-stampa--maestri " : ""}${page.getSqueezed() ? "tabella-stampa--7 " : ""}${page.getColumns().getFields() ? "min-w-208 print:min-w-0" : (page.getColumns().getTeacherDays().isEmpty() ? "min-w-0" : (page.getSqueezed() ? "min-w-208 print:min-w-0" : "min-w-144 print:min-w-0"))}">
              <caption>${page.getTeachers().getCaption()}</caption>
              <thead>
                <tr>
                  <th class="col-nome" scope="col">Cognome e nome</th>
                  @if(page.getColumns().getAge())
                    <th class="col-eta" scope="col">Età</th>
                  @endif
                  @for(ModuleDay day : page.getColumns().getTeacherDays())
                    <th class="col-giorno" scope="col"><span aria-hidden="true">${day.getShort()}</span><span class="sr-only">${day.getSpoken()}</span></th>
                  @endfor
                  @if(page.getColumns().getDocument())
                    <th class="col-campo" scope="col">Documento d’identità</th>
                  @endif
                  @if(page.getColumns().getTaxCode())
                    <th class="col-campo" scope="col">Codice fiscale</th>
                  @endif
                </tr>
              </thead>
              <tbody>
                @for(ModuleTeacherRow row : page.getTeachers().getRows())
                  <tr class="${row.getBlank() != null ? "riga-vuota" : null}">
                    @if(row.getBlank() == null)
                      <td><span class="cognome">${row.getLast()}</span> ${row.getFirst()}</td>
                    @elseif(page.getColumns().getFields())
                      <td class="cella-campo"><input type="text" class="campo-stampa" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" maxlength="60" data-solo-carta aria-label="${row.label("Cognome e nome")}"></td>
                    @else
                      <td></td>
                    @endif
                    @if(page.getColumns().getAge())
                      <td class="col-eta"></td>
                    @endif
                    @for(ModuleDay day : page.getColumns().getTeacherDays())
                      <td class="cella-presenza"></td>
                    @endfor
                    @if(page.getColumns().getDocument())
                      <td class="cella-campo"><input type="text" class="campo-stampa" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" maxlength="20" data-solo-carta aria-label="${row.label("Documento d’identità")}"></td>
                    @endif
                    @if(page.getColumns().getTaxCode())
                      <td class="cella-campo"><input type="text" class="campo-stampa" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" maxlength="16" data-solo-carta aria-label="${row.label("Codice fiscale")}"></td>
                    @endif
                  </tr>
                @endfor
              </tbody>
            </table>
          </div>
        </div>
      </div>
      <p class="mt-3 text-sm text-ink-muted print:text-[8.5pt] print:text-ink">${page.getFooter()}</p>
    @endif
  </div>
`)
