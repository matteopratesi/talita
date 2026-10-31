package talita.account

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.web.filter.OncePerRequestFilter
import talita.ui.Flash

/**
 * On every request, turns "whoever the session says is inside" into the account as the database
 * says it is now, with its roles ([AccountAuthentication]). That is how a role that changes while a
 * person is inside is in force at the next request, and how an account that no longer exists is
 * out. The result lives only for the request: the session still holds the plain [AccountUserDetails].
 *
 * It runs also for the error pages (the error dispatch), which show the header of the person.
 */
class CurrentAccountFilter(private val accounts: Accounts) : OncePerRequestFilter() {

    override fun shouldNotFilterErrorDispatch(): Boolean = false

    /** The static files have no use for the account, and are the most numerous requests. */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = PublicPaths.isStaticFile(PublicPaths.pathOf(request))

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val authentication: Authentication? = SecurityContextHolder.getContext().authentication
        val principal = authentication?.principal
        if (authentication != null && authentication.isAuthenticated && principal is AccountUserDetails) {
            val account = accounts.current(principal.id)
            if (account == null) {
                // The account is gone (TALITA-7 deletes them, on request or when they stay empty): the session is worth nothing. The person is told so on the access
                // page, once, and the request goes on without access, so that it ends there as a whole page (never inside the page that was open, never an error).
                request.getSession(false)?.invalidate()
                SecurityContextHolder.clearContext()
                request.setAttribute(LoginEntryPoint.ACCOUNT_GONE, true)
                Flash.put(request, ACCOUNT_DELETED_NOTICE)
            } else {
                // A new context, not the one of the session: this one must not be saved there.
                SecurityContextHolder.setContext(SecurityContextImpl(AccountAuthentication(account, authoritiesOf(account))))
            }
        }
        chain.doFilter(request, response)
    }

    /** The roles of the request: every account is a parent (no authority needed); a teacher has `ROLE_TEACHER`. */
    private fun authoritiesOf(account: CurrentAccount): Collection<GrantedAuthority> =
        if (account.isTeacher) listOf(AccountAuthentication.teacherAuthority()) else emptyList()
}

/**
 * Ends a session that must not count any more, **before** the filter that lets a person in with the cookie of «Ricordami su questo telefono» (TALITA-15, criteria 7, 15, 16): the session is older than
 * 12 hours (used or not), the device it came from was taken away from another phone, or «Esci da tutti gli altri dispositivi» raised the number of the sessions of the account ([SessionOrigins.endOf]).
 * The session is invalidated and the context is emptied, so that the next filter, which looks at the cookie, finds nobody inside and, if the cookie is good, lets the person in again **in this same
 * request** with a new session (with the 12 hours, the person with the cookie never notices); without a good cookie the request goes on without access and ends at the access page, with the notice
 * that says why ([SessionEnd]). An account that is gone is for [CurrentAccountFilter]. It runs also for the error pages, and not for the static files.
 */
class SessionGuardFilter(private val origins: SessionOrigins) : OncePerRequestFilter() {

    override fun shouldNotFilterErrorDispatch(): Boolean = false

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = PublicPaths.isStaticFile(PublicPaths.pathOf(request))

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val authentication: Authentication? = SecurityContextHolder.getContext().authentication
        val principal = authentication?.principal
        if (authentication != null && authentication.isAuthenticated && principal is AccountUserDetails) {
            val end = origins.endOf(request.getSession(false), principal.id)
            if (end != null) {
                request.getSession(false)?.invalidate()
                SecurityContextHolder.clearContext()
                request.setAttribute(LoginEntryPoint.SESSION_ENDED, end)
            }
        }
        chain.doFilter(request, response)
    }
}
