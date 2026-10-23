@import talita.registration.ChildView
@import talita.registration.DayRow
@import talita.registration.KindOption
@import talita.registration.OfferView
@import talita.ui.Icon
@param ChildView child
@param OfferView offer
@param String formId
@param String quotaAction
<%-- «Quando viene?» (criteria 1, 4, 5; design A 1/2, 4): what the fees offer and nothing else. «Tutti i giorni» or «Alcuni giorni», and under «Alcuni giorni» a
     segment of choices for each day, shown by CSS (`:has()`), so that it also works without JavaScript. Nothing is chosen before the person chooses: the
     presence decides the fee. The fee of the child is a line that the server writes (htmx replaces its inside, never the line: it is a live region). With only
     the full fee there is nothing to choose: the sentence is read only and the value goes in a hidden field. --%>
!{String p = "children[" + child.getIndex() + "].";
String id = "b" + child.getIndex();
String error = child.getErrors().get("presence");
String selected = child.getPresence();}
@if(!offer.getChoice())
  <div>
    <p class="font-semibold">Quando viene?</p>
    <p class="mt-1.5 font-semibold">${offer.getAllDaysLabel()}</p>
    <p class="text-sm text-ink-muted">${offer.getAllDaysHelp()}</p>
    <input type="hidden" name="${p}presence" value="all_days" data-quota>
    <p id="quota-${child.getIndex()}" aria-live="polite" class="mt-3 flex flex-wrap items-baseline justify-between gap-x-4 rounded-xl bg-brand-soft px-4 py-3 busy:opacity-60 empty:hidden">@if(child.getQuota() != null)<span>Quota</span><strong class="font-bold">${child.getQuota()}</strong>@endif</p>
  </div>
@else
  <fieldset class="group min-w-0" aria-describedby="${error != null ? id + "-quando-errore" : null}" hx-post="${quotaAction}" hx-trigger="change" hx-swap="none" hx-include="${"#" + formId + " [data-quota], #" + formId + " [name='_csrf']"}" hx-sync="closest form:replace">
    <legend class="font-semibold">Quando viene?</legend>
    @if(error != null)
      <p id="${id}-quando-errore" class="mt-1.5 flex items-start gap-1.5 text-sm font-medium text-danger">
        @template.components.icon(icon = Icon.CIRCLE_ALERT, classes = "mt-0.5 size-4 shrink-0")
        ${error}
      </p>
    @endif
    <label class="flex min-h-12 items-start gap-3 py-2">
      <input type="radio" name="${p}presence" value="all_days" checked="${"all_days".equals(selected)}" aria-invalid="${error != null ? "true" : null}" data-quota class="mt-0.5 size-6 shrink-0 accent-brand">
      <span class="min-w-0"><span class="block font-semibold">${offer.getAllDaysLabel()}</span><span class="block text-sm text-ink-muted wrap-anywhere">${offer.getAllDaysHelp()}</span></span>
    </label>
    @if(offer.getSomeDays())
      <label class="flex min-h-12 items-start gap-3 py-2">
        <input type="radio" name="${p}presence" value="days" checked="${"days".equals(selected)}" data-quota class="mt-0.5 size-6 shrink-0 accent-brand">
        <span class="min-w-0"><span class="block font-semibold">Alcuni giorni</span><span class="block text-sm text-ink-muted">${offer.getSomeDaysHelp()}</span></span>
      </label>
      <div class="mt-1 ml-9 hidden rounded-2xl border border-line bg-canvas p-4 group-has-[input[value=days]:checked]:block not-supports-[selector(:has(*))]:block">
        <p class="text-sm text-ink-muted">${offer.getDaysHelp()}</p>
        <div class="mt-3 space-y-4">
          @for(DayRow row : offer.getDayRows())
            <fieldset class="min-w-0">
              <legend class="mb-1.5 font-semibold">${row.getLabel()}</legend>
              <div class="grid ${row.getKinds().size() > 1 ? "grid-cols-3" : "grid-cols-2"} gap-2">
                <label class="relative block">
                  <input type="radio" name="${p}day.${row.getKey()}" value="" checked="${child.getDays().get(row.getKey()) == null || child.getDays().get(row.getKey()).isEmpty()}" data-quota class="peer absolute inset-0 size-full cursor-pointer opacity-0">
                  <span class="flex min-h-12 items-center justify-center rounded-xl border border-line-strong bg-surface px-1.5 py-1 text-center text-sm font-medium transition-colors duration-150 peer-checked:border-brand peer-checked:bg-brand-soft peer-checked:font-semibold peer-checked:text-brand peer-checked:ring-1 peer-checked:ring-brand peer-focus-visible:outline-3 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-focus">Non viene</span>
                </label>
                @for(KindOption kind : row.getKinds())
                  <label class="relative block">
                    <input type="radio" name="${p}day.${row.getKey()}" value="${kind.getCode()}" checked="${kind.getCode().equals(child.getDays().get(row.getKey()))}" data-quota class="peer absolute inset-0 size-full cursor-pointer opacity-0">
                    <span class="flex min-h-12 items-center justify-center rounded-xl border border-line-strong bg-surface px-1.5 py-1 text-center text-sm font-medium transition-colors duration-150 peer-checked:border-brand peer-checked:bg-brand-soft peer-checked:font-semibold peer-checked:text-brand peer-checked:ring-1 peer-checked:ring-brand peer-focus-visible:outline-3 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-focus">${kind.getLabel()}</span>
                  </label>
                @endfor
              </div>
            </fieldset>
          @endfor
        </div>
      </div>
    @endif
    @if(offer.getLunchOnly())
      <label class="flex min-h-12 items-start gap-3 py-2">
        <input type="radio" name="${p}presence" value="lunch" checked="${"lunch".equals(selected)}" data-quota class="mt-0.5 size-6 shrink-0 accent-brand">
        <span class="min-w-0"><span class="block font-semibold">Solo pranzo</span><span class="block text-sm text-ink-muted">${offer.getLunchHelp()}</span></span>
      </label>
    @endif
    <p id="quota-${child.getIndex()}" aria-live="polite" class="mt-3 flex flex-wrap items-baseline justify-between gap-x-4 rounded-xl bg-brand-soft px-4 py-3 busy:opacity-60 empty:hidden">@if(child.getQuota() != null)<span>Quota</span><strong class="font-bold">${child.getQuota()}</strong>@endif</p>
  </fieldset>
@endif
