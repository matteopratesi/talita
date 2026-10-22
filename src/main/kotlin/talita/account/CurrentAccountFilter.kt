package talita.account

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.web.filter.OncePerRequestFilter

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
                // The account is gone (TALITA-7 deletes them): the session is worth nothing.
                request.getSession(false)?.invalidate()
                SecurityContextHolder.clearContext()
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
