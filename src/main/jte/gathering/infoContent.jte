@import talita.gathering.InfoVariant
@import talita.gathering.PublicFee
@import talita.gathering.PublicInfo
@import talita.ui.AlertVariant
@param PublicInfo info
@param int level = 1
@param boolean preview = false
<%-- The content of the information page (criteria 24, 26, 27, 28, 33), one component for the public page (`level` 1: the title is the h1, the cards h2) and for
     the preview of the teachers (`level` 2: the title is an h2 of the same size, the cards h3). Nothing here is personal data: [info] holds only what the page shows.
     The parts that are empty are not drawn, cards included. In print (app.css and the `print:` classes) the button and its line are not printed, and where the button is
     there is the address of the page, complete, for whoever reads the sheet.
     [preview]: the button «Iscrivi i tuoi figli» is a button that does nothing (the notice of the preview says so), never a link to the registration. --%>
<div class="mb-6 lg:mb-8">
  @template.components.heading(level = level, classes = "text-2xl font-semibold tracking-tight wrap-anywhere lg:text-3xl", text = info.getTitle())
  @if(info.getVariant() == InfoVariant.OVER)
    <p class="mt-2 text-ink-muted">${info.getDates()}</p>
  @endif
</div>

@if(info.getVariant() == InfoVariant.CLOSED)
  <div class="mb-3">
    @template.components.alert(variant = AlertVariant.WARNING, content = @`Le iscrizioni sono chiuse. Per iscrivere un altro bambino o cambiare un’iscrizione già fatta, chiedi ai maestri.`)
  </div>
@elseif(info.getVariant() == InfoVariant.OVER)
  <div class="mb-3">
    @template.components.alert(variant = AlertVariant.INFO, content = @`La convivenza è finita. Per la prossima arriverà un link nuovo.`)
  </div>
@endif

@if(info.getVariant() != InfoVariant.OVER)
  <div class="space-y-3">
    <section class="break-inside-avoid rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="quando">
      @template.components.heading(level = level + 1, id = "quando", classes = "text-lg font-semibold", text = "Quando e dove")
      <dl class="mt-3 space-y-3">
        <div class="sm:grid sm:grid-cols-[11rem_1fr] sm:gap-6">
          <dt class="text-sm text-ink-muted">Quando</dt>
          <dd class="font-medium wrap-anywhere">${info.getDays()}</dd>
        </div>
        @if(info.getPlace() != null)
          <div class="sm:grid sm:grid-cols-[11rem_1fr] sm:gap-6">
            <dt class="text-sm text-ink-muted">Dove</dt>
            <dd class="font-medium wrap-anywhere">${info.getPlace()}</dd>
          </div>
        @endif
      </dl>
    </section>

    @if(!info.getFees().isEmpty())
      <section class="break-inside-avoid rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="quanto">
        @template.components.heading(level = level + 1, id = "quanto", classes = "text-lg font-semibold", text = "Quanto costa")
        <dl class="mt-3 divide-y divide-line">
          @for(PublicFee fee : info.getFees())
            <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5 first:pt-0 last:pb-0">
              <dt class="text-ink-muted">${fee.getLabel()}</dt>
              <dd class="text-right font-semibold">${fee.getValue()}</dd>
            </div>
          @endfor
        </dl>
      </section>
    @endif

    @if(!info.getParagraphs().isEmpty())
      <section class="break-inside-avoid rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="informazioni">
        @template.components.heading(level = level + 1, id = "informazioni", classes = "text-lg font-semibold", text = "Informazioni")
        <div class="mt-3 max-w-prose space-y-3">
          @for(String paragraph : info.getParagraphs())
            <p class="whitespace-pre-line wrap-anywhere">${paragraph}</p>
          @endfor
        </div>
      </section>
    @endif

    @if(!info.getWhatToBring().isEmpty())
      <section class="break-inside-avoid rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="portare">
        @template.components.heading(level = level + 1, id = "portare", classes = "text-lg font-semibold", text = "Cosa portare")
        <ul class="mt-3 list-disc space-y-1 pl-5 marker:text-ink-muted wrap-anywhere">
          @for(String thing : info.getWhatToBring())
            <li class="pl-1">${thing}</li>
          @endfor
        </ul>
      </section>
    @endif
  </div>
@endif

@if(info.getRegisterHref() != null)
  <div class="mt-8 print:hidden">
    @if(preview)
      <button type="button" aria-describedby="avviso-anteprima" class="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-brand px-5 text-base font-semibold text-white transition-colors duration-150 select-none hover:bg-brand-strong active:bg-brand-strong sm:w-auto">Iscrivi i tuoi figli</button>
    @else
      @template.components.linkButton(href = info.getRegisterHref(), label = "Iscrivi i tuoi figli", width = "phone")
    @endif
    <p class="mt-3 text-sm text-ink-muted">Per iscriverli entri con il tuo indirizzo email: ti mandiamo un link, senza password.</p>
  </div>
@endif

@if(info.getAddress() != null)
  <div class="mt-8 hidden break-inside-avoid border-t-2 border-ink pt-4 print:block">
    <p>Per iscrivere i tuoi figli apri questo indirizzo dal telefono o dal computer:</p>
    <p class="mt-2 text-xl font-bold break-all">${info.getAddress()}</p>
    <p class="mt-2 text-sm">Poi entri con il tuo indirizzo email: ti mandiamo un link, senza password.</p>
  </div>
@endif
