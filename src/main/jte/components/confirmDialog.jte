@import org.springframework.security.web.csrf.CsrfToken
@import talita.ui.ConfirmModel
@param ConfirmModel dialog
@param CsrfToken csrf
<%-- A dialog of confirmation (design guide 4.8): the native <dialog>, that app.js opens with showModal() when a button with
     data-apri="<id>" is touched. It sits inside the block that the action replaces, so it goes away with the answer. The first
     focus is on «Annulla». Title = the question with the verb, text = what happens, button = the same verb. --%>
<dialog id="${dialog.getId()}" data-ambito="${dialog.getConfirmFor() != null ? "" : null}" aria-labelledby="${dialog.getId()}-titolo" aria-describedby="${dialog.getId()}-testo" class="mx-0 mt-auto mb-0 w-full max-w-none rounded-t-2xl bg-surface px-gutter pt-6 pb-[calc(1.5rem+env(safe-area-inset-bottom))] text-ink shadow-xl backdrop:bg-ink/50 motion-safe:animate-sheet-in sm:m-auto sm:max-w-md sm:rounded-2xl sm:p-6">
  <h2 id="${dialog.getId()}-titolo" class="text-xl font-semibold tracking-tight wrap-anywhere">${dialog.getTitle()}</h2>
  <div id="${dialog.getId()}-testo" class="mt-2 space-y-2 text-ink-muted">
    <p class="wrap-anywhere">${dialog.getText()}</p>
    @for(String paragraph : dialog.getMore())
      <p class="wrap-anywhere">${paragraph}</p>
    @endfor
    @if(dialog.getNote() != null)
      <p class="text-sm wrap-anywhere">${dialog.getNote()}</p>
    @endif
  </div>
  @if(dialog.getConfirmFor() != null)
    <%-- The action wants the confirmation with the passkey first (TALITA-15): the dialog stays open while the phone asks, and says so here, in a live region that the script of the passkeys fills. --%>
    <p aria-live="polite" aria-atomic="true" data-stato hidden class="mt-4 text-sm text-ink-muted"></p>
    <template data-attesa=""><strong>In attesa della conferma…</strong> Conferma con l’impronta, il volto o il codice di sblocco.<span class="hidden [@media(pointer:fine)]:inline"> Dal computer, il browser può chiederti di usare il telefono.</span></template>
  @endif
  <div class="mt-6 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
    <form method="post" action="${dialog.getAction()}" hx-post="${dialog.getTarget() != null ? dialog.getAction() : null}" hx-target="${dialog.getTarget()}" hx-swap="${dialog.getTarget() != null ? "outerHTML" : null}" hx-disabled-elt="find button[type=submit]" data-conferma="${dialog.getConfirmFor()}" class="contents">
      @template.components.csrf(csrf = csrf)
      <input type="hidden" name="${dialog.getField()}" value="${dialog.getValue()}">
      @template.components.button(label = dialog.getConfirmLabel(), busyLabel = dialog.getBusyLabel(), variant = dialog.getDanger() ? "danger" : "primary")
    </form>
    <form method="dialog" class="contents">
      <button autofocus class="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl border border-line-strong bg-surface px-5 text-base font-semibold text-ink transition-colors duration-150 select-none hover:bg-surface-muted active:bg-surface-muted sm:w-auto">Annulla</button>
    </form>
  </div>
</dialog>
