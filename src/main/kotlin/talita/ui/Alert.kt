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
     * What a screen reader announces: errors and warnings interrupt (`alert`), a confirmation is
     * said when there is a pause (`status`). An information that is always there says nothing.
     */
    val role: String? get() = when (this) {
        INFO -> null
        SUCCESS -> "status"
        WARNING, DANGER -> "alert"
    }
}

/** A notice at the top of a page, with its text. The notice that follows an action is one of these. */
data class Alert(val variant: AlertVariant, val text: String) : Serializable

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
