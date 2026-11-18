package talita.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import talita.account.AccessFlash
import talita.account.CurrentAccount
import talita.account.PasskeyConfirmations
import talita.account.ProfileController
import talita.account.isHtmxFragment
import talita.account.seeOther
import talita.ui.Alert
import talita.ui.ShellFactory

/**
 * The card «Token per le API» of the profile (TALITA-21, criteria 1-5): `POST /profilo/token` makes the token, `POST /profilo/token/revoca` takes it away, `GET /profilo/token` draws the block again («Fatto»),
 * and the pages that stand in for the dialogs without JavaScript (`…/conferma`). On the chain of the pages, with the CSRF code of always, only for the teachers (`TeacherPaths`: a parent gets 403).
 *
 * - **Making a token** wants the confirmation with the passkey for a teacher who has one, decided by the server at every request ([PasskeyConfirmations.needed]): without it the answer is 409 «Serve la
 *   conferma.» and nothing is made. A teacher with no passkey makes it with a tap.
 * - **The answer that makes it is the only place where the value is**: 200 with the block (htmx) or the whole profile (without JavaScript), `Cache-Control: no-store`, `hx-history="false"` on the block. The value is
 *   text to select: not in an address, not in an attribute, not in the log. A page that makes it again by reloading makes a new token and the old one stops working: acceptable.
 * - **«Revoca» carries the number of the token that the page showed**: if it is gone or is another one (a double tap, two pages), nothing changes and the block says so, so that the token of now is never
 *   taken away by a request about an old one. It does not ask for the passkey (it lowers the risk).
 */
@Controller
class ApiTokenController(
    private val tokens: ApiTokens,
    private val presenter: ApiTokenPresenter,
    private val confirmations: PasskeyConfirmations,
    private val profile: ProfileController,
    private val shells: ShellFactory,
) {

    private fun confirmFirst(account: CurrentAccount, request: HttpServletRequest) = confirmations.needed(account, request.getSession(false))

    /** The block as the answer of an action: htmx gets it, a form without JavaScript gets the whole profile (the block is the card). */
    private fun answer(account: CurrentAccount, request: HttpServletRequest, response: HttpServletResponse, block: TokenBlock, status: HttpStatus = HttpStatus.OK): ModelAndView {
        response.setHeader("Cache-Control", "no-store")
        if (request.isHtmxFragment()) return ModelAndView("profile/tokenBlock", mapOf("block" to block, "oob" to false)).also { it.status = status }
        return profile.page(account, null, status, token = block)
    }

    /** After an action that leaves a notice for the page that a form without JavaScript goes back to. */
    private fun notice(account: CurrentAccount, request: HttpServletRequest, response: HttpServletResponse, alert: Alert, status: HttpStatus = HttpStatus.OK): ModelAndView {
        if (request.isHtmxFragment()) return answer(account, request, response, presenter.block(account.id, confirmFirst(account, request), alert), status)
        AccessFlash.put(request, "token", alert)
        return seeOther("/profilo#token")
    }

    @GetMapping("/profilo/token")
    fun reload(account: CurrentAccount, request: HttpServletRequest, response: HttpServletResponse): ModelAndView =
        if (request.isHtmxFragment()) answer(account, request, response, presenter.block(account.id, confirmFirst(account, request), AccessFlash.take(request, "token"))) else seeOther("/profilo#token")

    @PostMapping("/profilo/token")
    fun create(account: CurrentAccount, request: HttpServletRequest, response: HttpServletResponse): ModelAndView {
        if (confirmFirst(account, request)) {
            // 409 for htmx and for a request made by hand alike: the block (or the whole profile) as it is, with what to do first.
            val alert = if (request.isHtmxFragment()) presenter.needsConfirmation() else presenter.needsConfirmationWithoutScript()
            return answer(account, request, response, presenter.block(account.id, confirmFirst = true, alert = alert), HttpStatus.CONFLICT)
        }
        val made = tokens.create(account.id) ?: return seeOther("/profilo")
        return answer(account, request, response, presenter.created(made, confirmFirst = false))
    }

    @PostMapping("/profilo/token/revoca")
    fun revoke(account: CurrentAccount, @RequestParam(name = "token", defaultValue = "") target: String, request: HttpServletRequest, response: HttpServletResponse): ModelAndView {
        val id = target.trim().toLongOrNull()
        val alert = if (id != null && tokens.revoke(account.id, id)) presenter.revoked() else presenter.alreadyRevoked()
        return notice(account, request, response, alert)
    }

    // ---- Without JavaScript: the page that stands in for the dialog -----------------------------------------------------------------------

    @PostMapping("/profilo/token/revoca/conferma")
    fun confirmRevocation(account: CurrentAccount, @RequestParam(name = "token", defaultValue = "") target: String, request: HttpServletRequest): ModelAndView =
        confirmPage(presenter.dialogFor(account.id, confirmFirst(account, request), "revoca", target))

    @PostMapping("/profilo/token/nuovo/conferma")
    fun confirmNew(account: CurrentAccount, request: HttpServletRequest): ModelAndView =
        confirmPage(presenter.dialogFor(account.id, confirmFirst(account, request), "nuovo", null))

    private fun confirmPage(model: talita.ui.ConfirmModel?): ModelAndView {
        if (model == null) return seeOther("/profilo#token")
        return ModelAndView("pages/confirmAction", mapOf("shell" to shells.shell(page = model.title, current = null, onProfile = true), "page" to model.copy(target = null)))
    }

    /** A form without JavaScript that came back leaves the page at the address of its action: reloading it asks with a GET, and gets the profile. */
    @GetMapping("/profilo/token/revoca", "/profilo/token/revoca/conferma", "/profilo/token/nuovo/conferma")
    fun reloaded(): ModelAndView = seeOther("/profilo#token")
}
