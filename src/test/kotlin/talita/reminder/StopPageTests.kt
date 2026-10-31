package talita.reminder

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import talita.CapturesOutput
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.gathering.PublicCodes
import talita.gathering.heading
import talita.gathering.text
import talita.gathering.title
import talita.jooq.tables.references.ACCOUNT
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The page that the link of every reminder leads to (TALITA-13, criterion 9): public, asks for a tap, opening it changes nothing, the tap stops the account of the code, a code that does
 * not exist has the same page and the same answer, the page never shows the address, and the code is never in the log. The addresses of the tests are only made with `Addresses`.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class StopPageTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = ReminderTestKit(dsl, clock)

    private val browsers = ReminderBrowsers(mockMvc, tokens, dsl)

    @BeforeEach
    fun clean() = kit.reset()

    private fun newCode(): String = PublicCodes.next()

    /** The page as a link opens it: nobody inside, the code in the query. */
    private fun open(code: String?) = mockMvc.get("/promemoria/smetti") { if (code != null) param("codice", code) }.andReturn()

    /** The tap, as a browser does it: the page first, for the CSRF code and the session, then the form. */
    private fun tap(browser: MockBrowser, code: String): org.springframework.test.web.servlet.MvcResult =
        browser.submit("/promemoria/smetti?codice=$code", "/promemoria/smetti", mapOf("codice" to code))

    @Test
    fun `t13 c09 the page is public, says what the mails are and asks for a tap, and opening it changes nothing`() {
        val s = kit.scenario()
        val code = newCode()
        dsl.update(ACCOUNT).set(ACCOUNT.REMINDER_CODE, code).where(ACCOUNT.ID.eq(s.b.id)).execute()
        val consent = kit.consentOf(s.b.id)
        assertNotNull(consent)

        val result = open(code)

        assertEquals(200, result.response.status, "nobody is inside: it is not the access page")
        val html = result.response.contentAsString
        assertEquals("Promemoria di Talità · Talità", title(html))
        assertEquals("Promemoria di Talità", heading(html))
        val said = text(html)
        assertTrue(said.contains("Ti scriviamo solo per ricordarti di confermare un’iscrizione in forse o, se l’hai chiesto, per avvisarti quando si aprono le iscrizioni di una nuova convivenza."), said)
        assertTrue(said.contains("Se non vuoi più riceverli, tocca «Non mandarmi più promemoria»: non ti scriveremo più per nessuno dei due motivi."), said)
        assertTrue(said.contains("Hai aperto questa pagina per sbaglio? Chiudila: finché non tocchi il bottone, non cambia niente. I link per entrare in Talità ti arrivano comunque, quando li chiedi."), said)
        assertTrue(Regex("""<form method="post" action="/promemoria/smetti"""").containsMatchIn(html))
        assertTrue(html.contains("""<input type="hidden" name="codice" value="$code">"""), "the code is in the form for the tap")
        assertTrue(Regex("""name="_csrf" value="[^"]+"""").containsMatchIn(html), "the tap has the CSRF code")
        assertTrue(html.contains("Non mandarmi più promemoria") && html.contains("Salvataggio…"))
        // The shell without access: the mark, no navigation, no profile.
        assertFalse(html.contains("<nav"), "no navigation")
        // Nothing of the account is in the page.
        assertFalse(html.contains(s.b.email), "the address of the account is never shown")
        assertFalse(Regex("""(src|href)="https?://""").containsMatchIn(html), "no link to another site: no Referer takes the code out")
        assertTrue(html.contains("""<meta name="robots" content="noindex, nofollow">"""))
        assertEquals("noindex, nofollow", result.response.getHeader("X-Robots-Tag"))
        assertTrue(result.response.getHeader("Cache-Control").orEmpty().contains("no-store"), "never kept: ${result.response.getHeader("Cache-Control")}")
        // Opening it, twice, changed nothing.
        open(code)
        assertNull(kit.stopOf(s.b.id))
        assertEquals(consent, kit.consentOf(s.b.id), "the consent is where it was")
    }

    @Test
    fun `t13 c09 the tap stops the account of the code, takes the avviso away, and says it, without the address`() {
        val s = kit.scenario()
        val code = newCode()
        dsl.update(ACCOUNT).set(ACCOUNT.REMINDER_CODE, code).where(ACCOUNT.ID.eq(s.b.id)).execute()
        val visitor = MockBrowser(mockMvc)

        val result = tap(visitor, code)

        assertEquals(200, result.response.status)
        val html = result.response.contentAsString
        assertEquals("Promemoria fermati · Talità", title(html))
        assertEquals("Non riceverai più promemoria", heading(html))
        assertTrue(text(html).contains("Da ora Talità non ti manda più promemoria né avvisi. I link per entrare ti arrivano comunque, quando li chiedi."), text(html))
        assertTrue(text(html).contains("Se cambi idea, puoi riprendere i promemoria dal tuo profilo."))
        assertTrue(html.contains("""href="/profilo"""") && text(html).contains("Vai al profilo"))
        assertFalse(html.contains(s.b.email) || html.contains(code), "neither the address nor the code")
        assertNotNull(kit.stopOf(s.b.id))
        assertNull(kit.consentOf(s.b.id), "the consent to the avviso is taken away")
        assertNull(kit.stopOf(s.a.id), "no other account is touched")
        assertNull(kit.stopOf(s.d.id))
    }

    @Test
    fun `t13 c09 a code that does not exist, one that is not the shape of a code, and none, have the same page and the same answer, and an account that stopped already too`() {
        val s = kit.scenario()
        val code = newCode()
        dsl.update(ACCOUNT).set(ACCOUNT.REMINDER_CODE, code).where(ACCOUNT.ID.eq(s.b.id)).execute()

        // The page: the same, but for the code that goes back in the form (and the CSRF code, which is random).
        fun normal(html: String) = html.replace(Regex("""name="codice" value="[^"]*""""), """name="codice" value="CODE"""").replace(Regex("""name="_csrf" value="[^"]+""""), """name="_csrf" value="X"""")
        assertEquals(normal(open(code).response.contentAsString), normal(open(newCode()).response.contentAsString), "same page for a code that does not exist")
        val empty = open(null).response.contentAsString
        val wrong = open("non-un-codice").response.contentAsString
        for (html in listOf(empty, wrong)) {
            assertEquals("Promemoria di Talità", heading(html))
            assertTrue(html.contains("""<input type="hidden" name="codice" value="">"""), "a code that is not one is not echoed")
        }
        assertEquals(normal(empty), normal(wrong))

        // The answer: the same page byte for byte for the code that exists, one that does not, none and a wrong one.
        val answers = listOf(code, newCode(), "", "non-un-codice", "x".repeat(2000)).map { tap(MockBrowser(mockMvc), it).response }
        assertTrue(answers.all { it.status == 200 })
        assertEquals(1, answers.map { it.contentAsString }.toSet().size, "the answer does not say whether the account is there")
        assertNotNull(kit.stopOf(s.b.id))
        assertNull(kit.stopOf(s.a.id))

        // Again, for an account that has stopped: the same, and the moment of the first stop stays.
        val first = kit.stopOf(s.b.id)
        val again = tap(MockBrowser(mockMvc), code).response
        assertEquals(answers.first().contentAsString, again.contentAsString)
        assertEquals(first, kit.stopOf(s.b.id))
    }

    @Test
    fun `t13 c09 the tap needs the CSRF code, a page left open for too long comes back to itself with a notice, and nothing changes`() {
        val s = kit.scenario()
        val code = newCode()
        dsl.update(ACCOUNT).set(ACCOUNT.REMINDER_CODE, code).where(ACCOUNT.ID.eq(s.b.id)).execute()

        val refused = mockMvc.post("/promemoria/smetti") { param("codice", code) }.andReturn()

        assertEquals(303, refused.response.status, "not the page of the error, not the access page")
        assertEquals("/promemoria/smetti?codice=$code", refused.response.getHeader("Location"))
        assertNull(kit.stopOf(s.b.id))
        val back = mockMvc.get("/promemoria/smetti") { param("codice", code); session = refused.request.getSession(false) as org.springframework.mock.web.MockHttpSession }.andReturn()
        assertTrue(back.response.contentAsString.contains("""role="alert""""))
        assertTrue(text(back.response.contentAsString).contains("La pagina era aperta da troppo tempo. Riprova."), text(back.response.contentAsString))
        assertTrue(back.response.contentAsString.contains("""name="codice" value="$code""""), "the code is still in the form: the tap can be repeated")
        // Without a code in the body it comes back to the page without one.
        assertEquals("/promemoria/smetti", mockMvc.post("/promemoria/smetti").andReturn().response.getHeader("Location"))
    }

    @Test
    fun `t13 c09 a person who is inside and taps stops the account of the code, and nothing of their own`() {
        val s = kit.scenario()
        val code = newCode()
        dsl.update(ACCOUNT).set(ACCOUNT.REMINDER_CODE, code).where(ACCOUNT.ID.eq(s.b.id)).execute()
        val inside = browsers.enter(s.a.email)

        val result = tap(inside, code)

        assertEquals(200, result.response.status)
        assertNotNull(kit.stopOf(s.b.id), "the account of the code")
        assertNull(kit.stopOf(s.a.id), "not the account of the session: the link of a forwarded mail can stop that account and nothing else")
    }

    @Test
    @CapturesOutput
    fun `t13 c12 the code of the link is never in the log, whatever happens to the page`(output: CapturedOutput) {
        val s = kit.scenario()
        val code = newCode()
        dsl.update(ACCOUNT).set(ACCOUNT.REMINDER_CODE, code).where(ACCOUNT.ID.eq(s.b.id)).execute()

        open(code)
        tap(MockBrowser(mockMvc), code)
        tap(MockBrowser(mockMvc), newCode())
        mockMvc.post("/promemoria/smetti") { param("codice", code) }.andReturn()

        assertFalse(output.all.contains(code), "the code is in the log")
        assertFalse(output.all.contains("codice="), "no address of the page in the log")
        assertFalse(output.all.contains(s.b.email), "no address in the log")
    }
}
