package talita.reminder

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import talita.account.isHtmxFragment
import talita.account.seeOther
import talita.gathering.GatheringReader
import talita.ui.Flash
import talita.ui.Section
import talita.ui.ShellFactory

/**
 * The reminders of the teachers (TALITA-13): `/convivenza/{id}/promemoria`, under the addresses of the gathering (`/convivenza` and what is below it), so only for teachers (`TeacherPaths` answers 403 to anyone else, for a
 * page and for a request made by hand, before a request gets here; a person without access goes to `/accedi`).
 *
 * - **POST** with the kind in the body (`tipo=maybe` or `tipo=notice`: nothing of a person in an address) sends the reminder, if everything allows it ([Reminders.send]).
 *   A request from htmx answers with the card `#promemoria` as a whole, with the notice in it: htmx replaces it and the rest of the detail, the scroll and the address stay. Anything
 *   else (the page of confirmation without JavaScript) leaves the notice in the session and goes back to the detail with a 303, which shows it once.
 * - **GET** with the kind in the query is the page of confirmation for whoever has no JavaScript: the same words as the dialog. When there is nothing to confirm (the registrations
 *   closed, one that cannot go yet, nobody to write to) it goes back to the detail, which says why.
 * - A gathering that does not exist, or a kind that is neither `maybe` nor `notice`, is the page that does not exist (and for htmx the message «Qualcosa non ha funzionato»):
 *   nothing is sent.
 */
@Controller
class ReminderController(
    private val shells: ShellFactory,
    private val gatherings: GatheringReader,
    private val reminders: Reminders,
    private val presenter: ReminderPresenter,
) {

    @GetMapping("/convivenza/{id:[0-9]{1,18}}/promemoria")
    fun confirm(@PathVariable id: Long, @RequestParam(name = "tipo", defaultValue = "") tipo: String): ModelAndView {
        val type = ReminderType.ofCode(tipo) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (gatherings.byId(id) == null) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val dialog = presenter.confirmation(id, type) ?: return seeOther("/convivenza/$id")
        return ModelAndView("pages/confirmAction", mapOf("shell" to shells.shell(page = dialog.title, current = Section.CONVIVENZA), "page" to dialog))
    }

    @PostMapping("/convivenza/{id:[0-9]{1,18}}/promemoria")
    fun send(@PathVariable id: Long, @RequestParam(name = "tipo", defaultValue = "") tipo: String, request: HttpServletRequest): ModelAndView {
        val type = ReminderType.ofCode(tipo) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val result = reminders.send(id, type)
        if (result == SendResult.NotFound) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val outcome = presenter.outcome(result)
        if (request.isHtmxFragment()) {
            return ModelAndView("reminder/remindersCard", mapOf("card" to presenter.card(id, outcome.alert))).also { it.status = outcome.status }
        }
        outcome.alert?.let { Flash.put(request, it) }
        return seeOther("/convivenza/$id")
    }
}
