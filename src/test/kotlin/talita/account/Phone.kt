package talita.account

import jakarta.servlet.http.Cookie
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.net.URLDecoder
import java.time.Duration
import java.util.Base64

/**
 * A phone, for the tests of «Ricordami su questo telefono» and of the sessions (TALITA-15): a browser with its **cookies**, which [MockBrowser] has not (it only keeps the session). It sends
 * what its jar holds with every request, whatever the address (the static files too, like a real browser), and keeps what the answers set: a cookie with `Max-Age=0` leaves the jar. The
 * session is the one that the server made: [forgetSession] is two hours without use, a restart of the application or a browser that was closed. Two phones are two devices.
 */
class Phone(private val mockMvc: MockMvc, private val tokens: OneTimeTokenService, private val secure: Boolean = false) {

    /** The session of this browser (what its cookie `JSESSIONID` points to), once the server has made one. */
    var session: MockHttpSession? = null

    /** The other cookies, by name. */
    val jar = LinkedHashMap<String, Cookie>()

    /** Every answer that this phone got, last one last: what a test looks at when it needs the headers. */
    var last: MvcResult? = null
        private set

    fun forgetSession() {
        session = null
    }

    private fun send(builder: MockHttpServletRequestDsl) {
        session?.let { builder.session = it }
        jar.values.forEach { builder.cookie(it) }
        if (secure) builder.secure = true
    }

    private fun remember(result: MvcResult): MvcResult {
        val sent = result.request.getSession(false) as? MockHttpSession
        session = when {
            sent != null -> sent
            session?.isInvalid == true -> null
            else -> session
        }
        for (cookie in result.response.cookies) {
            if (cookie.maxAge == 0) jar.remove(cookie.name) else jar[cookie.name] = cookie
        }
        last = result
        return result
    }

    fun get(path: String, configure: MockHttpServletRequestDsl.() -> Unit = {}): MvcResult =
        remember(mockMvc.get(path) { send(this); configure() }.andReturn())

    fun post(path: String, fields: Map<String, String> = emptyMap(), csrf: String? = null, configure: MockHttpServletRequestDsl.() -> Unit = {}): MvcResult =
        remember(
            mockMvc.post(path) {
                send(this)
                fields.forEach { (name, value) -> param(name, value) }
                if (csrf != null) param("_csrf", csrf)
                configure()
            }.andReturn(),
        )

    /** A POST with a JSON body, as the script of the passkeys sends it: the CSRF code in the header, the answer read as JSON. */
    fun postJson(path: String, body: String? = null, csrf: String? = null, configure: MockHttpServletRequestDsl.() -> Unit = {}): MvcResult =
        remember(
            mockMvc.post(path) {
                send(this)
                accept = MediaType.APPLICATION_JSON
                if (body != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }
                if (csrf != null) header("X-CSRF-TOKEN", csrf)
                configure()
            }.andReturn(),
        )

    /** Asks for the page of the form first, for its CSRF code (and the session), then posts [fields] to [action] with it, as the browser does. */
    fun submit(formPath: String, action: String, fields: Map<String, String> = emptyMap(), configure: MockHttpServletRequestDsl.() -> Unit = {}): MvcResult {
        val page = get(formPath)
        val code = checkNotNull(MockBrowser.csrfIn(page.response.contentAsString)) { "$formPath has no form with a CSRF code" }
        return post(action, fields, code, configure)
    }

    /** The CSRF code of the page of [path]. */
    fun csrfOf(path: String): String = checkNotNull(MockBrowser.csrfIn(get(path).response.contentAsString)) { "$path has no form with a CSRF code" }

    /** A new link for [email], as the service of the one-time tokens makes it (what the application does, without the mail). */
    fun link(email: String): String = tokens.generate(GenerateOneTimeTokenRequest(email, Duration.ofMinutes(15))).tokenValue

    /** Opens the link of the mail and taps «Entra», with the box «Ricordami su questo telefono» touched or not. The answer is that of the tap. */
    fun enter(email: String, remember: Boolean = false, token: String = link(email)): MvcResult =
        submit("/accedi/conferma?token=$token", "/accedi/conferma", buildMap { put("token", token); if (remember) put(DeviceRememberMeServices.PARAMETER, "true") })

    fun body(path: String): String = get(path).response.contentAsString

    // ---- The cookie of «Ricordami» ----------------------------------------------------------------------------------------------------

    /** The cookie of the device, as the browser holds it, or null. */
    val deviceCookie: Cookie? get() = jar[DeviceRememberMeServices.COOKIE]

    /** The selector and the value inside the cookie, as the server wrote them. */
    fun deviceTokens(cookie: Cookie? = deviceCookie): Pair<String, String>? = cookie?.let { decode(it.value) }

    companion object {
        /** What is inside the value of a cookie of «Ricordami»: `selector:value`, URL-encoded and in base64. */
        fun decode(value: String): Pair<String, String> {
            val padded = value + "=".repeat((4 - value.length % 4) % 4)
            val text = String(Base64.getDecoder().decode(padded))
            val (selector, token) = text.split(':').map { URLDecoder.decode(it, Charsets.UTF_8) }
            return selector to token
        }
    }
}
