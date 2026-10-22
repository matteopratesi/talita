package talita.account

import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * A person at a browser, for the tests of the pages: it keeps the session between requests (the cookie)
 * and sends the CSRF code that the page of the form carries, like a browser does. Two of them are two
 * devices.
 */
class MockBrowser(private val mockMvc: MockMvc) {

    /** The session of this browser (what its cookie points to), once the server has made one. */
    var cookie: MockHttpSession? = null
        private set

    private fun remember(result: MvcResult): MvcResult {
        (result.request.getSession(false) as? MockHttpSession)?.let { cookie = it }
        return result
    }

    fun get(path: String, configure: MockHttpServletRequestDsl.() -> Unit = {}): MvcResult =
        remember(
            mockMvc.get(path) {
                cookie?.let { session = it }
                configure()
            }.andReturn(),
        )

    /**
     * Sends the form of [formPath]: asks for the page first, to have the CSRF code of its form (and the
     * session), then posts [fields] to [action] with it. The result is the answer to the post.
     */
    fun submit(
        formPath: String,
        action: String,
        fields: Map<String, String>,
        configure: MockHttpServletRequestDsl.() -> Unit = {},
    ): MvcResult {
        val page = get(formPath)
        val csrf = Regex("""name="_csrf" value="([^"]+)"""").find(page.response.contentAsString)?.groupValues?.get(1)
        checkNotNull(csrf) { "$formPath has no form with a CSRF code" }
        return remember(
            mockMvc.post(action) {
                cookie?.let { session = it }
                fields.forEach { (name, value) -> param(name, value) }
                param("_csrf", csrf)
                configure()
            }.andReturn(),
        )
    }

    /** A post without asking for the page first, with the code that the caller gives (or none). */
    fun post(action: String, fields: Map<String, String> = emptyMap(), csrf: String? = null, configure: MockHttpServletRequestDsl.() -> Unit = {}): MvcResult =
        remember(
            mockMvc.post(action) {
                cookie?.let { session = it }
                fields.forEach { (name, value) -> param(name, value) }
                if (csrf != null) param("_csrf", csrf)
                configure()
            }.andReturn(),
        )

    /** Asks for the link of the mail and taps "Entra", as the person does. The answer is that of the tap. */
    fun confirm(token: String): MvcResult = submit("/accedi/conferma?token=$token", "/accedi/conferma", mapOf("token" to token))

    companion object {
        /** The CSRF code in a page, if it has a form. */
        fun csrfIn(html: String): String? = Regex("""name="_csrf" value="([^"]+)"""").find(html)?.groupValues?.get(1)

        fun header(result: MvcResult, name: String): String? = result.response.getHeader(name)

        fun body(result: MvcResult): String = result.response.contentAsString
    }
}
