package talita.account

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AuthenticationTrustResolverImpl
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CsrfException
import org.springframework.security.web.savedrequest.DefaultSavedRequest
import org.springframework.security.web.savedrequest.HttpSessionRequestCache
import org.springframework.security.web.savedrequest.RequestCache
import org.springframework.security.web.util.matcher.RequestMatcher
import talita.ui.Alert
import talita.ui.AlertVariant.INFO
import talita.ui.AlertVariant.WARNING
import talita.ui.AlertVariant.SUCCESS
import talita.ui.Flash

/** The request comes from htmx (a page change by link or form, or a piece of a page). */
internal fun HttpServletRequest.isHtmx(): Boolean = getHeader("HX-Request") == "true"

/** The request comes from htmx and is not a page change: it wants a piece of a page. */
internal fun HttpServletRequest.isHtmxFragment(): Boolean =
    isHtmx() && getHeader("HX-Boosted") != "true" && getHeader("HX-History-Restore-Request") != "true"

/** A redirect after a POST: 303, so that the browser (and htmx) ask for the next page with a GET. */
internal fun HttpServletResponse.redirectSeeOther(location: String) {
    status = HttpStatus.SEE_OTHER.value()
    setHeader(HttpHeaders.LOCATION, location)
}

/** Who is making the request is a person who is inside (not anonymous). */
private fun Authentication?.isSignedIn(): Boolean =
    this != null && isAuthenticated && !AuthenticationTrustResolverImpl().isAnonymous(this)

/**
 * Where a person without access goes: to `/accedi`.
 *
 * A request from htmx gets the answer `HX-Redirect`, and not a 302 or a 401: the browser then loads
 * the page of access as a whole page, and never puts it inside the old page or shows "Qualcosa non ha
 * funzionato" (criterion 23). The one exception is the request of the back button
 * (`HX-History-Restore-Request`), where htmx does not read `HX-Redirect`: there a 401 makes htmx give up
 * and app.js load the address as a whole page.
 *
 * If the browser carries the cookie of a session that the server no longer knows (more than two hours without touching anything, or a restart), the person is told that the access closed on its own: a
 * person who never was inside is not. The same page says other things when they are true (TALITA-15): that the access on this phone was closed (the device was taken away, «Esci da tutti» was
 * touched on another one, or the cookie of «Ricordami su questo telefono» was not accepted), and that the session passed its 12 hours.
 */
class LoginEntryPoint(private val texts: SessionTexts) : AuthenticationEntryPoint {

    override fun commence(request: HttpServletRequest, response: HttpServletResponse, authException: AuthenticationException) {
        goToLogin(request, response)
    }

    fun goToLogin(request: HttpServletRequest, response: HttpServletResponse) {
        if (request.getHeader("HX-History-Restore-Request") == "true") {
            // The back button asks the server for a page that is not kept (nothing of a person who is inside is):
            // htmx does not read `HX-Redirect` on this request, but treats an error as "I cannot restore it", and
            // app.js then loads the address as a whole page, which the next request sends to the access page.
            response.status = HttpStatus.UNAUTHORIZED.value()
            return
        }
        // A session that was closed because its account is gone (TALITA-7) says so with its own notice, and not that the access closed by itself.
        val sessionExpired = request.requestedSessionId != null && !request.isRequestedSessionIdValid && request.getAttribute(ACCOUNT_GONE) != true
        val ended = request.getAttribute(SESSION_ENDED) as? SessionEnd
        when {
            ended != null -> Flash.put(request, texts.of(ended))
            request.getAttribute(DeviceRememberMeServices.CLOSED_BY_COOKIE) == true -> Flash.put(request, texts.closedHere)
            sessionExpired -> Flash.put(request, texts.inactivity)
        }
        if (request.isHtmx()) {
            response.status = HttpStatus.OK.value()
            response.setHeader("HX-Redirect", LOGIN_PATH)
        } else {
            response.sendRedirect(LOGIN_PATH)
        }
    }

    companion object {
        const val LOGIN_PATH = "/accedi"

        /** The attribute of a request whose session was closed because the account it pointed at is gone ([CurrentAccountFilter]). */
        const val ACCOUNT_GONE = "talita.account.gone"

        /** The attribute of a request whose session was closed by [SessionGuardFilter]: the [SessionEnd] that says why. */
        const val SESSION_ENDED = "talita.account.sessionEnded"
    }
}

/**
 * What `/accedi` says to someone whose account was deleted (on request: TALITA-7), whether they did it from this phone or another phone still had the session. It says
 * nothing of what stays: the dialog said it a moment before, and a page without access must not carry dates or data of a registration.
 */
val ACCOUNT_DELETED_NOTICE = Alert(
    SUCCESS, "Se ti serve, puoi rientrare con lo stesso indirizzo: troverai un account nuovo e vuoto.", lead = "Account cancellato.",
)

/** The notice of a form that was open for too long (a CSRF code that the session no longer knows). */
internal const val PAGE_EXPIRED = "La pagina era aperta da troppo tempo. Riprova."

/** The forms of the pages that are open to anyone and that have a tap with a CSRF code: the two of access (the link, and its confirmation) and the one for stopping the reminders (TALITA-13). */
private val ACCESS_FORMS = setOf("/accedi", "/accedi/conferma", "/promemoria/smetti")

/**
 * What happens when the request is refused to someone who is inside, and when the CSRF code of a
 * form does not match.
 *
 * - A form of the access pages (public, [ACCESS_FORMS]) whose code expired (a page left open for hours): the same
 *   page again (a redirect, not a forward: a forward goes through the security filters a second time, with
 *   the CSRF check already marked as done), with the field still filled in, a new code and an invitation to
 *   try again. Never "Non puoi vedere questa pagina". The page for stopping the reminders comes back the same way, with its code.
 * - Any other form without the code, from someone who is not inside any more: the access page, as
 *   for any request without access.
 * - The same with a person who is inside (two tabs, a form from before the login), or who is inside by the cookie of «Ricordami su questo telefono» and whose session
 *   ended: the page is reloaded (htmx) or the person goes to the home, with the notice «La pagina era aperta da troppo tempo. Riprova.».
 * - Anything else is a 403, which the error page shows (or, for a request that does not change the
 *   page, the message "Accesso negato").
 */
class AccessHandler(private val entryPoint: LoginEntryPoint, private val remembered: DeviceRememberMeServices) : AccessDeniedHandler {

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, accessDeniedException: AccessDeniedException) {
        if (accessDeniedException is CsrfException) {
            handleCsrf(request, response)
            return
        }
        response.sendError(HttpStatus.FORBIDDEN.value())
    }

    private fun handleCsrf(request: HttpServletRequest, response: HttpServletResponse) {
        val path = PublicPaths.pathOf(request)
        when {
            path in ACCESS_FORMS -> backToAccessForm(path, request, response)

            // A person who has no session any more but the cookie of «Ricordami su questo telefono» is still inside: the form of a page that was open for hours has no code that the session knows, and
            // the person must not see «Accedi» for it (TALITA-15, criterion 8). The cookie is not used here (it is used by the next request, a page): it is only looked at.
            !SecurityContextHolder.getContext().authentication.isSignedIn() && !remembered.canEnter(request) -> entryPoint.goToLogin(request, response)

            request.isHtmx() -> {
                response.status = HttpStatus.OK.value()
                response.setHeader("HX-Refresh", "true")
            }

            else -> {
                Flash.put(request, Alert(WARNING, PAGE_EXPIRED))
                response.redirectSeeOther("/")
            }
        }
    }
}

/**
 * The form of access whose CSRF code expired comes back as itself: a redirect to the page, which finds
 * in the session the notice and, for the first form, what was written in the field (read once, in memory).
 * The session is a new one if the old one is gone, and so is the code in the form.
 */
private fun backToAccessForm(path: String, request: HttpServletRequest, response: HttpServletResponse) {
    Flash.put(request, Alert(WARNING, PAGE_EXPIRED))
    if (path == "/accedi") {
        TypedAddress.keep(request, request.getParameter("email"))
        response.redirectSeeOther("/accedi")
        return
    }
    if (path == "/promemoria/smetti") {
        // The same page again, with the code that was in the address when it was opened (it is in the body of the form, and it is still good): never written anywhere else.
        val code = request.getParameter("codice")?.trim().orEmpty()
        response.redirectSeeOther(if (code.isEmpty()) "/promemoria/smetti" else "/promemoria/smetti?codice=" + URLEncoder.encode(code, StandardCharsets.UTF_8))
        return
    }
    val token = request.getParameter("token")?.trim().orEmpty()
    // The link is in the address, as it was when the page was opened: it is still good.
    response.redirectSeeOther(if (token.isEmpty()) "/accedi" else "/accedi/conferma?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8))
}

/**
 * A place that is more urgent than the page the person asked for: whoever enters with an address that
 * has an invitation goes to «Accetta l'invito» (`talita.teacher`). Returns the path, or null when it has
 * nothing to say about this person. The first that answers wins.
 */
fun interface PostLoginDestination {
    fun forNewLogin(email: String): String?
}

/**
 * After a login: where [destinations] say, if one of them does (an invitation to accept); else back to the
 * page that the person asked for before being sent to the access page, if the link was opened in the same
 * browser; else the home. Only an address of Talità, never one that comes from the outside, and nothing
 * in the URL.
 */
class LoginSuccessHandler(
    private val requestCache: RequestCache,
    private val destinations: List<PostLoginDestination> = emptyList(),
    private val origins: SessionOrigins? = null,
) : AuthenticationSuccessHandler {

    override fun onAuthenticationSuccess(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication) {
        val person = authentication.principal as? AccountUserDetails
        // The session is born here: it remembers how (the number of the sessions of the account, the moment, the device that the box made if it was touched), so that it ends when it must (TALITA-15).
        if (person != null) origins?.stamp(request, person.id, request.getAttribute(DeviceRememberMeServices.DEVICE) as? Long)
        val email = person?.username
        val urgent = email?.let { address -> destinations.firstNotNullOfOrNull { it.forNewLogin(address) } }
        val target = urgent ?: destination(request, response)
        requestCache.removeRequest(request, response)
        response.redirectSeeOther(target)
    }

    private fun destination(request: HttpServletRequest, response: HttpServletResponse): String {
        val saved = requestCache.getRequest(request, response) as? DefaultSavedRequest ?: return "/"
        val path = (saved.requestURI ?: return "/").removePrefix(saved.contextPath.orEmpty())
        val query = saved.queryString?.let { "?$it" }.orEmpty()
        val local = path.startsWith("/") && !path.startsWith("//") && !path.contains('\\') && !PublicPaths.matches(path)
        return if (local) path + query else "/"
    }
}

/**
 * A link that does not work (used, expired, invented): "Il link non vale più". If in this browser
 * the person is already inside (the link was used a moment ago, a double tap), the home.
 */
class LoginFailureHandler : AuthenticationFailureHandler {

    private val sessions = HttpSessionSecurityContextRepository()

    override fun onAuthenticationFailure(request: HttpServletRequest, response: HttpServletResponse, exception: AuthenticationException) {
        // The filter has already emptied the context of this request; the session still has it.
        val inside = request.getSession(false) != null && sessions.loadDeferredContext(request).get().authentication.isSignedIn()
        response.redirectSeeOther(if (inside) "/" else "/accedi/link-scaduto")
    }
}

/** After "Esci": the access page, with the confirmation, once. The session is new: the old one is over. */
class LogoutRedirect(private val texts: SessionTexts) : LogoutSuccessHandler {

    private val log = LoggerFactory.getLogger(LogoutRedirect::class.java)

    override fun onLogoutSuccess(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication?) {
        Flash.put(request, texts.signedOut)
        log.debug("Signed out.")
        response.redirectSeeOther(LoginEntryPoint.LOGIN_PATH)
    }
}

/**
 * Which requests are kept to bring the person back after the login: a page that was asked for
 * (GET, for HTML), not a file, not a piece of page that htmx asks for.
 */
internal class ReturnablePage : RequestMatcher {
    override fun matches(request: HttpServletRequest): Boolean =
        request.method == "GET" &&
            !request.isHtmxFragment() &&
            !PublicPaths.matches(PublicPaths.pathOf(request)) &&
            (request.getHeader(HttpHeaders.ACCEPT) ?: "").let { it.isEmpty() || it.contains("text/html") || it.contains("*/*") }
}

/**
 * The cache that keeps the page that was asked for. Nothing of it goes in the address: the way back
 * is in the session, and [LoginSuccessHandler] reads it (the default `?continue` parameter, which
 * lets Spring replay the request, is never in an address of ours, so it never does).
 */
internal fun requestCache(): HttpSessionRequestCache = HttpSessionRequestCache().apply {
    setRequestMatcher(ReturnablePage())
}
