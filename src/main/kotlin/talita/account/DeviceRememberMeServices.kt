package talita.account

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices
import org.springframework.security.web.authentication.rememberme.InvalidCookieException
import org.springframework.stereotype.Component
import talita.ClockConfiguration
import talita.retention.RetentionPolicy
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * **«Ricordami su questo telefono»**: the cookie that lets a browser enter again without a link, and the one class that talks about it with Spring Security (TALITA-15, criteria 1-13). It is
 * ours, on top of [AbstractRememberMeServices] (the cookie, the parameter, the filter and the way out stay Spring's), and not Spring's `PersistentTokenBasedRememberMeServices`, which writes the
 * value of the cookie in the table, counts the length from the last use with no cap and reads the clock of the machine, and whose way out takes away the tokens of every device.
 *
 * - **The cookie** is `selector:value`, both random (128 and 256 bits), `HttpOnly`, `SameSite=Lax`, `Path=/`, `Secure` when the request came by HTTPS, and it expires when the row does. The table has
 *   the selector and the SHA-256 of the value, never the value ([RememberedDevices]).
 * - **The value changes at every re-entry** (the selector stays): a value that is not the last one with a selector that exists is a cookie that was copied, the row is taken away, the cookie too, and
 *   the log says it in one line with no data. **With a margin of 60 seconds** ([RememberedDevices.ROTATION_MARGIN], decision of Matteo): for 60 seconds after a change the value before it is good too,
 *   so two tabs that the phone reopens together after the session ended (the same cookie in both) enter both. The second one enters like a re-entry (a new session) but does not change the value
 *   again and does not touch the cookie, which the first answer has already put in the browser. After the 60 seconds, or for a value from two changes ago, it is a copy as above.
 * - **The length is the one of the role today**, read at every re-entry ([RetentionPolicy.deviceExpired]); the clock is the one of the application and the day is a day of Rome.
 * - **The account is read by its id and never created** ([Accounts.userDetailsById]): the cookie of an account that was deleted is thrown away and the account does not come back.
 * - **The same request asks once**: the error page of a request that failed is a second pass through the filters with the same cookie, which would find the value already changed; the answer
 *   of the first pass is kept in the request ([ATTEMPT]) and given again.
 * - **A failed link does not take the cookie away** ([loginFail] does nothing): someone who taps an old mail keeps the device.
 * - **«Esci»** takes away the row of the cookie of this browser, and no other ([logout]).
 *
 * A request that arrives with a cookie that is not good (unknown selector, a value that is not the last, a role that makes the row expire, an account that is gone) is without access, with no error, and
 * the page that sends it to `/accedi` says that the access on this phone was closed ([CLOSED_BY_COOKIE]).
 */
@Component
class DeviceRememberMeServices(
    private val devices: RememberedDevices,
    private val accounts: Accounts,
    private val origins: SessionOrigins,
    private val policy: RetentionPolicy,
    private val clock: Clock,
) : AbstractRememberMeServices(UUID.randomUUID().toString(), Unused) {

    private val log = LoggerFactory.getLogger(DeviceRememberMeServices::class.java)

    init {
        // The names of the cookie and of the box of the form are ours: nothing else in the app is called «remember-me».
        setCookieName(COOKIE)
        setParameter(PARAMETER)
        setCookieCustomizer { it.setAttribute("SameSite", "Lax") }
    }

    /** The answer of the first pass of a request, kept for the second (the error dispatch). */
    private class Attempt(val authentication: Authentication?)

    // ---- Entering again ----------------------------------------------------------------------------------------------------------

    override fun autoLogin(request: HttpServletRequest, response: HttpServletResponse): Authentication? {
        // The static files are the most numerous requests and have no use of the account: they never touch the cookie, so a page and its style sheet that arrive together do not spend the value twice.
        if (PublicPaths.isStaticFile(PublicPaths.pathOf(request))) return null
        (request.getAttribute(ATTEMPT) as? Attempt)?.let { return it.authentication }
        val authentication = try {
            super.autoLogin(request, response)
        } catch (e: RuntimeException) {
            // The database is down, or anything else that is not the cookie's fault: the cookie is not cancelled (the next touch tries again), and the second pass of this request does not try at all.
            request.setAttribute(ATTEMPT, Attempt(null))
            throw e
        }
        request.setAttribute(ATTEMPT, Attempt(authentication))
        return authentication
    }

    override fun processAutoLoginCookie(cookieTokens: Array<String>, request: HttpServletRequest, response: HttpServletResponse): UserDetails {
        if (cookieTokens.size != 2) throw rejected(request, "malformed")
        val cookie = DeviceCookie(cookieTokens[0], cookieTokens[1])
        val row = devices.find(cookie.selector) ?: throw rejected(request, "unknown selector")
        val presented = devices.presented(row, cookie.value)
        if (presented == Presented.NONE) {
            // A value that is not the last one (nor the one before it inside the margin) for a device that exists: the cookie was copied. The device is unlinked, and the browser that had the new value loses it too.
            devices.delete(row.id)
            log.warn("Remember-me: a cookie with a value that is not the last one was presented: the device was unlinked.")
            throw rejected(request, "value that is not the last")
        }
        val account = accounts.current(row.accountId)
        if (account == null) {
            // The account is gone (the rows go with it, so this is a race): the cookie is worth nothing and nothing is created.
            devices.delete(row.id)
            throw rejected(request, "account gone")
        }
        if (devices.expired(row, account.isTeacher)) {
            devices.delete(row.id)
            throw rejected(request, "expired")
        }
        // The value before the last one, inside the margin, enters but does not change the value again and does not touch the cookie: the browser has the new one already.
        if (presented == Presented.LAST) {
            val renewed = devices.rotate(row)
            if (renewed != null) {
                setCookie(arrayOf(renewed.selector, renewed.value), maxAgeSeconds(row, account.isTeacher), request, response)
            } else if (!arrivedSecond(row, cookie)) {
                // Someone else used the cookie between the reading and now, and what the row has is not a value that this one can follow: only one can have the new value.
                devices.delete(row.id)
                log.warn("Remember-me: a cookie was used twice at the same moment: the device was unlinked.")
                throw rejected(request, "used twice")
            }
        }
        request.setAttribute(DEVICE, row.id)
        // The session is made here, with its origin, so that it ends when the row is taken away from another device (the filter of Spring writes the context in it after).
        origins.stamp(request, account.id, row.id)
        return AccountUserDetails(account.id, account.email)
    }

    /**
     * The second of two requests with the same cookie that came together: the first changed the value between the reading of the row and the change of the second, so the row is read again, and the value
     * that was presented is now the one before the last, inside the margin. It enters as the first did, without the cookie (the first answer has the new one).
     */
    private fun arrivedSecond(row: DeviceRow, cookie: DeviceCookie): Boolean {
        val fresh = devices.get(row.id) ?: return false
        return devices.presented(fresh, cookie.value) == Presented.PREVIOUS
    }

    private fun rejected(request: HttpServletRequest, why: String): InvalidCookieException {
        request.setAttribute(CLOSED_BY_COOKIE, true)
        return InvalidCookieException("Remember-me cookie not accepted: $why")
    }

    // ---- Entering with a link, with the box touched ----------------------------------------------------------------------------------

    /** A failed link must not cancel the cookie of a device that is good. */
    override fun loginFail(request: HttpServletRequest, response: HttpServletResponse) {
        // Nothing: the default takes the cookie away.
    }

    override fun onLoginSuccess(request: HttpServletRequest, response: HttpServletResponse, successfulAuthentication: Authentication) {
        val person = successfulAuthentication.principal as? AccountUserDetails ?: return
        // The cookie that this browser already has (of this account or another one) is about to be overwritten: its row goes, so the browser has one row only.
        val presented = presentedRow(request)
        val made = devices.add(person.id, replacing = presented?.id)
        setCookie(arrayOf(made.cookie.selector, made.cookie.value), maxAgeSeconds(made.row, devices.isTeacher(person.id)), request, response)
        request.setAttribute(DEVICE, made.row.id)
    }

    /** How long the cookie lives: until the first day on which the row does not enter any more, at midnight of Rome. At least a second. */
    private fun maxAgeSeconds(row: DeviceRow, teacher: Boolean): Int {
        val goneOn = policy.deviceGoneOn(row.createdOn, teacher).atStartOfDay(ClockConfiguration.ZONE).toInstant()
        return Duration.between(clock.instant(), goneOn).seconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }

    // ---- Out -----------------------------------------------------------------------------------------------------------------------

    /** «Esci»: the row of the cookie of this browser goes with the cookie. The other devices of the account stay inside. */
    override fun logout(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication?) {
        presentedRow(request)?.let { devices.delete(it.id) }
        super.logout(request, response, authentication)
    }

    /** The cookie of this browser is taken away from the browser (the row is the caller's to delete): the device that the person is on was taken away from the profile, which is «Esci». */
    fun forget(request: HttpServletRequest, response: HttpServletResponse) = cancelCookie(request, response)

    // ---- What a page asks (nothing is changed) -------------------------------------------------------------------------------------------

    /** The row that the cookie of this request belongs to, if its value is the last one: the device that the person is on. It changes nothing. */
    fun presentedRow(request: HttpServletRequest): DeviceRow? {
        val raw = extractRememberMeCookie(request)?.takeIf { it.isNotEmpty() } ?: return null
        val tokens = try {
            decodeCookie(raw)
        } catch (e: InvalidCookieException) {
            return null
        }
        if (tokens.size != 2) return null
        return devices.match(DeviceCookie(tokens[0], tokens[1]))
    }

    /**
     * Whether this request could enter by itself with its cookie (the value is the last one, the day has not passed, the account is there), without doing it: what a form that arrives with a CSRF code
     * that expired needs to know, to send the person to the same page and not to the access page (criterion 8).
     */
    fun canEnter(request: HttpServletRequest): Boolean {
        val row = presentedRow(request) ?: return false
        val account = accounts.current(row.accountId) ?: return false
        return !devices.expired(row, account.isTeacher)
    }

    /** The user of the cookie is never looked up through Spring's service: [processAutoLoginCookie] reads the account by its id. */
    private object Unused : UserDetailsService {
        override fun loadUserByUsername(username: String): UserDetails = throw UsernameNotFoundException("not used: the account is read by its id")
    }

    companion object {
        /** The cookie of the device, as the browser holds it. */
        const val COOKIE = "ricordami"

        /** The box of the form of the link. */
        const val PARAMETER = "ricordami"

        /** The id of the row of the device that this request came in with (a re-entry) or made (the box was touched). */
        const val DEVICE = "talita.account.device"

        /** The request carried a cookie of «Ricordami» that was not accepted: the person who ends at the access page is told that the access on this phone was closed. */
        const val CLOSED_BY_COOKIE = "talita.account.deviceRejected"

        private const val ATTEMPT = "talita.account.rememberMeAttempt"
    }
}
