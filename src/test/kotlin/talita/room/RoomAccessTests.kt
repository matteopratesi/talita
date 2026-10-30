package talita.room

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
import talita.account.MockBrowser
import talita.account.TeacherPaths
import talita.checkin.words
import talita.gathering.body
import talita.jooq.enums.RegistrationStatus
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who can open what in the rooms (criteria 17, 18): a parent, also one whose child is in a room, is told 403 on every address of the rooms with a page and with a form, and nothing
 * changes; whoever has no access goes to the access page; a teacher is told 404 for a room, a registration or a teacher that does not exist or is of another gathering, and nothing
 * changes. The parent sees no room, neither of their children nor of anyone else's. One list of the addresses, tried with everyone. Names are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RoomAccessTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = RoomTestKit(mockMvc, tokens, dsl, clock)

    private inner class World {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        val two = kit.room(setup.id, "Stanza azzurra", 2)
        val emailA = Addresses.unique("genitore-a")
        val parentA: MockBrowser = kit.checkin.kit.parent(emailA)
        val accountA = kit.checkin.kit.accountId(emailA)
        val emailB = Addresses.unique("genitore-b")
        val parentB: MockBrowser = kit.checkin.kit.parent(emailB)
        val accountB = kit.checkin.kit.accountId(emailB)
        val luca = kit.rows.register(setup.id, accountA, "Luca", "Rossi")
        val anna = kit.rows.register(setup.id, accountA, "Anna", "Rossi")
        val sara = kit.rows.register(setup.id, accountB, "Sara", "Neri", status = RegistrationStatus.maybe)
        val base = "/convivenza/${setup.id}/camere"

        init {
            kit.put(luca, setup.id, one)
            kit.put(sara, setup.id, two)
            kit.putTeacher(setup.id, setup.paoloId, one)
        }

        /** The pages of the rooms as a link opens them. */
        fun pages(): List<String> = listOf(base, "$base/stampa", "$base/nuova", "$base/$one/modifica", "$base/$one/togli", "$base/scegli", "$base/sposta", "$base/maestri", "$base/copia")

        /** The forms of the rooms, as the pages send them, with the fields that make a good request. */
        fun forms(): List<Pair<String, Map<String, String>>> = listOf(
            "$base/scegli" to mapOf("persona" to "b$anna", "camera" to two.toString()),
            "$base/sposta" to mapOf("persona" to "b$luca", "azione" to "togli"),
            "$base/maestri" to mapOf("camera" to two.toString(), "persona" to "m${setup.giuliaId}"),
            "$base/nuova" to mapOf("name" to "Camera 9", "beds" to "3"),
            "$base/$one/modifica" to mapOf("name" to "Camera 1 bis", "beds" to "3"),
            "$base/$one/togli" to mapOf("camera" to one.toString()),
            "$base/copia" to emptyMap(),
        )
    }

    @Test
    fun `t11 c17 the rule of the teachers covers every address of the rooms, so a parent cannot reach any of them`() {
        assertTrue("/convivenza/**" in TeacherPaths.patterns)
        for (path in listOf("/convivenza/1/camere", "/convivenza/1/camere/stampa", "/convivenza/1/camere/nuova", "/convivenza/1/camere/2/modifica", "/convivenza/1/camere/2/togli", "/convivenza/1/camere/copia", "/convivenza/1/camere/scegli")) {
            assertTrue(TeacherPaths.matches(path), path)
        }
    }

    @Test
    fun `t11 c17 a parent, also A whose child is in a room, is told 403 on every address of the rooms with a page and with a form, and nothing changes`() {
        val w = World()
        val state = kit.state()

        for (parent in listOf(w.parentA, w.parentB)) {
            for (path in w.pages()) assertEquals(403, parent.get(path).response.status, "GET $path")
            for ((path, fields) in w.forms()) {
                val answer = mockMvc.post(path) { session = parent.cookie; with(csrf()); fields.forEach { (name, value) -> param(name, value) } }.andReturn().response
                assertEquals(403, answer.status, "POST $path")
                // The same as a piece of a page that htmx asks for by hand: refused, not replaced.
                val htmx = mockMvc.post(path) { session = parent.cookie; with(csrf()); header("HX-Request", "true"); fields.forEach { (name, value) -> param(name, value) } }.andReturn().response
                assertEquals(403, htmx.status, "POST (htmx) $path")
            }
        }
        assertEquals(state, kit.state(), "nothing was changed")
    }

    @Test
    fun `t11 c17 whoever has no access goes to the access page on every address of the rooms, with a page and with a form, and nothing changes`() {
        val w = World()
        val state = kit.state()

        for (path in w.pages()) {
            val answer = mockMvc.get(path).andReturn().response
            assertEquals(302, answer.status, "GET $path")
            assertEquals("/accedi", answer.getHeader("Location"), "GET $path")
        }
        for ((path, fields) in w.forms()) {
            val answer = mockMvc.post(path) { fields.forEach { (name, value) -> param(name, value) } }.andReturn().response
            assertEquals(302, answer.status, "POST $path")
            assertEquals("/accedi", answer.getHeader("Location"), "POST $path")
            val htmx = mockMvc.post(path) { header("HX-Request", "true"); fields.forEach { (name, value) -> param(name, value) } }.andReturn().response
            assertEquals("/accedi", htmx.getHeader("HX-Redirect"), "POST (htmx) $path")
        }
        assertEquals(state, kit.state())
    }

    @Test
    fun `t11 c17 a teacher is told 404 for a room, a registration or a teacher that does not exist or is of another gathering, and nothing changes`() {
        val w = World()
        val other = kit.otherGathering()
        val elsewhere = kit.room(other, "Camera 1", 4)
        val stranger = kit.rows.register(other, kit.checkin.account(), "Elena", "Marino")
        val missing = w.one + 100_000
        val state = kit.state()
        val marta = w.setup.marta

        // A room that is not there, or of another gathering: the pages and the forms of that room are not found.
        for (room in listOf(missing, elsewhere)) {
            assertEquals(404, marta.get("${w.base}/$room/modifica").response.status, "GET modifica $room")
            assertEquals(404, marta.get("${w.base}/$room/togli").response.status, "GET togli $room")
            assertEquals(404, marta.submit("/profilo", "${w.base}/$room/modifica", mapOf("name" to "Camera 7", "beds" to "2")).response.status, "POST modifica $room")
            assertEquals(404, marta.submit("/profilo", "${w.base}/$room/togli", mapOf("camera" to room.toString())).response.status, "POST togli $room")
            assertEquals(404, marta.submit("/profilo", "${w.base}/maestri", mapOf("camera" to room.toString(), "persona" to "m${w.setup.giuliaId}")).response.status, "POST maestri $room")
            assertEquals(404, marta.submit("/profilo", "${w.base}/scegli", mapOf("persona" to "b${w.anna}", "camera" to room.toString())).response.status, "POST scegli $room")
            assertEquals(404, marta.submit("/profilo", "${w.base}/sposta", mapOf("persona" to "b${w.luca}", "azione" to "sposta", "camera" to room.toString())).response.status, "POST sposta $room")
        }
        // A registration that is not there, or of another gathering, and a person that is not a teacher.
        for (person in listOf("b${w.anna + 100_000}", "b$stranger", "m${w.accountA}", "m${w.setup.giuliaId + 100_000}", "x12", "b", "")) {
            assertEquals(404, marta.submit("/profilo", "${w.base}/scegli", mapOf("persona" to person, "camera" to w.two.toString())).response.status, "POST scegli $person")
            assertEquals(404, marta.submit("/profilo", "${w.base}/sposta", mapOf("persona" to person, "azione" to "togli")).response.status, "POST sposta togli $person")
        }
        assertEquals(404, marta.submit("/profilo", "${w.base}/maestri", mapOf("camera" to w.two.toString(), "persona" to "m${w.accountA}")).response.status, "a parent is no teacher")
        assertEquals(404, marta.submit("/profilo", "${w.base}/maestri", mapOf("camera" to w.two.toString(), "persona" to "b${w.anna}")).response.status, "a child is no teacher")
        // A gathering that is not there: nothing of it is found.
        val none = "/convivenza/${w.setup.id + 100_000}/camere"
        for (path in listOf(none, "$none/stampa", "$none/nuova", "$none/${w.one}/modifica", "$none/${w.one}/togli")) assertEquals(404, marta.get(path).response.status, "GET $path")
        for (path in listOf("$none/scegli", "$none/sposta", "$none/maestri", "$none/nuova", "$none/${w.one}/modifica", "$none/${w.one}/togli", "$none/copia")) {
            assertEquals(404, marta.submit("/profilo", path, mapOf("persona" to "b${w.anna}", "camera" to w.one.toString(), "name" to "x", "beds" to "1")).response.status, "POST $path")
        }
        assertEquals(state, kit.state(), "nothing was changed")
    }

    @Test
    fun `t11 c17 a teacher can open every address of the rooms`() {
        val w = World()
        for (path in listOf(w.base, "${w.base}/stampa", "${w.base}/nuova", "${w.base}/${w.one}/modifica", "${w.base}/${w.one}/togli")) assertEquals(200, w.setup.marta.get(path).response.status, "GET $path")
        // Paolo Verdi is a teacher too (and a custodian): the rule is for the teachers.
        assertEquals(200, w.setup.paolo.get(w.base).response.status)
    }

    @Test
    fun `t11 c18 the pages of a parent do not say the room of a child, nor of anyone else`() {
        val w = World()
        // A's child Luca is in Camera 1, and B's child Sara in Stanza azzurra. Whoever looks says nothing of the rooms.
        for ((parent, name) in listOf(w.parentA to "A", w.parentB to "B")) {
            val pages = listOf("/", "/iscrizioni", "/profilo", "/iscrizioni/${w.luca}/modifica", "/iscrizioni/${w.luca}/segnalazioni", "/iscrizioni/${w.sara}/modifica", "/iscrizioni/${w.sara}/segnalazioni")
            var seen = 0
            for (path in pages) {
                val answer = parent.get(path)
                if (answer.response.status != 200) continue
                seen += 1
                val text = words(answer.body())
                assertFalse(text.contains("camera", ignoreCase = true), "the page $path of the parent $name says «camera»")
                assertFalse(text.contains("Stanza azzurra") || text.contains("dorme"), "$path of $name")
            }
            assertTrue(seen >= 3, "the pages of the parent $name are there: $seen")
        }
        // The registration of B's child is not found for A, as it was before the rooms.
        assertEquals(404, w.parentA.get("/iscrizioni/${w.sara}/modifica").response.status)
    }
}
