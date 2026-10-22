package talita.account

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest
import org.springframework.security.authentication.ott.OneTimeTokenService
import talita.Addresses
import talita.TestcontainersConfiguration
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The cookie of the session, as the real server (Tomcat) writes it (criterion 20), and the length of
 * the session (criterion 23). Behind a proxy that speaks HTTPS the server learns it from
 * `X-Forwarded-Proto`; here the test plays the proxy, with the same two settings of Tomcat that the
 * deploy (TALITA-8) will need.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|::1", "server.tomcat.remoteip.protocol-header=X-Forwarded-Proto"],
)
class SessionCookieTests(
    @LocalServerPort private val port: Int,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val environment: Environment,
) {

    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build()

    private fun send(request: HttpRequest.Builder, secure: Boolean): HttpResponse<String> {
        if (secure) request.header("X-Forwarded-Proto", "https")
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    }

    private fun uri(path: String) = URI.create("http://localhost:$port$path")

    private fun sessionCookie(response: HttpResponse<String>): String? =
        response.headers().allValues("Set-Cookie").firstOrNull { it.startsWith("JSESSIONID=") }

    /** Asks for the page of the link and taps "Entra", as a browser does, and returns the cookie that the tap sets. */
    private fun enter(secure: Boolean): Pair<String, String> {
        val token = tokens.generate(GenerateOneTimeTokenRequest(Addresses.unique("cookie"), Duration.ofMinutes(15))).tokenValue
        val page = send(HttpRequest.newBuilder(uri("/accedi/conferma?token=$token")).GET(), secure)
        val before = sessionCookie(page)
        assertNotNull(before, "the page of the link starts no session, so there is no CSRF code in the form")
        val csrf = Regex("""name="_csrf" value="([^"]+)"""").find(page.body())!!.groupValues[1]

        val form = "token=$token&_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8)
        val tap = send(
            HttpRequest.newBuilder(uri("/accedi/conferma"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", before.substringBefore(";"))
                .POST(HttpRequest.BodyPublishers.ofString(form)),
            secure,
        )
        assertEquals(303, tap.statusCode())
        val after = sessionCookie(tap)
        assertNotNull(after, "the login did not give a new session cookie")
        return before to after
    }

    @Test
    fun `t3 c20 at the login the session has a new id, and the cookie is HttpOnly and SameSite Lax`() {
        val (before, after) = enter(secure = false)

        assertNotEquals(before.substringBefore(";"), after.substringBefore(";"), "the id of the session did not change")
        for (cookie in listOf(before, after)) {
            assertTrue(cookie.contains("HttpOnly", ignoreCase = true), cookie)
            assertTrue(cookie.contains("SameSite=Lax", ignoreCase = true), cookie)
            assertFalse(cookie.contains("Secure", ignoreCase = true), "Secure on plain HTTP: $cookie")
        }
    }

    @Test
    fun `t3 c20 on HTTPS the cookie is Secure too`() {
        val (before, after) = enter(secure = true)

        for (cookie in listOf(before, after)) {
            assertTrue(cookie.contains("; Secure", ignoreCase = true), cookie)
            assertTrue(cookie.contains("HttpOnly", ignoreCase = true), cookie)
            assertTrue(cookie.contains("SameSite=Lax", ignoreCase = true), cookie)
        }
    }

    @Test
    fun `t3 c20 the session never travels in the address`() {
        val page = send(HttpRequest.newBuilder(uri("/accedi")).GET(), secure = false)

        assertFalse(page.body().contains("jsessionid", ignoreCase = true))
        assertFalse(page.headers().firstValue("Location").orElse("").contains("jsessionid", ignoreCase = true))
        assertEquals("cookie", environment.getProperty("server.servlet.session.tracking-modes"))
    }

    @Test
    fun `t3 c23 the session ends after two hours without use, and it is in memory`() {
        assertEquals("2h", environment.getProperty("server.servlet.session.timeout"))
        // Tomcat does not keep the sessions on disk across a restart: nothing to read the day after.
        assertNotEquals("true", environment.getProperty("server.servlet.session.persistent"))
    }
}
