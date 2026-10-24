package talita.checkin

import org.jooq.DSLContext
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
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.TeacherPaths
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.REGISTRATION
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who can open what in the arrivals (criterion 28): a parent, also one with children registered, is told 403 on every address under `/arrivi`, with a page and with a
 * form, and nothing changes; whoever has no access goes to the access page; a teacher is told 404 for an id that does not exist or that is of another gathering.
 * One list of the addresses, tried with everyone.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ArrivalsAccessTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = CheckinTestKit(mockMvc, tokens, dsl, clock)

    /** The pages of the arrivals as a link opens them, for the registration [id]. */
    private fun pages(id: Long): List<String> = listOf("/arrivi", "/arrivi?giorno=2027-09-18", "/arrivi/$id", "/arrivi/$id?giorno=2027-09-18")

    /** The forms of the arrivals, as the pages send them. */
    private fun forms(id: Long): List<String> = listOf("/arrivi/$id/presenze", "/arrivi/$id/presenze/togli", "/arrivi/$id/manleva", "/arrivi/$id/rimetti")

    private inner class World {
        val setup = kit.start()
        val parentEmail = Addresses.unique("genitore-a")
        val parent = kit.kit.parent(parentEmail)
        val parentId = kit.kit.accountId(parentEmail)
        val child = kit.rows.register(setup.id, parentId, "Luca", "Rossi")
        val paper = kit.rows.register(setup.id, null, "Nicolò", "Ferri", enteredBy = setup.martaId)
        val withdrawn = kit.rows.register(setup.id, kit.account(), "Chiara", "Fontana", status = RegistrationStatus.withdrawn)

        /** What can be changed: if nothing is, the numbers are the same after the attempts. */
        fun state(): List<Any?> = listOf(
            kit.presenceCount(), kit.paymentCount(), kit.statusOf(child), kit.statusOf(withdrawn),
            dsl.select(REGISTRATION.PAPER_FORM_RECEIVED).from(REGISTRATION).where(REGISTRATION.ID.eq(paper)).fetchSingle(REGISTRATION.PAPER_FORM_RECEIVED),
        )
    }

    @Test
    fun `t6 c28 the rule of the teachers covers every address under arrivi, so a parent cannot reach any of them`() {
        // The one list of the addresses of the teachers has the whole branch, with the arrivals as a branch and not address by address.
        assertTrue("/arrivi/**" in TeacherPaths.patterns)
        for (path in listOf("/arrivi", "/arrivi/1", "/arrivi/1/presenze", "/arrivi/registro", "/arrivi/elenco", "/arrivi/foglio", "/arrivi/1/pagamenti/2/togli", "/arrivi/x/y/z")) {
            assertTrue(TeacherPaths.matches(path), path)
        }
    }

    @Test
    fun `t6 c28 a parent, also one with children registered, is told 403 on every address of the arrivals with a page and with a form, and nothing changes`() {
        val w = World()
        val before = w.state()

        for (path in pages(w.child)) {
            assertEquals(403, w.parent.get(path).response.status, "GET $path")
        }
        for (path in forms(w.child)) {
            val answer = mockMvc.post(path) { session = w.parent.cookie; with(csrf()); param("giorno", w.setup.today.toString()) }.andReturn().response
            assertEquals(403, answer.status, "POST $path")
            // The same as a piece of a page that htmx asks for by hand: refused, not replaced.
            val htmx = mockMvc.post(path) { session = w.parent.cookie; with(csrf()); header("HX-Request", "true"); param("giorno", w.setup.today.toString()) }.andReturn().response
            assertEquals(403, htmx.status, "POST (htmx) $path")
        }
        assertEquals(before, w.state(), "nothing was changed")
    }

    @Test
    fun `t6 c28 whoever has no access is sent to the access page, with a page and with a form`() {
        val w = World()
        val before = w.state()

        for (path in pages(w.child)) {
            val answer = mockMvc.get(path).andReturn().response
            assertEquals(302, answer.status, "GET $path")
            assertEquals("/accedi", answer.getHeader("Location"), "GET $path")
        }
        for (path in forms(w.child)) {
            // A form without access has no code against forgery either: the answer is the access page, as for any other form.
            val answer = mockMvc.post(path) { param("giorno", w.setup.today.toString()) }.andReturn().response
            assertEquals(302, answer.status, "POST $path")
            assertEquals("/accedi", answer.getHeader("Location"), "POST $path")
            val htmx = mockMvc.post(path) { header("HX-Request", "true"); param("giorno", w.setup.today.toString()) }.andReturn().response
            assertEquals("/accedi", htmx.getHeader("HX-Redirect"), "POST (htmx) $path")
        }
        assertEquals(before, w.state())
    }

    @Test
    fun `t6 c28 a teacher is told 404 for an id that does not exist or is of another gathering, and nothing changes`() {
        val w = World()
        val before = w.state()
        val missing = w.child + 100_000
        // A registration of another gathering: this one is closed and another, that starts later, is the one of the arrivals.
        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).where(GATHERING.ID.eq(w.setup.id)).execute()
        val later = kit.kit.gatherings.make(title = "La convivenza dopo", start = LocalDate.now(clock).plusDays(60), status = GatheringStatus.`open`, custodians = listOf(w.setup.martaId))
        val newer = kit.rows.register(later, kit.account(), "Elena", "Marino")
        assertEquals(200, w.setup.marta.get("/arrivi/$newer").response.status)

        for (id in listOf(missing, w.child)) {
            assertEquals(404, w.setup.marta.get("/arrivi/$id").response.status, "GET /arrivi/$id")
            for (path in forms(id)) {
                val answer = w.setup.marta.submit("/profilo", path, mapOf("giorno" to w.setup.today.toString())).response
                assertEquals(404, answer.status, "POST $path")
            }
        }
        assertEquals(before, w.state(), "nothing was changed")
    }
}
