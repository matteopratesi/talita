package talita.account

import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest
import org.springframework.security.authentication.ott.OneTimeTokenService
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.REMEMBERED_DEVICE
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cookie of «Ricordami su questo telefono» as the real server (Tomcat) writes it, and a re-entry with nothing but the cookie, over a real connection (TALITA-15, criteria 3, 4): `HttpOnly`,
 * `SameSite=Lax`, `Path=/`, `Secure` behind a proxy that speaks HTTPS (the same two settings of Tomcat that the deploy will need, as in [SessionCookieTests]), an expiry date, and a value that changes
 * with every re-entry while the selector stays.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|::1", "server.tomcat.remoteip.protocol-header=X-Forwarded-Proto"],
)
class DeviceCookieTests(
    @LocalServerPort private val port: Int,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
) {

    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build()

    private val emails = mutableListOf<String>()

    private fun uri(path: String) = URI.create("http://localhost:$port$path")

    private fun send(request: HttpRequest.Builder, secure: Boolean = false): HttpResponse<String> {
        if (secure) request.header("X-Forwarded-Proto", "https")
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    }

    private fun cookie(response: HttpResponse<String>, name: String): String? = response.headers().allValues("Set-Cookie").firstOrNull { it.startsWith("$name=") }

    /** The page of the link and the tap with the box touched (or not): the cookies that the tap sets, by name. */
    private fun enter(secure: Boolean, remember: Boolean = true): Map<String, String> {
        val email = Addresses.unique("cookie-ricordami").also { emails += it }
        val token = tokens.generate(GenerateOneTimeTokenRequest(email, Duration.ofMinutes(15))).tokenValue
        val page = send(HttpRequest.newBuilder(uri("/accedi/conferma?token=$token")).GET(), secure)
        val session = assertNotNull(cookie(page, "JSESSIONID"))
        val csrf = Regex("""name="_csrf" value="([^"]+)"""").find(page.body())!!.groupValues[1]
        val form = "token=$token&_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8) + if (remember) "&ricordami=true" else ""
        val tap = send(
            HttpRequest.newBuilder(uri("/accedi/conferma")).header("Content-Type", "application/x-www-form-urlencoded").header("Cookie", session.substringBefore(";")).POST(HttpRequest.BodyPublishers.ofString(form)),
            secure,
        )
        assertEquals(303, tap.statusCode())
        return tap.headers().allValues("Set-Cookie").associate { it.substringBefore("=") to it }
    }

    @AfterEach
    fun clean() {
        val ids = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.`in`(emails)).fetch(ACCOUNT.ID).map { it!! }
        dsl.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.`in`(ids)).execute()
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.`in`(ids)).execute()
        emails.clear()
    }

    @Test
    fun `t15 c03 the cookie of the device is HttpOnly, SameSite Lax, on the whole site and with an expiry, and not Secure on plain HTTP`() {
        val cookies = enter(secure = false)

        val device = assertNotNull(cookies["ricordami"], "the tap with the box sets the cookie of the device: $cookies")
        assertTrue(device.contains("HttpOnly", ignoreCase = true), device)
        assertTrue(device.contains("SameSite=Lax", ignoreCase = true), device)
        assertTrue(device.contains("Path=/"), device)
        assertTrue(device.contains("Max-Age="), device)
        assertTrue(device.contains("Expires="), device)
        assertFalse(device.contains("; Secure", ignoreCase = true), device)
    }

    @Test
    fun `t15 c03 on HTTPS the cookie of the device is Secure too`() {
        val device = assertNotNull(enter(secure = true)["ricordami"])

        assertTrue(device.contains("; Secure", ignoreCase = true), device)
        assertTrue(device.contains("HttpOnly", ignoreCase = true), device)
        assertTrue(device.contains("SameSite=Lax", ignoreCase = true), device)
    }

    @Test
    fun `t15 c03 the tap without the box sets no cookie of the device`() {
        val cookies = enter(secure = false, remember = false)

        assertEquals(setOf("JSESSIONID"), cookies.keys)
    }

    @Test
    fun `t15 c04 a request with nothing but the cookie enters without the access page, with a new session and a new value, over a real connection`() {
        val first = assertNotNull(enter(secure = false)["ricordami"]).substringBefore(";")
        val (selector, firstValue) = Phone.decode(first.substringAfter("="))

        val page = send(HttpRequest.newBuilder(uri("/profilo")).header("Cookie", first).GET())

        assertEquals(200, page.statusCode())
        assertNotNull(cookie(page, "JSESSIONID"), "a new session")
        val renewed = assertNotNull(cookie(page, "ricordami")).substringBefore(";").substringAfter("=")
        val (sameSelector, secondValue) = Phone.decode(renewed)
        assertEquals(selector, sameSelector)
        assertNotEquals(firstValue, secondValue)
        // For 60 seconds the old value is good too (two tabs that the phone reopens together): it enters, and does not touch the cookie.
        val together = send(HttpRequest.newBuilder(uri("/profilo")).header("Cookie", first).GET())
        assertEquals(200, together.statusCode())
        assertNull(cookie(together, "ricordami"), "the cookie is the new one already, from the first answer")
        // After them it is a copy: the next request with it does not enter, and the device is unlinked.
        dsl.execute("update remembered_device set rotated_at = rotated_at - interval '61 seconds' where selector = ?", selector)
        val copy = send(HttpRequest.newBuilder(uri("/profilo")).header("Cookie", first).GET())
        assertEquals(302, copy.statusCode())
        assertTrue(copy.headers().firstValue("Location").orElse("").endsWith("/accedi"), "the access page")
        assertTrue(cookie(copy, "ricordami")!!.contains("Expires=Thu, 01 Jan 1970"), "the cookie is cancelled (a date in 1970)")
        val later = send(HttpRequest.newBuilder(uri("/profilo")).header("Cookie", "ricordami=$renewed").GET())
        assertEquals(302, later.statusCode(), "the device is gone for the person too")
    }

    @Test
    fun `t15 c04 a page and its style sheet that arrive together with the same cookie do not unlink the device`() {
        val cookie = assertNotNull(enter(secure = false)["ricordami"]).substringBefore(";")
        val html = send(HttpRequest.newBuilder(uri("/accedi")).GET()).body()
        val css = Regex("""href="(/css/app-[0-9a-f]+\.css)"""").find(html)!!.groupValues[1]

        // The style sheet first, then the page: the style sheet never spends the value.
        assertEquals(200, send(HttpRequest.newBuilder(uri(css)).header("Cookie", cookie).GET()).statusCode())
        assertEquals(200, send(HttpRequest.newBuilder(uri("/profilo")).header("Cookie", cookie).GET()).statusCode())
    }
}
