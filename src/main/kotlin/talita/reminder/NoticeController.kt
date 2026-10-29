package talita.reminder

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import talita.account.CurrentAccount
import talita.account.isHtmxFragment
import talita.account.seeOther
import talita.ui.Alert
import talita.ui.AlertVariant.SUCCESS
import talita.ui.Flash

/**
 * What a person chooses about the mail of Talità that is not a login link (TALITA-13, design part 2): the box «Avvisami per email…» (in «Iscrizioni» and in the profile), the stop
 * («Non mandarmi più promemoria») and taking the reminders back, both in the profile. **They are of every account, and change only the account of the session**: no id, no address, no
 * code in the request (a test tries them with two accounts). They are not under `TeacherPaths`.
 *
 * Every action answers with the card as a whole (htmx replaces it) with the notice of what happened; without JavaScript it leaves the notice in the session and goes back to the page with a
 * 303, where it shows once. A request that cannot be done (the box for someone who stopped: 409; for someone without children: the page that does not exist) changes nothing.
 */
@Controller
class NoticeController(private val notices: Notices, private val presenter: NoticePresenter) {

    /** The box in the profile (the complete card). The box ticked sends `notice=true`; unticked, nothing. */
    @PostMapping("/profilo/avviso")
    fun askFromProfile(account: CurrentAccount, @RequestParam(name = "notice", required = false) notice: String?, request: HttpServletRequest): ModelAndView =
        ask(account, notice == "true", full = true, request = request)

    /** The box in «Iscrizioni» (the short card). */
    @PostMapping("/iscrizioni/avviso")
    fun askFromRegistrations(account: CurrentAccount, @RequestParam(name = "notice", required = false) notice: String?, request: HttpServletRequest): ModelAndView =
        ask(account, notice == "true", full = false, request = request)

    @PostMapping("/profilo/promemoria/stop")
    fun stop(account: CurrentAccount, request: HttpServletRequest): ModelAndView {
        val hadConsent = notices.stop(account.id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return done(account, full = true, request = request, alert = Alert(SUCCESS, if (hadConsent) NoticePresenter.STOPPED_WITH_CONSENT else NoticePresenter.STOPPED))
    }

    @PostMapping("/profilo/promemoria/riprendi")
    fun resume(account: CurrentAccount, request: HttpServletRequest): ModelAndView {
        if (!notices.resume(account.id)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return done(account, full = true, request = request, alert = Alert(SUCCESS, NoticePresenter.RESUMED))
    }

    private fun ask(account: CurrentAccount, wanted: Boolean, full: Boolean, request: HttpServletRequest): ModelAndView =
        when (val result = notices.setConsent(account.id, wanted)) {
            is ConsentResult.Saved ->
                done(account, full, request, Alert(SUCCESS, if (result.at != null) NoticePresenter.ASKED else NoticePresenter.NOT_ASKED))
            // The person stopped the reminders (in another tab, or by hand): the card says so by itself, and the box is not there.
            ConsentResult.Stopped -> fragment(account, full, null, HttpStatus.CONFLICT)
            ConsentResult.NoChildren -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }

    /** The card with [alert] (htmx), or back to the page with the notice kept for it (a form without JavaScript). */
    private fun done(account: CurrentAccount, full: Boolean, request: HttpServletRequest, alert: Alert): ModelAndView {
        if (request.isHtmxFragment()) return fragment(account, full, alert, HttpStatus.OK)
        Flash.put(request, alert)
        return seeOther(if (full) "/profilo#email" else "/iscrizioni#avviso-card")
    }

    private fun fragment(account: CurrentAccount, full: Boolean, alert: Alert?, status: HttpStatus): ModelAndView {
        val card = presenter.card(account.id, full, alert) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return ModelAndView("reminder/emailCard", mapOf("card" to card)).also { it.status = status }
    }
}
