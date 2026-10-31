package talita.account

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.core.Authentication
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions
import org.springframework.security.web.webauthn.authentication.PublicKeyCredentialRequestOptionsFilter
import org.springframework.security.web.webauthn.authentication.PublicKeyCredentialRequestOptionsRepository
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationFilter
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationRequestToken
import org.springframework.security.web.webauthn.registration.PublicKeyCredentialCreationOptionsFilter
import org.springframework.security.web.webauthn.registration.PublicKeyCredentialCreationOptionsRepository
import org.springframework.security.web.webauthn.registration.WebAuthnRegistrationFilter
import org.springframework.web.filter.OncePerRequestFilter
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** What a session holds of a challenge of WebAuthn: the options that were given, and when (a challenge can be answered once, and for five minutes). */
internal class HeldOptions(val options: Any, val at: Instant) : Serializable

/** The options of an assertion for the filters of Spring, kept in the session for one use and [validity] (Spring's own keeps them until they are used, with no limit of time). */
internal class SessionRequestOptions(private val attribute: String, private val clock: Clock, private val validity: Duration) : PublicKeyCredentialRequestOptionsRepository {

    override fun save(request: HttpServletRequest, response: HttpServletResponse, options: PublicKeyCredentialRequestOptions?) {
        if (options == null) {
            request.getSession(false)?.removeAttribute(attribute)
        } else {
            request.getSession(true).setAttribute(attribute, HeldOptions(options, clock.instant()))
        }
    }

    override fun load(request: HttpServletRequest): PublicKeyCredentialRequestOptions? {
        val held = request.getSession(false)?.getAttribute(attribute) as? HeldOptions ?: return null
        return if (clock.instant().isBefore(held.at.plus(validity))) held.options as? PublicKeyCredentialRequestOptions else null
    }
}

/** The same for the options of a registration. */
internal class SessionCreationOptions(private val attribute: String, private val clock: Clock, private val validity: Duration) : PublicKeyCredentialCreationOptionsRepository {

    override fun save(request: HttpServletRequest, response: HttpServletResponse, options: PublicKeyCredentialCreationOptions?) {
        if (options == null) {
            request.getSession(false)?.removeAttribute(attribute)
        } else {
            request.getSession(true).setAttribute(attribute, HeldOptions(options, clock.instant()))
        }
    }

    override fun load(request: HttpServletRequest): PublicKeyCredentialCreationOptions? {
        val held = request.getSession(false)?.getAttribute(attribute) as? HeldOptions ?: return null
        return if (clock.instant().isBefore(held.at.plus(validity))) held.options as? PublicKeyCredentialCreationOptions else null
    }
}

/**
 * Turns the assertion of a phone into a person who is inside (TALITA-15, criteria 22, 23): the verification is [PasskeyOperations.authenticate], and the account is read by its id
 * ([Accounts.userDetailsById]), **never created**: a passkey of an account that was deleted does not make it come back. The principal is the same [AccountUserDetails] as for a link, so
 * [CurrentAccountFilter] and everything after it do not know the difference. Whatever goes wrong is a bad credential, with no detail.
 */
internal class PasskeyAuthenticationProvider(private val operations: PasskeyOperations, private val accounts: Accounts) : AuthenticationProvider {

    override fun authenticate(authentication: Authentication): Authentication {
        val request = authentication as WebAuthnAuthenticationRequestToken
        try {
            val identity = operations.authenticate(request.webAuthnRequest)
            val person = accounts.userDetailsById(identity.name.toLong()) ?: throw BadCredentialsException("the account is gone")
            return UsernamePasswordAuthenticationToken.authenticated(person, null, emptyList())
        } catch (e: RuntimeException) {
            // The type and nothing else: the message of the library or of a check could carry data of the assertion.
            LoggerFactory.getLogger(PasskeyAuthenticationProvider::class.java).warn("Passkey: an assertion was not accepted ({}).", e.javaClass.simpleName)
            throw BadCredentialsException("passkey not accepted")
        }
    }

    override fun supports(authentication: Class<*>): Boolean = WebAuthnAuthenticationRequestToken::class.java.isAssignableFrom(authentication)
}

/** What happens next to a person who has just entered with a passkey: a listener may add to it (the confirmation before the notes on health is marked here, criterion 40). */
fun interface PasskeySignInListener {
    fun signedIn(request: HttpServletRequest, accountId: Long)
}

/**
 * After a passkey: the same as after a link ([LoginSuccessHandler], which writes the origin of the session and decides where the person goes: «Accetta l'invito», the page that was asked for before,
 * the home), answered as JSON with the destination, because the request is made by the script of the passkeys and not by a form. No cookie of «Ricordami su questo telefono»: the passkey is the way in.
 */
internal class PasskeySuccessHandler(private val login: LoginSuccessHandler, private val listeners: ObjectProvider<PasskeySignInListener>) : AuthenticationSuccessHandler {

    override fun onAuthenticationSuccess(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication) {
        val person = authentication.principal as AccountUserDetails
        val target = login.enter(request, response, authentication)
        listeners.orderedStream().forEach { it.signedIn(request, person.id) }
        response.status = HttpStatus.OK.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("""{"authenticated":true,"redirectUrl":${jsonString(target)}}""")
    }

    private fun jsonString(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
 * The filters of WebAuthn, wired by hand (TALITA-15, criteria 20-28) instead of with `webAuthn {}` of Spring, which makes its own `ProviderManager` (the account would be read by the address, and
 * created), writes no session strategy for the passkey login and keeps the options with no limit of time. The classes are Spring's; the repositories, the provider, the operations and the success handler are ours.
 *
 * - `POST /webauthn/authenticate/options` and `POST /login/webauthn`: the access (public, with the CSRF code, in [PublicPaths]).
 * - `POST /webauthn/register/options` and `POST /webauthn/register`: a person who is inside adds a passkey. `DELETE /webauthn/register/{id}` is `denyAll` (the removal is `POST /profilo/passkey/togli`).
 * - **Nothing at all without `talita.public-url`** ([PasskeySupport.enabled]): no filter, so the addresses answer 404.
 */
class PasskeyConfigurer(
    private val support: PasskeySupport,
    private val operations: PasskeyOperations,
    private val passkeys: Passkeys,
    private val accounts: Accounts,
    private val login: LoginSuccessHandler,
    private val listeners: ObjectProvider<PasskeySignInListener>,
    private val settings: LoginSettings,
    private val clock: Clock,
    private val extra: List<OncePerRequestFilter> = emptyList(),
) : AbstractHttpConfigurer<PasskeyConfigurer, HttpSecurity>() {

    override fun configure(http: HttpSecurity) {
        if (!support.enabled) return
        val requestOptions = SessionRequestOptions("talita.passkey.access", clock, settings.confirmationChallengeValidity)
        val creationOptions = SessionCreationOptions("talita.passkey.registration", clock, settings.confirmationChallengeValidity)

        val authentication = WebAuthnAuthenticationFilter().apply {
            setAuthenticationManager(ProviderManager(PasskeyAuthenticationProvider(operations, accounts)))
            setRequestOptionsRepository(requestOptions)
            setAuthenticationSuccessHandler(PasskeySuccessHandler(login, listeners))
            // The id of the session changes at the entry, as for the link (and the CSRF code with it).
            http.getSharedObject(SessionAuthenticationStrategy::class.java)?.let { setSessionAuthenticationStrategy(it) }
        }
        http.addFilterBefore(authentication, BasicAuthenticationFilter::class.java)
        http.addFilterAfter(PublicKeyCredentialRequestOptionsFilter(operations).apply { setRequestOptionsRepository(requestOptions) }, AuthorizationFilter::class.java)
        // Before the options and the registration of a person who is inside: what has to be true first (the confirmation of a teacher who has a passkey, the most passkeys), answered in JSON.
        extra.forEach { http.addFilterAfter(it, AuthorizationFilter::class.java) }
        http.addFilterAfter(PublicKeyCredentialCreationOptionsFilter(operations).apply { setCreationOptionsRepository(creationOptions) }, AuthorizationFilter::class.java)
        http.addFilterAfter(WebAuthnRegistrationFilter(passkeys, operations).apply { setCreationOptionsRepository(creationOptions) }, AuthorizationFilter::class.java)
    }
}

/**
 * What a request to add a passkey meets **before** the filters of Spring (TALITA-15, criteria 20, 45): the most passkeys (409 `piena`), and an exception of the verification that must not
 * reach the container as a 500 (a registration that is not accepted is 400 `rifiutata`, the most passkeys reached at the very end is 409 `piena`). The answer is JSON, which the script of the passkeys
 * reads. The confirmation for a teacher who already has a passkey is added to this by [PasskeyConfirmations].
 */
class PasskeyRegistrationGuard(
    private val accounts: CurrentAccounts,
    private val passkeys: Passkeys,
    private val before: List<(CurrentAccount, HttpServletRequest) -> String?> = emptyList(),
    /** What to do when a passkey was added (the profile leaves the notice «Passkey aggiunta.» for the block that the script asks for next). */
    private val registered: (CurrentAccount, HttpServletRequest) -> Unit = { _, _ -> },
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(PasskeyRegistrationGuard::class.java)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != "POST" || PublicPaths.pathOf(request) !in setOf("/webauthn/register/options", "/webauthn/register")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: jakarta.servlet.FilterChain) {
        val account = accounts.current()
        if (account == null) {
            chain.doFilter(request, response)
            return
        }
        // «piena» for the most passkeys, then whatever else the other rules say (a state name that the script knows).
        val state = if (passkeys.count(account.id) >= Passkeys.MAX_PER_ACCOUNT) "piena" else before.firstNotNullOfOrNull { it(account, request) }
        if (state != null) {
            answer(response, HttpStatus.CONFLICT, state)
            return
        }
        try {
            chain.doFilter(request, response)
            // The session is still ours to write when the filter of Spring has answered: the script asks for the block of the passkeys only after it has the answer.
            if (PublicPaths.pathOf(request) == "/webauthn/register" && response.status in 200..299) registered(account, request)
        } catch (e: PasskeyLimitException) {
            answer(response, HttpStatus.CONFLICT, "piena")
        } catch (e: PasskeyRefusedException) {
            log.warn("Passkey: a registration was not accepted.")
            answer(response, HttpStatus.BAD_REQUEST, "rifiutata")
        }
    }

    private fun answer(response: HttpServletResponse, status: HttpStatus, state: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("""{"stato":"$state"}""")
    }
}
