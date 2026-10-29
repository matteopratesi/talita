package talita.reminder

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import talita.TestcontainersConfiguration
import talita.account.TeacherPaths
import talita.mail.MailKind
import talita.mail.MailQuota
import talita.mail.Mailbox
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who can send the reminders (TALITA-13, criterion 11): only a teacher. A parent (A or B, also with children) is told 403 on the addresses of the reminders of the teachers, for a page and for a
 * form, also by hand, and nothing is sent; whoever has no access goes to `/accedi`. The addresses of the avviso and of the stop are for every account, and the page for stopping is public:
 * those are tried in `NoticePagesTests` and `StopPageTests`.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ReminderAccessTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val quota: MailQuota,
) {

    private val kit = ReminderTestKit(dsl, clock)

    private val browsers = ReminderBrowsers(mockMvc, tokens, dsl)

    @BeforeEach
    fun clean() {
        kit.reset()
        quota.release(MailKind.REMINDER, 1000)
    }

    private fun addresses(gathering: Long): List<String> = listOf(
        "/convivenza/$gathering/promemoria?tipo=maybe", "/convivenza/$gathering/promemoria?tipo=notice", "/convivenza/$gathering/promemoria",
    )

    @Test
    fun `t13 c11 the addresses of the reminders are under the rule of the teachers`() {
        for (path in listOf("/convivenza/1/promemoria", "/convivenza/12345/promemoria")) assertTrue(TeacherPaths.matches(path), path)
    }

    @Test
    fun `t13 c11 a parent is told 403 on the page and on the form of the reminders, also by hand, and nothing is sent`() {
        val s = kit.scenario()
        for (parent in listOf(s.a, s.b)) {
            val browser = browsers.enter(parent.email)

            for (path in addresses(s.gathering)) assertEquals(403, browser.get(path).response.status, "GET $path as a parent")
            for (type in listOf("maybe", "notice")) {
                val form = mockMvc.post("/convivenza/${s.gathering}/promemoria") { session = browser.cookie; with(csrf()); param("tipo", type) }.andReturn().response
                assertEquals(403, form.status, "POST $type")
                val htmx = mockMvc.post("/convivenza/${s.gathering}/promemoria") { session = browser.cookie; with(csrf()); header("HX-Request", "true"); param("tipo", type) }.andReturn().response
                assertEquals(403, htmx.status, "POST (htmx) $type")
                assertFalse(htmx.contentAsString.contains("id=\"promemoria\""), "nothing of the card is sent to a parent")
            }
        }

        assertTrue(kit.batches(s.gathering).isEmpty(), "no row in the register")
        for (person in listOf(s.a, s.b, s.c, s.d)) assertTrue(Mailbox.sentTo(person.email).isEmpty(), "a mail left for somebody")
        assertEquals(quota.limit(MailKind.REMINDER), quota.remaining(MailKind.REMINDER), "no place of the day was used")
    }

    @Test
    fun `t13 c11 the detail of the gathering is not for a parent either, and the answer to the page of the reminders has nothing of the dialog`() {
        val s = kit.scenario()
        val browser = browsers.enter(s.a.email)

        // The status is sent as an error, which the server answers with the page «Non puoi vedere questa pagina» (MockMvc does not run that dispatch: the page itself is tried by RolesTests).
        val forbidden = browser.get("/convivenza/${s.gathering}/promemoria?tipo=maybe").response
        assertEquals(403, forbidden.status)
        assertFalse(forbidden.contentAsString.contains("Mandare il promemoria"), "the dialog is not shown to a parent")
        assertEquals(403, browser.get("/convivenza/${s.gathering}").response.status)
    }

    @Test
    fun `t13 c11 whoever has no access is sent to the access page, with a page and with a form, and nothing is sent`() {
        val s = kit.scenario()

        for (path in addresses(s.gathering)) {
            val answer = mockMvc.get(path).andReturn().response
            assertEquals(302, answer.status, "GET $path")
            assertEquals("/accedi", answer.getHeader("Location"), "GET $path")
        }
        val form = mockMvc.post("/convivenza/${s.gathering}/promemoria") { param("tipo", "maybe") }.andReturn().response
        assertEquals(302, form.status)
        assertEquals("/accedi", form.getHeader("Location"))
        val htmx = mockMvc.post("/convivenza/${s.gathering}/promemoria") { header("HX-Request", "true"); param("tipo", "maybe") }.andReturn().response
        assertEquals("/accedi", htmx.getHeader("HX-Redirect"))
        assertTrue(kit.batches(s.gathering).isEmpty())
        assertTrue(Mailbox.sentTo(s.a.email).isEmpty())
    }

    @Test
    fun `t13 c11 a teacher is told 404 for a gathering that does not exist and for a kind that is not one, and nothing is sent`() {
        val s = kit.scenario()
        val browser = browsers.enter(s.m.email)

        assertEquals(404, browser.get("/convivenza/987654321/promemoria?tipo=maybe").response.status)
        assertEquals(404, browser.get("/convivenza/${s.gathering}/promemoria?tipo=altro").response.status)
        assertEquals(404, browser.send(987654321, "maybe").status())
        assertEquals(404, browser.send(s.gathering, "altro").status())
        assertTrue(kit.batches(s.gathering).isEmpty())
        assertTrue(Mailbox.sentTo(s.a.email).isEmpty())
    }
}
