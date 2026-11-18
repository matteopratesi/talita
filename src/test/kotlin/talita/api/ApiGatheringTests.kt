package talita.api

import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.Phone
import talita.account.TestAccounts
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.REMEMBERED_DEVICE
import talita.jooq.tables.references.VENUE
import talita.registration.Notes
import talita.retention.RetentionTestKit
import talita.room.RoomTestKit
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gathering through the APIs (TALITA-21, part 3, criteria 13, 15-21 and 25). **The table of the criterion 13**: the same values sent to the form of the app and to the API have the same outcome, the same
 * sentences for each field and the same row in the database. And the criterion 25: on a database full of synthetic data of every kind, no route says a name, an address, a phone, a note or an amount. Names, dates
 * and addresses are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ApiGatheringTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val oneTimeTokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val tokens: ApiTokens,
    @Autowired @Qualifier("requestMappingHandlerMapping") private val mapping: RequestMappingHandlerMapping,
) {

    private val kit = ApiKit(mockMvc, dsl, tokens)
    private val gatherings = GatheringSupport(dsl, clock)
    private val made = mutableListOf<String>()

    @BeforeEach
    fun start() {
        gatherings.reset()
    }

    @AfterEach
    fun clean() {
        gatherings.reset()
        val ids = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.`in`(made)).fetch(ACCOUNT.ID).map { it!! }
        dsl.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.`in`(ids)).execute()
        dsl.execute("delete from teacher where account_id = any(?)", ids.toTypedArray())
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.`in`(ids)).execute()
        made.clear()
        kit.clean()
    }

    private fun today() = LocalDate.now(clock)

    private fun json(vararg pairs: Pair<String, Any?>): String = kit.json.writeValueAsString(linkedMapOf(*pairs))

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private class Both(val phone: Phone, val teacher: ApiTeacher)

    /** A teacher at a browser (for the form) with a token (for the API): the same account. [last] is the surname, so that a text with the name of the teacher can be tried. */
    private fun both(first: String = "Elisa", last: String = "Conti"): Both {
        val teacher = kit.teacher("maestro-convivenza", first, last)
        val phone = Phone(mockMvc, oneTimeTokens)
        phone.enter(teacher.email)
        return Both(phone, teacher)
    }

    /** What the table of the criterion 13 compares: the row of the gathering, the way the database holds it. */
    private fun state(id: Long): List<Any?> =
        dsl.select(GATHERING.TITLE, GATHERING.START_DATE, GATHERING.END_DATE, VENUE.PLACE, GATHERING.FULL_FEE_CENTS, GATHERING.DAILY_FEE_CENTS, GATHERING.LUNCH_ONLY_FEE_CENTS, GATHERING.INFO_TEXT, GATHERING.WHAT_TO_BRING, GATHERING.STATUS)
            .from(GATHERING).leftJoin(VENUE).on(VENUE.ID.eq(GATHERING.VENUE_ID)).where(GATHERING.ID.eq(id)).fetchSingle().intoList()

    private fun fieldSentences(api: MvcResult): Map<String, String> {
        if (api.response.status != 422) return emptyMap()
        val fields = kit.tree(api).get("fields")
        return fields.propertyNames().associateWith { fields.get(it).stringValue() }
    }

    /**
     * One row of the table: the gathering is made twice with [setup] (the database is cleared between the two attempts), once for the form and once for the API, and the two outcomes are compared: the same kind of
     * answer (saved or not), the same sentences (each one that the API gives for a field is in the page of the form) and the same row in the database afterwards.
     */
    private fun row(what: String, setup: () -> Long, form: (Both, Long) -> MvcResult, api: (Both, Long) -> MvcResult, saved: Int = 303, apiSaved: Int = 200, check: (Both, Long, MvcResult, MvcResult) -> Unit = { _, _, _, _ -> }) {
        val b = both()
        gatherings.reset()
        val first = setup()
        val formResult = form(b, first)
        val formState = state(first)
        val formHtml = formResult.response.contentAsString
        gatherings.reset()
        val second = setup()
        val apiResult = api(b, second)
        val apiState = state(second)
        assertEquals(formResult.response.status == saved, apiResult.response.status == apiSaved, "$what: saved (form ${formResult.response.status}, API ${apiResult.response.status})")
        assertEquals(formState, apiState, "$what: the same row")
        for ((field, sentence) in fieldSentences(apiResult)) assertTrue(formHtml.contains(escape(sentence)), "$what: the form says «$sentence» for $field")
        check(b, first, formResult, apiResult)
        kit.clean()
    }

    private fun draft(title: String = "Convivenza di prova", start: LocalDate = today().plusDays(30), end: LocalDate = start.plusDays(2), venue: Boolean = true, status: GatheringStatus = GatheringStatus.draft, full: Int? = 6000, daily: Int? = 2500, lunch: Int? = 1000) =
        gatherings.make(title = title, start = start, end = end, status = status, place = if (venue) "Casa per ritiri di esempio" else null, full = full, daily = daily, lunchOnly = lunch)

    // ---- Criterion 13: title, dates and venue ------------------------------------------------------------------------------------------------

    @Test
    fun `t21 c13 title, dates and venue through the form and through the API have the same outcome, the same sentences and the same row`() {
        val start = today().plusDays(30)
        val past = today().minusDays(2)
        val cases = listOf(
            Triple("valid", "Un altro titolo", start.plusDays(1) to start.plusDays(3)),
            Triple("email in the title", "Scrivimi a nome@example.org", start to start.plusDays(2)),
            Triple("phone in the title", "Chiama 333 1234567", start to start.plusDays(2)),
            Triple("a name of a teacher", "Con Elisa Conti", start to start.plusDays(2)),
            Triple("too long", "T".repeat(150), start to start.plusDays(2)),
            Triple("empty title", "", start to start.plusDays(2)),
            Triple("start in the past", "Titolo", past to start),
            Triple("end before start", "Titolo", start.plusDays(5) to start),
            Triple("more than 7 days", "Titolo", start to start.plusDays(9)),
        )
        for ((what, title, dates) in cases) {
            row(
                what, { draft() },
                { b, id -> b.phone.submit("/convivenza/$id/date", "/convivenza/$id/date", mapOf("title" to title, "startDate" to dates.first.toString(), "endDate" to dates.second.toString(), "venue" to gatherings.venueIdOf(id).toString())) },
                { b, id -> kit.put("/api/convivenza/$id/date", b.teacher.token, json("title" to title, "startDate" to dates.first.toString(), "endDate" to dates.second.toString(), "venue" to gatherings.venueIdOf(id))) },
            )
        }
    }

    @Test
    fun `t21 c13 a draft without a venue, a gathering that is not a draft without one, and the dates of a gathering that is over`() {
        row(
            "a draft may have no venue", { draft(venue = false) },
            { b, id -> b.phone.submit("/convivenza/$id/date", "/convivenza/$id/date", mapOf("title" to "Titolo", "startDate" to today().plusDays(30).toString(), "endDate" to today().plusDays(31).toString(), "venue" to "")) },
            { b, id -> kit.put("/api/convivenza/$id/date", b.teacher.token, json("title" to "Titolo", "startDate" to today().plusDays(30).toString(), "endDate" to today().plusDays(31).toString(), "venue" to null)) },
        )
        row(
            "an open gathering needs a venue", { draft(status = GatheringStatus.`open`) },
            { b, id -> b.phone.submit("/convivenza/$id/date", "/convivenza/$id/date", mapOf("title" to "Titolo", "startDate" to today().plusDays(30).toString(), "endDate" to today().plusDays(31).toString(), "venue" to "")) },
            { b, id -> kit.put("/api/convivenza/$id/date", b.teacher.token, json("title" to "Titolo", "startDate" to today().plusDays(30).toString(), "endDate" to today().plusDays(31).toString(), "venue" to null)) },
            check = { _, _, form, api -> assertEquals(422, form.response.status); assertEquals(422, api.response.status) },
        )
        // A gathering that is over: other dates are refused whole (409, DATES_CLOSED), and the title alone is saved.
        val ended = today().minusDays(10)
        row(
            "dates of a gathering that is over", { draft(start = ended.minusDays(2), end = ended, status = GatheringStatus.closed) },
            { b, id -> b.phone.submit("/convivenza/$id/date", "/convivenza/$id/date", mapOf("title" to "Titolo", "startDate" to today().plusDays(1).toString(), "endDate" to today().plusDays(2).toString(), "venue" to gatherings.venueIdOf(id).toString())) },
            { b, id -> kit.put("/api/convivenza/$id/date", b.teacher.token, json("title" to "Titolo", "startDate" to today().plusDays(1).toString(), "endDate" to today().plusDays(2).toString(), "venue" to gatherings.venueIdOf(id))) },
            check = { _, _, form, api ->
                assertEquals(409, form.response.status)
                assertEquals(409, api.response.status)
                assertEquals("Le date non si cambiano più, perché la convivenza è finita.", kit.error(api))
            },
        )
        row(
            "the title of a gathering that is over", { draft(start = ended.minusDays(2), end = ended, status = GatheringStatus.closed) },
            { b, id -> b.phone.submit("/convivenza/$id/date", "/convivenza/$id/date", mapOf("title" to "Titolo nuovo", "venue" to gatherings.venueIdOf(id).toString())) },
            { b, id -> kit.put("/api/convivenza/$id/date", b.teacher.token, json("title" to "Titolo nuovo", "startDate" to ended.minusDays(2).toString(), "endDate" to ended.toString(), "venue" to gatherings.venueIdOf(id))) },
        )
    }

    @Test
    fun `t21 c13 c18 a day that a registration uses is not taken away, by the form and by the API with the same sentence`() {
        val rk = RetentionTestKit(dsl, clock)
        val start = today().plusDays(30)
        row(
            "R3", {
                val id = draft(start = start, end = start.plusDays(2), status = GatheringStatus.`open`)
                rk.registration(id, rk.account("genitore-r3"))
                id
            },
            { b, id -> b.phone.submit("/convivenza/$id/date", "/convivenza/$id/date", mapOf("title" to "Titolo", "startDate" to start.plusDays(1).toString(), "endDate" to start.plusDays(2).toString(), "venue" to gatherings.venueIdOf(id).toString())) },
            { b, id -> kit.put("/api/convivenza/$id/date", b.teacher.token, json("title" to "Titolo", "startDate" to start.plusDays(1).toString(), "endDate" to start.plusDays(2).toString(), "venue" to gatherings.venueIdOf(id))) },
            check = { _, _, form, api ->
                assertEquals(422, form.response.status)
                assertEquals(422, api.response.status)
                assertTrue(kit.tree(api).get("fields").get("startDate").stringValue().startsWith("Non puoi togliere "))
            },
        )
    }

    @Test
    fun `t21 c18 a venue that changes with people in the rooms of the one before is a 409 that says how many, and nothing changes, because the APIs never confirm`() {
        val rk = RoomTestKit(mockMvc, oneTimeTokens, dsl, clock)
        val s = rk.start()
        val other = gatherings.makeVenue("Casa per ritiri di un altro posto", "Altra casa")
        val room = rk.room(s.id, "Camera 1", 4)
        val retention = RetentionTestKit(dsl, clock)
        rk.put(retention.registration(s.id, retention.account("genitore-camere")), s.id, room)
        val t = checkNotNull(tokens.create(s.martaId)).value
        val before = state(s.id)

        val answer = kit.put("/api/convivenza/${s.id}/date", t, json("title" to "Titolo", "startDate" to s.today.minusDays(1).toString(), "endDate" to s.today.plusDays(1).toString(), "venue" to other))

        assertEquals(409, answer.response.status)
        val from = dsl.select(VENUE.NAME).from(VENUE).where(VENUE.ID.eq(rk.venueOf(s.id))).fetchSingle(VENUE.NAME)
        assertEquals("Nelle camere di «$from» c’è 1 persona: cambia la struttura dall’app, che chiede la conferma.", kit.error(answer))
        assertEquals(before, state(s.id))
        assertEquals(1, rk.peopleCount(s.id), "nobody left a room")
        // A room where somebody sleeps is not taken away by the APIs either (criterion 24): 409, and the room and the person stay.
        val removal = kit.delete("/api/strutture/${rk.venueOf(s.id)}/camere/$room", t)
        assertEquals(409, removal.response.status)
        assertEquals("Ci dorme 1 persona: togli la camera dall’app, che chiede la conferma.", kit.error(removal))
        assertEquals(1, rk.roomCount(s.id))
        assertEquals(1, rk.peopleCount(s.id))
    }

    // ---- Criterion 13: fees and information --------------------------------------------------------------------------------------------

    @Test
    fun `t21 c13 c19 the fees through the form and through the API, with the full fee asked of a gathering that is not a draft and a fee that is used`() {
        val rk = RetentionTestKit(dsl, clock)
        val values = listOf(
            Triple("valid", Triple("12,50", "0", ""), GatheringStatus.draft),
            Triple("all empty in a draft", Triple("", "", ""), GatheringStatus.draft),
            Triple("full missing in an open gathering", Triple("", "5", "5"), GatheringStatus.`open`),
            Triple("a thousand separator", Triple("1.000", "5", "5"), GatheringStatus.draft),
            Triple("a negative", Triple("-3", "5", "5"), GatheringStatus.draft),
            Triple("too much", Triple("1000,01", "5", "5"), GatheringStatus.draft),
        )
        for ((what, fees, status) in values) {
            row(
                what, { draft(status = status) },
                { b, id -> b.phone.submit("/convivenza/$id/quote", "/convivenza/$id/quote", mapOf("fullFee" to fees.first, "dailyFee" to fees.second, "lunchOnlyFee" to fees.third)) },
                { b, id -> kit.put("/api/convivenza/$id/quote", b.teacher.token, json("fullFee" to fees.first, "dailyFee" to fees.second, "lunchOnlyFee" to fees.third)) },
            )
        }
        // R2: a fee that a registration uses cannot be left empty.
        row(
            "R2", {
                val first = today().plusDays(30)
                val id = draft(start = first, status = GatheringStatus.`open`)
                // A registration of some days, which uses the fee of the day.
                rk.rows.register(id, rk.account("genitore-r2"), "Luca", "Rossi", days = mapOf(first to "full"))
                id
            },
            { b, id -> b.phone.submit("/convivenza/$id/quote", "/convivenza/$id/quote", mapOf("fullFee" to "60", "dailyFee" to "", "lunchOnlyFee" to "")) },
            { b, id -> kit.put("/api/convivenza/$id/quote", b.teacher.token, json("fullFee" to "60", "dailyFee" to "", "lunchOnlyFee" to "")) },
            check = { _, _, form, api ->
                assertEquals(422, form.response.status)
                assertEquals(422, api.response.status)
                assertTrue(kit.tree(api).get("fields").has("dailyFee") || kit.tree(api).get("fields").has("lunchOnlyFee"))
            },
        )
    }

    @Test
    fun `t21 c19 c21 the fee that is now 0 is a GET and a PUT with the other two as they were, the answer is the gathering with the notice of the form, and a field that is missing is a 400`() {
        val rk = RetentionTestKit(dsl, clock)
        val b = both()
        val id = draft(status = GatheringStatus.`open`)
        rk.registration(id, rk.account("genitore-quote"))
        rk.registration(id, rk.account("genitore-quote-2"), "Elena", "Marino")

        val now = kit.tree(kit.get("/api/convivenza/$id", b.teacher.token))
        assertEquals("60", now.get("fullFee").stringValue())
        val answer = kit.put("/api/convivenza/$id/quote", b.teacher.token, json("fullFee" to "0", "dailyFee" to now.get("dailyFee").stringValue(), "lunchOnlyFee" to now.get("lunchOnlyFee").stringValue()))

        assertEquals(200, answer.response.status)
        assertEquals("0", kit.tree(answer).get("fullFee").stringValue())
        assertEquals("25", kit.tree(answer).get("dailyFee").stringValue())
        assertEquals("Hai già 2 iscrizioni: se cambi le quote, la quota dovuta di ognuna si ricalcola. Quello che è già stato pagato non cambia.", kit.tree(answer).get("notice").stringValue())
        assertEquals(0, dsl.select(GATHERING.FULL_FEE_CENTS).from(GATHERING).where(GATHERING.ID.eq(id)).fetchSingle(GATHERING.FULL_FEE_CENTS))
        // A PUT that forgets a field is a 400 and the fee is not emptied by mistake.
        val forgot = kit.put("/api/convivenza/$id/quote", b.teacher.token, json("fullFee" to "10", "dailyFee" to "5"))
        assertEquals(400, forgot.response.status)
        assertTrue(kit.error(forgot).contains("lunchOnlyFee"))
        assertEquals(0, dsl.select(GATHERING.FULL_FEE_CENTS).from(GATHERING).where(GATHERING.ID.eq(id)).fetchSingle(GATHERING.FULL_FEE_CENTS))
    }

    @Test
    fun `t21 c13 c20 the information through the form and through the API`() {
        val cases = listOf(
            Triple("valid", "Si arriva alle 17.", "Un sacco a pelo."),
            Triple("empty", "", ""),
            Triple("email", "Scrivi a nome@example.org", "Un sacco a pelo."),
            Triple("a name of a teacher", "Referente Elisa Conti", ""),
            Triple("too long", "T".repeat(5100), ""),
        )
        for ((what, info, bring) in cases) {
            row(
                what, { draft() },
                { b, id -> b.phone.submit("/convivenza/$id/informazioni", "/convivenza/$id/informazioni", mapOf("infoText" to info, "whatToBring" to bring)) },
                { b, id -> kit.put("/api/convivenza/$id/informazioni", b.teacher.token, json("infoText" to info, "whatToBring" to bring)) },
            )
        }
    }

    // ---- Criteria 15-17: reading and making -------------------------------------------------------------------------------------------

    @Test
    fun `t21 c16 the detail has the fields of the criterion and nothing else, with a draft that has no venue and no fees`() {
        val b = both()
        val id = draft(venue = false, full = null, daily = null, lunch = null)

        val detail = kit.tree(kit.get("/api/convivenza/$id", b.teacher.token))

        assertEquals(
            listOf("id", "title", "startDate", "endDate", "state", "over", "venue", "fullFee", "dailyFee", "lunchOnlyFee", "infoText", "whatToBring", "missing", "notice"),
            detail.propertyNames().toList(),
        )
        assertTrue(detail.get("venue").isNull)
        assertTrue(detail.get("fullFee").isNull)
        assertEquals("draft", detail.get("state").stringValue())
        assertFalse(detail.get("over").booleanValue())
        assertEquals(listOf("Scegli la struttura", "Scrivi la quota intera", "Scegli almeno un maestro affidatario"), detail.get("missing").toList().map { n -> n.stringValue() })
        assertEquals(404, kit.get("/api/convivenza/999999999", b.teacher.token).response.status)
        // With a venue: its id, name and place, and the fees as the form writes them.
        val full = kit.tree(kit.get("/api/convivenza/${draft(status = GatheringStatus.closed)}", b.teacher.token))
        assertEquals(listOf("id", "name", "place"), full.get("venue").propertyNames().toList())
        assertEquals("60", full.get("fullFee").stringValue())
        assertEquals("25", full.get("dailyFee").stringValue())
    }

    @Test
    fun `t21 c17 a new draft is made, and with a draft or an open gathering already the answer is a 409 with the sentence of the page and nothing is made`() {
        val b = both()
        val start = today().plusDays(40)

        val made = kit.post("/api/convivenza", b.teacher.token, json("title" to "Convivenza nuova", "startDate" to start.toString(), "endDate" to start.plusDays(2).toString()))
        assertEquals(201, made.response.status)
        assertEquals("draft", kit.tree(made).get("state").stringValue())
        assertEquals(1, dsl.fetchCount(GATHERING))

        val again = kit.post("/api/convivenza", b.teacher.token, json("title" to "Un'altra", "startDate" to start.toString(), "endDate" to start.plusDays(2).toString()))
        assertEquals(409, again.response.status)
        assertEquals("C’è già una convivenza in bozza: «Convivenza nuova». Puoi modificarla invece di crearne un’altra.", kit.error(again))
        assertEquals(1, dsl.fetchCount(GATHERING))
        dsl.update(GATHERING).set(GATHERING.VENUE_ID, gatherings.makeVenue()).set(GATHERING.FULL_FEE_CENTS, 6000).set(GATHERING.STATUS, GatheringStatus.`open`).execute()
        assertEquals("C’è già una convivenza con le iscrizioni aperte: «Convivenza nuova». Chiudi le iscrizioni prima di crearne un’altra.", kit.error(kit.post("/api/convivenza", b.teacher.token, json("title" to "X", "startDate" to start.toString(), "endDate" to start.toString()))))
        // Closed, a new one can be made: the year after.
        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).execute()
        assertEquals(201, kit.post("/api/convivenza", b.teacher.token, json("title" to "Convivenza dell’anno dopo", "startDate" to start.plusDays(365).toString(), "endDate" to start.plusDays(367).toString())).response.status)
        assertEquals(2, dsl.fetchCount(GATHERING))
    }

    @Test
    fun `t21 c13 c17 a new gathering through the form and through the API`() {
        val start = today().plusDays(40)
        val cases = listOf(
            "valid" to Triple("Convivenza nuova", start, start.plusDays(2)),
            "empty title" to Triple("", start, start.plusDays(2)),
            "email" to Triple("Scrivi a nome@example.org", start, start.plusDays(2)),
            "past" to Triple("Titolo", today().minusDays(3), start),
        )
        for ((what, case) in cases) {
            val b = both()
            gatherings.reset()
            val form = b.phone.submit("/convivenza/nuova", "/convivenza/nuova", mapOf("title" to case.first, "startDate" to case.second.toString(), "endDate" to case.third.toString()))
            val formRows = dsl.select(GATHERING.TITLE, GATHERING.START_DATE, GATHERING.END_DATE).from(GATHERING).fetch().map { it.intoList() }
            gatherings.reset()
            val api = kit.post("/api/convivenza", b.teacher.token, json("title" to case.first, "startDate" to case.second.toString(), "endDate" to case.third.toString()))
            val apiRows = dsl.select(GATHERING.TITLE, GATHERING.START_DATE, GATHERING.END_DATE).from(GATHERING).fetch().map { it.intoList() }
            assertEquals(form.response.status == 303, api.response.status == 201, what)
            assertEquals(formRows, apiRows, what)
            for ((field, sentence) in fieldSentences(api)) assertTrue(form.response.contentAsString.contains(escape(sentence)), "$what: $field")
            kit.clean()
        }
    }

    // ---- Criterion 23 with a gathering: a venue that is used ----------------------------------------------------------------------------

    @Test
    fun `t21 c23 a venue that a gathering uses is not taken away, and a PUT on it has the notice of the form`() {
        val b = both()
        val id = draft()
        val venue = checkNotNull(gatherings.venueIdOf(id))

        val removed = kit.delete("/api/strutture/$venue", b.teacher.token)
        assertEquals(409, removed.response.status)
        assertEquals("Non è stato tolto niente. Questa struttura è usata da 1 convivenza.", kit.error(removed))
        val changed = kit.put("/api/strutture/$venue", b.teacher.token, json("name" to "T21 Casa usata", "place" to "Casa per ritiri"))
        assertEquals(200, changed.response.status)
        assertEquals("Nome e luogo nuovi sono già nella pagina per i genitori. Le iscrizioni già fatte restano con il testo di allora.", kit.tree(changed).get("notice").stringValue())
        assertEquals(1, kit.tree(changed).get("gatherings").intValue())
    }

    // ---- Criterion 25: no personal data -----------------------------------------------------------------------------------------------

    @Test
    fun `t21 c25 on a database with data of every kind no route and no error says a name, an address, a phone, a note, an amount or a code of a person`() {
        val rk = RoomTestKit(mockMvc, oneTimeTokens, dsl, clock)
        val retention = RetentionTestKit(dsl, clock)
        val s = rk.start()
        val parent = retention.account("genitore-dati")
        val parentEmail = retention.emailOf(parent)
        val luca = retention.registration(s.id, parent, "Luca", "Rossi", notes = Notes("Arachidi", "Lattosio", "Antistaminico"))
        val paper = retention.paper(s.id, s.martaId, "Nicolò", "Ferri")
        retention.presence(luca, s.today, s.martaId)
        retention.payment(luca, 1234, s.martaId)
        retention.reading(luca, s.martaId)
        retention.invitation("giorgioneri", java.time.OffsetDateTime.now(clock).plusDays(3))
        val room = rk.room(s.id, "Camera 1", 4)
        rk.put(luca, s.id, room)
        rk.putTeacher(s.id, s.paoloId, room)
        val code = dsl.select(GATHERING.PUBLIC_CODE).from(GATHERING).where(GATHERING.ID.eq(s.id)).fetchSingle(GATHERING.PUBLIC_CODE)!!
        val teacherEmails = listOf(s.martaId, s.paoloId, s.giuliaId).map { retention.emailOf(it) }
        val token = checkNotNull(tokens.create(s.martaId)).value
        val venue = checkNotNull(gatherings.venueIdOf(s.id))

        val calls = mutableListOf<MvcResult>()
        for (route in listOf("/api/convivenza", "/api/convivenza/${s.id}", "/api/strutture", "/api/strutture/$venue")) calls += kit.get(route, token)
        // Errors, of every kind that a route can give.
        calls += kit.get("/api/convivenza/999999999", token)
        calls += kit.get("/api/non-esiste", token)
        calls += kit.get("/api/strutture", "talita_" + "Z".repeat(43))
        calls += kit.delete("/api/strutture", token)
        calls += kit.put("/api/convivenza/${s.id}/informazioni", token, "{non json")
        calls += kit.put("/api/convivenza/${s.id}/informazioni", token, json("infoText" to "Il referente è Luca Rossi", "whatToBring" to "Nicolò Ferri"))
        calls += kit.put("/api/convivenza/${s.id}/informazioni", token, json("infoText" to "Chiama Marta Bianchi", "whatToBring" to ""))
        calls += kit.put("/api/convivenza/${s.id}/date", token, json("title" to "Marta Bianchi e Paolo Verdi", "startDate" to s.yesterday.toString(), "endDate" to s.tomorrow.toString(), "venue" to venue))
        calls += kit.put("/api/convivenza/${s.id}/quote", token, json("fullFee" to "12,34", "dailyFee" to "x", "lunchOnlyFee" to ""))
        calls += kit.delete("/api/strutture/$venue/camere/$room", token)
        calls += kit.delete("/api/strutture/$venue", token)
        calls += kit.post("/api/strutture", token, json("name" to "Casa di Giulia Serra", "place" to "Luca Rossi"))
        calls += kit.post("/api/strutture/$venue/camere", token, json("name" to "Camera dei maschi", "beds" to 4))
        calls += kit.post("/api/strutture/$venue/moduli", token, json("name" to "Registro Nicolò Ferri"))
        // A route for every route of the APIs: each one is called at least once.
        val routes = mapping.handlerMethods.keys.flatMap { info -> info.pathPatternsCondition!!.patternValues.filter { it.startsWith("/api/") } }.toSet()
        assertEquals(setOf("/api/convivenza", "/api/convivenza/{id:[0-9]{1,18}}", "/api/convivenza/{id:[0-9]{1,18}}/date", "/api/convivenza/{id:[0-9]{1,18}}/quote", "/api/convivenza/{id:[0-9]{1,18}}/informazioni", "/api/strutture", "/api/strutture/{id:[0-9]{1,18}}", "/api/strutture/{id:[0-9]{1,18}}/camere", "/api/strutture/{id:[0-9]{1,18}}/camere/{camera:[0-9]{1,18}}", "/api/strutture/{id:[0-9]{1,18}}/moduli", "/api/strutture/{id:[0-9]{1,18}}/moduli/{modulo:[0-9]{1,18}}"), routes)
        calls += kit.put("/api/strutture/$venue/camere/$room", token, json("name" to "Camera 1", "beds" to 4))
        calls += kit.put("/api/strutture/$venue/moduli/999999", token, json("name" to "x", "withAge" to true, "withDays" to true, "withTeacherDocument" to true, "withTeacherTaxCode" to true))
        calls += kit.post("/api/convivenza", token, json("title" to "Luca Rossi", "startDate" to "", "endDate" to ""))

        val said = { call: MvcResult ->
            call.response.status.toString() + "\n" + call.response.headerNames.joinToString("\n") { name -> name + ": " + call.response.getHeaders(name).joinToString(",") } + "\n" + call.response.contentAsString
        }
        val everything = calls.joinToString("\n", transform = said)
        val secrets = listOf(
            "Luca", "Rossi", "Nicolò", "Ferri", "Marta", "Bianchi", "Paolo", "Verdi", "Giulia", "Serra", "giorgioneri", parentEmail, "Arachidi", "Lattosio", "Antistaminico", "12,34", "1234", "333 123 4567", "4 aprile 2016", "2016-04-04", code,
        ) + teacherEmails
        for (secret in secrets) {
            val offender = calls.firstOrNull { said(it).contains(secret) }
            assertFalse(offender != null, "an answer of the APIs has «$secret»: ${offender?.request?.method} ${offender?.request?.requestURI} ${offender?.response?.status}")
        }
        for (word in listOf("accountId", "registrationId", "teacherId", "publicCode", "custod", "affidatari", "password")) assertFalse(everything.lowercase().contains(word.lowercase()), "an answer of the APIs has «$word»")
    }
}
