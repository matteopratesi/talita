package talita.account

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import talita.ui.Alert
import talita.ui.AlertVariant.INFO
import talita.ui.AlertVariant.SUCCESS
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * How a session was born (TALITA-15), in memory like the session itself: the account, the number that the sessions of the account had ([Accounts.sessionGeneration]), the moment it started and the
 * row of «Ricordami su questo telefono» that it came from, if any. It lets the application end the session when the row is taken away from another device, when «Esci da tutti gli altri
 * dispositivi» raises the number, and after 12 hours, and never writes anything about the person: a number, a moment and the id of a row.
 */
data class SessionOrigin(val accountId: Long, val generation: Int, val startedAt: Instant, val deviceId: Long?) : Serializable

/** Why a session is over though the container still keeps it. */
enum class SessionEnd {
    /** The session is older than the cap of [LoginSettings.sessionMaxAge], or it carries no origin (it was not born from a login of ours): the notice is the one of the inactivity. */
    TOO_OLD,

    /** The device it came from was taken away, or the number of the sessions of the account grew: «L’accesso su questo telefono è stato chiuso.» */
    CLOSED,
}

/**
 * The origin of the sessions, and the one place that says whether a session still counts ([endOf]). Written at every way in: the link, the cookie of a device, a passkey. A session that has no
 * origin does not count: the way in that forgot to write it would be a person thrown out at the next request, and a test would say so.
 */
@Component
class SessionOrigins(
    private val accounts: Accounts,
    private val devices: RememberedDevices,
    private val settings: LoginSettings,
    private val clock: Clock,
) {

    /** Writes the origin of the session of [request] (it is made if there is none) for the person [accountId], with the row of a device if they came in with one. */
    fun stamp(request: HttpServletRequest, accountId: Long, deviceId: Long?) {
        request.getSession(true).setAttribute(KEY, SessionOrigin(accountId, accounts.sessionGeneration(accountId) ?: 0, clock.instant(), deviceId))
    }

    fun of(session: HttpSession?): SessionOrigin? = session?.getAttribute(KEY) as? SessionOrigin

    /** After «Esci da tutti gli altri dispositivi»: this session adopts the new number of the account and stays inside. */
    fun adopt(session: HttpSession, generation: Int) {
        of(session)?.let { session.setAttribute(KEY, it.copy(generation = generation)) }
    }

    /**
     * Whether the session of the person [accountId] is over, and why; null if it goes on. Three reasons: it is older than 12 hours (used or not), the row of the device that it came from is
     * gone, or the number of the sessions of the account is not the one it was born with. An account that is gone is not here: [CurrentAccountFilter] sees to it.
     */
    fun endOf(session: HttpSession?, accountId: Long): SessionEnd? {
        val origin = of(session)
        if (origin == null || origin.accountId != accountId) return SessionEnd.TOO_OLD
        if (!clock.instant().isBefore(origin.startedAt.plus(settings.sessionMaxAge))) return SessionEnd.TOO_OLD
        val generation = accounts.sessionGeneration(accountId) ?: return null
        if (generation != origin.generation) return SessionEnd.CLOSED
        if (origin.deviceId != null && !devices.exists(origin.deviceId)) return SessionEnd.CLOSED
        return null
    }

    companion object {
        internal const val KEY = "talita.sessionOrigin"
    }
}

/**
 * The notices that the access page shows when a session ended or the person went out, with the numbers of the container and of [LoginSettings] (two hours without use, 12 hours at most). Each
 * one has a second sentence that only a browser that can use passkeys shows (`data-passkey`).
 */
@Component
class SessionTexts(private val settings: LoginSettings, @Value("\${server.servlet.session.timeout:2h}") private val idle: Duration) {

    private fun hours(duration: Duration): String = duration.toHours().let { if (it == 1L) "1 ora" else "$it ore" }

    /** «2 ore»: how long a session lives without use. */
    val idleWords: String get() = hours(idle)

    /** «12 ore»: the longest a session lives, used or not. */
    val capWords: String get() = hours(settings.sessionMaxAge)

    /** «L’accesso si chiude da solo dopo 2 ore che non usi Talità, e comunque dopo 12 ore dall’entrata.» */
    val inactivity: Alert
        get() = Alert(
            INFO,
            "L’accesso si chiude da solo dopo ${hours(idle)} che non usi Talità, e comunque dopo ${hours(settings.sessionMaxAge)} dall’entrata. Per rientrare, chiedi un nuovo link.",
            passkeyHint = PASSKEY_HINT,
        )

    /** The device was taken away, «Esci da tutti» was touched on another one, or the cookie was not recognised. */
    val closedHere: Alert get() = Alert(INFO, "L’accesso su questo telefono è stato chiuso. Per rientrare, chiedi un nuovo link.", passkeyHint = PASSKEY_HINT)

    /** After «Esci». */
    val signedOut: Alert get() = Alert(SUCCESS, "Accesso chiuso. Per rientrare, chiedi un nuovo link.", passkeyHint = PASSKEY_HINT)

    fun of(end: SessionEnd): Alert = if (end == SessionEnd.CLOSED) closedHere else inactivity

    companion object {
        const val PASSKEY_HINT = "Se hai una passkey, puoi usarla qui sotto."
    }
}
