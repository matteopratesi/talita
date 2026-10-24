package talita.retention

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import talita.account.CurrentAccount
import talita.account.LoginEntryPoint
import talita.account.ProfileController
import talita.account.isHtmx
import talita.account.seeOther
import talita.ui.Flash
import talita.ui.ShellFactory

/**
 * The deletions on request from the profile (criteria 16-26): `POST /profilo/figli/{id}/cancella` and `POST /profilo/account/cancella`, from the dialogs of «I tuoi
 * dati», and the pages that stand in for the dialogs without JavaScript (the same addresses with GET: the same words, a button, «Annulla»). Open to every person who is
 * inside (every account is a parent, a teacher too: the addresses are not in `TeacherPaths`), and each person can do it only for their own: a child of someone else, or
 * one that never existed, is the page of an id that does not exist, on GET and on POST, whatever the person is.
 *
 * - **A change of page, nothing that updates itself**: the form of the dialog is boosted and the answer is a 303 to `/profilo` with a notice that is read once
 *   (in memory: it carries the name, never an address or a log). A second request for the same child, a double tap or a network that came back, is the same page with
 *   an information notice and no error; the register has one row.
 * - **A request that cannot be made** (a page that was left open, or a request made by hand) is the profile as it is now, with 409 and the notice «Non è stato cancellato
 *   niente», and below it what to do first. The check is made inside the transaction ([ErasureService]).
 * - **The account** ends the session and goes to `/accedi` as a whole page (`HX-Redirect`, or 303 without JavaScript), with «Account cancellato.» once.
 */
@Controller
class ErasureController(
    private val shells: ShellFactory,
    private val profile: ProfileController,
    private val erasures: ErasureService,
    private val data: YourDataPresenter,
) {

    private fun notFound(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND)

    private fun confirmPage(dialog: ErasureDialog): ModelAndView =
        ModelAndView("profile/confirmErasure", mapOf("shell" to shells.shell(page = dialog.title, current = null, onProfile = true), "page" to dialog))

    // ---- A child ------------------------------------------------------------------------------------------------------------------

    /** The page that stands in for the dialog without JavaScript. A child that is not the person's is a 404; one that cannot be deleted now leads back to the profile, which says why. */
    @GetMapping("/profilo/figli/{id:[0-9]{1,18}}/cancella")
    fun confirmChild(account: CurrentAccount, @PathVariable id: Long, request: HttpServletRequest): ModelAndView {
        val plan = erasures.planChild(account.id, id)
        if (plan == null) {
            if (!erasures.childWasErased(id)) notFound()
            Flash.put(request, data.childAlreadyDone())
            return seeOther("/profilo")
        }
        if (plan.blocked) return seeOther("/profilo")
        return confirmPage(data.childDialog(plan, data.shownName(account.id, id) ?: plan.firstName))
    }

    @PostMapping("/profilo/figli/{id:[0-9]{1,18}}/cancella")
    fun eraseChild(account: CurrentAccount, @PathVariable id: Long, request: HttpServletRequest): ModelAndView {
        // The name for the notice is worked out before: afterwards the child may not be there.
        val name = data.shownName(account.id, id)
        return when (val result = erasures.eraseChild(account.id, id)) {
            is ChildErasure.Done -> {
                Flash.put(request, data.childDone(result, name ?: result.firstName))
                seeOther("/profilo")
            }

            ChildErasure.AlreadyDone -> {
                Flash.put(request, data.childAlreadyDone())
                seeOther("/profilo")
            }

            ChildErasure.NotFound -> notFound()

            is ChildErasure.Blocked -> profile.page(account, data.notDeleted(), HttpStatus.CONFLICT)
        }
    }

    // ---- The account --------------------------------------------------------------------------------------------------------------

    @GetMapping("/profilo/account/cancella")
    fun confirmAccount(account: CurrentAccount): ModelAndView {
        val plan = erasures.planAccount(account.id) ?: return seeOther(LoginEntryPoint.LOGIN_PATH)
        if (plan.blocked) return seeOther("/profilo")
        return confirmPage(data.accountDialog(plan))
    }

    @PostMapping("/profilo/account/cancella")
    fun eraseAccount(account: CurrentAccount, request: HttpServletRequest, response: HttpServletResponse): Any =
        when (erasures.eraseAccount(account.id)) {
            is AccountErasure.Done -> {
                endSession(request, response)
                Flash.put(request, data.accountDeleted())
                goToLogin(request)
            }

            // The account is gone already (another phone, a second tap): the same ending, to the access page.
            AccountErasure.NotFound -> {
                endSession(request, response)
                goToLogin(request)
            }

            is AccountErasure.Blocked -> profile.page(account, data.notDeleted(), HttpStatus.CONFLICT)
        }

    /** The session of the person ends: the account that it pointed at is gone (or is only an address now), and the old cookie is worth nothing. */
    private fun endSession(request: HttpServletRequest, response: HttpServletResponse) {
        SecurityContextLogoutHandler().logout(request, response, null)
    }

    /** To the access page as a whole page: `HX-Redirect` for htmx (the shell, the navigation and the role change), a 303 otherwise. */
    private fun goToLogin(request: HttpServletRequest): Any =
        if (request.isHtmx()) ResponseEntity.ok().header("HX-Redirect", LoginEntryPoint.LOGIN_PATH).build<Void>() else seeOther(LoginEntryPoint.LOGIN_PATH)
}
