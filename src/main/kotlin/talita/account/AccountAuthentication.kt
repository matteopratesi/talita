package talita.account

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Component

/**
 * What the session remembers about who is inside: the id and the address, nothing else. The roles
 * are not here, so a role that changes (an invitation accepted, a teacher removed) is in force at the
 * next request without the person going out and in: [CurrentAccountFilter] reads them from the
 * database on every request.
 */
class AccountUserDetails(val id: Long, private val email: String) : UserDetails {

    override fun getAuthorities(): Collection<GrantedAuthority> = emptyList()

    override fun getPassword(): String? = null

    override fun getUsername(): String = email

    override fun toString(): String = "AccountUserDetails"

    private companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Who is making this request, for Spring Security: [account] as the database says now, with its
 * roles. It lives for one request and is never stored in the session.
 */
class AccountAuthentication(val account: CurrentAccount, authorities: Collection<GrantedAuthority>) :
    AbstractAuthenticationToken(authorities) {

    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): Any = account

    /** The id and not the address: this is the name that Spring Security writes in its messages. */
    override fun getName(): String = account.id.toString()

    override fun toString(): String = "AccountAuthentication(${account.id})"

    companion object {
        /** The authority of a teacher, as `hasRole("TEACHER")` reads it. */
        const val ROLE_TEACHER = "ROLE_TEACHER"

        fun teacherAuthority(): GrantedAuthority = SimpleGrantedAuthority(ROLE_TEACHER)
    }
}

/**
 * Loads the account for the login of a person who has just used a valid link: the account is born
 * here, at the first successful login (Spring Security consumes the link first, and calls this only
 * after). A link that was never used creates nothing.
 */
@Component
class AccountUserDetailsService(private val accounts: Accounts) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {
        // Links are made only for addresses that EmailAddress accepted; anything else is a bug.
        val address = EmailAddress.parse(username) ?: throw UsernameNotFoundException("not an address")
        return AccountUserDetails(accounts.findOrCreate(address), address.value)
    }
}
