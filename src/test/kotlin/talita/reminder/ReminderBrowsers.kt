package talita.reminder

import org.jooq.DSLContext
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import talita.Addresses
import talita.account.MockBrowser
import talita.account.MockSignIn

/** The people at a browser, for the tests of the pages of the reminders: they enter the real way (the link and the tap), and send the CSRF code that the page carries. */
internal class ReminderBrowsers(private val mockMvc: MockMvc, tokens: OneTimeTokenService, dsl: DSLContext) {

    private val signIn = MockSignIn(mockMvc, tokens, dsl)

    /** A browser that has entered as [email] (the account is there already, or is born here). */
    fun enter(email: String): MockBrowser = MockBrowser(mockMvc).also { it.confirm(signIn.link(email)) }

    /** A person with no account yet who enters: a parent, with an address of their own. */
    fun parent(name: String = "promemoria-genitore"): Pair<MockBrowser, String> {
        val email = Addresses.unique(name)
        return enter(email) to email
    }
}

/**
 * «Manda il promemoria» as the dialog sends it (htmx), or as the page of confirmation without JavaScript does ([htmx] false): the page of «Maestri» first for the CSRF
 * code (every teacher has it, and it has forms in every state), then the post with the kind in the body, as the page does.
 */
internal fun MockBrowser.send(gathering: Long, type: String, htmx: Boolean = true): MvcResult =
    submit("/maestri", "/convivenza/$gathering/promemoria", mapOf("tipo" to type)) { if (htmx) header("HX-Request", "true") }

internal fun MvcResult.html(): String = response.contentAsString

internal fun MvcResult.status(): Int = response.status

internal fun MvcResult.location(): String? = response.getHeader("Location")
