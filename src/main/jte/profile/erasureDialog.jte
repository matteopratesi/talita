@import org.springframework.security.web.csrf.CsrfToken
@import talita.retention.ErasureDialog
@param ErasureDialog dialog
@param CsrfToken csrf
<%-- The dialog that deletes data (guide 4.8, `erasureDialog`): the native <dialog>, that app.js opens with showModal() when a button with data-apri="<id>" is touched.
     First focus on «Annulla». It scrolls inside its panel when it is taller than the screen. The form of the confirmation is boosted: the answer is a change of page
     (303 to the profile), and while it is on its way the button is disabled and says «Cancellazione…». The id has no name in it. --%>
<dialog id="${dialog.getId()}" aria-labelledby="${dialog.getId()}-titolo" aria-describedby="${dialog.getId()}-testo" class="mx-0 mt-auto mb-0 max-h-[92dvh] w-full max-w-none overflow-y-auto rounded-t-2xl bg-surface px-gutter pt-6 pb-[calc(1.5rem+env(safe-area-inset-bottom))] text-ink shadow-xl backdrop:bg-ink/50 motion-safe:animate-sheet-in sm:m-auto sm:max-w-md sm:rounded-2xl sm:p-6">
  <h2 id="${dialog.getId()}-titolo" class="text-xl font-semibold tracking-tight wrap-anywhere">${dialog.getTitle()}</h2>
  @template.profile.erasureText(dialog = dialog, id = dialog.getId() + "-testo")
  <div class="mt-6 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
    <form method="post" action="${dialog.getAction()}" hx-disabled-elt="find button[type=submit]" class="contents">
      @template.components.csrf(csrf = csrf)
      @template.components.button(label = dialog.getConfirmLabel(), busyLabel = dialog.getBusyLabel(), variant = "danger")
    </form>
    <form method="dialog" class="contents">
      <button autofocus class="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl border border-line-strong bg-surface px-5 text-base font-semibold text-ink transition-colors duration-150 select-none hover:bg-surface-muted active:bg-surface-muted sm:w-auto">Annulla</button>
    </form>
  </div>
</dialog>
