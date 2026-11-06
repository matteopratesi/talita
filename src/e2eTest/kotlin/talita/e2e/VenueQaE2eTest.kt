package talita.e2e

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.RequestOptions
import org.springframework.boot.test.system.CapturedOutput
import talita.CapturesOutput
import talita.account.TestAccounts
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.ROOM
import talita.jooq.tables.references.ROOM_CHILD
import talita.jooq.tables.references.ROOM_TEACHER
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.TEACHER_INVITATION
import talita.jooq.tables.references.VENUE
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The QA of TALITA-20, parts 1 and 2 (the venues and their rooms), on the real interface and on the real stack: what a parent gets on every address of the venues, two teachers who write the same name at the same
 * moment, the words that a public text refuses in both fields, the text of the custody that a parent has open while the place of the venue changes, a room that is changed during the gathering, a venue that
 * is taken away, what the parents read in each state of the gathering, and what the log does not say. Names, places and numbers are synthetic.
 */
@E2eApplication
class VenueQaE2eTest {

    private val woodPlace = "Casa per ritiri del bosco, Località Esempio"
    private val lakePlace = "Casa per ritiri sul lago, Località Esempio"

    // ---- The world of the tests -----------------------------------------------------------------------------------------------------------------

    /** Only the person of the test is a teacher (Elisa Conti), with Marta Bianchi; no gathering, no venue. Returns the account of the person, and the support to make the rest. */
    private fun E2e.onlyMe(): Pair<Long, GatheringSupport> {
        dsl().deleteFrom(TEACHER_INVITATION).execute()
        val support = GatheringSupport(dsl(), application.getBean(Clock::class.java))
        support.reset()
        dsl().deleteFrom(TEACHER).execute()
        val me = TestAccounts.makeTeacher(dsl(), checkNotNull(signedInAs), "Elisa", "Conti")
        TestAccounts.makeTeacher(dsl(), talita.Addresses.unique("qa-t20-marta"), "Marta", "Bianchi")
        return me to support
    }

    private fun E2e.room(venue: Long, name: String, beds: Int): Long =
        dsl().insertInto(ROOM).set(ROOM.VENUE_ID, venue).set(ROOM.NAME, name).set(ROOM.BEDS, beds.toShort()).returningResult(ROOM.ID).fetchSingle().value1()!!

    private fun E2e.venueOf(gathering: Long): Long = dsl().select(GATHERING.VENUE_ID).from(GATHERING).where(GATHERING.ID.eq(gathering)).fetchSingle(GATHERING.VENUE_ID)!!

    private fun E2e.putChild(registration: Long, gathering: Long, room: Long) {
        dsl().insertInto(ROOM_CHILD).set(ROOM_CHILD.REGISTRATION_ID, registration).set(ROOM_CHILD.GATHERING_ID, gathering).set(ROOM_CHILD.VENUE_ID, venueOf(gathering)).set(ROOM_CHILD.ROOM_ID, room).execute()
    }

    private fun E2e.putTeacher(account: Long, gathering: Long, room: Long) {
        dsl().insertInto(ROOM_TEACHER).set(ROOM_TEACHER.GATHERING_ID, gathering).set(ROOM_TEACHER.VENUE_ID, venueOf(gathering)).set(ROOM_TEACHER.TEACHER_ACCOUNT_ID, account).set(ROOM_TEACHER.ROOM_ID, room).execute()
    }

    /** What the tables of the venues and of the rooms hold: a change by hand that was refused leaves it as it was. */
    private fun E2e.snapshot(): List<String> =
        dsl().select(VENUE.ID, VENUE.NAME, VENUE.PLACE).from(VENUE).orderBy(VENUE.ID).fetch().map { "venue ${it.value1()} ${it.value2()} ${it.value3()}" } +
            dsl().select(ROOM.ID, ROOM.VENUE_ID, ROOM.NAME, ROOM.BEDS).from(ROOM).orderBy(ROOM.ID).fetch().map { "room ${it.value1()} ${it.value2()} ${it.value3()} ${it.value4()}" } +
            dsl().select(GATHERING.ID, GATHERING.VENUE_ID, GATHERING.TITLE).from(GATHERING).orderBy(GATHERING.ID).fetch().map { "gathering ${it.value1()} ${it.value2()} ${it.value3()}" } +
            dsl().select(ROOM_CHILD.REGISTRATION_ID, ROOM_CHILD.ROOM_ID).from(ROOM_CHILD).orderBy(ROOM_CHILD.REGISTRATION_ID).fetch().map { "child ${it.value1()} ${it.value2()}" } +
            dsl().select(ROOM_TEACHER.TEACHER_ACCOUNT_ID, ROOM_TEACHER.ROOM_ID).from(ROOM_TEACHER).orderBy(ROOM_TEACHER.TEACHER_ACCOUNT_ID).fetch().map { "teacher ${it.value1()} ${it.value2()}" }

    private fun Page.csrfToken(): String = requireNotNull(locator("input[type=hidden][name=_csrf]").first().getAttribute("value"))

    /** A request the way a form or htmx sends it, from the page of the person (so with their cookies), by hand: the status and the text of the answer. Redirects are not followed. */
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

    // ---- Criterion 7: a parent on every address, whoever has not entered, nothing changes --------------------------------------------------------

    @E2eTest(profiles = [Profile.DESKTOP], teacher = false)
    fun `t20 c07 a parent is told 403 on every address of the venues and of the rooms of the venue, by hand with a page or with a form, sees no name or place, and nothing changes`(e2e: E2e) {
        val page = e2e.page
        val support = GatheringSupport(e2e.dsl(), e2e.application.getBean(Clock::class.java)).also { it.reset() }
        val wood = support.makeVenue(woodPlace, "Casa del bosco")
        val azzurra = e2e.room(wood, "Camera azzurra", 4)
        val gathering = support.make(status = GatheringStatus.draft, venue = wood)
        val before = e2e.snapshot()
        assertTrue(before.isNotEmpty())

        e2e.open("/profilo")
        val token = page.csrfToken()
        val pages = listOf(
            "/strutture", "/strutture/nuova", "/strutture/$wood", "/strutture/$wood/modifica", "/strutture/$wood/togli",
            "/strutture/$wood/camere/nuova", "/strutture/$wood/camere/$azzurra/modifica", "/strutture/$wood/camere/$azzurra/togli",
            "/convivenza/$gathering/date", "/convivenza/$gathering/camere",
        )
        val forms = listOf(
            "/strutture/nuova" to mapOf("name" to "Casa sul lago", "place" to lakePlace),
            "/strutture/$wood/modifica" to mapOf("name" to "Cambiata", "place" to "Cambiato"),
            "/strutture/$wood/togli" to emptyMap(),
            "/strutture/$wood/camere/nuova" to mapOf("name" to "Camera 2", "beds" to "3"),
            "/strutture/$wood/camere/$azzurra/modifica" to mapOf("name" to "Cambiata", "beds" to "1"),
            "/strutture/$wood/camere/$azzurra/togli" to emptyMap(),
            "/convivenza/$gathering/date" to mapOf("title" to "Cambiato", "venue" to "$wood"),
        )
        val secrets = listOf("Casa del bosco", "Camera azzurra", "Località Esempio", "Convivenza di settembre 2027")
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
        // The paths that are not the ones of the pages say 403 too, not a page: a parent never learns which venue exists.
        for (path in listOf("/strutture/9999999", "/strutture/$wood/camere/9999999/modifica", "/strutture/abc")) {
            assertTrue(page.byHand(token, "GET", path).first in setOf(403, 404), "GET $path")
        }
        assertEquals(before, e2e.snapshot(), "nothing was changed")

        // Whoever has not entered goes to the access page, with a page and with a form; nothing changes.
        val anonymous = e2e.newPage(signedIn = false)
        for (path in pages) {
            val answer = anonymous.request().get(e2e.url(path), RequestOptions.create().setMaxRedirects(0))
            assertEquals(302, answer.status(), "anonymous GET $path")
            assertTrue(answer.headers()["location"].orEmpty().endsWith("/accedi"), "anonymous GET $path goes to ${answer.headers()["location"]}")
        }
        for ((path, fields) in forms) {
            val form = fields.entries.fold(com.microsoft.playwright.options.FormData.create()) { acc, entry -> acc.set(entry.key, entry.value) }
            val answer = anonymous.request().post(e2e.url(path), RequestOptions.create().setMaxRedirects(0).setForm(form))
            assertEquals(302, answer.status(), "anonymous POST $path")
            assertTrue(answer.headers()["location"].orEmpty().endsWith("/accedi"), "anonymous POST $path goes to ${answer.headers()["location"]}")
        }
        assertEquals(before, e2e.snapshot(), "nothing was changed by whoever has not entered")
    }

    // ---- Criteria 2 and 13: two teachers at the same moment -------------------------------------------------------------------------------------

    private data class Answer(val status: Int, val body: String)

    private fun BrowserContext.cookieHeader(): String = cookies().joinToString("; ") { "${it.name}=${it.value}" }

    private fun post(client: HttpClient, e2e: E2e, cookies: String, token: String, path: String, fields: Map<String, String>): Answer {
        val body = (fields + ("_csrf" to token)).entries.joinToString("&") { URLEncoder.encode(it.key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(it.value, StandardCharsets.UTF_8) }
        val request = HttpRequest.newBuilder(URI.create(e2e.url(path))).header("Cookie", cookies).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Answer(response.statusCode(), response.body())
    }

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t20 c02 c13 two teachers who write the same name of a venue, or of a room, at the same moment leave one, and the second is told that the name is taken with no error`(e2e: E2e) {
        val (_, support) = e2e.onlyMe()
        val other = e2e.newPage()
        val wood = support.makeVenue(woodPlace, "Casa del bosco")
        e2e.open("/strutture/nuova")
        val mine = e2e.page.csrfToken()
        val mineCookies = e2e.browserContext.cookieHeader()
        other.navigate(e2e.url("/strutture/nuova"))
        val theirs = other.csrfToken()
        val theirCookies = other.context().cookieHeader()
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
        val pool = Executors.newFixedThreadPool(2)
        try {
            // The same name in each round, written with other capitals and spaces at the sides by the second teacher: the answers are one «saved» and one «taken».
            repeat(6) { round ->
                val gate = CountDownLatch(1)
                val venueName = "Casa tonda $round"
                val first = pool.submit<Answer> { gate.await(); post(client, e2e, mineCookies, mine, "/strutture/nuova", mapOf("name" to venueName, "place" to "Un luogo di prova")) }
                val second = pool.submit<Answer> { gate.await(); post(client, e2e, theirCookies, theirs, "/strutture/nuova", mapOf("name" to "  ${venueName.uppercase()} ", "place" to "Un altro luogo")) }
                gate.countDown()
                val answers = listOf(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))
                assertEquals(listOf(303, 422), answers.map { it.status }.sorted(), "venue round $round: $answers")
                assertTrue(answers.single { it.status == 422 }.body.contains("C’è già una struttura con questo nome."), "venue round $round")
                assertEquals(1, e2e.dsl().fetchCount(VENUE, org.jooq.impl.DSL.lower(org.jooq.impl.DSL.trim(VENUE.NAME)).eq(venueName.lowercase())), "venue round $round: one row")

                val roomName = "Camera tonda $round"
                val roomGate = CountDownLatch(1)
                val roomFirst = pool.submit<Answer> { roomGate.await(); post(client, e2e, mineCookies, mine, "/strutture/$wood/camere/nuova", mapOf("name" to roomName, "beds" to "4")) }
                val roomSecond = pool.submit<Answer> { roomGate.await(); post(client, e2e, theirCookies, theirs, "/strutture/$wood/camere/nuova", mapOf("name" to " ${roomName.uppercase()}", "beds" to "2")) }
                roomGate.countDown()
                val roomAnswers = listOf(roomFirst.get(20, TimeUnit.SECONDS), roomSecond.get(20, TimeUnit.SECONDS))
                assertEquals(listOf(303, 422), roomAnswers.map { it.status }.sorted(), "room round $round: ${roomAnswers.map { it.status }}")
                assertTrue(roomAnswers.single { it.status == 422 }.body.contains("C’è già una camera con questo nome."), "room round $round")
                assertEquals(1, e2e.dsl().fetchCount(ROOM, ROOM.VENUE_ID.eq(wood).and(org.jooq.impl.DSL.lower(org.jooq.impl.DSL.trim(ROOM.NAME)).eq(roomName.lowercase()))), "room round $round: one row")
            }
        } finally {
            pool.shutdownNow()
        }
        // The same name of a room in another venue is not a collision.
        val lake = support.makeVenue(lakePlace, "Casa sul lago")
        e2e.open("/strutture/$lake/camere/nuova")
        e2e.page.locator("#nome").fill("Camera tonda 0")
        e2e.page.locator("#posti").fill("3")
        e2e.press(e2e.page.locator("main").buttonNamed("Salva la camera"))
        assertThat(e2e.page.locator("#camere-struttura [role=status]")).containsText("Camera salvata.")
        assertEquals(1, e2e.dsl().fetchCount(ROOM, ROOM.VENUE_ID.eq(lake)))
    }

    // ---- Criterion 3: the public text, in both fields, in the real form ---------------------------------------------------------------------------

    @E2eTest
    fun `t20 c03 the name and the place of a venue refuse an email, a phone, an IBAN and the first and last name of a child or of a teacher, in both fields, in the new and in the change form, and nothing is saved`(e2e: E2e) {
        val page = e2e.page
        val (_, support) = e2e.onlyMe()
        val wood = support.makeVenue(woodPlace, "Casa del bosco")
        support.make(status = GatheringStatus.draft, venue = wood)
        e2e.dsl().insertInto(CHILD).set(CHILD.FIRST_NAME, "Luca").set(CHILD.LAST_NAME, "Rossi").set(CHILD.BIRTH_DATE, LocalDate.of(2016, 4, 4)).execute()
        val same = " Toglilo: la pagina la legge chiunque abbia il link."
        val cases = listOf(
            "scrivi a nome@example.org" to "Qui c’è un indirizzo email.$same",
            "tel. 333 123 4567" to "Qui c’è un numero di telefono.$same",
            "IT60 X054 2811 1010 0000 0123 456" to "Qui c’è un IBAN.$same",
            "Casa di Luca Rossi" to "Qui ci sono nome e cognome di una persona. Toglili: la pagina la legge chiunque abbia il link.",
            "Casa di Marta Bianchi" to "Qui ci sono nome e cognome di una persona. Toglili: la pagina la legge chiunque abbia il link.",
            "Casa di Bianchi Marta" to "Qui ci sono nome e cognome di una persona. Toglili: la pagina la legge chiunque abbia il link.",
        )
        val before = e2e.snapshot()
        for ((form, buttonPath) in listOf("new" to "/strutture/nuova", "change" to "/strutture/$wood/modifica")) {
            for ((text, sentence) in cases) {
                for (field in listOf("nome", "luogo")) {
                    e2e.open(buttonPath)
                    page.locator("#nome").fill(if (field == "nome") text else "Casa nuova")
                    page.locator("#luogo").fill(if (field == "luogo") text else "Un luogo nuovo, Località Esempio")
                    e2e.press(page.locator("main").buttonNamed("Salva la struttura"))
                    assertThat(page.locator("#$field-errore")).hasText(sentence)
                    assertThat(page.locator("#$field")).hasValue(text)
                    assertThat(page.locator("#$field")).hasAttribute("aria-invalid", "true")
                    page.focusIs("document.activeElement.id === '$field'")
                }
            }
            // The help under both fields says where the text appears and what not to write.
            assertThat(page.locator("#nome-aiuto")).containsText("Compare nella pagina per i genitori e nell’anteprima del link.")
            assertThat(page.locator("#nome-aiuto")).containsText("Non scrivere nome e cognome di bambini o maestri, numeri di telefono né email.")
            assertThat(page.locator("#luogo-aiuto")).containsText("nel testo dell’affidamento.")
            assertThat(page.locator("#luogo-aiuto")).containsText("Non scrivere nome e cognome di bambini o maestri, numeri di telefono né email.")
            assertTrue(page.noHorizontalScrollAt320(e2e), "$form form with an error at 320 px")
            assertEquals(before, e2e.snapshot(), "$form: nothing was saved")
        }
        // Two findings in the same text: the plural, the name first.
        e2e.open("/strutture/nuova")
        page.locator("#nome").fill("Casa di Luca Rossi, tel. 333 123 4567")
        page.locator("#luogo").fill("Un luogo")
        e2e.press(page.locator("main").buttonNamed("Salva la struttura"))
        assertThat(page.locator("#nome-errore")).hasText("Qui ci sono nome e cognome di una persona e un numero di telefono. Toglili: la pagina la legge chiunque abbia il link.")
    }

    // ---- Criteria 4 and 5: the text of the custody that a parent has open while the venue changes ---------------------------------------------

    private fun Page.toStepTwo(e2e: E2e, code: String, first: String, last: String) {
        e2e.open("/info/$code/iscrizione")
        settled()
        locator("#telefono").fill("333 123 4567")
        locator("#b0-nome").fill(first)
        locator("#b0-cognome").fill(last)
        locator("#b0-giorno").fill("4")
        locator("#b0-mese").fill("4")
        locator("#b0-anno").fill("2016")
        locator("input[name='children[0].presence'][value='all_days']").check()
        e2e.press(locator("main").buttonNamed("Controlla e accetta"))
        assertThat(locator("h1")).hasText("Controlla e accetta")
    }

    private fun Page.tickTheThreeBoxes() {
        locator("#accetto").check()
        locator("#altro-genitore").check()
        locator("#convinzioni").check()
    }

    private fun E2e.changeVenue(teacher: Page, venue: Long, name: String?, place: String?) {
        teacher.navigate(url("/strutture/$venue/modifica"))
        teacher.settled()
        if (name != null) teacher.locator("#nome").fill(name)
        if (place != null) teacher.locator("#luogo").fill(place)
        press(teacher.locator("main").buttonNamed("Salva la struttura"))
        assertThat(teacher.locator("main [role=status][data-fuoco]")).containsText("Struttura salvata.")
    }

    private fun E2e.custodyTexts(): List<String> = dsl().select(REGISTRATION.CUSTODY_TEXT).from(REGISTRATION).orderBy(REGISTRATION.ID).fetch(REGISTRATION.CUSTODY_TEXT).map { it!! }

    @E2eTest(teacher = false)
    fun `t20 c04 c05 a parent with the text open finds the new place when a teacher changes it in the real form and nothing is saved, a new name does not disturb the confirmation, and the text that was accepted stays the one of then`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.openGathering()
        val venue = e2e.venueOf(gathering.id)
        val oldPlace = e2e.dsl().select(VENUE.PLACE).from(VENUE).where(VENUE.ID.eq(venue)).fetchSingle(VENUE.PLACE)!!
        val teacher = e2e.newPage(teacher = true)

        // Only the name changes while the text is open: the text of the custody has the place and not the name, so it is not an old text.
        page.toStepTwo(e2e, gathering.code, "Luca", "Rossi")
        assertThat(page.locator("main")).containsText(oldPlace)
        e2e.changeVenue(teacher, venue, "Casa del bosco", null)
        page.tickTheThreeBoxes()
        e2e.press(page.locator("main").buttonNamed("Accetta e iscrivi"))
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertEquals(1, e2e.custodyTexts().size, "the registration was saved")
        assertTrue(e2e.custodyTexts().single().contains(oldPlace), "with the place of the text that the parent read")

        // The place changes while the text is open: nothing is saved, the focus is on the notice, the new text is there, and the boxes are to tick again.
        page.toStepTwo(e2e, gathering.code, "Anna", "Rossi")
        page.tickTheThreeBoxes()
        e2e.changeVenue(teacher, venue, null, woodPlace)
        e2e.press(page.locator("main").buttonNamed("Accetta e iscrivi"))
        assertThat(page.locator("main [role=alert]")).containsText("Mentre leggevi, i maestri hanno cambiato il testo dell’affidamento (maestri affidatari, date o luogo).")
        assertThat(page.locator("main")).containsText(woodPlace)
        assertThat(page.locator("input[type=checkbox]:checked")).hasCount(0)
        assertEquals(1, e2e.custodyTexts().size, "nothing was saved")
        assertTrue(page.noHorizontalScrollAt320(e2e))
        page.tickTheThreeBoxes()
        e2e.press(page.locator("main").buttonNamed("Accetta e iscrivi"))
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        val texts = e2e.custodyTexts()
        assertEquals(2, texts.size)
        assertTrue(texts[0].contains(oldPlace) && !texts[0].contains(woodPlace))
        assertTrue(texts[1].contains(woodPlace) && !texts[1].contains(oldPlace))

        // The place changes once more: the pages read the new one at once and the texts that were accepted do not change.
        e2e.changeVenue(teacher, venue, "Casa sul lago", lakePlace)
        assertEquals(texts, e2e.custodyTexts(), "the accepted texts are what they were")
        val visitor = e2e.newPage(signedIn = false)
        visitor.navigate(e2e.url("/info/${gathering.code}"))
        assertThat(visitor.locator("section[aria-labelledby=quando]")).containsText("Casa sul lago")
        assertThat(visitor.locator("section[aria-labelledby=quando]")).containsText(lakePlace)
        assertThat(visitor.locator("meta[property='og:description']")).hasAttribute("content", java.util.regex.Pattern.compile(".*Casa sul lago · Casa per ritiri sul lago, Località Esempio"))
    }

    // ---- Criteria 15 and 16: a room that changes during the gathering ---------------------------------------------------------------------------

    @E2eTest
    fun `t20 c15 c16 a room that a teacher renames and shrinks in the page of the venue is read at once by the rooms of the gathering, by its print and by the card of the child, and one that is taken away leaves its people without a room`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val venue = e2e.venueOf(w.id)
        val one = e2e.room(venue, "Camera 1", 4)
        val luca = e2e.rows().register(w.id, e2e.someParent(), "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        val sara = e2e.rows().register(w.id, e2e.someParent(), "Sara", "Neri", LocalDate.of(2017, 3, 3))
        e2e.putChild(luca, w.id, one)
        e2e.putChild(sara, w.id, one)
        e2e.putTeacher(w.paoloId, w.id, one)

        e2e.open("/convivenza/${w.id}/camere")
        assertThat(page.locator("main")).containsText("Camera 1")
        assertThat(page.locator("main")).containsText("3 persone · 4 posti")

        // The change in the page of the venue: a new name, one bed.
        e2e.open("/strutture/$venue/camere/$one/modifica")
        assertThat(page.locator("#posti-aiuto")).containsText("Ora ci dormono 3 persone.")
        page.locator("#nome").fill("Camera azzurra")
        page.locator("#posti").fill("1")
        e2e.press(page.locator("main").buttonNamed("Salva la camera"))
        assertThat(page.locator("main [role=status][data-fuoco]")).containsText("Camera salvata.")

        e2e.open("/convivenza/${w.id}/camere")
        assertThat(page.locator("main")).containsText("Camera azzurra")
        assertThat(page.locator("main")).not().containsText("Camera 1")
        assertThat(page.locator("main")).containsText("3 persone · 1 posto")
        assertThat(page.locator("main")).containsText("Più persone dei posti. Puoi tenerle così, ma controlla.")
        assertThat(page.locator("#riepilogo")).containsText("Posti letto")
        assertThat(page.locator("#riepilogo")).containsText("1")
        assertTrue(page.noHorizontalScrollAt320(e2e))
        e2e.open("/convivenza/${w.id}/camere/stampa")
        assertThat(page.locator("main")).containsText("Camera azzurra")
        assertThat(page.locator("main")).not().containsText("Camera 1")
        e2e.open("/arrivi/$luca")
        assertThat(page.locator("section[aria-labelledby=camera-titolo]")).containsText("Camera azzurra")
        e2e.open("/convivenza/${w.id}")
        assertThat(page.locator("section[aria-labelledby=camere]")).containsText("1 camera · 1 posto")

        // Taken away with people in it: the dialog counts them, the people are without a room in the gathering, and the room is gone from the venue.
        e2e.open("/strutture/$venue")
        e2e.press(page.locator("#camere-struttura").locator("a, button").filter(Locator.FilterOptions().setHasText("Togli")).first())
        page.waitForSettledDialog()
        assertThat(page.openDialog().locator("h2")).hasText("Togliere Camera azzurra?")
        assertThat(page.openDialog()).containsText("Le 3 persone che ci dormono tornano fra quelle senza camera.")
        assertEquals(1, e2e.dsl().fetchCount(ROOM, ROOM.VENUE_ID.eq(venue)), "the dialog took nothing away")
        e2e.touch(page.openDialog().buttonNamed("Togli la camera"))
        assertThat(page.locator("#camere-struttura [role=status]")).containsText("Camera tolta.")
        assertEquals(0, e2e.dsl().fetchCount(ROOM, ROOM.VENUE_ID.eq(venue)))
        assertEquals(0, e2e.dsl().fetchCount(ROOM_CHILD) + e2e.dsl().fetchCount(ROOM_TEACHER))
        e2e.open("/arrivi/$luca")
        assertThat(page.locator("section[aria-labelledby=camera-titolo]")).containsText("Senza camera")
        e2e.open("/convivenza/${w.id}/camere")
        assertThat(page.locator("main")).containsText("Nessuna camera")
    }

    // ---- Criterion 6: a venue that is used and one that is not ------------------------------------------------------------------------------------

    @E2eTest
    fun `t20 c06 a request by hand to take away a venue that a gathering uses is refused with 409 and nothing changes, and one that no gathering uses goes with its rooms after the dialog that counts them`(e2e: E2e) {
        val page = e2e.page
        val (me, support) = e2e.onlyMe()
        val wood = support.makeVenue(woodPlace, "Casa del bosco")
        val lake = support.makeVenue(lakePlace, "Casa sul lago")
        e2e.room(wood, "Camera azzurra", 4)
        e2e.room(lake, "Camera azzurra", 4)
        e2e.room(lake, "Camera 2", 6)
        support.make(status = GatheringStatus.draft, venue = wood, custodians = listOf(me))

        e2e.open("/strutture/nuova")
        val token = page.csrfToken()
        val before = e2e.snapshot()
        for (htmx in listOf(false, true)) {
            val (status, body) = page.byHand(token, "POST", "/strutture/$wood/togli", htmx = htmx)
            assertEquals(409, status, "htmx $htmx")
            assertTrue(body.contains("Non è stato tolto niente. Questa struttura è usata da 1 convivenza."), "htmx $htmx")
        }
        assertEquals(before, e2e.snapshot(), "nothing was taken away")
        // The page that stands in for the dialog, for a venue that is used, goes back to the page of the venue and says why.
        e2e.open("/strutture/$wood/togli")
        assertThat(page).hasURL(e2e.url("/strutture/$wood"))
        assertThat(page.locator("main")).containsText("Questa struttura è usata da 1 convivenza, quindi non si toglie. Puoi cambiarle il nome e il luogo.")

        e2e.open("/strutture/$lake")
        e2e.press(page.locator("main").linkNamed("Togli la struttura"))
        page.waitForSettledDialog()
        val dialog = page.openDialog()
        assertThat(dialog.locator("h2")).hasText("Togliere Casa sul lago?")
        assertThat(dialog).containsText("Si cancellano anche le sue 2 camere.")
        assertTrue(page.noHorizontalScrollAt320(e2e))
        e2e.touch(dialog.buttonNamed("Togli la struttura"))
        assertThat(page).hasURL(e2e.url("/strutture"))
        assertThat(page.locator("main [role=status][data-fuoco]")).hasText("Struttura tolta.")
        assertEquals(0, e2e.dsl().fetchCount(ROOM, ROOM.VENUE_ID.eq(lake)), "the rooms went with the venue")
        assertEquals(1, e2e.dsl().fetchCount(ROOM, ROOM.VENUE_ID.eq(wood)), "the rooms of the other venue stayed")
        // A venue that is already gone: the same request is a 404, not an error.
        assertEquals(404, page.byHand(token, "POST", "/strutture/$lake/togli").first)
    }

    // ---- Criterion 10: what the parents read in each state of the gathering ----------------------------------------------------------------------

    @E2eTest
    fun `t20 c10 the page for the parents and the preview of the link say the name and the place while the registrations are open and closed, never the rooms, and neither of them once the gathering is over`(e2e: E2e) {
        val (me, support) = e2e.onlyMe()
        val wood = support.makeVenue(woodPlace, "Casa del bosco")
        e2e.room(wood, "Camera azzurra", 4)
        val start = support.today().plusDays(30)
        val code = "q7Vd_3KmZ0xRb-Ew"
        val gathering = support.make(status = GatheringStatus.`open`, venue = wood, start = start, end = start.plusDays(2), custodians = listOf(me), code = code)
        val visitor = e2e.newPage(signedIn = false)
        fun og(): String = visitor.locator("meta[property='og:description']").getAttribute("content").orEmpty()

        visitor.navigate(e2e.url("/info/$code"))
        visitor.settled()
        val where = visitor.locator("section[aria-labelledby=quando]")
        assertThat(where).containsText("Casa del bosco")
        assertThat(where).containsText(woodPlace)
        assertTrue(og().endsWith("Casa del bosco · $woodPlace"), og())
        for (never in listOf("Camera azzurra", "Camere", "Moduli")) assertFalse(visitor.locator("body").innerText().contains(never), "the page for the parents says «$never»")
        assertTrue(visitor.noHorizontalScrollAt320(e2e))
        assertAccessible(visitor, "page for the parents with the venue (${e2e.profile.label})")

        e2e.dsl().update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).where(GATHERING.ID.eq(gathering)).execute()
        visitor.navigate(e2e.url("/info/$code"))
        visitor.settled()
        assertThat(visitor.locator("section[aria-labelledby=quando]")).containsText("Casa del bosco")
        assertTrue(og().endsWith("Casa del bosco · $woodPlace · iscrizioni chiuse"), og())

        // The detail and the preview of the teacher have the venue (the link leads to its page) and the place.
        e2e.open("/convivenza/$gathering")
        assertThat(e2e.page.locator("section[aria-labelledby=quando]")).containsText(woodPlace)
        e2e.press(e2e.page.locator("section[aria-labelledby=quando]").linkNamed("Casa del bosco"))
        assertThat(e2e.page).hasURL(e2e.url("/strutture/$wood"))
        e2e.open("/convivenza/$gathering/anteprima")
        assertThat(e2e.page.locator("main")).containsText("Casa del bosco")
        assertThat(e2e.page.locator("main")).containsText(woodPlace)

        // Over: neither the name nor the place, in the page and in the preview of the link.
        e2e.dsl().update(GATHERING).set(GATHERING.START_DATE, support.today().minusDays(5)).set(GATHERING.END_DATE, support.today().minusDays(3)).where(GATHERING.ID.eq(gathering)).execute()
        visitor.navigate(e2e.url("/info/$code"))
        visitor.settled()
        val text = visitor.locator("body").innerText()
        assertFalse(text.contains("Casa del bosco") || text.contains("Località Esempio"), "a gathering that is over says its venue: $text")
        assertTrue(og().endsWith("Convivenza finita"), og())
        assertFalse(og().contains("Casa del bosco"))
        assertAccessible(visitor, "page for the parents, over (${e2e.profile.label})")
    }

    // ---- The loading states ---------------------------------------------------------------------------------------------------------------------------

    /** Holds the POST requests that match [pattern] (the server does not see them) until the test releases the route: the page is in its state of «waiting for the answer». */
    private fun Page.holdPosts(pattern: String): java.util.concurrent.atomic.AtomicReference<com.microsoft.playwright.Route?> {
        val held = java.util.concurrent.atomic.AtomicReference<com.microsoft.playwright.Route?>()
        route(pattern) { route -> if (route.request().method() == "POST") held.set(route) else route.resume() }
        return held
    }

    private fun Page.release(pattern: String, held: java.util.concurrent.atomic.AtomicReference<com.microsoft.playwright.Route?>) {
        checkNotNull(held.get()) { "the request was not held" }.resume()
        unroute(pattern)
    }

    private fun Page.waitForLabel(selector: String, label: String) =
        waitUntil("Array.from(document.querySelectorAll('$selector')).some(b => b.innerText.includes('$label'))")

    @E2eTest
    fun `t20 c02 c06 c13 while the answer is awaited the buttons of the forms and of the dialogs of the venues and of the rooms say Salvataggio, Rimozione and Tolgo, and a dialog stays open until the answer comes`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val support = GatheringSupport(e2e.dsl(), e2e.application.getBean(Clock::class.java))
        val used = e2e.venueOf(w.id)
        val free = support.makeVenue(lakePlace, "Casa sul lago")
        val roomUsed = e2e.room(used, "Camera 1", 4)
        e2e.putChild(e2e.rows().register(w.id, e2e.someParent(), "Luca", "Rossi", LocalDate.of(2016, 4, 4)), w.id, roomUsed)

        // «Salva la struttura».
        e2e.open("/strutture/nuova")
        page.locator("#nome").fill("Casa del bosco")
        page.locator("#luogo").fill(woodPlace)
        var pattern = "**/strutture/nuova"
        var held = page.holdPosts(pattern)
        e2e.press(page.locator("main").buttonNamed("Salva la struttura"))
        page.waitForLabel("main button", "Salvataggio…")
        assertEquals(0, e2e.dsl().fetchCount(VENUE, VENUE.NAME.eq("Casa del bosco")), "the server has not seen the request")
        page.release(pattern, held)
        assertThat(page.locator("main [role=status][data-fuoco]")).containsText("Struttura salvata.")

        // «Salva la camera».
        e2e.open("/strutture/$used/camere/nuova")
        page.locator("#nome").fill("Camera 2")
        page.locator("#posti").fill("3")
        pattern = "**/strutture/$used/camere/nuova"
        held = page.holdPosts(pattern)
        e2e.press(page.locator("main").buttonNamed("Salva la camera"))
        page.waitForLabel("main button", "Salvataggio…")
        page.release(pattern, held)
        assertThat(page.locator("#camere-struttura [role=status]")).containsText("Camera salvata.")

        // «Togli la camera» (with a person in it, so with its dialog): the dialog stays open and the button says «Tolgo…» until the answer comes.
        pattern = "**/strutture/$used/camere/$roomUsed/togli"
        e2e.press(page.locator("#camere-struttura").locator("a, button").filter(Locator.FilterOptions().setHasText("Togli")).first())
        page.waitForSettledDialog()
        held = page.holdPosts(pattern)
        e2e.touch(page.openDialog().buttonNamed("Togli la camera"))
        page.waitForLabel("dialog[open] button", "Tolgo…")
        assertEquals(1, page.openDialog().count(), "the dialog is open while the answer is awaited")
        assertEquals(1, e2e.dsl().fetchCount(ROOM, ROOM.ID.eq(roomUsed)), "nothing was taken away yet")
        page.release(pattern, held)
        assertThat(page.locator("#camere-struttura [role=status]")).containsText("Camera tolta.")

        // «Togli la struttura»: the same.
        e2e.open("/strutture/$free")
        pattern = "**/strutture/$free/togli"
        e2e.press(page.locator("main").linkNamed("Togli la struttura"))
        page.waitForSettledDialog()
        held = page.holdPosts(pattern)
        e2e.touch(page.openDialog().buttonNamed("Togli la struttura"))
        page.waitForLabel("dialog[open] button", "Rimozione…")
        assertEquals(1, page.openDialog().count(), "the dialog is open while the answer is awaited")
        assertEquals(1, e2e.dsl().fetchCount(VENUE, VENUE.ID.eq(free)))
        page.release(pattern, held)
        assertThat(page.locator("main [role=status][data-fuoco]")).hasText("Struttura tolta.")
    }

    @E2eTest
    fun `t20 c02 c13 without the network nothing changes in the forms and in the blocks of the venues, the fields keep what was written, a message says so with no Riprova, and with the network back the same touch works`(e2e: E2e) {
        val page = e2e.page
        val (_, support) = e2e.onlyMe()
        val wood = support.makeVenue(woodPlace, "Casa del bosco")
        val empty = e2e.room(wood, "Camera vuota", 3)

        e2e.open("/strutture/nuova")
        page.locator("#nome").fill("Casa sul lago")
        page.locator("#luogo").fill(lakePlace)
        page.route("**/strutture/nuova") { it.abort() }
        e2e.press(page.locator("main").buttonNamed("Salva la struttura"))
        assertThat(page.locator("#messaggi")).containsText("Nessuna connessione")
        assertEquals(0, page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, Page.GetByRoleOptions().setName("Riprova")).count(), "no «Riprova»: it is a send")
        assertThat(page.locator("#nome")).hasValue("Casa sul lago")
        assertThat(page.locator("#luogo")).hasValue(lakePlace)
        assertThat(page.locator("main").buttonNamed("Salva la struttura")).isEnabled()
        assertEquals(1, e2e.dsl().fetchCount(VENUE))
        page.unroute("**/strutture/nuova")
        e2e.press(page.locator("main").buttonNamed("Salva la struttura"))
        assertThat(page.locator("main [role=status][data-fuoco]")).containsText("Struttura salvata.")
        assertEquals(2, e2e.dsl().fetchCount(VENUE))

        // A room with nobody in it is taken away at once, with no dialog: without the network it stays.
        e2e.open("/strutture/$wood")
        page.route("**/strutture/$wood/camere/$empty/togli") { it.abort() }
        e2e.press(page.locator("#camere-struttura").locator("a, button").filter(Locator.FilterOptions().setHasText("Togli")).first())
        assertThat(page.locator("#messaggi")).containsText("Nessuna connessione")
        assertThat(page.locator("#camere-struttura")).containsText("Camera vuota")
        assertEquals(1, e2e.dsl().fetchCount(ROOM, ROOM.ID.eq(empty)))
        page.unroute("**/strutture/$wood/camere/$empty/togli")
        e2e.press(page.locator("#camere-struttura").locator("a, button").filter(Locator.FilterOptions().setHasText("Togli")).first())
        assertThat(page.locator("#camere-struttura [role=status]")).containsText("Camera tolta.")
        assertEquals(0, e2e.dsl().fetchCount(ROOM, ROOM.ID.eq(empty)))
    }

    // ---- A draft with no venue ----------------------------------------------------------------------------------------------------------------------

    @E2eTest
    fun `t20 c09 c10 a draft with no venue has a preview with no Dove and no error, its public page answers without a venue, and the rooms and the detail say to choose it`(e2e: E2e) {
        val page = e2e.page
        val (me, support) = e2e.onlyMe()
        val code = "q7Vd_3KmZ0xRb-Ew"
        val start = support.today().plusDays(30)
        val id = support.make(status = GatheringStatus.draft, place = null, start = start, end = start.plusDays(2), custodians = listOf(me), code = code)

        e2e.open("/convivenza/$id/anteprima")
        assertThat(page.locator("h1")).isVisible()
        assertFalse(page.locator("main").innerText().contains("Dove"), page.locator("main").innerText())
        assertTrue(page.noHorizontalScrollAt320(e2e))
        assertAccessible(page, "preview of a draft with no venue (${e2e.profile.label})")

        val visitor = e2e.newPage(signedIn = false)
        val response = visitor.navigate(e2e.url("/info/$code"))
        assertTrue(response!!.status() < 500, "the public page of a draft answered ${response.status()}")
        assertFalse(visitor.locator("body").innerText().contains("Casa per ritiri"), "a draft with no venue says no place")

        e2e.open("/convivenza/$id/camere")
        assertThat(page.locator("main")).containsText("Scegli la struttura")
        e2e.open("/convivenza/$id")
        assertThat(page.locator("section[aria-labelledby=quando]")).containsText("Non indicata")
        assertTrue(page.noHorizontalScrollAt320(e2e))
        assertAccessible(page, "detail of a draft with no venue (${e2e.profile.label})")
    }

    // ---- Markup characters in the public text -----------------------------------------------------------------------------------------------------

    @E2eTest
    fun `t20 c04 c10 a name and a place with angle brackets, quotes and ampersands are shown as written in every page, in the select, in the dialog and in the preview of the link, and never as markup`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val from = e2e.venueOf(w.id)
        val code = e2e.dsl().select(GATHERING.PUBLIC_CODE).from(GATHERING).where(GATHERING.ID.eq(w.id)).fetchSingle(GATHERING.PUBLIC_CODE)!!
        val room = e2e.room(from, "Camera 1", 4)
        e2e.putChild(e2e.rows().register(w.id, e2e.someParent(), "Luca", "Rossi", LocalDate.of(2016, 4, 4)), w.id, room)
        val name = "Casa \"del\" <b>bosco</b> & 'lago'"
        val place = "Via <i>dei</i> pini 1 & \"2\", 'Località' </option><script>1</script>"
        val scriptsOfThePage = { page.locator("script").count() }

        e2e.open("/strutture/nuova")
        val scripts = scriptsOfThePage()
        page.locator("#nome").fill(name)
        page.locator("#luogo").fill(place)
        e2e.press(page.locator("main").buttonNamed("Salva la struttura"))
        assertThat(page.locator("main [role=status][data-fuoco]")).containsText("Struttura salvata.")
        assertThat(page.locator("h1")).hasText(name)
        assertThat(page).hasTitle("$name · Talità")
        assertThat(page.locator("main")).containsText(place)
        assertEquals(0, page.locator("main b, main i").count(), "the name and the place are not markup")
        assertEquals(scripts, scriptsOfThePage(), "no script was added by the text")
        val to = e2e.dsl().select(VENUE.ID).from(VENUE).where(VENUE.NAME.eq(name)).fetchSingle(VENUE.ID)!!
        assertEquals(place, e2e.dsl().select(VENUE.PLACE).from(VENUE).where(VENUE.ID.eq(to)).fetchSingle(VENUE.PLACE), "saved as written")

        e2e.open("/strutture")
        assertThat(page.locator("main ul li").filter(Locator.FilterOptions().setHasText(name))).containsText(place)
        assertEquals(0, page.locator("main b, main i").count())

        // The select has the name as its text and the place as the value of an attribute, as written; the dialog says both, with what the script puts there as text.
        e2e.open("/convivenza/${w.id}/date")
        val option = page.locator("#struttura option[value='$to']")
        assertEquals(name, option.textContent().orEmpty().trim())
        assertEquals(place, option.getAttribute("data-luogo"))
        assertEquals(3, page.locator("#struttura option").count())
        page.locator("#struttura").selectOption(to.toString())
        e2e.press(page.locator("main").buttonNamed("Salva le modifiche"))
        page.waitForSettledDialog()
        val dialog = page.openDialog()
        assertThat(dialog).containsText("Per i genitori nome e luogo diventano «$name» e «$place».")
        assertEquals(0, dialog.locator("b, i, script").count(), "the dialog has no markup from the text")
        assertTrue(page.noHorizontalScrollAt320(e2e))
        e2e.touch(dialog.buttonNamed("Cambia la struttura"))
        assertThat(page.locator("section[aria-labelledby=quando]")).containsText(place)
        assertEquals(0, page.locator("main b, main i").count())

        val visitor = e2e.newPage(signedIn = false)
        visitor.navigate(e2e.url("/info/$code"))
        visitor.settled()
        val dates = visitor.locator("meta[property='og:description']").getAttribute("content").orEmpty()
        assertTrue(dates.endsWith(" · $name · $place"), dates)
        assertEquals(dates, visitor.locator("meta[name=description]").getAttribute("content"))
        assertThat(visitor.locator("section[aria-labelledby=quando]")).containsText(name)
        assertThat(visitor.locator("section[aria-labelledby=quando]")).containsText(place)
        assertEquals(0, visitor.locator("main b, main i, main option").count(), "the page for the parents has no markup from the text")
        assertTrue(visitor.noHorizontalScrollAt320(e2e))
    }

    // ---- The log ---------------------------------------------------------------------------------------------------------------------------------

    @E2eTest(profiles = [Profile.DESKTOP])
    @CapturesOutput
    fun `t20 c02 c13 the log has no name of a venue, no place, no name of a room and no name of a person while the venues and their rooms are written, changed, taken away and chosen`(e2e: E2e, output: CapturedOutput) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val old = e2e.venueOf(w.id)
        val room = e2e.room(old, "Camera segreta", 4)
        val luca = e2e.rows().register(w.id, e2e.someParent(), "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        e2e.putChild(luca, w.id, room)

        e2e.open("/strutture/nuova")
        page.locator("#nome").fill("Casa nascosta")
        page.locator("#luogo").fill("Casa per ritiri nascosta, Località Segreta")
        e2e.press(page.locator("main").buttonNamed("Salva la struttura"))
        assertThat(page.locator("main [role=status][data-fuoco]")).containsText("Struttura salvata.")
        val hidden = e2e.dsl().select(VENUE.ID).from(VENUE).where(VENUE.NAME.eq("Casa nascosta")).fetchSingle(VENUE.ID)!!
        // A refused name, a room written and taken away, and the change of the venue with a person in a room.
        e2e.open("/strutture/nuova")
        page.locator("#nome").fill("Casa di Luca Rossi")
        page.locator("#luogo").fill("Altro luogo segreto")
        e2e.press(page.locator("main").buttonNamed("Salva la struttura"))
        e2e.open("/strutture/$hidden/camere/nuova")
        page.locator("#nome").fill("Stanza segreta")
        page.locator("#posti").fill("2")
        e2e.press(page.locator("main").buttonNamed("Salva la camera"))
        e2e.open("/convivenza/${w.id}/date")
        page.locator("#struttura").selectOption(hidden.toString())
        e2e.press(page.locator("main").buttonNamed("Salva le modifiche"))
        page.waitForSettledDialog()
        e2e.touch(page.openDialog().buttonNamed("Cambia la struttura"))
        assertThat(page.locator("main [role=status][data-fuoco]")).containsText("senza camera")
        e2e.open("/strutture/$hidden")
        e2e.press(page.locator("#camere-struttura").locator("a, button").filter(Locator.FilterOptions().setHasText("Togli")).first())
        e2e.open("/strutture/$old/camere/$room/modifica")

        val log = output.all
        for (secret in listOf("Casa nascosta", "Località Segreta", "Altro luogo segreto", "Camera segreta", "Stanza segreta", "Luca Rossi", "Marta Bianchi", "Giulia Serra", "Casa di Luca")) {
            assertFalse(log.contains(secret), "the log says «$secret»")
        }
    }
}
