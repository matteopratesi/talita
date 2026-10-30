package talita.ui

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import java.io.Serializable

/**
 * The four kinds of notice in a page (design guide, 4.6): which icon it has. The colors are in
 * `components/alert.jte`: Tailwind reads the classes from the templates, not from Kotlin.
 */
enum class AlertVariant(val icon: Icon) {
    INFO(Icon.INFO),
    SUCCESS(Icon.CIRCLE_CHECK),
    WARNING(Icon.TRIANGLE_ALERT),
    DANGER(Icon.CIRCLE_ALERT),
    ;

    /**
     * What a screen reader announces when the notice follows an action (`live`, see `alert.jte`): errors and
     * warnings interrupt (`alert`), a confirmation or an information is said when there is a pause
     * (`status`). A notice that is always there, whatever the person does, is not `live` and says nothing.
     */
    val role: String get() = when (this) {
        INFO, SUCCESS -> "status"
        WARNING, DANGER -> "alert"
    }
}

/**
 * A notice at the top of a page, with its text. The notice that follows an action is one of these. A notice can have a bold [lead] before the text («Dati di Luca
 * cancellati.», then what stays and until when: design of TALITA-7), and a text button under it ([actionLabel] that leads to [actionHref]).
 */
data class Alert(
    val variant: AlertVariant,
    val text: String,
    val lead: String? = null,
    val actionLabel: String? = null,
    val actionHref: String? = null,
    /** A second sentence that is shown only where the browser can use passkeys (`data-passkey`, hidden until the script of the passkeys sees the support): «Se hai una passkey, puoi usarla qui sotto.». */
    val passkeyHint: String? = null,
) : Serializable

/**
 * A notice that is shown once, on the page where a redirect leads ("Accesso chiuso."): the
 * confirmations after an action are notices in the page, not pop-up messages (design guide, 4.7).
 * It lives in the session, in memory, until the first page that shows it; it has no personal data.
 */
object Flash {

    private const val KEY = "talita.flash"

    /** Leaves [alert] for the next page. Creates the session if there is none. */
    fun put(request: HttpServletRequest, alert: Alert) {
        request.getSession(true).setAttribute(KEY, alert)
    }

    /** Leaves [alert] in a session that exists already. */
    fun put(session: HttpSession, alert: Alert) {
        session.setAttribute(KEY, alert)
    }

    /** The notice that was left, once: the next call finds nothing. */
    fun take(request: HttpServletRequest): Alert? {
        val session = request.getSession(false) ?: return null
        val alert = session.getAttribute(KEY) as? Alert
        if (alert != null) session.removeAttribute(KEY)
        return alert
    }
}
