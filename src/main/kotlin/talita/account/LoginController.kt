package talita.account

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.view.RedirectView
import org.springframework.beans.factory.ObjectProvider
import talita.retention.RetentionTexts
import talita.ui.Alert
import talita.ui.AlertVariant.DANGER
import talita.ui.AlertVariant.WARNING
import talita.ui.Flash
import talita.ui.ShellFactory

/**
 * Model of the page of access: what is in the field, the error of the field, the notice above the card. [passkeys] says that the site can use passkeys (there is a public address, TALITA-15): the
 * block «Entra con una passkey» is in the page, hidden until the script sees that the browser can use them, and the notices show their second sentence under the same condition.
 */
data class LoginPage(val email: String, val emailError: String?, val alert: Alert?, val passkeys: Boolean = false)

/** Model of "Controlla la posta": the address that was just written, if the session still knows it. */
data class CheckMailPage(val email: String?, val validityMinutes: Long)

/** The box «Ricordami su questo telefono» of the page of the link: its name, what it does and for how long (the numbers come from the policy of the retention), and the advice not to touch it on the phone of others. */
data class RememberBox(val label: String, val lasts: String, val advice: String)

/** Model of "Conferma l'accesso": the link to confirm (it goes back in the form), a notice if the page was open too long, and the box that keeps the person inside on this phone. */
data class ConfirmPage(val token: String, val alert: Alert?, val remember: RememberBox)

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
    private val retention: RetentionTexts,
    private val destinations: ObjectProvider<PostLoginDestination>,
    private val passkeys: PasskeySupport,
) {

    /**
     * Ask for a link. Whoever is already inside, with a session or with the cookie of «Ricordami su questo telefono», goes where a person goes after the link: to «Accetta l'invito» if there is
     * an invitation for their address (the link of the mail of the invitation leads here), else to the home (TALITA-15, criterion 9).
     */
    @GetMapping("/accedi")
    fun login(request: HttpServletRequest): ModelAndView {
        accounts.current()?.let { return ModelAndView(RedirectView(destinationOf(it))) }
        return loginForm(email = TypedAddress.take(request).orEmpty(), emailError = null, alert = Flash.take(request), status = HttpStatus.OK)
    }

    private fun destinationOf(account: CurrentAccount): String = destinations.orderedStream().toList().firstNotNullOfOrNull { it.forNewLogin(account.email) } ?: "/"

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
        return page("Conferma l’accesso", "pages/confirmLogin", ConfirmPage(token.trim(), Flash.take(request), rememberBox()))
    }

    /** "Il link non vale più". Whoever is already inside in this browser goes to the home. */
    @GetMapping("/accedi/link-scaduto")
    fun expiredLink(): ModelAndView {
        if (accounts.current() != null) return ModelAndView(RedirectView("/"))
        return page("Il link non vale più", "pages/expiredLink", ExpiredLinkPage(settings.linkValidity.toMinutes()))
    }

    /** The box of the page of the link: the lengths are those of the policy (30 days for a parent, 7 for a teacher), the page does not know who is entering and says both. */
    private fun rememberBox(): RememberBox = RememberBox(
        label = "Ricordami su questo telefono",
        lasts = "Entri in Talità senza chiedere un nuovo link, per ${retention.devicesParent} (${retention.devicesTeacherNumber} se sei tra i maestri) o finché non esci.",
        advice = "Non spuntarla su un telefono o un computer di altri: chi lo usa entrerebbe al posto tuo.",
    )

    private fun loginForm(email: String, emailError: String?, alert: Alert?, status: HttpStatus): ModelAndView =
        page("Accedi", "pages/login", LoginPage(email, emailError, alert, passkeys.enabled)).also { it.status = status }

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
