@import talita.room.PrintPage
@import talita.room.PrintPerson
@import talita.room.PrintRoom
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param PrintPage page
<%-- The print «Camere» (criterion 14; design TALITA-11, part 2, 3): a document of the teachers on A4, upright, black on white. The title, the dates, when it was printed, the sentence that
     says to keep it among the teachers and to destroy it at the end, a block for each room (its name and beds, the children one for each line, then the teachers) and, at the bottom, who
     has no room. It has no ages, no presences, no contacts and no notes on health, and never the count of the people of a room. A room is never split between two sheets
     (`break-inside-avoid`); the block of who has no room may be, with its lines whole and in two columns (with thirty children it is longer than a page). --%>

@template.layout.shell(shell = shell, content = @`
  <div class="-mt-2 mb-2 -ml-3 print:hidden">
    @template.components.linkButton(href = "/convivenza/" + page.getGatheringId() + "/camere", label = "Torna alle camere", variant = "text", icon = Icon.CHEVRON_LEFT)
  </div>
  @template.components.pageHead(title = page.getTitle(), lead = page.getLead())
  @if(page.getEmpty() != null)
    @template.components.emptyState(icon = page.getEmpty().getIcon(), title = page.getEmpty().getTitle(), text = page.getEmpty().getText(), href = "/convivenza/" + page.getGatheringId() + "/camere", label = "Torna alle camere", variant = "secondary", buttonIcon = Icon.CHEVRON_LEFT)
  @else
    @template.checkin.printBar(help = "Contiene i nomi di chi dorme in ogni camera: stampalo solo se ti serve e tienilo fra i maestri. Stampa su A4, in verticale.")
    <p class="mb-3 text-sm text-ink-muted print:text-[8.5pt] print:text-ink">${page.getPrintedLine()}</p>
    <p class="riquadro-riservato">${page.getNotice()}</p>
    <div class="grid gap-3 print:gap-[4mm]">
      @for(PrintRoom room : page.getRooms())
        <section id="stampa-camera-${room.getId()}" aria-labelledby="${"stampa-camera-" + room.getId() + "-t"}" class="rounded-lg border border-ink bg-surface p-3 break-inside-avoid print:rounded-none print:border-[0.3mm] print:px-4 print:py-2.5">
          <h2 id="${"stampa-camera-" + room.getId() + "-t"}" class="flex items-baseline justify-between gap-3 text-lg font-bold break-after-avoid print:text-[12pt]"><span class="wrap-anywhere">${room.getName()}</span><small class="shrink-0 text-sm font-normal text-ink-muted print:text-[10pt] print:text-ink">${room.getBeds()}</small></h2>
          @if(room.getChildren().isEmpty() && room.getTeachers().isEmpty())
            <p class="mt-1 text-ink-muted print:text-[10.5pt] print:text-ink">Vuota</p>
          @endif
          @if(!room.getChildren().isEmpty())
            <ul class="mt-1">
              @for(PrintPerson person : room.getChildren())
                <li class="wrap-anywhere print:text-[10.5pt]">${person.getName()}@if(person.getMaybe()) <small>(in forse)</small>@endif</li>
              @endfor
            </ul>
          @endif
          @if(!room.getTeachers().isEmpty())
            <p class="mt-2 text-sm font-semibold break-after-avoid print:text-[9.5pt]">Maestri</p>
            <ul>
              @for(PrintPerson person : room.getTeachers())
                <li class="wrap-anywhere print:text-[10.5pt]">${person.getName()}</li>
              @endfor
            </ul>
          @endif
        </section>
      @endfor
      @if(page.getFree() != null)
        <section id="stampa-senza-camera" aria-labelledby="stampa-senza-camera-t" class="rounded-lg border border-dashed border-ink bg-surface p-3 print:rounded-none print:border-[0.3mm] print:px-4 print:py-2.5">
          <h2 id="stampa-senza-camera-t" class="text-lg font-bold break-after-avoid print:text-[12pt]">${page.getFree().getTitle()}</h2>
          @if(!page.getFree().getChildren().isEmpty())
            <p class="mt-2 text-sm font-semibold break-after-avoid print:text-[9.5pt]">Bambini</p>
            <ul class="print:columns-2 print:gap-x-6">
              @for(PrintPerson person : page.getFree().getChildren())
                <li class="wrap-anywhere break-inside-avoid print:text-[10.5pt]">${person.getName()}@if(person.getMaybe()) <small>(in forse)</small>@endif</li>
              @endfor
            </ul>
          @endif
          @if(!page.getFree().getTeachers().isEmpty())
            <p class="mt-2 text-sm font-semibold break-after-avoid print:text-[9.5pt]">Maestri</p>
            <ul class="print:columns-2 print:gap-x-6">
              @for(PrintPerson person : page.getFree().getTeachers())
                <li class="wrap-anywhere break-inside-avoid print:text-[10.5pt]">${person.getName()}</li>
              @endfor
            </ul>
          @endif
        </section>
      @endif
    </div>
    <p class="mt-3 text-sm text-ink-muted print:text-[8.5pt] print:text-ink">${page.getFooter()}</p>
  @endif
`)
