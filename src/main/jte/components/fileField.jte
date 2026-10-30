@import talita.ui.Icon
@param String id
@param String label
@param String hint
@param String accept
@param String name = null
@param String also = null
@param String error = null
<%-- The field that chooses a file (design TALITA-12, guide 4.2): the label always visible above, the help, the native field of the browser (on the phone it opens the
     choice between the camera, the photos and the files) and the error below, with the icon. `accept` only suggests: what the file really is, the server says. `also`:
     ids of other elements that describe the field (a notice above it). With an error the field is `aria-invalid` and has the focus (`autofocus`: the answer is a whole
     page, and `app.js` moves the focus only after an exchange of htmx). There is no `required`: the form has `novalidate`, the errors are ours. --%>
!{String described = id + "-aiuto" + (also != null ? " " + also : "") + (error != null ? " " + id + "-errore" : "");}
<div class="space-y-1.5">
  <label for="${id}" class="block font-semibold">${label}</label>
  <p id="${id}-aiuto" class="text-sm text-ink-muted">${hint}</p>
  <input id="${id}" name="${name != null ? name : id}" type="file" accept="${accept}" autofocus="${error != null}" aria-describedby="${described}" aria-invalid="${error != null ? "true" : null}" class="block min-h-12 w-full cursor-pointer overflow-hidden rounded-xl border border-line-strong bg-surface text-sm text-ink-muted file:mr-3 file:min-h-12 file:cursor-pointer file:border-0 file:bg-surface-muted file:px-4 file:text-base file:font-semibold file:text-ink focus:border-brand aria-[invalid=true]:border-danger aria-[invalid=true]:bg-danger-soft">
  @if(error != null)
    <p id="${id}-errore" class="flex items-start gap-1.5 text-sm font-medium text-danger">
      @template.components.icon(icon = Icon.CIRCLE_ALERT, classes = "mt-0.5 size-4 shrink-0")
      ${error}
    </p>
  @endif
</div>
