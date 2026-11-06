package talita.gathering

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.GATHERING
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gathering and its venue (TALITA-20, part 1, criteria 8-10): the form «Modifica titolo, date e luogo» chooses the venue, the registrations cannot be opened without one, and the detail, the preview,
 * the public page and the description of the link show its name and its place; a finished gathering does not say where it was. The teacher has entered with the link; names and places are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class GatheringVenueTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired clock: Clock,
    @Autowired private val reader: GatheringReader,
) {

    private val kit = GatheringTestKit(mockMvc, tokens, dsl, clock)
    private val gatherings = kit.gatherings

    private val woodPlace = "Casa per ritiri del bosco, Località Esempio"
    private val lakePlace = "Casa per ritiri sul lago, Località Esempio"

    private fun datesFields(id: Long, venue: String, title: String = "Convivenza di settembre 2027"): Map<String, String> {
        val row = checkNotNull(dsl.select(GATHERING.START_DATE, GATHERING.END_DATE).from(GATHERING).where(GATHERING.ID.eq(id)).fetchOne())
        return mapOf("title" to title, "startDate" to row[GATHERING.START_DATE].toString(), "endDate" to row[GATHERING.END_DATE].toString(), "venue" to venue)
    }

    private fun MockBrowser.saveDates(id: Long, venue: String, title: String = "Convivenza di settembre 2027") =
        submit("/convivenza/$id/date", "/convivenza/$id/date", datesFields(id, venue, title))

    /** The value of the option that is selected in the select of the venue ("" is «Scegli la struttura»), or null if none is. */
    private fun chosenIn(html: String): String? = Regex("""<option value="(\d*)"[^>]*selected""").find(html)?.groupValues?.get(1)

    // ---- «Modifica titolo, date e luogo» (criterion 8) -------------------------------------------------------------------------

    @Test
    fun `t20 c08 the form has a select of the venues instead of the place, with Scegli la struttura first, the place of now and the link to Nuova struttura`() {
        val (browser, _) = kit.start()
        val wood = gatherings.makeVenue(woodPlace, "Casa del bosco")
        val lake = gatherings.makeVenue(lakePlace, "Casa sul lago")
        val id = gatherings.make(status = GatheringStatus.draft, venue = wood)

        val page = browser.get("/convivenza/$id/date")
        val html = page.body()
        val words = text(html)

        assertEquals(200, page.status())
        assertFalse(html.contains("""name="place""""), "the place is not a field of the gathering any more")
        assertFalse(html.contains("""id="luogo""""))
        assertTrue(Regex("""<label for="struttura"[^>]*>Struttura</label>""").containsMatchIn(html))
        assertTrue(words.contains("Nome e luogo della struttura li leggono i genitori."), words)
        // The options: the first has no value and says what is chosen, then the venues by name; the one of the gathering is selected.
        val options = Regex("""<option value="(\d*)"[^>]*>([^<]*)</option>""").findAll(html).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(listOf("" to "Scegli la struttura", wood.toString() to "Casa del bosco", lake.toString() to "Casa sul lago"), options)
        assertEquals(wood.toString(), chosenIn(html))
        assertTrue(words.contains("Luogo di adesso: $woodPlace."), words)
        // The way to write a new one is a text button under the field.
        assertTrue(Regex("""<a href="/strutture/nuova"[^>]*>[\s\S]*?Nuova struttura</a>""").containsMatchIn(html))
        // The script knows which venue the gathering has now.
        assertTrue(html.contains("""data-valore-struttura="$wood""""))

        // A gathering with no venue has the first choice selected and no place of now.
        gatherings.reset()
        gatherings.makeVenue(woodPlace, "Casa del bosco")
        val draft = gatherings.make(status = GatheringStatus.draft, place = null, full = null)
        val noVenue = browser.get("/convivenza/$draft/date").body()
        assertEquals("", chosenIn(noVenue))
        assertFalse(text(noVenue).contains("Luogo di adesso"))
        assertTrue(noVenue.contains("""data-valore-struttura="""""))
    }

    @Test
    fun `t20 c08 the venue is chosen and saved in every state, also when the gathering is over, and the place that the parents read is the one of the venue`() {
        val (browser, me) = kit.start()
        val end = gatherings.today().minusDays(3)

        for (label in listOf("a draft", "open", "closed", "over")) {
            gatherings.reset()
            val wood = gatherings.makeVenue(woodPlace, "Casa del bosco")
            val lake = gatherings.makeVenue(lakePlace, "Casa sul lago")
            val id = when (label) {
                "a draft" -> gatherings.make(status = GatheringStatus.draft, venue = wood)
                "open" -> gatherings.make(status = GatheringStatus.`open`, venue = wood, custodians = listOf(me))
                "closed" -> gatherings.make(status = GatheringStatus.closed, venue = wood)
                else -> gatherings.make(status = GatheringStatus.closed, venue = wood, start = end.minusDays(2), end = end)
            }

            val saved = browser.saveDates(id, lake.toString())

            assertEquals(303, saved.status(), label)
            assertEquals("/convivenza/$id", saved.location(), label)
            assertEquals(lake, gatherings.venueIdOf(id), label)
            val info = reader.byId(id)!!
            assertEquals(lakePlace, info.place, "$label: the place is the venue's")
            assertEquals("Casa sul lago", info.venueName, label)
            assertEquals(lake, info.venueId, label)
            assertEquals(lake.toString(), chosenIn(browser.get("/convivenza/$id/date").body()), label)
        }
    }

    @Test
    fun `t20 c08 a draft may stay with no venue, and a gathering that is not a draft needs one, the error is under the select, an id that is not a venue is refused, and nothing is saved`() {
        val (browser, me) = kit.start()
        val wood = gatherings.makeVenue(woodPlace, "Casa del bosco")
        val draft = gatherings.make(status = GatheringStatus.draft, venue = wood)

        // A draft goes back to no venue.
        assertEquals(303, browser.saveDates(draft, "", "Un titolo").status())
        assertNull(gatherings.venueIdOf(draft))
        assertEquals("Un titolo", dsl.select(GATHERING.TITLE).from(GATHERING).where(GATHERING.ID.eq(draft)).fetchSingle(GATHERING.TITLE))

        for ((label, status) in listOf("open" to GatheringStatus.`open`, "closed" to GatheringStatus.closed)) {
            gatherings.reset()
            val venue = gatherings.makeVenue(woodPlace, "Casa del bosco")
            val id = gatherings.make(status = status, venue = venue, custodians = listOf(me))
            for (typed in listOf("", " ", "abc", "-5", "0")) {
                val answer = browser.saveDates(id, typed)
                assertEquals(422, answer.status(), "$label «$typed»")
                assertEquals("Scegli la struttura.", errorOf(answer.body(), "struttura"), "$label «$typed»")
                assertFalse(text(answer.body()).contains("Controlla i"), "one error: no notice above, the focus goes to the field")
                assertTrue(Regex("""<select id="struttura"[^>]*aria-invalid="true"""").containsMatchIn(answer.body()), "the select is marked, so that the focus goes to it")
            }
            // A venue that is not there: the page was old, or the request is made by hand. The choices are those of now.
            val missing = browser.saveDates(id, (venue + 100_000).toString())
            assertEquals(422, missing.status(), label)
            assertEquals("Scegli la struttura.", errorOf(missing.body(), "struttura"), label)
            assertEquals(venue, gatherings.venueIdOf(id), "$label: nothing was saved")
        }
        // A draft with an id that is not a venue is refused as well.
        gatherings.reset()
        val other = gatherings.make(status = GatheringStatus.draft)
        assertEquals(422, browser.saveDates(other, "999999").status())
        assertEquals(422, browser.saveDates(other, "abc").status())
    }

    @Test
    fun `t20 c08 with no venue written the form says to write one, with Nuova struttura`() {
        val (browser, _) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft, place = null, full = null)

        val html = browser.get("/convivenza/$id/date").body()
        val words = text(html)

        assertFalse(html.contains("<select"), "nothing to choose")
        assertTrue(words.contains("Struttura Non c’è ancora nessuna struttura. Scrivi la casa dove fai la convivenza: poi la scegli qui. Nuova struttura"), words)
        assertTrue(Regex("""<a href="/strutture/nuova"[^>]*>[\s\S]*?Nuova struttura</a>""").containsMatchIn(html))
        assertTrue(html.contains("""id="struttura""""), "the link «Scegli la struttura» of the detail lands somewhere")
        // The draft is saved all the same.
        assertEquals(303, browser.submit("/convivenza/$id/date", "/convivenza/$id/date", datesFields(id, "", "Un titolo")).status())
    }

    // ---- Opening the registrations (criterion 9) -------------------------------------------------------------------------------

    @Test
    fun `t20 c09 a draft with no venue does not open, and the page lists Scegli la struttura with the way to the field, which exists`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft, place = null, custodians = listOf(me))

        val answer = browser.submit("/convivenza/$id", "/convivenza/$id/apri", mapOf("from" to "draft"))
        val html = answer.body()

        assertEquals(GatheringStatus.draft, gatherings.statusOf(id))
        assertTrue(Regex("""<a href="/convivenza/$id/date#struttura"[^>]*>Scegli la struttura</a>""").containsMatchIn(html), "the first thing that is missing")
        assertFalse(html.contains("Scrivi il luogo"), "the old sentence is gone")
        assertTrue(browser.get("/convivenza/$id/date").body().contains("""id="struttura""""), "the field that the link names")
        // With a venue it opens.
        val wood = gatherings.makeVenue(woodPlace, "Casa del bosco")
        assertEquals(303, browser.saveDates(id, wood.toString()).status())
        assertEquals(303, browser.submit("/convivenza/$id", "/convivenza/$id/apri", mapOf("from" to "draft")).status())
        assertEquals(GatheringStatus.`open`, gatherings.statusOf(id))
    }

    @Test
    fun `t20 c09 the table refuses a gathering that is not a draft and has no venue`() {
        kit.start()
        val wood = gatherings.makeVenue(woodPlace, "Casa del bosco")
        val id = gatherings.make(status = GatheringStatus.draft, venue = wood)

        val failure = runCatching { dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.`open`).set(GATHERING.VENUE_ID, null as Long?).where(GATHERING.ID.eq(id)).execute() }
            .exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("gathering_complete_when_not_draft"), failure?.message)
    }

    // ---- The venue in every page (criterion 10) --------------------------------------------------------------------------------

    @Test
    fun `t20 c10 the detail shows the venue as a link and its place, and a draft with none says Non indicata and Da completare`() {
        val (browser, _) = kit.start()
        val wood = gatherings.makeVenue(woodPlace, "Casa del bosco")
        val id = gatherings.make(status = GatheringStatus.draft, venue = wood)

        val html = browser.get("/convivenza/$id").body()
        val words = text(html)

        assertTrue(Regex("""<dt[^>]*>Struttura</dt>\s*<dd[^>]*>\s*<a href="/strutture/$wood"[^>]*>Casa del bosco</a>""").containsMatchIn(html), "the name leads to the page of the venue")
        assertTrue(words.contains("Struttura Casa del bosco Dove $woodPlace"), words)
        assertFalse(words.contains("Non indicata"))
    }

    @Test
    fun `t20 c10 a gathering with no venue shows Struttura Non indicata with the badge, and no Dove`() {
        val (browser, _) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft, place = null, full = null)

        val words = text(browser.get("/convivenza/$id").body())

        assertTrue(words.contains("Struttura Non indicata Da completare"), words)
        assertFalse(words.contains("Dove "), words)
    }

    @Test
    fun `t20 c10 the preview and the public page show the name of the venue and its place in Quando e dove, never its rooms or its forms, and the description of the link too`() {
        val (browser, me) = kit.start()
        val wood = gatherings.makeVenue(woodPlace, "Casa del bosco")
        val start = gatherings.today().plusDays(30)
        val id = gatherings.make(status = GatheringStatus.`open`, venue = wood, start = start, end = start.plusDays(2), custodians = listOf(me), code = "q7Vd_3KmZ0xRb-Ew")
        val dates = GatheringFormat.range(start, start.plusDays(2))

        val preview = text(browser.get("/convivenza/$id/anteprima").body())
        val days = GatheringFormat.days((0L..2L).map { start.plusDays(it) })
        assertTrue(preview.contains("Quando e dove Quando $days Dove Casa del bosco $woodPlace"), preview)

        val publicPage = mockMvc.get("/info/q7Vd_3KmZ0xRb-Ew").andReturn()
        val html = publicPage.body()
        assertEquals(200, publicPage.status())
        assertTrue(Regex("""<dt[^>]*>Dove</dt>\s*<dd[^>]*>Casa del bosco<span class="block font-normal wrap-anywhere">${Regex.escape(woodPlace)}</span></dd>""").containsMatchIn(html), "the name in bold, the place under it")
        val head = Regex("""<head>[\s\S]*?</head>""").find(html)!!.value
        assertTrue(head.contains("""<meta property="og:description" content="$dates · Casa del bosco · $woodPlace">"""), head)
        assertTrue(head.contains("""<meta name="description" content="$dates · Casa del bosco · $woodPlace">"""), head)
        // Nothing of the rooms or of the forms of the house.
        assertFalse(Regex("""camere|moduli|posti""", RegexOption.IGNORE_CASE).containsMatchIn(text(html)), "only the name and the place")
    }

    @Test
    fun `t20 c10 once the gathering is over the page for the parents says neither the name nor the place, while the detail still shows them`() {
        val (browser, _) = kit.start()
        val wood = gatherings.makeVenue(woodPlace, "Casa del bosco")
        val end = gatherings.today().minusDays(3)
        val id = gatherings.make(status = GatheringStatus.closed, venue = wood, start = end.minusDays(2), end = end, code = "q7Vd_3KmZ0xRb-Ew")

        val html = mockMvc.get("/info/q7Vd_3KmZ0xRb-Ew").andReturn().body()
        val head = Regex("""<head>[\s\S]*?</head>""").find(html)!!.value

        assertFalse(html.contains("Casa del bosco") || html.contains(woodPlace), "the page does not say where it was")
        assertFalse(head.contains("Casa del bosco") || head.contains(woodPlace))
        // The teachers still see it: the detail shows the venue of today.
        assertTrue(text(browser.get("/convivenza/$id").body()).contains("Struttura Casa del bosco Dove $woodPlace"))
    }

    @Test
    fun `t20 c04 when the venue is renamed or its place changes the page, the preview, the detail and the description of the link have the new ones at once`() {
        val (browser, me) = kit.start()
        val wood = gatherings.makeVenue(woodPlace, "Casa del bosco")
        val id = gatherings.make(status = GatheringStatus.`open`, venue = wood, custodians = listOf(me), code = "q7Vd_3KmZ0xRb-Ew")

        assertEquals(303, browser.submit("/strutture/$wood/modifica", "/strutture/$wood/modifica", mapOf("name" to "Casa del bosco grande", "place" to lakePlace)).status())

        val html = mockMvc.get("/info/q7Vd_3KmZ0xRb-Ew").andReturn().body()
        assertTrue(text(html).contains("Dove Casa del bosco grande $lakePlace"), text(html))
        assertTrue(html.contains(" · Casa del bosco grande · $lakePlace\">"), "the description of the link")
        assertFalse(html.contains(woodPlace))
        assertTrue(text(browser.get("/convivenza/$id/anteprima").body()).contains("Dove Casa del bosco grande $lakePlace"))
        assertTrue(text(browser.get("/convivenza/$id").body()).contains("Struttura Casa del bosco grande Dove $lakePlace"))
        assertEquals(lakePlace, reader.byId(id)!!.place)
    }
}
