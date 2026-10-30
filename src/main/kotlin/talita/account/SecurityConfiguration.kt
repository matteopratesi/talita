package talita.account

import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpMethod
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.ott.InvalidOneTimeTokenException
import org.springframework.security.authentication.ott.JdbcOneTimeTokenService
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.authentication.AuthenticationConverter
import org.springframework.security.web.authentication.ott.GenerateOneTimeTokenRequestResolver
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter
import org.springframework.security.web.authentication.ott.OneTimeTokenGenerationSuccessHandler
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy
import talita.ui.TooLargeUploadFilter
import java.time.Clock

/**
 * Who can open what, and how a person enters: with a link that arrives by email (one-time token of
 * Spring Security), no password.
 *
 * - **Everything asks for a login**, except the list of [PublicPaths]; the addresses of [TeacherPaths]
 *   ask for a teacher (every account is a parent, so there is nothing to ask for the others).
 * - **The link** is born only in [LoginLinks] (from `/accedi`). Spring's own address for generating
 *   tokens (`/ott/generate`) is switched off: it generates nothing and sends nothing.
 * - **Opening the link does not log in** (mail programs open links to look at them): the page asks
 *   for a tap, and the tap is a POST with the CSRF code, to `/accedi/conferma`.
 * - **The session** lives in memory and ends after two hours without use (`server.servlet.session.timeout`) and, used or not, after 12 hours ([SessionGuardFilter]). A person who touched
 *   «Ricordami su questo telefono» when entering has a cookie ([DeviceRememberMeServices]) that lets the browser in again by itself, without a link, for 30 days (7 for a teacher).
 * - **The headers** of every response: a Content-Security-Policy that allows only what the app
 *   serves itself, no referrer outside the site, no sniffing of the type of file.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfiguration {

    /** The links, in the table `one_time_tokens` (V2): they survive nothing but a restart of the database. */
    @Bean
    fun oneTimeTokenService(jdbc: JdbcTemplate, clock: Clock): OneTimeTokenService =
        JdbcOneTimeTokenService(jdbc).also { it.setClock(clock) }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        tokens: OneTimeTokenService,
        accounts: Accounts,
        destinations: ObjectProvider<PostLoginDestination>,
        texts: SessionTexts,
        origins: SessionOrigins,
        remembered: DeviceRememberMeServices,
        passkeySupport: PasskeySupport,
        passkeyOperations: PasskeyOperations,
        passkeys: Passkeys,
        passkeyListeners: ObjectProvider<PasskeySignInListener>,
        currentAccounts: CurrentAccounts,
        confirmations: PasskeyConfirmations,
        passkeyPresenter: PasskeyPresenter,
        settings: LoginSettings,
        clock: Clock,
    ): SecurityFilterChain {
        val entryPoint = LoginEntryPoint(texts)
        val requests = requestCache()
        // The one way in after a link and after a passkey: the origin of the session, and where the person goes.
        val login = LoginSuccessHandler(requests, destinations.orderedStream().toList(), origins)

        http {
            authorizeHttpRequests {
                PublicPaths.all.forEach { authorize(it, permitAll) }
                TeacherPaths.patterns.forEach { authorize(it, hasRole("TEACHER")) }
                // The removal of a passkey is ours (`POST /profilo/passkey/togli`, which a person can use only for their own): Spring's `DELETE /webauthn/register/{id}` is closed (TALITA-15, criterion 26).
                authorize(HttpMethod.DELETE, "/webauthn/register/**", denyAll)
                authorize(anyRequest, authenticated)
            }

            oneTimeTokenLogin {
                tokenService = tokens
                // A POST here is the tap on "Entra": the only way in. It consumes the link.
                loginProcessingUrl = "/accedi/conferma"
                showDefaultSubmitPage = false
                authenticationConverter = LinkFromForm()
                authenticationSuccessHandler = login
                authenticationFailureHandler = LoginFailureHandler()
                // Links are made by LoginLinks and nowhere else: this one makes none and sends none.
                generateRequestResolver = GenerateOneTimeTokenRequestResolver { null }
                oneTimeTokenGenerationSuccessHandler = OneTimeTokenGenerationSuccessHandler { _, _, _ ->
                    error("Spring's own generation of links must not be reached")
                }
            }

            // «Ricordami su questo telefono» (TALITA-15): our own services on top of Spring's, which wire the session strategy and the way out by themselves.
            rememberMe {
                rememberMeServices = remembered
            }

            logout {
                logoutUrl = "/esci"
                logoutSuccessHandler = LogoutRedirect(texts)
            }

            exceptionHandling {
                authenticationEntryPoint = entryPoint
                accessDeniedHandler = AccessHandler(entryPoint, remembered)
            }

            requestCache { requestCache = requests }

            // Passkeys (TALITA-15): the filters of Spring's WebAuthn, wired by us. Without `talita.public-url` there are none.
            with(PasskeyConfigurer(passkeySupport, passkeyOperations, passkeys, accounts, login, passkeyListeners, settings, clock, listOf(passkeyGuard(currentAccounts, passkeys, confirmations, passkeyPresenter))))

            headers {
                contentSecurityPolicy {
                    policyDirectives = CONTENT_SECURITY_POLICY
                }
                referrerPolicy {
                    policy = ReferrerPolicy.SAME_ORIGIN
                }
            }

            // A session that must not count any more ends before the cookie of a device is looked at, so the person with a good cookie enters again in the same request.
            addFilterBefore<RememberMeAuthenticationFilter>(SessionGuardFilter(origins))
            addFilterBefore<AnonymousAuthenticationFilter>(CurrentAccountFilter(accounts))
            // A form with a file that is past the limit of the container (TALITA-12): Tomcat throws when the filter of the CSRF code asks for the code, so this one comes before.
            addFilterBefore<CsrfFilter>(TooLargeUploadFilter())
        }
        return http.build()
    }

    /**
     * What a request to add a passkey meets first: the most passkeys, the confirmation of a teacher who already has one (criterion 45: without it `POST /webauthn/register/options` answers
     * «serve-conferma» and nothing changes), and the notice that a registration leaves for the profile.
     */
    private fun passkeyGuard(accounts: CurrentAccounts, passkeys: Passkeys, confirmations: PasskeyConfirmations, presenter: PasskeyPresenter) = PasskeyRegistrationGuard(
        accounts, passkeys,
        before = listOf({ account, request -> if (confirmations.needed(account, request.getSession(false))) "serve-conferma" else null }),
        registered = { account, request -> AccessFlash.put(request, "passkey", presenter.added(account.isTeacher)) },
    )

    companion object {
        /**
         * Only what the app serves itself: scripts, styles, fonts, images and forms from its own
         * address, no plug-ins, no `<base>`, never in a frame. No `unsafe-inline` and no `unsafe-eval`:
         * the pages have no inline script, no `on*` handler, no `style=""` (a test checks them).
         */
        const val CONTENT_SECURITY_POLICY =
            "default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; " +
                "base-uri 'none'; frame-ancestors 'none'; form-action 'self'"
    }
}

/**
 * The link that is being confirmed, from the field `token` of the form. A request without it is a
 * link that does not work, like any other that does not: the same page, "Il link non vale più".
 */
private class LinkFromForm : AuthenticationConverter {
    override fun convert(request: jakarta.servlet.http.HttpServletRequest): OneTimeTokenAuthenticationToken {
        val token = request.getParameter("token")?.trim()
        if (token.isNullOrEmpty()) throw InvalidOneTimeTokenException("no token")
        return OneTimeTokenAuthenticationToken(token)
    }
}
