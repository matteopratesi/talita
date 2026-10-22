package talita.account

import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import talita.Addresses
import java.time.Duration

/**
 * How a test of the pages gets inside: the real way, with no shortcut in the production code. The
 * link comes from the service of the one-time tokens (as [LoginLinks] makes it, without the mail), the
 * page of the link is opened, and the tap on "Entra" is sent with the CSRF code that the page carries.
 * What comes back is the session of the person, to put in the next requests.
 */
class MockSignIn(private val mockMvc: MockMvc, private val tokens: OneTimeTokenService) {

    /** A new link for [email]. */
    fun link(email: String): String = tokens.generate(GenerateOneTimeTokenRequest(email, Duration.ofMinutes(15))).tokenValue

    /** Enters as [email] and returns the session. The account is born here, as for anyone at the first login. */
    fun session(email: String = Addresses.unique("persona")): MockHttpSession {
        val token = link(email)
        val page = mockMvc.get("/accedi/conferma") { param("token", token) }.andReturn()
        val csrf = Regex("""name="_csrf" value="([^"]+)"""").find(page.response.contentAsString)?.groupValues?.get(1)
        checkNotNull(csrf) { "the page of the link has no CSRF code" }

        val tap = mockMvc.post("/accedi/conferma") {
            session = page.request.getSession(false) as MockHttpSession
            param("token", token)
            param("_csrf", csrf)
        }.andReturn()
        check(tap.response.status == 303 && tap.response.getHeader("Location") == "/") { "the login did not lead to the home: ${tap.response.status} ${tap.response.getHeader("Location")}" }
        return tap.request.getSession(false) as MockHttpSession
    }
}
