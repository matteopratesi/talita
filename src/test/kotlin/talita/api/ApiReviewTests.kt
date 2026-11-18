package talita.api

import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.Addresses
import talita.MutableClock
import talita.TestcontainersConfiguration
import talita.account.Phone
import talita.account.TestAccounts
import talita.gathering.ArrivalUsage
import talita.gathering.DatesResult
import talita.gathering.Fees
import talita.gathering.FeesResult
import talita.gathering.GatheringSupport
import talita.gathering.Gatherings
import talita.gathering.RegistrationUsage
import talita.gathering.RoomAssignments
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.API_TOKEN
import talita.jooq.tables.references.REMEMBERED_DEVICE
import talita.jooq.tables.references.ROOM
import talita.jooq.tables.references.VENUE
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What the review of CodeRabbit asked for and was right about (TALITA-21): the checks that are made again under the lock, the names of people in the rooms, and the small rules of the APIs. Synthetic data. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ApiReviewTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val oneTimeTokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: MutableClock,
    @Autowired private val tokens: ApiTokens,
    @Autowired private val gatherings: Gatherings,
    @Autowired private val usage: RegistrationUsage,
    @Autowired private val arrivals: ArrivalUsage,
    @Autowired private val assignments: RoomAssignments,
) {

    private val kit = ApiKit(mockMvc, dsl, tokens)
    private val support = GatheringSupport(dsl, clock)

    @BeforeEach
    fun start() {
        support.reset()
    }

    @AfterEach
    fun clean() {
        support.reset()
        kit.clean()
        dsl.deleteFrom(VENUE).where(VENUE.NAME.like("T21 %")).execute()
    }

    private fun json(vararg pairs: Pair<String, Any?>): String = kit.json.writeValueAsString(linkedMapOf(*pairs))

    @Test
    fun `t21 c13 a gathering that was opened after the checks is not left without a venue or a full fee, and one that ended is not given new dates, under the lock`() {
        val today = LocalDate.now(clock)
        val open = support.make(status = GatheringStatus.`open`, start = today.plusDays(10))
        assertEquals(DatesResult.VenueNotFound, gatherings.saveDates(open, "Titolo", today.plusDays(10), today.plusDays(12), null, usage, arrivals, assignments))
        assertEquals(FeesResult.FullFeeMissing, gatherings.saveFees(open, Fees(null, null, null), usage))
        assertEquals(FeesResult.Saved, gatherings.saveFees(open, Fees(0, null, null), usage))
        val ended = support.make(status = GatheringStatus.closed, start = today.minusDays(10), end = today.minusDays(8))
        val venue = support.venueIdOf(ended)
        assertEquals(DatesResult.DatesClosed, gatherings.saveDates(ended, "Titolo", today.minusDays(10), today.plusDays(1), venue, usage, arrivals, assignments))
        assertEquals(DatesResult.Saved, gatherings.saveDates(ended, "Titolo nuovo", today.minusDays(10), today.minusDays(8), venue, usage, arrivals, assignments))
    }

    @Test
    fun `t21 c09 the API needs both dates, and a null is a 422`() {
        val t = kit.teacher()
        val today = LocalDate.now(clock)
        val id = support.make(status = GatheringStatus.closed, start = today.minusDays(10), end = today.minusDays(8))

        val answer = kit.put("/api/convivenza/$id/date", t.token, json("title" to "Titolo", "startDate" to null, "endDate" to null, "venue" to support.venueIdOf(id)))

        assertEquals(422, answer.response.status)
        assertTrue(kit.tree(answer).get("fields").has("startDate"))
    }

    @Test
    fun `t21 c13 a room is not named after a person that the application knows, by the form and by the API`() {
        val t = kit.teacher("maestro-camere", "Giulia", "Serra")
        val venue = kit.tree(kit.post("/api/strutture", t.token, json("name" to "T21 Casa camere persone", "place" to "Casa per ritiri"))).get("id").longValue()

        val answer = kit.post("/api/strutture/$venue/camere", t.token, json("name" to "Camera di Giulia Serra", "beds" to 4))

        assertEquals(422, answer.response.status)
        assertEquals("Scrivi il nome della camera nella casa, non il nome di una persona.", kit.tree(answer).get("fields").get("name").stringValue())
        assertEquals(0, dsl.fetchCount(ROOM, ROOM.VENUE_ID.eq(venue)))
        assertEquals(201, kit.post("/api/strutture/$venue/camere", t.token, json("name" to "Camera azzurra", "beds" to 4)).response.status)
    }

    @Test
    fun `t21 c09 a null choice of a form, a repeated field, a multipart body and an id of zero are errors, and a 405 keeps Allow`() {
        val t = kit.teacher()
        val venue = kit.tree(kit.post("/api/strutture", t.token, json("name" to "T21 Casa piccole regole", "place" to "Casa per ritiri"))).get("id").longValue()
        val form = kit.tree(kit.post("/api/strutture/$venue/moduli", t.token, json("name" to "Registro", "withAge" to true))).get("id").longValue()

        val nulled = kit.put("/api/strutture/$venue/moduli/$form", t.token, json("name" to "Registro", "withAge" to null, "withDays" to false, "withTeacherDocument" to false, "withTeacherTaxCode" to false))
        assertEquals(400, nulled.response.status)
        assertTrue(kit.tree(kit.get("/api/strutture/$venue", t.token)).get("forms").get(0).get("withAge").booleanValue(), "nothing was cleared")
        assertEquals(400, kit.post("/api/strutture/$venue/camere", t.token, "{\"name\":\"Camera 1\",\"beds\":\"2\",\"beds\":2}").response.status)
        assertEquals(415, kit.call(HttpMethod.POST, "/api/strutture", t.token, "--x\r\n\r\n--x--", MediaType.parseMediaType("multipart/form-data; boundary=x")).response.status)
        assertEquals(404, kit.delete("/api/strutture/$venue/camere/0", t.token).response.status)
        assertEquals(404, kit.delete("/api/strutture/$venue/moduli/0", t.token).response.status)
        val wrong = kit.delete("/api/strutture", t.token)
        assertEquals(405, wrong.response.status)
        assertTrue(wrong.response.getHeader("Allow")!!.contains("GET"), "Allow: ${wrong.response.getHeader("Allow")}")
    }

    @Test
    fun `t21 c10 Retry-After is a whole number of seconds that is not less than the wait`() {
        val t = kit.teacher()
        repeat(ApiRateLimit.LIMIT) { kit.get("/api/convivenza", t.token) }

        val over = kit.get("/api/convivenza", t.token)

        val seconds = over.response.getHeader("Retry-After")!!.toLong()
        assertTrue(seconds in 1..60)
    }

    @Test
    fun `t21 c03 a card without a token that arrives when there is one does not replace it`() {
        val email = Addresses.unique("maestro-scheda-vecchia")
        val id = TestAccounts.makeTeacher(dsl, email)
        val phone = Phone(mockMvc, oneTimeTokens)
        phone.enter(email)
        val first = checkNotNull(tokens.create(id)).value

        val answer = phone.submit("/profilo", "/profilo/token", mapOf("azione" to "crea")) { header("HX-Request", "true") }

        assertEquals(200, answer.response.status)
        assertTrue(answer.response.contentAsString.contains("C’è già un token."))
        assertNotNull(tokens.authenticate(first), "the token that was there still works")
        assertEquals(1, dsl.fetchCount(API_TOKEN, API_TOKEN.ACCOUNT_ID.eq(id)))
        dsl.execute("delete from teacher where account_id = ?", id)
        dsl.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.eq(id)).execute()
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.eq(id)).execute()
    }

    @Test
    fun `t21 c07 a form-encoded body on the APIs is a 415 before the token and before it is read, whatever its size`() {
        val big = "a=" + "x".repeat(200_000)

        for (method in listOf(HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.POST)) {
            val answer = kit.call(method, "/api/strutture/1", null, big, MediaType.APPLICATION_FORM_URLENCODED)
            assertEquals(415, answer.response.status, "$method")
            assertTrue(answer.response.contentType!!.startsWith("application/json"))
        }
        val t = kit.teacher()
        assertEquals(415, kit.call(HttpMethod.PUT, "/api/strutture/1", t.token, "a=b", MediaType.APPLICATION_FORM_URLENCODED).response.status)
    }

    @Test
    fun `t21 c03 two cards that ask for a first token together make one, and the second finds it`() {
        val email = Addresses.unique("maestro-due-schede")
        val id = TestAccounts.makeTeacher(dsl, email)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val gate = java.util.concurrent.CountDownLatch(1)

        val results = (1..2).map { pool.submit<ApiTokens.Created> { gate.await(); tokens.createUnlessThere(id) } }
        gate.countDown()
        val outcomes = results.map { it.get() }
        pool.shutdown()

        assertEquals(1, outcomes.count { it is ApiTokens.Created.Made })
        assertEquals(1, outcomes.count { it == ApiTokens.Created.Exists })
        assertEquals(1, dsl.fetchCount(API_TOKEN, API_TOKEN.ACCOUNT_ID.eq(id)))
        // An expired token is replaced by a card that showed none; one that works is replaced only by the dialog (`create`).
        dsl.update(API_TOKEN).set(API_TOKEN.CREATED_ON, LocalDate.now(clock).minusDays(40)).where(API_TOKEN.ACCOUNT_ID.eq(id)).execute()
        assertTrue(tokens.createUnlessThere(id) is ApiTokens.Created.Made)
        assertTrue(tokens.createUnlessThere(id) == ApiTokens.Created.Exists)
        assertNotNull(tokens.create(id))
        dsl.execute("delete from teacher where account_id = ?", id)
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.eq(id)).execute()
    }
}
