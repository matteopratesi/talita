@import talita.registration.ChildView
@import talita.registration.OfferView
@import talita.ui.Icon
@param ChildView child
@param OfferView offer
@param String quotaAction
@param String quotaFields
@param boolean withHealth = true
@param boolean paper = false
<%-- The fields of a child (design A 1/2, 3-5): name, surname, date of birth in three fields, when the child comes with the quota that is due, the
     state of the registration, and the notes on health. One component for the first step, the modification, the paper form of the teachers and their
     corrections: they differ in what surrounds it. The names of the fields are `children[0].firstName`... (design A 1/2, 10). The errors are the ones
     that the server wrote for each field; the first wrong field of the page takes the focus (`aria-invalid`), after it the one with `data-fuoco`. --%>
!{String p = "children[" + child.getIndex() + "].";
String id = "b" + child.getIndex();
String firstError = child.getErrors().get("firstName");
String lastError = child.getErrors().get("lastName");
String birthError = child.getErrors().get("birth");
String statusError = child.getErrors().get("status");}
<div class="space-y-5">
  @if(child.getKnownId() != null)
    <input type="hidden" name="${p}childId" value="${child.getKnownId()}">
  @endif
  <div class="grid gap-5 sm:grid-cols-2">
    <div class="space-y-1.5">
      <label for="${id}-nome" class="block font-semibold">Nome</label>
      <input id="${id}-nome" name="${p}firstName" type="text" value="${child.getFirstName()}" autocomplete="off" autocapitalize="words" data-fuoco="${child.getFocus() ? "" : null}" aria-describedby="${firstError != null ? id + "-nome-errore" : null}" aria-invalid="${firstError != null ? "true" : null}" class="block min-h-12 w-full rounded-xl border border-line-strong bg-surface px-4 text-base text-ink focus:border-brand aria-[invalid=true]:border-danger aria-[invalid=true]:bg-danger-soft">
      @if(firstError != null)
        <p id="${id}-nome-errore" class="flex items-start gap-1.5 text-sm font-medium text-danger">
          @template.components.icon(icon = Icon.CIRCLE_ALERT, classes = "mt-0.5 size-4 shrink-0")
          <span class="min-w-0 wrap-anywhere">${firstError}@if(child.getTaken())<a href="/iscrizioni" class="font-semibold underline underline-offset-2">«Iscrizioni»</a>.@endif</span>
        </p>
      @endif
    </div>
    <div class="space-y-1.5">
      <label for="${id}-cognome" class="block font-semibold">Cognome</label>
      <input id="${id}-cognome" name="${p}lastName" type="text" value="${child.getLastName()}" autocomplete="off" autocapitalize="words" aria-describedby="${lastError != null ? id + "-cognome-errore" : null}" aria-invalid="${lastError != null ? "true" : null}" class="block min-h-12 w-full rounded-xl border border-line-strong bg-surface px-4 text-base text-ink focus:border-brand aria-[invalid=true]:border-danger aria-[invalid=true]:bg-danger-soft">
      @if(lastError != null)
        <p id="${id}-cognome-errore" class="flex items-start gap-1.5 text-sm font-medium text-danger">
          @template.components.icon(icon = Icon.CIRCLE_ALERT, classes = "mt-0.5 size-4 shrink-0")
          ${lastError}
        </p>
      @endif
    </div>
  </div>

  <fieldset aria-describedby="${birthError != null ? id + "-nascita-aiuto " + id + "-nascita-errore" : id + "-nascita-aiuto"}">
    <legend class="font-semibold">Data di nascita</legend>
    <p id="${id}-nascita-aiuto" class="mt-1.5 text-sm text-ink-muted">Scrivi giorno, mese e anno: per esempio 4 4 2016.</p>
    <div class="mt-2 flex gap-3">
      <div class="w-18">
        <label for="${id}-giorno" class="block text-sm text-ink-muted">Giorno</label>
        <input id="${id}-giorno" name="${p}birthDay" type="text" inputmode="numeric" autocomplete="off" value="${child.getBirthDay()}" aria-invalid="${birthError != null ? "true" : null}" class="mt-1 block min-h-12 w-full rounded-xl border border-line-strong bg-surface px-3 text-base text-ink focus:border-brand aria-[invalid=true]:border-danger aria-[invalid=true]:bg-danger-soft">
      </div>
      <div class="w-18">
        <label for="${id}-mese" class="block text-sm text-ink-muted">Mese</label>
        <input id="${id}-mese" name="${p}birthMonth" type="text" inputmode="numeric" autocomplete="off" value="${child.getBirthMonth()}" class="mt-1 block min-h-12 w-full rounded-xl border border-line-strong bg-surface px-3 text-base text-ink focus:border-brand aria-[invalid=true]:border-danger aria-[invalid=true]:bg-danger-soft" aria-invalid="${birthError != null ? "true" : null}">
      </div>
      <div class="w-26">
        <label for="${id}-anno" class="block text-sm text-ink-muted">Anno</label>
        <input id="${id}-anno" name="${p}birthYear" type="text" inputmode="numeric" autocomplete="off" value="${child.getBirthYear()}" class="mt-1 block min-h-12 w-full rounded-xl border border-line-strong bg-surface px-3 text-base text-ink focus:border-brand aria-[invalid=true]:border-danger aria-[invalid=true]:bg-danger-soft" aria-invalid="${birthError != null ? "true" : null}">
      </div>
    </div>
    @if(birthError != null)
      <p id="${id}-nascita-errore" class="mt-1.5 flex items-start gap-1.5 text-sm font-medium text-danger">
        @template.components.icon(icon = Icon.CIRCLE_ALERT, classes = "mt-0.5 size-4 shrink-0")
        ${birthError}
      </p>
    @endif
  </fieldset>

  @template.registration.presenceChoice(child = child, offer = offer, quotaAction = quotaAction, quotaFields = quotaFields)

  <fieldset aria-describedby="${statusError != null ? id + "-stato-errore" : null}">
    <legend class="font-semibold">Stato dell’iscrizione</legend>
    @if(statusError != null)
      <p id="${id}-stato-errore" class="mt-1.5 flex items-start gap-1.5 text-sm font-medium text-danger">
        @template.components.icon(icon = Icon.CIRCLE_ALERT, classes = "mt-0.5 size-4 shrink-0")
        ${statusError}
      </p>
    @endif
    <label class="flex min-h-12 items-start gap-3 py-2">
      <input type="radio" name="${p}status" value="confirmed" checked="${!"maybe".equals(child.getStatus())}" aria-invalid="${statusError != null ? "true" : null}" class="mt-0.5 size-6 shrink-0 accent-brand">
      <span class="min-w-0"><span class="block font-semibold">Confermata</span><span class="block text-sm text-ink-muted">Il bambino viene.</span></span>
    </label>
    <label class="flex min-h-12 items-start gap-3 py-2">
      <input type="radio" name="${p}status" value="maybe" checked="${"maybe".equals(child.getStatus())}" class="mt-0.5 size-6 shrink-0 accent-brand">
      <span class="min-w-0"><span class="block font-semibold">In forse</span><span class="block text-sm text-ink-muted">Non è ancora sicuro. Puoi cambiare idea finché le iscrizioni sono aperte.</span></span>
    </label>
  </fieldset>

  @if(withHealth)
    @template.registration.healthFields(child = child, paper = paper)
  @endif
</div>
