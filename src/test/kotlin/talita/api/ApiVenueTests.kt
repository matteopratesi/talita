package talita.api

import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.Phone
import talita.account.TestAccounts
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.REMEMBERED_DEVICE
import talita.jooq.tables.references.ROOM
import talita.jooq.tables.references.VENUE
import talita.jooq.tables.references.VENUE_FORM
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The venues, the rooms and the forms of the venues through the APIs (TALITA-21, part 2, criteria 13, 14 and 22-24). **The table of the criterion 13**: the same values sent to the form of the app and to the API have
 * the same outcome (saved or not), the same sentences for each field and the same rows in the database. The people of the examples are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ApiVenueTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val oneTimeTokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val tokens: ApiTokens,
) {

    private val kit = ApiKit(mockMvc, dsl, tokens)
    private val made = mutableListOf<String>()
    private val venuesBefore = mutableSetOf<Long>()

    @AfterEach
    fun clean() {
        val ids = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.`in`(made)).fetch(ACCOUNT.ID).map { it!! }
        dsl.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.`in`(ids)).execute()
        dsl.execute("delete from teacher where account_id = any(?)", ids.toTypedArray())
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.`in`(ids)).execute()
        made.clear()
        kit.clean()
        // The venues of the tests (the ones that were not there when a test began) go with their rooms and forms.
        dsl.deleteFrom(VENUE).where(VENUE.NAME.like("T21 %")).execute()
    }

    /** A teacher at a browser (for the form) with a token (for the API), the two of the same account. */
    private class Both(val phone: Phone, val teacher: ApiTeacher)

    private fun both(): Both {
        val teacher = kit.teacher("maestro-struttura")
        val phone = Phone(mockMvc, oneTimeTokens)
        phone.enter(teacher.email)
        return Both(phone, teacher)
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun venueId(name: String): Long = dsl.select(VENUE.ID).from(VENUE).where(VENUE.NAME.eq(name)).fetchSingle(VENUE.ID)!!

    private fun venueRows(): List<List<Any?>> = dsl.select(VENUE.NAME, VENUE.PLACE).from(VENUE).where(VENUE.NAME.like("T21 %").or(VENUE.NAME.like("%@%")).or(VENUE.NAME.like("%Verdi%"))).orderBy(VENUE.ID).fetch().map { it.intoList() }

    private fun json(vararg pairs: Pair<String, Any?>): String = kit.json.writeValueAsString(linkedMapOf(*pairs))

    /** The outcome of an attempt, as the criterion 13 compares it. */
    private data class Outcome(val saved: Boolean, val sentences: Set<String>, val rows: List<List<Any?>>)

    // ---- Criterion 13: the same values, the same outcome --------------------------------------------------------------------------------

    private fun venueCase(b: Both, name: String, place: String, setup: () -> Unit = {}): Pair<Outcome, Outcome> {
        // The form.
        setup()
        val form = b.phone.submit("/strutture/nuova", "/strutture/nuova", mapOf("name" to name, "place" to place))
        val html = form.response.contentAsString
        val formRows = venueRows()
        dsl.deleteFrom(VENUE).where(VENUE.NAME.like("T21 %").and(VENUE.NAME.ne("T21 Casa già c’è"))).execute()
        // The API, from the same state.
        val api = kit.post("/api/strutture", b.teacher.token, json("name" to name, "place" to place))
        val apiRows = venueRows()
        val sentences = if (api.response.status == 422) kit.tree(api).get("fields").propertyNames().map { kit.tree(api).get("fields").get(it).stringValue() }.toSet() else emptySet()
        dsl.deleteFrom(VENUE).where(VENUE.NAME.like("T21 %").and(VENUE.NAME.ne("T21 Casa già c’è"))).execute()
        val formSaved = form.response.status == 303
        val apiSaved = api.response.status == 201
        return Outcome(formSaved, if (formSaved) emptySet() else sentences.filter { html.contains(escape(it)) }.toSet(), formRows) to Outcome(apiSaved, sentences, apiRows)
    }

    @Test
    fun `t21 c13 a venue written through the form and through the API has the same outcome, the same sentences and the same rows`() {
        val b = both()
        TestAccounts.makeTeacher(dsl, Addresses.unique("maestro-verdi").also { made += it }, "Paolo", "Verdi")
        val long = "T21 " + "A".repeat(80)
        val cases = listOf(
            "T21 Casa del bosco" to "Casa per ritiri sul lago, Località Esempio",
            "T21 Casa del bosco" to "Scrivimi a nome@example.org",
            "T21 Casa del bosco" to "Telefono 333 1234567",
            "T21 Casa del bosco" to "Conto IT60 X054 2811 1010 0000 0123 456",
            "T21 Paolo Verdi" to "Casa per ritiri sul lago, Località Esempio",
            long to "Casa per ritiri",
            "" to "Casa per ritiri",
            "T21 Casa del bosco" to "",
            "  T21 Casa del bosco  " to "  Casa per ritiri  ",
        )

        for ((name, place) in cases) {
            val (form, api) = venueCase(b, name, place)
            assertEquals(form.saved, api.saved, "saved: $name / $place")
            assertEquals(form.rows, api.rows, "the same rows: $name / $place")
            // Every sentence that the API gives for a field is on the page of the form, written by the same words.
            assertEquals(api.sentences, form.sentences, "the same sentences: $name / $place")
        }
    }

    @Test
    fun `t21 c13 a name that another venue has is refused by both, whatever the capitals and the spaces`() {
        val b = both()
        kit.post("/api/strutture", b.teacher.token, json("name" to "T21 Casa già c’è", "place" to "Casa per ritiri")).also { assertEquals(201, it.response.status) }

        val (form, api) = venueCase(b, "  t21 CASA già c’è ", "Altro posto")

        assertFalse(form.saved)
        assertFalse(api.saved)
        assertEquals(setOf("C’è già una struttura con questo nome."), api.sentences)
        assertEquals(api.sentences, form.sentences)
    }

    @Test
    fun `t21 c13 a room is written the same way by the form and by the API, with the words on the sex, the beds from 1 to 50 and the name that is taken`() {
        val b = both()
        val venue = kit.post("/api/strutture", b.teacher.token, json("name" to "T21 Casa per le camere", "place" to "Casa per ritiri")).let { kit.tree(it).get("id").longValue() }
        kit.post("/api/strutture/$venue/camere", b.teacher.token, json("name" to "Camera 1", "beds" to 4)).also { assertEquals(201, it.response.status) }
        val cases = listOf("Camera 2" to 4, "Camera dei maschi" to 4, "Camera 3" to 0, "Camera 3" to 51, "Camera 3" to 50, "camera 1" to 3, "" to 3, "A".repeat(60) to 3)

        for ((name, beds) in cases) {
            val before = dsl.select(ROOM.ID).from(ROOM).where(ROOM.VENUE_ID.eq(venue)).fetch(ROOM.ID).map { it!! }.toSet()
            val form = b.phone.submit("/strutture/$venue", "/strutture/$venue/camere/nuova", mapOf("name" to name, "beds" to beds.toString()))
            val formRows = dsl.select(ROOM.NAME, ROOM.BEDS).from(ROOM).where(ROOM.VENUE_ID.eq(venue)).and(ROOM.ID.notIn(before)).fetch().map { it.intoList() }
            dsl.deleteFrom(ROOM).where(ROOM.VENUE_ID.eq(venue)).and(ROOM.ID.notIn(before)).execute()
            val api = kit.post("/api/strutture/$venue/camere", b.teacher.token, json("name" to name, "beds" to beds))
            val apiRows = dsl.select(ROOM.NAME, ROOM.BEDS).from(ROOM).where(ROOM.VENUE_ID.eq(venue)).and(ROOM.ID.notIn(before)).fetch().map { it.intoList() }
            dsl.deleteFrom(ROOM).where(ROOM.VENUE_ID.eq(venue)).and(ROOM.ID.notIn(before)).execute()

            assertEquals(form.response.status == 303, api.response.status == 201, "saved: $name / $beds")
            assertEquals(formRows, apiRows, "rows: $name / $beds")
            if (api.response.status == 422) {
                val fields = kit.tree(api).get("fields")
                val html = form.response.contentAsString
                for (field in fields.propertyNames()) assertTrue(html.contains(escape(fields.get(field).stringValue())), "$name / $beds: $field says ${fields.get(field).stringValue()}")
            }
        }
    }

    @Test
    fun `t21 c13 a form of a venue is written the same way by the form and by the API, with the name of a person refused`() {
        val b = both()
        TestAccounts.makeTeacher(dsl, Addresses.unique("maestro-serra").also { made += it }, "Giulia", "Serra")
        val venue = kit.post("/api/strutture", b.teacher.token, json("name" to "T21 Casa per i moduli", "place" to "Casa per ritiri")).let { kit.tree(it).get("id").longValue() }
        kit.post("/api/strutture/$venue/moduli", b.teacher.token, json("name" to "Registro tassa")).also { assertEquals(201, it.response.status) }

        for (name in listOf("Registro presenti", "Giulia Serra", "registro TASSA", "", "Scrivimi a nome@example.org")) {
            val before = dsl.select(VENUE_FORM.ID).from(VENUE_FORM).where(VENUE_FORM.VENUE_ID.eq(venue)).fetch(VENUE_FORM.ID).map { it!! }.toSet()
            val form = b.phone.submit("/strutture/$venue", "/strutture/$venue/moduli/nuovo", mapOf("name" to name, "withAge" to "on"))
            val formRows = dsl.select(VENUE_FORM.NAME, VENUE_FORM.WITH_AGE, VENUE_FORM.WITH_DAYS).from(VENUE_FORM).where(VENUE_FORM.VENUE_ID.eq(venue)).and(VENUE_FORM.ID.notIn(before)).fetch().map { it.intoList() }
            dsl.deleteFrom(VENUE_FORM).where(VENUE_FORM.VENUE_ID.eq(venue)).and(VENUE_FORM.ID.notIn(before)).execute()
            val api = kit.post("/api/strutture/$venue/moduli", b.teacher.token, json("name" to name, "withAge" to true))
            val apiRows = dsl.select(VENUE_FORM.NAME, VENUE_FORM.WITH_AGE, VENUE_FORM.WITH_DAYS).from(VENUE_FORM).where(VENUE_FORM.VENUE_ID.eq(venue)).and(VENUE_FORM.ID.notIn(before)).fetch().map { it.intoList() }
            dsl.deleteFrom(VENUE_FORM).where(VENUE_FORM.VENUE_ID.eq(venue)).and(VENUE_FORM.ID.notIn(before)).execute()

            assertEquals(form.response.status == 303, api.response.status == 201, "saved: $name")
            assertEquals(formRows, apiRows, "rows: $name")
            if (api.response.status == 422) assertTrue(form.response.contentAsString.contains(escape(kit.tree(api).get("fields").get("name").stringValue())), name)
        }
    }

    // ---- Criteria 22 and 23: the venues ------------------------------------------------------------------------------------------------

    @Test
    fun `t21 c22 a venue has its rooms and its forms in the detail, and the list has the counts`() {
        val t = kit.teacher()
        val venue = kit.tree(kit.post("/api/strutture", t.token, json("name" to "T21 Casa del detail", "place" to "Casa per ritiri"))).get("id").longValue()
        kit.post("/api/strutture/$venue/camere", t.token, json("name" to "Camera 1", "beds" to 4))
        kit.post("/api/strutture/$venue/camere", t.token, json("name" to "Camera 2", "beds" to 6))
        kit.post("/api/strutture/$venue/moduli", t.token, json("name" to "Registro tassa", "withAge" to true, "withDays" to true))

        val detail = kit.tree(kit.get("/api/strutture/$venue", t.token))

        assertEquals(listOf("id", "name", "place", "gatherings", "rooms", "forms", "notice"), detail.propertyNames().toList())
        assertEquals(listOf("id", "name", "beds"), detail.get("rooms").get(0).propertyNames().toList())
        assertEquals(listOf("id", "name", "withAge", "withDays", "withTeacherDocument", "withTeacherTaxCode"), detail.get("forms").get(0).propertyNames().toList())
        assertEquals(listOf(true, true, false, false), listOf("withAge", "withDays", "withTeacherDocument", "withTeacherTaxCode").map { detail.get("forms").get(0).get(it).booleanValue() })
        val item = kit.tree(kit.get("/api/strutture", t.token)).first { it.get("id").longValue() == venue }
        assertEquals(2, item.get("rooms").intValue())
        assertEquals(10, item.get("beds").intValue())
        assertEquals(1, item.get("forms").intValue())
        assertEquals(404, kit.get("/api/strutture/999999999", t.token).response.status)
    }

    @Test
    fun `t21 c23 a venue is made, changed with PUT writing every field, and taken away only if no gathering uses it, with its rooms and forms`() {
        val t = kit.teacher()
        val created = kit.post("/api/strutture", t.token, json("name" to "T21 Casa nuova", "place" to "Casa per ritiri"))
        assertEquals(201, created.response.status)
        val id = kit.tree(created).get("id").longValue()

        // A PUT writes every field: one that is missing is a 400 that names it; a field that does not exist is a 400 that names it; nothing is saved.
        val missing = kit.put("/api/strutture/$id", t.token, json("name" to "T21 Casa cambiata"))
        assertEquals(400, missing.response.status)
        assertTrue(kit.error(missing).contains("place"))
        val unknown = kit.put("/api/strutture/$id", t.token, json("name" to "T21 Casa cambiata", "place" to "Altro", "colore" to "blu"))
        assertEquals(400, unknown.response.status)
        assertTrue(kit.error(unknown).contains("colore"))
        assertEquals(400, kit.put("/api/strutture/$id", t.token, "[]").response.status)
        assertEquals(400, kit.put("/api/strutture/$id", t.token, "{non json").response.status)
        assertEquals(400, kit.put("/api/strutture/$id", t.token, json("name" to 5, "place" to "Altro")).response.status)
        assertEquals("T21 Casa nuova", dsl.select(VENUE.NAME).from(VENUE).where(VENUE.ID.eq(id)).fetchSingle(VENUE.NAME))
        assertEquals(415, kit.call(HttpMethod.PUT, "/api/strutture/$id", t.token, "name=x", org.springframework.http.MediaType.TEXT_PLAIN).response.status)

        val changed = kit.put("/api/strutture/$id", t.token, json("name" to "T21 Casa cambiata", "place" to "Altro posto"))
        assertEquals(200, changed.response.status)
        assertEquals("T21 Casa cambiata", kit.tree(changed).get("name").stringValue())
        assertNotNull(kit.tree(changed).get("notice").takeIf { it.isNull }, "no gathering uses it: no notice")
        assertEquals(404, kit.put("/api/strutture/999999999", t.token, json("name" to "x", "place" to "y")).response.status)

        // Taken away with its rooms and forms.
        assertEquals(201, kit.post("/api/strutture/$id/camere", t.token, json("name" to "Camera 1", "beds" to 4)).response.status)
        assertEquals(201, kit.post("/api/strutture/$id/moduli", t.token, json("name" to "Registro")).response.status)
        assertEquals(1, dsl.fetchCount(ROOM, ROOM.VENUE_ID.eq(id)))
        assertEquals(1, dsl.fetchCount(VENUE_FORM, VENUE_FORM.VENUE_ID.eq(id)))
        val removed = kit.delete("/api/strutture/$id", t.token)
        assertEquals(200, removed.response.status)
        assertEquals("Struttura tolta.", kit.tree(removed).get("message").stringValue())
        assertEquals(0, dsl.fetchCount(ROOM, ROOM.VENUE_ID.eq(id)))
        assertEquals(0, dsl.fetchCount(VENUE_FORM, VENUE_FORM.VENUE_ID.eq(id)))
        assertEquals(404, kit.delete("/api/strutture/$id", t.token).response.status)
    }

    // ---- Criterion 24: rooms and forms -------------------------------------------------------------------------------------------------

    @Test
    fun `t21 c24 a room is changed and taken away, one in another venue is a 404, a repeated POST is a 422 and no double, and a room taken away already is a 200`() {
        val t = kit.teacher()
        val one = kit.tree(kit.post("/api/strutture", t.token, json("name" to "T21 Prima casa", "place" to "Casa per ritiri"))).get("id").longValue()
        val two = kit.tree(kit.post("/api/strutture", t.token, json("name" to "T21 Seconda casa", "place" to "Casa per ritiri"))).get("id").longValue()
        val room = kit.tree(kit.post("/api/strutture/$one/camere", t.token, json("name" to "Camera 1", "beds" to 4))).get("id").longValue()
        assertEquals(422, kit.post("/api/strutture/$one/camere", t.token, json("name" to "Camera 1", "beds" to 4)).response.status)
        assertEquals(1, dsl.fetchCount(ROOM, ROOM.VENUE_ID.eq(one)), "a POST that is repeated does not make a double")

        // A room of another venue is a 404, for PUT and DELETE, and nothing changes.
        assertEquals(404, kit.put("/api/strutture/$two/camere/$room", t.token, json("name" to "Camera 9", "beds" to 4)).response.status)
        assertEquals(404, kit.delete("/api/strutture/$two/camere/$room", t.token).response.status)
        val changed = kit.put("/api/strutture/$one/camere/$room", t.token, json("name" to "Camera 1 bis", "beds" to 6))
        assertEquals(200, changed.response.status)
        assertEquals(6, kit.tree(changed).get("beds").intValue())
        assertEquals(400, kit.put("/api/strutture/$one/camere/$room", t.token, json("name" to "Camera 1 bis")).response.status)
        // The beds are a whole number, not a text.
        assertEquals(400, kit.post("/api/strutture/$one/camere", t.token, json("name" to "Camera 2", "beds" to "4")).response.status)

        val removed = kit.delete("/api/strutture/$one/camere/$room", t.token)
        assertEquals(200, removed.response.status)
        assertEquals("Camera tolta.", kit.tree(removed).get("message").stringValue())
        val again = kit.delete("/api/strutture/$one/camere/$room", t.token)
        assertEquals(200, again.response.status)
        assertEquals("La camera era già stata tolta.", kit.tree(again).get("message").stringValue())
        assertEquals(404, kit.delete("/api/strutture/$one/camere/999999999", t.token).response.status)
    }

    @Test
    fun `t21 c24 a form is written with the choices that are not written false in a POST, and a PUT writes all four`() {
        val t = kit.teacher()
        val venue = kit.tree(kit.post("/api/strutture", t.token, json("name" to "T21 Casa moduli", "place" to "Casa per ritiri"))).get("id").longValue()

        val made = kit.post("/api/strutture/$venue/moduli", t.token, json("name" to "Registro tassa", "withDays" to true))
        assertEquals(201, made.response.status)
        val id = kit.tree(made).get("id").longValue()
        assertEquals(listOf(false, true, false, false), listOf("withAge", "withDays", "withTeacherDocument", "withTeacherTaxCode").map { kit.tree(made).get(it).booleanValue() })
        assertEquals(400, kit.put("/api/strutture/$venue/moduli/$id", t.token, json("name" to "Registro tassa", "withDays" to false)).response.status)
        val all = kit.put("/api/strutture/$venue/moduli/$id", t.token, json("name" to "Registro tassa", "withAge" to true, "withDays" to false, "withTeacherDocument" to true, "withTeacherTaxCode" to false))
        assertEquals(200, all.response.status)
        assertEquals(listOf(true, false, true, false), listOf("withAge", "withDays", "withTeacherDocument", "withTeacherTaxCode").map { kit.tree(all).get(it).booleanValue() })
        assertEquals(404, kit.put("/api/strutture/999999999/moduli/$id", t.token, json("name" to "x", "withAge" to true, "withDays" to true, "withTeacherDocument" to true, "withTeacherTaxCode" to true)).response.status)
        assertEquals("Modulo tolto.", kit.tree(kit.delete("/api/strutture/$venue/moduli/$id", t.token)).get("message").stringValue())
        assertEquals("Il modulo era già stato tolto.", kit.tree(kit.delete("/api/strutture/$venue/moduli/$id", t.token)).get("message").stringValue())
    }

    @Test
    fun `t21 c09 a body that is not JSON, one that is too large and one that is not an object are errors that change nothing`() {
        val t = kit.teacher()

        assertEquals(413, kit.post("/api/strutture", t.token, json("name" to "T21 " + "x".repeat(70_000), "place" to "Casa")).response.status)
        assertEquals(400, kit.post("/api/strutture", t.token, "").response.status)
        assertEquals(400, kit.post("/api/strutture", t.token, "\"testo\"").response.status)
        assertEquals(400, kit.post("/api/strutture", t.token, "{\"name\": null, \"name\": null").response.status)
        assertEquals(415, kit.call(HttpMethod.POST, "/api/strutture", t.token, "{}", org.springframework.http.MediaType.APPLICATION_XML).response.status)
        assertEquals(0, dsl.fetchCount(VENUE, VENUE.NAME.like("T21 %")))
    }
}
