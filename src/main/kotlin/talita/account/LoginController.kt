package talita.account

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.view.RedirectView
import talita.ui.Alert
import talita.ui.AlertVariant.DANGER
import talita.ui.AlertVariant.WARNING
import talita.ui.Flash
import talita.ui.ShellFactory

/** Model of the page of access: what is in the field, the error of the field, the notice above the card. */
data class LoginPage(val email: String, val emailError: String?, val alert: Alert?)

/** Model of "Controlla la posta": the address that was just written, if the session still knows it. */
data class CheckMailPage(val email: String?, val validityMinutes: Long)

/** Model of "Conferma l'accesso": the link to confirm (it goes back in the form), and a notice if the page was open too long. */
data class ConfirmPage(val token: String, val alert: Alert?)

/** Model of "Il link non vale più". */
data class ExpiredLinkPage(val validityMinutes: Long)

/**
 * What was written in the field of the access form when its CSRF code had expired, kept for the page that
 * the person is sent back to. In the session, in memory, read once; it is never in an address.
 */
internal object TypedAddress {
    private const val KEY = "talita.typedAddress"

    fun keep(request: HttpServletRequest, value: String?) {
        if (!value.isNullOrEmpty()) request.getSession(true).setAttribute(KEY, value.take(MAX_ECHO))
    }

    fun take(request: HttpServletRequest): String? {
        val session = request.getSession(false) ?: return null
        val value = session.getAttribute(KEY) as? String
        if (value != null) session.removeAttribute(KEY)
        return value
    }
}

/** The most that is written back in a field that was wrong: enough for an address that is too long. */
internal const val MAX_ECHO = 1000

/** A redirect after a POST: 303, so that the browser and htmx ask for the next page with a GET. */
internal fun seeOther(location: String): ModelAndView =
    ModelAndView(RedirectView(location).also { it.setStatusCode(HttpStatus.SEE_OTHER) })

/**
 * The pages of access (all public): ask for a link, "Controlla la posta", confirm the link,
 * "Il link non vale più". Confirming (the tap on "Entra") is not here: it is the filter of Spring
 * Security that consumes the link, see [SecurityConfiguration].
 *
 * Every page that has a form sends it back with the field filled in, and a notice, when something is
 * wrong: the page of the error replaces the one before (422, or 429 for the limits), it does not stack.
 */
@Controller
class LoginController(
    private val shells: ShellFactory,
    private val links: LoginLinks,
    private val settings: LoginSettings,
    private val accounts: CurrentAccounts,
) {

    /** Ask for a link. Whoever is already inside goes to the home. */
    @GetMapping("/accedi")
    fun login(request: HttpServletRequest): ModelAndView {
        if (accounts.current() != null) return ModelAndView(RedirectView("/"))
        return loginForm(email = TypedAddress.take(request).orEmpty(), emailError = null, alert = Flash.take(request), status = HttpStatus.OK)
    }

    @PostMapping("/accedi")
    fun requestLink(@RequestParam(defaultValue = "") email: String, request: HttpServletRequest): ModelAndView {
        val typed = email.take(MAX_ECHO)
        if (accounts.current() != null) return seeOther("/")

        val address = EmailAddress.parse(email) ?: return loginForm(typed, EMAIL_ERROR, null, HttpStatus.UNPROCESSABLE_ENTITY)
        return when (links.request(address)) {
            // The same answer for an address with an account and one without: the person never learns which.
            LinkOutcome.SENT -> {
                request.getSession(true).setAttribute(WRITTEN_ADDRESS, address.value)
                seeOther("/accedi/controlla-la-posta")
            }

            LinkOutcome.TOO_MANY_FOR_ADDRESS -> loginForm(typed, null, Alert(WARNING, TOO_MANY_LINKS), HttpStatus.TOO_MANY_REQUESTS)
            LinkOutcome.DAILY_LIMIT -> loginForm(typed, null, Alert(WARNING, DAILY_LIMIT), HttpStatus.TOO_MANY_REQUESTS)
            LinkOutcome.NOT_SENT -> loginForm(typed, null, Alert(DANGER, NOT_SENT), HttpStatus.UNPROCESSABLE_ENTITY)
        }
    }

    /**
     * "Controlla la posta". It repeats the address that was just written, so that who got a letter
     * wrong notices at once. The address comes from the session and is read once: a reload, or a
     * page opened by hand, says it without the address. It is never in the URL.
     */
    @GetMapping("/accedi/controlla-la-posta")
    fun checkMail(request: HttpServletRequest): ModelAndView {
        val session = request.getSession(false)
        val written = session?.getAttribute(WRITTEN_ADDRESS) as? String
        session?.removeAttribute(WRITTEN_ADDRESS)
        return page("Controlla la posta", "pages/checkMail", CheckMailPage(written, settings.linkValidity.toMinutes()))
    }

    /**
     * The page of the link. Opening it (GET) does nothing to the link: mail programs open links to look
     * at them. The tap on "Entra" is a POST with the CSRF code, and Spring Security consumes the link.
     * If the page was open for too long and its code expired, the person comes back here, to the same
     * link, with a notice (see [AccessHandler]).
     */
    @GetMapping("/accedi/conferma")
    fun confirm(@RequestParam(required = false) token: String?, request: HttpServletRequest): ModelAndView {
        if (token.isNullOrBlank()) return seeOther("/accedi/link-scaduto")
        return page("Conferma l’accesso", "pages/confirmLogin", ConfirmPage(token.trim(), Flash.take(request)))
    }

    /** "Il link non vale più". Whoever is already inside in this browser goes to the home. */
    @GetMapping("/accedi/link-scaduto")
    fun expiredLink(): ModelAndView {
        if (accounts.current() != null) return ModelAndView(RedirectView("/"))
        return page("Il link non vale più", "pages/expiredLink", ExpiredLinkPage(settings.linkValidity.toMinutes()))
    }

    private fun loginForm(email: String, emailError: String?, alert: Alert?, status: HttpStatus): ModelAndView =
        page("Accedi", "pages/login", LoginPage(email, emailError, alert)).also { it.status = status }

    private fun page(title: String, view: String, model: Any): ModelAndView =
        ModelAndView(view, mapOf("shell" to shells.shell(page = title, current = null), "page" to model))

    private companion object {
        const val WRITTEN_ADDRESS = "talita.writtenAddress"

        const val EMAIL_ERROR = "Scrivi un indirizzo email completo, per esempio nome@example.org."
        const val NOT_SENT = "Non siamo riusciti a mandare il link. Riprova tra qualche minuto. Se il problema continua, avvisa chi organizza la convivenza."
        const val TOO_MANY_LINKS = "Hai chiesto più link in pochi minuti. Controlla la posta, anche nello spam: l’ultimo dovrebbe essere arrivato. Se non c’è, riprova fra qualche minuto."
        const val DAILY_LIMIT = "Oggi Talità ha già mandato tante mail e non può mandarne altre. Riprova domani. Se è urgente, avvisa chi organizza la convivenza."
    }
}
