package talita.gathering

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import talita.account.CurrentAccount
import talita.account.seeOther
import talita.ui.Alert
import talita.ui.AlertVariant.SUCCESS
import talita.ui.AlertVariant.WARNING
import talita.ui.Flash
import talita.ui.Section
import talita.ui.ShellFactory

/**
 * The steps of the state of a gathering: open the registrations, close them, reopen them (criteria 9-14). A step is a POST to
 * `/convivenza/{id}/apri`, `/chiudi` or `/riapri`, and the GET of the same address is the page of confirmation that stands in for the
 * dialog without JavaScript (same words). The service ([Gatherings.change]) decides; this class says it in words.
 *
 * - **Done**, or **already done** by someone else a moment ago (a double tap, two teachers): 303 to the detail with the same
 *   confirmation, never an error;
 * - **a draft that is not complete**: the detail with the list of what is missing (422), each item a link to the part that fixes it;
 * - **a step that does not exist, a gathering that is over, another one that is current**: the detail with a warning (409); the state does
 *   not change.
 */
@Controller
class GatheringStateController(
    private val shells: ShellFactory,
    private val gatherings: Gatherings,
    private val presenter: GatheringPresenter,
) {

    @GetMapping("/convivenza/{id:[0-9]{1,18}}/apri")
    fun confirmOpen(@PathVariable id: Long): ModelAndView = confirmation(id, StateAction.OPEN)

    @GetMapping("/convivenza/{id:[0-9]{1,18}}/chiudi")
    fun confirmClose(@PathVariable id: Long): ModelAndView = confirmation(id, StateAction.CLOSE)

    @GetMapping("/convivenza/{id:[0-9]{1,18}}/riapri")
    fun confirmReopen(@PathVariable id: Long): ModelAndView = confirmation(id, StateAction.REOPEN)

    @PostMapping("/convivenza/{id:[0-9]{1,18}}/apri")
    fun open(account: CurrentAccount, @PathVariable id: Long, @RequestParam(defaultValue = "") from: String, request: HttpServletRequest): ModelAndView =
        step(account, id, StateAction.OPEN, from, request)

    @PostMapping("/convivenza/{id:[0-9]{1,18}}/chiudi")
    fun close(account: CurrentAccount, @PathVariable id: Long, @RequestParam(defaultValue = "") from: String, request: HttpServletRequest): ModelAndView =
        step(account, id, StateAction.CLOSE, from, request)

    @PostMapping("/convivenza/{id:[0-9]{1,18}}/riapri")
    fun reopen(account: CurrentAccount, @PathVariable id: Long, @RequestParam(defaultValue = "") from: String, request: HttpServletRequest): ModelAndView =
        step(account, id, StateAction.REOPEN, from, request)

    /** The page of confirmation, if the step makes sense now; otherwise back to the detail, which says how things are. */
    private fun confirmation(id: Long, action: StateAction): ModelAndView {
        val gathering = gatherings.find(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val today = gatherings.today()
        val possible = gathering.state == action.from && when (action) {
            StateAction.OPEN -> gatherings.missing(gathering, gatherings.custodians(id).size, today).isEmpty()
            StateAction.CLOSE -> true
            StateAction.REOPEN -> !gathering.endDate.isBefore(today) && gatherings.current() == null
        }
        if (!possible) return seeOther("/convivenza/$id")
        val model = presenter.confirm(id, action, gathering.isOver(today))
        return ModelAndView("pages/confirmAction", mapOf("shell" to shells.shell(page = model.title, current = Section.CONVIVENZA), "page" to model))
    }

    private fun step(account: CurrentAccount, id: Long, action: StateAction, from: String, request: HttpServletRequest): ModelAndView {
        val fromState = GatheringState.entries.firstOrNull { it.column.literal == from.trim() }
        return when (val result = gatherings.change(id, action, fromState)) {
            TransitionResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)

            is TransitionResult.Done, is TransitionResult.AlreadyDone -> {
                Flash.put(request, Alert(SUCCESS, confirmed(action)))
                seeOther("/convivenza/$id")
            }

            is TransitionResult.Incomplete -> detail(account, id, null, showMissing = true, status = HttpStatus.UNPROCESSABLE_ENTITY)

            TransitionResult.NotAllowed -> detail(account, id, Alert(WARNING, "Questo passaggio non è possibile."), status = HttpStatus.CONFLICT)

            TransitionResult.IsOver ->
                detail(account, id, Alert(WARNING, "Le iscrizioni non si possono riaprire: la convivenza è finita."), status = HttpStatus.CONFLICT)

            is TransitionResult.OtherIsCurrent ->
                detail(account, id, Alert(WARNING, "Non puoi riaprire le iscrizioni finché «${result.title}» non è chiusa."), status = HttpStatus.CONFLICT)
        }
    }

    private fun confirmed(action: StateAction): String = when (action) {
        StateAction.OPEN -> "Iscrizioni aperte."
        StateAction.CLOSE -> "Iscrizioni chiuse."
        StateAction.REOPEN -> "Iscrizioni riaperte."
    }

    private fun detail(account: CurrentAccount, id: Long, alert: Alert?, showMissing: Boolean = false, status: HttpStatus): ModelAndView {
        val gathering = gatherings.find(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return ModelAndView(
            "gathering/detail",
            mapOf("shell" to shells.shell(page = gathering.title, current = Section.CONVIVENZA), "page" to presenter.detail(gathering, account, alert, showMissing)),
        ).also { it.status = status }
    }
}
