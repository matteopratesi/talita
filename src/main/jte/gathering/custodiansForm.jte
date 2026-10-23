@import org.springframework.security.web.csrf.CsrfToken
@import talita.gathering.CustodiansForm
@import talita.gathering.TeacherOption
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param CustodiansForm page
@param CsrfToken csrf
<%-- «Modifica i maestri affidatari» (criterion 8): a box for every teacher, by surname and name. Whoever has only an invitation is not here. The error is under
     the list; the first box carries aria-invalid so that the focus goes there after a refusal. --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.pageHead(title = "Modifica i maestri affidatari")

  @if(page.getAlert() != null)
    <div class="mb-3">
      @template.components.alert(variant = page.getAlert().getVariant(), live = true, content = @`${page.getAlert().getText()}`)
    </div>
  @endif

  <form method="post" action="${"/convivenza/" + page.getId() + "/affidatari"}" novalidate hx-disabled-elt="find button[type=submit]" class="rounded-2xl border border-line bg-surface p-4 lg:p-6">
    @template.components.csrf(csrf = csrf)
    <fieldset aria-describedby="${page.getError() != null ? "affidatari-aiuto affidatari-errore" : "affidatari-aiuto"}">
      <legend class="font-semibold">Maestri affidatari</legend>
      <p id="affidatari-aiuto" class="mt-1.5 text-sm text-ink-muted">I loro nomi compaiono nel testo dell’affidamento che i genitori accettano con l’iscrizione. Non compaiono nella pagina per i genitori.</p>
      <div class="mt-2 divide-y divide-line overflow-hidden rounded-2xl border border-line bg-surface">
        @for(TeacherOption teacher : page.getTeachers())
          <label class="flex min-h-14 items-center gap-3 px-4 py-2">
            <input type="checkbox" name="custodians" value="${teacher.getAccountId()}" checked="${teacher.getChecked()}" aria-invalid="${teacher.getInvalid() ? "true" : null}" class="size-6 shrink-0 accent-brand">
            <span class="min-w-0 flex-1 font-semibold wrap-anywhere">${teacher.getName()}</span>
            @if(teacher.getYou())
              @template.components.badge(label = "Sei tu")
            @endif
          </label>
        @endfor
      </div>
      @if(page.getError() != null)
        <p id="affidatari-errore" class="mt-2 flex items-start gap-1.5 text-sm font-medium text-danger">
          @template.components.icon(icon = Icon.CIRCLE_ALERT, classes = "mt-0.5 size-4 shrink-0")
          ${page.getError()}
        </p>
      @endif
      <p class="mt-3 text-sm text-ink-muted">Non vedi un maestro? Invitalo dalla pagina <a href="/maestri" class="font-semibold text-brand underline underline-offset-2">«Maestri»</a>: lo trovi qui dopo che ha accettato l’invito.</p>
    </fieldset>
    <div class="mt-6 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
      @template.components.button(label = "Salva le modifiche", busyLabel = "Salvataggio…")
      @template.components.linkButton(href = "/convivenza/" + page.getId(), label = "Annulla", variant = "secondary", width = "phone")
    </div>
  </form>
`)
