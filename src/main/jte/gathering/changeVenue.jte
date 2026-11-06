@import org.springframework.security.web.csrf.CsrfToken
@import talita.gathering.VenueChangePage
@import talita.ui.Shell
@param Shell shell
@param VenueChangePage page
@param CsrfToken csrf
<%-- «Cambiare la struttura?» as a page (TALITA-20, criterion 12): what stands in for the dialog without JavaScript, and when the page of the form was old (the people in the rooms were more than the dialog said).
     The same words as the dialog, the form again with its fields as hidden ones, and the button of danger that sends it with the number of people that were said (`cambio`): the server then takes them out of the rooms
     in the same transaction that changes the venue. «Annulla» goes back to the form. --%>

@template.layout.shell(shell = shell, content = @`
  <div class="mx-auto w-full max-w-md">
    @template.components.pageHead(title = "Cambiare la struttura?")
    <div class="space-y-2 text-ink-muted">
      <p class="wrap-anywhere">${page.getFirst()}</p>
      @if(page.getSecond() != null)
        <p class="wrap-anywhere">${page.getSecond()}</p>
      @endif
    </div>
    <form method="post" action="${"/convivenza/" + page.getId() + "/date"}" hx-disabled-elt="find button[type=submit]" class="mt-6 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
      @template.components.csrf(csrf = csrf)
      <input type="hidden" name="title" value="${page.getTitle()}">
      @if(page.getStartDate() != null)
        <input type="hidden" name="startDate" value="${page.getStartDate()}">
        <input type="hidden" name="endDate" value="${page.getEndDate()}">
      @endif
      <input type="hidden" name="venue" value="${page.getVenue()}">
      <input type="hidden" name="cambio" value="${page.getPeople()}">
      @template.components.button(label = "Cambia la struttura", busyLabel = "Cambio…", variant = "danger")
      @template.components.linkButton(href = "/convivenza/" + page.getId() + "/date", label = "Annulla", variant = "secondary", width = "phone")
    </form>
  </div>
`)
