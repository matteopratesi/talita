package talita.api

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler
import org.springframework.security.web.firewall.RequestRejectedHandler
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.header.HeaderWriterFilter
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * **The chain of security of `/api/…`** (TALITA-21, criteria 6-8): a second [SecurityFilterChain], ordered before the one of the pages, which for the pages does not exist and for which the pages do not exist.
 *
 * - **Only `Authorization: Bearer`**: no session (nothing is read from one and none is made), no cookie, no CSRF code, no link, no passkey, no «Ricordami». A cookie of a session that is valid does not open
 *   the APIs, and the token does not open the pages (the chain of the pages does not look at the header).
 * - **No CORS**: nothing here answers `Access-Control-Allow-*`, so a browser on another site cannot use the APIs.
 * - **The headers** of every answer, errors too: JSON, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`.
 * - **A token that does not work** (missing, of another form, unknown, revoked, past its day, or of someone who is not a teacher any more) gets one answer, the same bytes ([ApiErrors.unauthorized]), and
 *   one line of the log a minute at most, with how many were refused and nothing else.
 */
@Configuration(proxyBeanMethods = false)
class ApiSecurityConfiguration {

    @Bean
    @Order(1)
    fun apiSecurityFilterChain(http: HttpSecurity, tokens: ApiTokens, limit: ApiRateLimit, requests: ApiRequests, clock: Clock): SecurityFilterChain {
        http {
            securityMatcher("/api/**")
            authorizeHttpRequests {
                authorize(anyRequest, hasRole("TEACHER"))
            }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            csrf { disable() }
            logout { disable() }
            exceptionHandling {
                authenticationEntryPoint = org.springframework.security.web.AuthenticationEntryPoint { _, response, _ -> ApiErrors.unauthorized(response) }
                accessDeniedHandler = org.springframework.security.web.access.AccessDeniedHandler { _, response, _ -> ApiErrors.unauthorized(response) }
            }
            headers {
                cacheControl { disable() }
                contentSecurityPolicy { policyDirectives = CONTENT_SECURITY_POLICY }
            }
            // After the writer of the headers: an error that is written resets the answer, and the headers are put on it when it is sent.
            addFilterAfter<HeaderWriterFilter>(ApiResponseFilter())
            addFilterBefore<AnonymousAuthenticationFilter>(ApiTokenFilter(tokens, limit, requests, clock))
        }
        return http.build()
    }

    /**
     * `FormContentFilter` of Spring MVC runs before the chain of security and reads the whole body of a form-encoded `PUT`, `PATCH` or `DELETE`: on `/api/…` that would be a body buffered and parsed before the
     * token and before the limit of 64 KB. The APIs take only JSON, so the form-encoded body is refused here, first of all (415), and never read. No page of the app sends a form with these methods.
     */
    @Bean
    fun apiFormBodyGuard(): org.springframework.boot.web.servlet.FilterRegistrationBean<ApiFormBodyGuard> =
        org.springframework.boot.web.servlet.FilterRegistrationBean(ApiFormBodyGuard()).also {
            it.order = org.springframework.core.Ordered.HIGHEST_PRECEDENCE
            it.addUrlPatterns("/api/*")
        }

    /**
     * The firewall of Spring Security refuses some addresses before any chain (`//`, `;`, an encoded `/` or `..`): for the pages the answer stays the 400 that it always was, for `/api/…` it is a JSON error with the
     * headers of the APIs (TALITA-21, criteria 7 and 9). What Tomcat refuses before the application (a bad `%` escape, `%00`) never gets here and keeps the page of the container: `docs/05`.
     */
    @Bean
    fun requestRejectedHandler(): RequestRejectedHandler {
        val pages = HttpStatusRequestRejectedHandler()
        return RequestRejectedHandler { request, response, rejected ->
            if (request.requestURI.startsWith("/api/") || request.requestURI == "/api") {
                ApiErrors.write(response, 400, ApiTexts.BAD_REQUEST, headers = mapOf("Content-Security-Policy" to CONTENT_SECURITY_POLICY))
            } else {
                pages.handle(request, response, rejected)
            }
        }
    }

    companion object {
        const val CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'"
    }
}

/** How many tokens that do not work were refused since the last line of the log, and when it was written: at most one line a minute, with the number and nothing else. */
internal class Rejections(private val clock: Clock) {

    private val log = LoggerFactory.getLogger(Rejections::class.java)
    private val count = AtomicInteger()
    private val lastLine = AtomicLong(0)

    fun record() {
        count.incrementAndGet()
        val now = clock.millis()
        val before = lastLine.get()
        if (now - before >= 60_000 && lastLine.compareAndSet(before, now)) {
            log.warn("API: {} requests refused for a token that is missing or not valid.", count.getAndSet(0))
        }
    }
}

/**
 * Reads `Authorization: Bearer <token>`, finds the teacher, counts the request against the limit of the token, refuses a body past 64 KB, lets the request go and writes one line in the register of the
 * requests with how it ended (criteria 8, 10, 12). A request with no valid token writes no line, and its answer is the one of [ApiErrors.unauthorized].
 */
internal class ApiTokenFilter(
    private val tokens: ApiTokens,
    private val limit: ApiRateLimit,
    private val requests: ApiRequests,
    clock: Clock,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(ApiTokenFilter::class.java)
    private val rejections = Rejections(clock)

    private fun presented(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        if (!header.startsWith(SCHEME, ignoreCase = true)) return null
        return header.substring(SCHEME.length).trim().takeIf { it.isNotEmpty() }
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val principal = presented(request)?.let(tokens::authenticate)
        if (principal == null) {
            rejections.record()
            ApiErrors.unauthorized(response)
            return
        }
        // The teacher of the token, for this request only: the chain is without a session, and the holder is emptied at the end of the request.
        SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext().also { it.authentication = UsernamePasswordAuthenticationToken(principal, null, listOf(SimpleGrantedAuthority("ROLE_TEACHER"))) })
        var skipRegister = false
        try {
            val refusal = limit.take(principal.tokenId)
            if (refusal != null) {
                // Only the first refusal of a minute is written in the register: a flood of 429 must not fill it, nor push the real requests out of the card.
                skipRegister = !refusal.first
                ApiErrors.write(response, 429, ApiTexts.tooMany(refusal.seconds), headers = mapOf("Retry-After" to refusal.seconds.toString()))
            } else if (request.contentType?.lowercase()?.startsWith("multipart/") == true) {
                // A multipart body is read by the resolver of Spring before the counter of the bytes: the APIs take only JSON.
                ApiErrors.write(response, 415, ApiTexts.NOT_JSON)
            } else if (request.contentLengthLong > LimitedRequest.MAX_BYTES) {
                ApiErrors.write(response, 413, ApiTexts.TOO_LARGE)
            } else {
                chain.doFilter(LimitedRequest(request, LimitedRequest.MAX_BYTES), response)
            }
        } finally {
            if (!skipRegister) record(request, response)
        }
    }

    /** The line of the register: the method, the route as a model and the code. A failure of the register never changes the answer. */
    private fun record(request: HttpServletRequest, response: HttpServletResponse) {
        try {
            // An address that no route of the APIs took (the handler of the static files has `/**`) is the same line for every one of them.
            val route = (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String)?.takeIf { it.startsWith("/api/") }?.let(ApiRequests::model) ?: ApiRequests.UNKNOWN_ROUTE
            requests.add(request.method, route, response.status)
        } catch (e: Exception) {
            log.warn("API: a request could not be written in the register ({}).", e.javaClass.simpleName)
        }
    }

    private companion object {
        const val SCHEME = "Bearer "
    }
}

/** Refuses with a 415 a body of `/api/…` that is a form (`application/x-www-form-urlencoded`), before anything reads it (see [ApiSecurityConfiguration.apiFormBodyGuard]). */
class ApiFormBodyGuard : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.contentType?.lowercase()?.startsWith("application/x-www-form-urlencoded") == true) {
            ApiErrors.write(response, 415, ApiTexts.NOT_JSON, headers = mapOf("Content-Security-Policy" to ApiSecurityConfiguration.CONTENT_SECURITY_POLICY))
            return
        }
        chain.doFilter(request, response)
    }
}
