package talita.e2e

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.FormData
import com.microsoft.playwright.options.RequestOptions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.springframework.boot.test.system.CapturedOutput
import talita.CapturesOutput
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.CHECKIN
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.ROOM
import talita.jooq.tables.references.ROOM_CHILD
import talita.jooq.tables.references.VENUE_FORM
import talita.registration.Notes
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The QA of TALITA-20, part 3 (the forms of the venue and the fields of the teachers), on the real interface and on the real stack, with what the tests of the developer leave out: who is on the sheet and what the
 * cells say when the days are around today (the rule of TALITA-19), what the page of the sheet must never hold, a parent and whoever has not entered on every address of the forms, two teachers who write the same
 * name of a form at the same moment, the fields of the teachers with their own eyes (no form around them, the limits, the button «Stampa», the page that comes back from the cache), the sheets as Chromium prints them
 * in several sizes against what the caption says («pagina N di M»), and the two notices. Names, venues and the values of the fields are synthetic.
 */
@E2eApplication
class ModuleQaE2eTest {

    private fun E2e.venueOf(gathering: Long): Long = dsl().select(GATHERING.VENUE_ID).from(GATHERING).where(GATHERING.ID.eq(gathering)).fetchSingle(GATHERING.VENUE_ID)!!

    private fun E2e.form(gathering: Long, name: String, age: Boolean = false, days: Boolean = false, document: Boolean = false, taxCode: Boolean = false): Long =
        dsl().insertInto(VENUE_FORM).set(VENUE_FORM.VENUE_ID, venueOf(gathering)).set(VENUE_FORM.NAME, name).set(VENUE_FORM.WITH_AGE, age).set(VENUE_FORM.WITH_DAYS, days)
            .set(VENUE_FORM.WITH_TEACHER_DOCUMENT, document).set(VENUE_FORM.WITH_TEACHER_TAX_CODE, taxCode).returningResult(VENUE_FORM.ID).fetchSingle().value1()!!

    /** The sign of the arrival of [registration] on [day], as a teacher makes it. */
    private fun E2e.mark(registration: Long, day: LocalDate, by: Long) {
        dsl().insertInto(CHECKIN).set(CHECKIN.REGISTRATION_ID, registration).set(CHECKIN.DAY, day).set(CHECKIN.RECORDED_AT, OffsetDateTime.now()).set(CHECKIN.RECORDED_BY_ACCOUNT_ID, by).execute()
    }

    /** [count] children, every day, who are present on [day]. */
    private fun E2e.present(world: ArrivalsWorld, count: Int, day: LocalDate = world.yesterday) {
        for (number in 1..count) {
            mark(rows().register(world.id, someParent(), "Bambino", "Prova%02d".format(number), LocalDate.of(2016, 1, 1).plusDays(number.toLong() * 9)), day, world.martaId)
        }
    }

    /** The cells of the rows of a table of the sheet (0 is the table of the children). */
    private fun Page.cells(table: Int): List<List<String>> =
        locator("div.blocco-stampa").nth(table).locator("tbody tr").evaluateAll("rows => rows.map(r => Array.from(r.cells).map(c => c.innerText.trim()))").let { found ->
            (found as List<*>).map { row -> (row as List<*>).map { it.toString() } }
        }

    private fun Page.csrfToken(): String = requireNotNull(locator("input[type=hidden][name=_csrf]").first().getAttribute("value"))

    private fun Page.byHand(token: String, method: String, path: String, fields: Map<String, String> = emptyMap(), htmx: Boolean = false): Pair<Int, String> {
        val answer = evaluate(
            "async ([method, path, fields, token, htmx]) => { const body = new URLSearchParams(fields); body.set('_csrf', token); " +
                "const headers = htmx ? { 'HX-Request': 'true' } : {}; " +
                "const r = await fetch(path, method === 'POST' ? { method, headers, body, redirect: 'manual' } : { method, headers, redirect: 'manual' }); return [r.status, await r.text()]; }",
            listOf(method, path, fields, token, htmx),
        ) as List<*>
        return (answer[0] as Number).toInt() to answer[1] as String
    }

    private fun Page.noHorizontalScrollAt320(e2e: E2e): Boolean {
        setViewportSize(320, 800)
        val fits = noHorizontalScroll()
        setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        return fits
    }

    /** The text of each sheet of a PDF, with `pdftotext` (null when the program is not there). */
    private fun sheetsOf(pdf: Path): List<String>? = try {
        val process = ProcessBuilder("pdftotext", "-layout", pdf.toString(), "-").redirectErrorStream(true).start()
        val text = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (process.waitFor() == 0) text.split('\u000c').filter { it.isNotBlank() } else null
    } catch (e: java.io.IOException) {
        null
    }

    // ---- Criterion 22: who is on the sheet, and what the cells say, by the rule of TALITA-19 --------------------------------------------------------

    @E2eTest
    fun `t20 c22 the sheet lists who is marked present on a day up to today and nobody else, the cells say presente and solo pranzo and stay empty for a day to come and for a day with no mark, and the order and the age are as written`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering(first = -1, last = 1)
        val registrations = e2e.rows()
        val firstDay = w.yesterday
        fun child(first: String, last: String, birth: LocalDate, days: Map<LocalDate, String>? = null, status: RegistrationStatus = RegistrationStatus.confirmed): Long =
            registrations.register(w.id, e2e.someParent(), first, last, birth, days = days, status = status)

        // Every day, marked on all three days (the sign of an arrival is written for the days of the registration): present yesterday and today, nothing for the day that has not come.
        val ferri = child("Nicolò", "Ferri", firstDay.minusYears(8))
        for (day in listOf(w.yesterday, w.today, w.tomorrow)) e2e.mark(ferri, day, w.martaId)
        // Lunch only today and tomorrow, marked on both: «solo pranzo» today, nothing yesterday, nothing for tomorrow. One day short of eight years on the first day.
        val costa = child("Davide", "Costa", firstDay.minusYears(8).plusDays(1), days = mapOf(w.today to "lunch", w.tomorrow to "lunch"))
        e2e.mark(costa, w.today, w.martaId)
        e2e.mark(costa, w.tomorrow, w.martaId)
        // A day that the registration did not choose but that has a mark: «presente».
        val deLuca = child("Marco", "De Luca", firstDay.minusYears(9), days = mapOf(w.yesterday to "full"))
        e2e.mark(deLuca, w.today, w.martaId)
        // The same surname: by first name. Marked on one day only.
        e2e.mark(child("Luca", "Rossi", firstDay.minusYears(10)), w.today, w.martaId)
        val anna = child("Anna", "Rossi", firstDay.minusYears(10).minusDays(1))
        e2e.mark(anna, w.yesterday, w.martaId)
        e2e.mark(anna, w.today, w.martaId)
        // Never on the sheet: registered and never marked, marked only for a day to come, and withdrawn with a mark.
        child("Sara", "Bianchi", firstDay.minusYears(9))
        e2e.mark(child("Paola", "Neri", firstDay.minusYears(9)), w.tomorrow, w.martaId)
        e2e.mark(child("Elena", "Verdi", firstDay.minusYears(9), status = RegistrationStatus.withdrawn), w.yesterday, w.martaId)
        val id = e2e.form(w.id, "Registro per la tassa di soggiorno", age = true, days = true)

        e2e.open("/arrivi/modulo/$id")
        assertThat(page.locator("main")).containsText("Bambini presenti · 5")
        assertEquals(
            listOf(
                listOf("Costa Davide", "7", "", "solo pranzo", ""),
                listOf("De Luca Marco", "9", "", "presente", ""),
                listOf("Ferri Nicolò", "8", "presente", "presente", ""),
                listOf("Rossi Anna", "10", "presente", "presente", ""),
                listOf("Rossi Luca", "10", "", "presente", ""),
            ),
            page.cells(0),
        )
        assertThat(page.locator("main")).containsText("Età in anni al primo giorno della convivenza")
        assertThat(page.locator("main")).containsText("Un giorno vuoto è senza presenza, oppure non ancora arrivato.")
        val everything = page.locator("body").innerText()
        assertFalse(everything.contains("Bianchi Sara") || everything.contains("Verdi Elena") || everything.contains("Neri Paola"), "a child who is not present is on the sheet")
        assertThat(page.locator("main")).containsText("Maestri · 2")
        assertTrue(page.noHorizontalScrollAt320(e2e))
    }

    @E2eTest
    fun `t20 c22 a gathering that is over still has its sheet, one of a year ago has none and no name, and a draft has no sheet at all`(e2e: E2e) {
        val page = e2e.page
        val over = e2e.arrivalsGathering(first = -12, last = -10)
        e2e.present(over, 3, day = over.today.minusDays(12))
        val id = e2e.form(over.id, "Registro per la tassa di soggiorno", age = true, days = true)
        e2e.open("/arrivi/modulo/$id")
        assertThat(page.locator("main")).containsText("Bambini presenti · 3")
        assertEquals(3, page.cells(0).size)

        val old = e2e.arrivalsGathering(first = -400, last = -398)
        e2e.present(old, 3, day = old.today.minusDays(400))
        val oldId = e2e.form(old.id, "Registro per la tassa di soggiorno", age = true, days = true)
        val answer = e2e.open("/arrivi/modulo/$oldId")
        assertTrue(answer.status() < 500, "the sheet of a gathering of more than a year ago answered ${answer.status()}")
        assertFalse(page.locator("body").innerText().contains("Prova01"), "the names of a gathering whose registrations are deleted are on the sheet")
        assertEquals(0, page.locator("input[data-solo-carta]").count())

        val draft = e2e.arrivalsGathering(status = GatheringStatus.draft)
        val draftId = e2e.form(draft.id, "Registro per la tassa di soggiorno")
        assertEquals(404, e2e.open("/arrivi/modulo/$draftId").status(), "a draft has no sheet")
    }

    // ---- Criterion 23: what the page of the sheet must never hold --------------------------------------------------------------------------------

    @E2eTest
    @CapturesOutput
    fun `t20 c23 c26 the page of the sheet has none of what a child has besides the name and the age, whatever it is, the contacts, the notes on health, the payments, the room, the paper, the date of birth, and the log has no name, no age and no name of the form`(e2e: E2e, output: CapturedOutput) {
        val page = e2e.page
        val w = e2e.arrivalsGathering(first = -1, last = 1)
        val registrations = e2e.rows()
        val venue = e2e.venueOf(w.id)
        val room = e2e.dsl().insertInto(ROOM).set(ROOM.VENUE_ID, venue).set(ROOM.NAME, "Camera Sentinella").set(ROOM.BEDS, 4.toShort()).returningResult(ROOM.ID).fetchSingle().value1()!!
        val sara = registrations.register(w.id, e2e.someParent(), "Sara", "Neri", LocalDate.of(2013, 3, 21), phone = "348 765 4321", notes = Notes("ArachidiSentinella", "LattosioSentinella", "FarmacoSentinella"))
        // On paper (no owner, entered by a teacher, with the paper received), with a payment and in a room.
        val paper = registrations.register(w.id, null, "Luca", "Rossi", LocalDate.of(2013, 5, 30), phone = "349 111 2233", enteredBy = w.martaId, paperReceived = true)
        for (child in listOf(sara, paper)) {
            e2e.mark(child, w.yesterday, w.martaId)
            e2e.payment(child, 77701, w.martaId)
            e2e.dsl().insertInto(ROOM_CHILD).set(ROOM_CHILD.REGISTRATION_ID, child).set(ROOM_CHILD.GATHERING_ID, w.id).set(ROOM_CHILD.VENUE_ID, venue).set(ROOM_CHILD.ROOM_ID, room).execute()
        }
        val id = e2e.form(w.id, "Registro per la tassa di soggiorno", age = true, days = true, document = true, taxCode = true)

        e2e.open("/arrivi/modulo/$id")
        assertThat(page.locator("main")).containsText("Bambini presenti · 2")
        val html = page.content()
        val text = page.locator("body").innerText()
        for (never in listOf(
            "348 765 4321", "3487654321", "349 111 2233", "nome@example.org", "ArachidiSentinella", "LattosioSentinella", "FarmacoSentinella", "Camera Sentinella", "777,01", "77701", "2013-", "21/03/2013", "30/05/2013",
        )) {
            assertFalse(html.contains(never), "the HTML of the sheet has «$never»")
            assertFalse(text.contains(never), "the sheet says «$never»")
        }
        for (never in listOf("cartaceo", "doppione", "Manleva", "Da pagare", "Pagato", "Quota", "€", "sesso", "Maschio", "Femmina", "nascita", "Allergi", "Segnalazion", "Camera", "Recapit", "Telefono")) {
            assertFalse(text.contains(never, ignoreCase = true), "the sheet says «$never»")
        }
        assertEquals(listOf("Neri Sara", "Rossi Luca"), page.cells(0).map { it.first() })
        val log = output.all
        for (secret in listOf("Neri Sara", "Rossi Luca", "Sara Neri", "Luca Rossi", "Bianchi", "Verdi", "Registro per la tassa di soggiorno", "Camera Sentinella", "348 765 4321", "ArachidiSentinella")) {
            assertFalse(log.contains(secret), "the log has «$secret»")
        }
    }

    // ---- Criterion 26: a parent and whoever has not entered, on every address of the forms ----------------------------------------------------------

    @E2eTest(profiles = [Profile.DESKTOP], teacher = false)
    fun `t20 c26 a parent is told 403 on every address of the forms and of the sheet, by hand with a page or with a form, whoever has not entered goes to the access page, and nothing changes`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering(meTeacher = false)
        e2e.present(w, 2)
        val venue = e2e.venueOf(w.id)
        val form = e2e.form(w.id, "Registro per la tassa di soggiorno", age = true, days = true, document = true)
        val before = e2e.dsl().select(VENUE_FORM.ID, VENUE_FORM.NAME, VENUE_FORM.WITH_AGE, VENUE_FORM.WITH_DAYS, VENUE_FORM.WITH_TEACHER_DOCUMENT, VENUE_FORM.WITH_TEACHER_TAX_CODE).from(VENUE_FORM).fetch().map { it.intoList() }

        e2e.open("/profilo")
        val token = page.csrfToken()
        val pages = listOf("/strutture/$venue/moduli/nuovo", "/strutture/$venue/moduli/$form/modifica", "/strutture/$venue/moduli/$form/togli", "/arrivi/modulo/$form")
        val forms = listOf(
            "/strutture/$venue/moduli/nuovo" to mapOf("name" to "Altro modulo", "withAge" to "true"),
            "/strutture/$venue/moduli/$form/modifica" to mapOf("name" to "Cambiato", "withDays" to "true"),
            "/strutture/$venue/moduli/$form/togli" to emptyMap(),
        )
        val secrets = listOf("Registro per la tassa di soggiorno", "Prova01", "Bianchi")
        for (path in pages) {
            val (status, body) = page.byHand(token, "GET", path)
            assertEquals(403, status, "GET $path")
            for (secret in secrets) assertFalse(body.contains(secret), "GET $path says «$secret» to a parent")
        }
        for ((path, fields) in forms) {
            for (htmx in listOf(false, true)) {
                val (status, body) = page.byHand(token, "POST", path, fields, htmx)
                assertEquals(403, status, "POST $path (htmx $htmx)")
                for (secret in secrets) assertFalse(body.contains(secret), "POST $path says «$secret» to a parent")
            }
        }
        assertEquals(before, e2e.dsl().select(VENUE_FORM.ID, VENUE_FORM.NAME, VENUE_FORM.WITH_AGE, VENUE_FORM.WITH_DAYS, VENUE_FORM.WITH_TEACHER_DOCUMENT, VENUE_FORM.WITH_TEACHER_TAX_CODE).from(VENUE_FORM).fetch().map { it.intoList() }, "nothing was changed")

        val anonymous = e2e.newPage(signedIn = false)
        for (path in pages) {
            val answer = anonymous.request().get(e2e.url(path), RequestOptions.create().setMaxRedirects(0))
            assertEquals(302, answer.status(), "anonymous GET $path")
            assertTrue(answer.headers()["location"].orEmpty().endsWith("/accedi"), "anonymous GET $path goes to ${answer.headers()["location"]}")
        }
        for ((path, fields) in forms) {
            val form = fields.entries.fold(FormData.create()) { acc, entry -> acc.set(entry.key, entry.value) }
            val answer = anonymous.request().post(e2e.url(path), RequestOptions.create().setMaxRedirects(0).setForm(form))
            assertEquals(302, answer.status(), "anonymous POST $path")
            assertTrue(answer.headers()["location"].orEmpty().endsWith("/accedi"), "anonymous POST $path goes to ${answer.headers()["location"]}")
        }
        assertEquals(before.size, e2e.dsl().fetchCount(VENUE_FORM), "nothing was changed by whoever has not entered")
    }

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t20 c26 a form that is not there, or is of another venue, is a page that does not exist for the sheet and for its pages of change, and the address has only numbers`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.present(w, 2)
        val venue = e2e.venueOf(w.id)
        val other = GatheringSupport(e2e.dsl(), e2e.application.getBean(Clock::class.java)).makeVenue("Casa per ritiri sul lago, Località Esempio", "Casa sul lago")
        val foreign = e2e.dsl().insertInto(VENUE_FORM).set(VENUE_FORM.VENUE_ID, other).set(VENUE_FORM.NAME, "Modulo dell'altra casa").returningResult(VENUE_FORM.ID).fetchSingle().value1()!!
        val mine = e2e.form(w.id, "Registro per la tassa di soggiorno", age = true)

        for (path in listOf("/arrivi/modulo/$foreign", "/arrivi/modulo/${mine + foreign + 1000}", "/arrivi/modulo/0", "/arrivi/modulo/abc", "/strutture/$venue/moduli/$foreign/modifica", "/strutture/$venue/moduli/$foreign/togli", "/strutture/$other/moduli/$mine/modifica")) {
            assertEquals(404, e2e.open(path).status(), path)
        }
        e2e.open("/strutture/nuova")
        val token = page.csrfToken()
        assertEquals(404, page.byHand(token, "POST", "/strutture/$venue/moduli/$foreign/modifica", mapOf("name" to "Cambiato")).first)
        assertEquals(404, page.byHand(token, "POST", "/strutture/$venue/moduli/$foreign/togli").first)
        assertEquals(1, e2e.dsl().fetchCount(VENUE_FORM, VENUE_FORM.VENUE_ID.eq(other)), "the form of the other venue is still there")
        assertEquals("Modulo dell'altra casa", e2e.dsl().select(VENUE_FORM.NAME).from(VENUE_FORM).where(VENUE_FORM.ID.eq(foreign)).fetchSingle(VENUE_FORM.NAME))
        e2e.open("/arrivi/modulo/$mine")
        assertTrue(page.url().matches(Regex(".*/arrivi/modulo/[0-9]+$")), "the address of the sheet: ${page.url()}")
    }

    // ---- Criterion 20: two teachers at the same moment ------------------------------------------------------------------------------------------

    private data class Answer(val status: Int, val body: String)

    private fun BrowserContext.cookieHeader(): String = cookies().joinToString("; ") { "${it.name}=${it.value}" }

    private fun post(client: HttpClient, e2e: E2e, cookies: String, token: String, path: String, fields: Map<String, String>): Answer {
        val body = (fields + ("_csrf" to token)).entries.joinToString("&") { URLEncoder.encode(it.key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(it.value, StandardCharsets.UTF_8) }
        val request = HttpRequest.newBuilder(URI.create(e2e.url(path))).header("Cookie", cookies).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Answer(response.statusCode(), response.body())
    }

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t20 c20 two teachers who write the same name of a form at the same moment leave one, with a 303 for the first and the error of the name for the second, and the boxes of the first are the ones that are kept`(e2e: E2e) {
        val w = e2e.arrivalsGathering()
        val venue = e2e.venueOf(w.id)
        val other = e2e.newPage()
        e2e.open("/strutture/$venue/moduli/nuovo")
        val mine = e2e.page.csrfToken()
        val mineCookies = e2e.browserContext.cookieHeader()
        other.navigate(e2e.url("/strutture/$venue/moduli/nuovo"))
        val theirs = other.csrfToken()
        val theirCookies = other.context().cookieHeader()
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(6) { round ->
                val gate = CountDownLatch(1)
                val name = "Registro tondo $round"
                val first = pool.submit<Answer> { gate.await(); post(client, e2e, mineCookies, mine, "/strutture/$venue/moduli/nuovo", mapOf("name" to name, "withAge" to "true")) }
                val second = pool.submit<Answer> { gate.await(); post(client, e2e, theirCookies, theirs, "/strutture/$venue/moduli/nuovo", mapOf("name" to "  ${name.uppercase()} ", "withDays" to "true")) }
                gate.countDown()
                val answers = listOf(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))
                assertEquals(listOf(303, 422), answers.map { it.status }.sorted(), "round $round: ${answers.map { it.status }}")
                assertTrue(answers.single { it.status == 422 }.body.contains("C’è già un modulo con questo nome."), "round $round")
                assertEquals(1, e2e.dsl().fetchCount(VENUE_FORM, VENUE_FORM.VENUE_ID.eq(venue).and(org.jooq.impl.DSL.lower(org.jooq.impl.DSL.trim(VENUE_FORM.NAME)).eq(name.lowercase()))), "round $round: one row")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- Criteria 29, 30 and 31: the fields of the teachers, looked at again --------------------------------------------------------------------------

    @E2eTest
    fun `t20 c29 c30 c31 the fields of the teachers have no form around them, take no more than their limit, are untouched by Enter, Tab and the button Stampa, are emptied by a page that comes back from the cache and not by one that stays, and no request in any page of the browser carries a value`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.present(w, 3)
        val id = e2e.form(w.id, "Registro per la tassa di soggiorno", age = true, days = true, document = true, taxCode = true)
        val values = listOf("DOCPROVA123", "CFPROVA000000001", "NOMEPROVA")
        val requests = CopyOnWriteArrayList<String>()
        e2e.browserContext.onRequest { requests += "${it.method()} ${it.url()} ${it.headers()} ${it.postData()}" }
        e2e.open("/arrivi/modulo/$id")
        val fields = page.locator("input[data-solo-carta]")
        val count = fields.count()
        assertEquals(13, count)

        // Not one of them has a form around it, or a name, and the browser has nothing to send: the properties that the browser itself reads.
        assertEquals(
            List(count) { "null|null|null|off|false" },
            fields.evaluateAll("els => els.map(e => [String(e.closest('form')), String(e.form), String(e.getAttribute('name')), e.getAttribute('autocomplete'), String(e.spellcheck)].join('|'))") as List<*>,
        )
        assertEquals(0, page.locator("form:has(input[data-solo-carta])").count())

        // The limits are the ones of the cell: what is typed past them is not taken (20, 16 and 60 characters), and the typing is by keys, as on a keyboard.
        val limits = mapOf("20" to 20, "16" to 16, "60" to 60)
        for (index in 0 until count) {
            val field = fields.nth(index)
            val limit = limits.getValue(field.getAttribute("maxlength")!!)
            field.scrollIntoViewIfNeeded()
            field.click()
            page.keyboard().type("X".repeat(limit + 15))
            assertEquals(limit, (field.inputValue()).length, "the field ${field.getAttribute("aria-label")} took more than its limit")
            field.fill(values[limits.keys.indexOf(field.getAttribute("maxlength")!!)])
            page.keyboard().press("Enter")
            page.keyboard().press("Tab")
        }
        val written = fields.evaluateAll("els => els.map(e => e.value)") as List<*>
        assertTrue(written.none { it.toString().isEmpty() })
        assertTrue(page.url().endsWith("/arrivi/modulo/$id"), "Enter and Tab changed the page: ${page.url()}")
        val beforePrint = requests.size

        // The button «Stampa» (window.print is counted, not shown): it prints and does nothing else.
        page.evaluate("() => { window.__printed = 0; window.print = () => { window.__printed += 1; }; }")
        e2e.press(page.locator("main button[data-azione=stampa]"))
        assertEquals(1, (page.evaluate("window.__printed") as Number).toInt(), "the button prints")
        assertEquals(written, fields.evaluateAll("els => els.map(e => e.value)") as List<*>, "the button did not touch the fields")
        // A page that stays (a restore that is not from the cache) keeps them; one that comes back from the cache of the browser is emptied: the event that the browser sends is sent here.
        page.evaluate("() => window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: false }))")
        assertEquals(written, fields.evaluateAll("els => els.map(e => e.value)") as List<*>, "a page that was not in the cache lost what was written")
        page.evaluate("() => window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))")
        assertEquals(List(count) { "" }, fields.evaluateAll("els => els.map(e => e.value)") as List<*>, "a page that came back from the cache kept what was written")

        // Written once more, nothing was sent in all this time, in any request of any page of the browser, and nothing is in the storage.
        for (index in 0 until count) fields.nth(index).fill(values[index % values.size])
        page.keyboard().press("Enter")
        assertEquals(beforePrint, requests.size, "a request went out while the fields were written or the page was printed: ${requests.drop(beforePrint)}")
        for (value in values) assertTrue(requests.none { it.contains(value) }, "«$value» is in a request")
        assertTrue(requests.none { it.startsWith("POST") || it.startsWith("PUT") }, "a request that changes something went out: ${requests.filter { it.startsWith("POST") }}")
        val storage = page.evaluate("async () => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage }, databases: (indexedDB.databases ? await indexedDB.databases() : []), cookie: document.cookie, caches: (window.caches ? await caches.keys() : []) })") as String
        for (value in values) assertFalse(storage.contains(value), "«$value» is in the storage of the page: $storage")
        assertEquals(0, (page.evaluate("async () => (navigator.serviceWorker ? (await navigator.serviceWorker.getRegistrations()).length : 0)") as Number).toInt(), "a service worker could keep what is written")
    }

    // ---- Criterion 25: the sheets as Chromium prints them, in sizes ---------------------------------------------------------------------------------

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t20 c25 the sheets that Chromium prints are as many as the caption says, with all the children once, the header on every sheet and the teachers under the last, at the sizes where the table of the teachers moves to a sheet of its own and where it does not`(e2e: E2e) {
        val page = e2e.page
        val folder = Files.createTempDirectory("talita-stampa-qa")
        try {
            // (days, with the fields of the teachers, children): around the capacities of the sheets (20 and 26, 19 and 25) and the eight rows that the teachers need.
            val cases = listOf(
                Triple(3, false, listOf(18, 21, 46, 52)),
                Triple(3, true, listOf(20, 21, 44, 46)),
                Triple(7, true, listOf(19, 20, 44, 45)),
            )
            for ((days, fields, sizes) in cases) {
                for (size in sizes) {
                    val w = e2e.arrivalsGathering(first = -1, last = (days - 2).toLong())
                    e2e.present(w, size)
                    val id = e2e.form(w.id, "Registro", age = true, days = true, document = fields, taxCode = fields)
                    e2e.open("/arrivi/modulo/$id")
                    val announced = Regex("pagina \\d+ di (\\d+)").find(page.locator("caption:not(.sr-only)").first().innerText())!!.groupValues[1].toInt()
                    val pdf = folder.resolve("d$days-$fields-$size.pdf")
                    page.pdf(Page.PdfOptions().setPath(pdf).setPreferCSSPageSize(true).setPrintBackground(false))
                    val sheets = sheetsOf(pdf)
                    assumeTrue(sheets != null, "pdftotext is not installed: the sheets of the PDF are not read")
                    val label = "$days days, fields $fields, $size children"
                    assertEquals(announced, sheets!!.size, "$label: the caption says $announced sheets and Chromium prints ${sheets.size}")
                    assertEquals(size, sheets.sumOf { Regex("Prova\\d\\d").findAll(it).count() }, "$label: every child is once on the sheets")
                    val text = sheets.joinToString("\n")
                    for (number in 1..size) assertEquals(1, Regex("Prova%02d".format(number)).findAll(text).count(), "$label: Prova%02d is once".format(number))
                    assertTrue(sheets.all { Regex("Cognome\\s+e\\b").containsMatchIn(it) }, "$label: every sheet has the header of its table")
                    assertTrue(sheets.last().contains("Maestri") && sheets.last().contains("Bianchi") && sheets.last().contains("Verdi"), "$label: the teachers are on the last sheet")
                    (1..announced).forEach { number -> assertTrue(text.contains("pagina $number di $announced"), "$label: the caption «pagina $number di $announced» is on the paper") }
                }
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    // ---- Criterion 27: the two notices, as a person reads them ---------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `t20 c27 the short notice of the second step and the complete one say what the venue receives on paper, the age and not the date of birth, the days, and that Talità does not receive nor keep the document and the tax code`(e2e: E2e) {
        val page = e2e.page
        val code = e2e.openGathering().code
        e2e.open("/info/$code/iscrizione")
        page.settled()
        page.locator("#telefono").fill("333 123 4567")
        page.locator("#b0-nome").fill("Luca")
        page.locator("#b0-cognome").fill("Rossi")
        page.locator("#b0-giorno").fill("4")
        page.locator("#b0-mese").fill("4")
        page.locator("#b0-anno").fill("2016")
        page.locator("input[name='children[0].presence'][value='all_days']").check()
        e2e.press(page.locator("main").buttonNamed("Controlla e accetta"))
        assertThat(page.locator("h1")).hasText("Controlla e accetta")
        val short = page.locator("main").innerText().replace(Regex("\\s+"), " ")
        assertTrue(
            short.contains(
                "I maestri della convivenza. La struttura che ospita la convivenza riceve su carta da Talità il nome e il cognome dei bambini e dei maestri affidatari. Se il suo modulo lo chiede, riceve anche l’età dei bambini il primo giorno della convivenza (non la data di nascita) e i giorni in cui sono presenti. Nient’altro.",
            ),
            short,
        )
        assertTrue(page.noHorizontalScrollAt320(e2e))

        e2e.open("/informativa")
        val complete = page.locator("main").innerText().replace(Regex("\\s+"), " ")
        for (sentence in listOf(
            "La struttura che ospita la convivenza riceve su carta da Talità un elenco con il nome e il cognome dei bambini e dei maestri affidatari.",
            "Se il suo modulo lo chiede, nell’elenco ci sono anche l’età dei bambini il primo giorno della convivenza (non la data di nascita) e i giorni in cui sono presenti. Nient’altro.",
            "Se il suo modulo lo chiede, al momento della stampa un maestro scrive sul foglio anche il documento d’identità e il codice fiscale dei maestri affidatari.",
            "Talità non li riceve e non li conserva.",
        )) {
            assertTrue(complete.contains(sentence), "the complete notice does not say: $sentence")
        }
        assertTrue(page.noHorizontalScrollAt320(e2e))
        assertAccessible(page, "complete notice (${e2e.profile.label})")
    }

    // ---- Criteria 1 and 20: the list of the venues and the card of the forms ------------------------------------------------------------------------

    @E2eTest
    fun `t20 c01 c20 the list of the venues and the page of the venue say how many forms there are, with what each one has, and taking the venue away says that its rooms and its forms go with it`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val support = GatheringSupport(e2e.dsl(), e2e.application.getBean(Clock::class.java))
        val venue = e2e.venueOf(w.id)
        e2e.form(w.id, "Registro per la tassa di soggiorno", age = true, days = true, document = true, taxCode = true)
        e2e.form(w.id, "Registro presenze", days = true)
        val free = support.makeVenue("Casa per ritiri sul lago, Località Esempio", "Casa sul lago")
        e2e.dsl().insertInto(ROOM).set(ROOM.VENUE_ID, free).set(ROOM.NAME, "Camera 1").set(ROOM.BEDS, 4.toShort()).execute()
        e2e.dsl().insertInto(VENUE_FORM).set(VENUE_FORM.VENUE_ID, free).set(VENUE_FORM.NAME, "Solo nomi").execute()

        e2e.open("/strutture")
        assertThat(page.locator("main ul li").filter(Locator.FilterOptions().setHasText("Casa sul lago"))).containsText("1 camera, 4 posti · 1 modulo")
        assertThat(page.locator("main ul li").filter(Locator.FilterOptions().setHasText("Nessuna camera"))).containsText("2 moduli")
        assertTrue(page.noHorizontalScrollAt320(e2e))
        assertAccessible(page, "list of the venues with the forms (${e2e.profile.label})")

        e2e.open("/strutture/$venue")
        val card = page.locator("#moduli-struttura")
        assertThat(card).containsText("Documenti che la struttura chiede. Talità li stampa da Arrivi, con i bambini presenti. Li vedono solo i maestri.")
        assertThat(card).containsText("Nome e cognome, età al primo giorno e giorni di presenza. Maestri: documento e codice fiscale.")
        assertThat(card).containsText("Nome e cognome, giorni di presenza.")
        assertTrue(page.noHorizontalScrollAt320(e2e))
        assertAccessible(page, "venue with two forms (${e2e.profile.label})")

        e2e.open("/strutture/$free")
        e2e.press(page.locator("main").linkNamed("Togli la struttura"))
        page.waitForSettledDialog()
        assertThat(page.openDialog()).containsText("Si cancellano anche la sua camera e il suo modulo.")
        assertTrue(page.noHorizontalScrollAt320(e2e))
    }

    // ---- Criterion 30: without JavaScript -------------------------------------------------------------------------------------------------------

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t20 c30 without JavaScript the fields of the teachers work, Enter sends nothing, the button Stampa is not there, the page is not kept, and the PDF that the browser makes has what was written`(e2e: E2e) {
        val w = e2e.arrivalsGathering()
        e2e.present(w, 2)
        val id = e2e.form(w.id, "Registro per la tassa di soggiorno", age = true, days = true, document = true, taxCode = true)
        val plain = e2e.newPage(customize = { setJavaScriptEnabled(false) })
        val requests = CopyOnWriteArrayList<String>()
        plain.context().onRequest { requests += "${it.method()} ${it.url()} ${it.postData()}" }
        val folder = Files.createTempDirectory("talita-stampa-senza-js")
        try {
            val response = plain.navigate(e2e.url("/arrivi/modulo/$id"))!!
            plain.settled()
            assertTrue(response.headers()["cache-control"].orEmpty().contains("no-store"), "the sheet is kept by a cache: ${response.headers()["cache-control"]}")
            assertEquals(0, plain.locator("button[data-azione=stampa]:visible").count(), "without JavaScript there is no button that prints")
            assertThat(plain.locator("main")).containsText("Stampa su A4, in verticale.")
            val fields = plain.locator("input[data-solo-carta]")
            assertEquals(13, fields.count())
            val before = requests.size
            for (index in 0 until fields.count()) {
                val field = fields.nth(index)
                field.fill(when (field.getAttribute("maxlength")) { "20" -> "DOCPROVA123"; "16" -> "CFPROVA000000001"; else -> "NOMEPROVA" })
                field.press("Enter")
            }
            assertEquals(before, requests.size, "a request went out: ${requests.drop(before)}")
            assertTrue(plain.url().endsWith("/arrivi/modulo/$id"))
            val pdf = folder.resolve("senza-js.pdf")
            plain.pdf(Page.PdfOptions().setPath(pdf).setPreferCSSPageSize(true).setPrintBackground(false))
            val sheets = sheetsOf(pdf)
            assumeTrue(sheets != null, "pdftotext is not installed: the PDF is not read")
            val text = sheets!!.joinToString("\n")
            assertEquals(5, Regex("DOCPROVA123").findAll(text).count())
            assertEquals(5, Regex("CFPROVA000000001").findAll(text).count())
            assertEquals(3, Regex("NOMEPROVA").findAll(text).count())
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    // ---- Criterion 20: the name of a form ---------------------------------------------------------------------------------------------------------

    @E2eTest
    fun `t20 c20 the name of a form refuses an email, a phone, an IBAN and the name of a person with the sentence that says to write only the name of the document, and the lengths and the name that exists have their own words`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val venue = e2e.venueOf(w.id)
        e2e.rows().register(w.id, e2e.someParent(), "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        e2e.form(w.id, "Registro per la tassa di soggiorno")
        val closing = " Toglilo: scrivi solo il nome del documento."
        val cases = listOf(
            "Registro, tel. 333 123 4567" to "Qui c’è un numero di telefono.$closing",
            "Registro di nome@example.org" to "Qui c’è un indirizzo email.$closing",
            "Registro IT60 X054 2811 1010 0000 0123 456" to "Qui c’è un IBAN.$closing",
            "Registro di Luca Rossi" to "Qui ci sono nome e cognome di una persona. Toglili: scrivi solo il nome del documento.",
            "Registro di Marta Bianchi" to "Qui ci sono nome e cognome di una persona. Toglili: scrivi solo il nome del documento.",
            "x".repeat(61) to "Il nome può avere al massimo 60 caratteri: ne hai scritti 61.",
            "  REGISTRO PER LA TASSA DI SOGGIORNO " to "C’è già un modulo con questo nome.",
            "   " to "Scrivi il nome del modulo.",
        )
        val before = e2e.dsl().fetchCount(VENUE_FORM)
        for ((name, sentence) in cases) {
            e2e.open("/strutture/$venue/moduli/nuovo")
            page.getByLabel("Nome del modulo").fill(name)
            page.getByLabel("Giorni di presenza").check()
            e2e.press(page.locator("main").buttonNamed("Salva il modulo"))
            assertThat(page.locator("#nome-modulo-errore")).hasText(sentence)
            assertThat(page.getByLabel("Giorni di presenza")).isChecked()
            page.focusIs("document.activeElement.id === 'nome-modulo'")
            assertEquals(before, e2e.dsl().fetchCount(VENUE_FORM), "«$name» saved something")
        }
        // The limit is 60: a name of 60 characters is saved, with the spaces at the sides taken off, and with no box ticked it has only the names.
        e2e.open("/strutture/$venue/moduli/nuovo")
        page.getByLabel("Nome del modulo").fill(" " + "y".repeat(60) + " ")
        e2e.press(page.locator("main").buttonNamed("Salva il modulo"))
        assertThat(page.locator("#moduli-struttura")).containsText("Modulo salvato.")
        assertEquals(listOf(false, false, false, false), e2e.dsl().select(VENUE_FORM.WITH_AGE, VENUE_FORM.WITH_DAYS, VENUE_FORM.WITH_TEACHER_DOCUMENT, VENUE_FORM.WITH_TEACHER_TAX_CODE).from(VENUE_FORM).where(VENUE_FORM.NAME.eq("y".repeat(60))).fetchSingle().intoList())
        assertThat(page.locator("#moduli-struttura")).containsText("Solo nome e cognome.")
        assertTrue(page.noHorizontalScrollAt320(e2e))
    }

    // ---- A name of 60 letters in one word ------------------------------------------------------------------------------------------------------------

    @E2eTest
    fun `t20 c20 c22 c25 a name of a form of 60 wide letters in one word wraps in every page that says it, with no sideways scrolling at 320 px, and does not change how many sheets the paper has`(e2e: E2e) {
        val page = e2e.page
        val long = "W".repeat(60)
        val w = e2e.arrivalsGathering(first = -1, last = 5)
        e2e.present(w, 19)
        val id = e2e.form(w.id, long, age = true, days = true, document = true, taxCode = true)
        val venue = e2e.venueOf(w.id)
        for ((label, path) in listOf("the venue" to "/strutture/$venue", "change the form" to "/strutture/$venue/moduli/$id/modifica", "Da stampare" to "/arrivi", "the sheet" to "/arrivi/modulo/$id", "the dialog of taking the form away" to "/strutture/$venue/moduli/$id/togli")) {
            e2e.open(path)
            page.settled()
            assertTrue(page.noHorizontalScroll(), "$label scrolls sideways")
            assertTrue(page.noHorizontalScrollAt320(e2e), "$label scrolls sideways at 320 px")
            assertAccessible(page, "$label with a name of 60 letters (${e2e.profile.label})")
        }
        if (e2e.profile == Profile.DESKTOP) {
            val folder = Files.createTempDirectory("talita-stampa-nome-lungo")
            try {
                e2e.open("/arrivi/modulo/$id")
                val announced = Regex("pagina \\d+ di (\\d+)").find(page.locator("caption:not(.sr-only)").first().innerText())!!.groupValues[1].toInt()
                val pdf = folder.resolve("nome-lungo.pdf")
                page.pdf(Page.PdfOptions().setPath(pdf).setPreferCSSPageSize(true).setPrintBackground(false))
                val sheets = sheetsOf(pdf)
                assumeTrue(sheets != null, "pdftotext is not installed: the PDF is not read")
                assertEquals(announced, sheets!!.size, "the caption says $announced sheets and Chromium prints ${sheets.size}")
                assertEquals(19, sheets.sumOf { Regex("Prova\\d\\d").findAll(it).count() })
            } finally {
                folder.toFile().deleteRecursively()
            }
        }
    }
}
