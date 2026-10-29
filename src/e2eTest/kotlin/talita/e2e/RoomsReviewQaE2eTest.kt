package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.ROOM
import talita.jooq.tables.references.ROOM_CHILD
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three points that the review of CodeRabbit changed in the rooms (TALITA-11), checked again by QA on a phone and on a desktop with axe: the card «Camere» of the detail of a gathering
 * that is over (criteria 16 and 13), the page of confirmation of a room that was emptied meanwhile (criterion 5), and two copies of the rooms at the same moment (criterion 4).
 * Names and numbers are synthetic.
 */
@E2eApplication
class RoomsReviewQaE2eTest {

    private fun E2e.room(gathering: Long, name: String, beds: Int): Long =
        dsl().insertInto(ROOM).set(ROOM.GATHERING_ID, gathering).set(ROOM.NAME, name).set(ROOM.BEDS, beds.toShort()).returningResult(ROOM.ID).fetchSingle().value1()!!

    private fun E2e.put(registration: Long, gathering: Long, room: Long) {
        dsl().insertInto(ROOM_CHILD).set(ROOM_CHILD.REGISTRATION_ID, registration).set(ROOM_CHILD.GATHERING_ID, gathering).set(ROOM_CHILD.ROOM_ID, room).execute()
    }

    private fun E2e.child(w: ArrivalsWorld, first: String, last: String, born: LocalDate): Long =
        rows().register(w.id, someParent(), first, last, born)

    /** The gathering ends: its last day was yesterday. */
    private fun E2e.end(w: ArrivalsWorld) {
        dsl().update(GATHERING).set(GATHERING.START_DATE, w.today.minusDays(3)).set(GATHERING.END_DATE, w.today.minusDays(1)).where(GATHERING.ID.eq(w.id)).execute()
    }

    private fun Page.sideways(e2e: E2e, what: String) {
        assertTrue(noHorizontalScroll(), "$what: no sideways scrolling")
        setViewportSize(320, 800)
        assertTrue(noHorizontalScroll(), "$what: no sideways scrolling at 320 px")
        setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
    }

    // ---- Criteria 16 and 13: the card «Camere» of a gathering that is over ----------------------------------------------------------------

    @E2eTest
    fun `t11 c16 c13 a gathering that is over and has no rooms has no card Camere, with rooms the card is as it was, and the page of the rooms has no action`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val detail = "/convivenza/${w.id}"
        val card = page.locator("section[aria-labelledby=camere]")

        // Before the end, with no rooms, the card says to write them.
        e2e.open(detail)
        assertThat(card).containsText("Scrivi le camere")
        assertThat(card).containsText("Nessuna camera per ora.")

        // After the last day, with no rooms: no card (the page does not let write them any more), and the others are there.
        e2e.end(w)
        e2e.open(detail)
        assertEquals(0, card.count(), "no card of the rooms")
        assertEquals(0, page.locator("#camere").count())
        assertThat(page.locator("main")).not().containsText("Scrivi le camere")
        assertThat(page.locator("main")).not().containsText("Nessuna camera per ora")
        assertThat(page.locator("section[aria-labelledby=iscrizioni]")).isVisible()
        assertThat(page.locator("section[aria-labelledby=conservazione]")).isVisible()
        page.sideways(e2e, "detail of a gathering that is over, no rooms")
        assertAccessible(page, "detail of a gathering that is over, no rooms (${e2e.profile.label})")

        // The page of the rooms is still reachable by its address, and has nothing to do.
        val response = e2e.open("$detail/camere")
        assertEquals(200, response.status())
        assertThat(page.locator("main h2:visible").first()).hasText("Nessuna camera")
        // Criterion 13: it says that the gathering is over, not that something was deleted (there was nothing) or that rooms can be written (nobody can).
        assertThat(page.locator("main")).containsText("La convivenza è finita: le camere non si cambiano più.")
        assertThat(page.locator("main")).containsText("In questa convivenza non sono state scritte camere.")
        assertThat(page.locator("main")).not().containsText("è stato cancellato")
        assertThat(page.locator("main")).not().containsText("Scrivi le camere")
        assertEquals(0, page.locator("select, details, form").count(), "no choice, no form")
        assertEquals(0, page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName(Pattern.compile("Aggiungi|Modifica|Togli|Copia"))).count(), "no link of an action")
        assertEquals(0, page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(Pattern.compile("Aggiungi|Copia|Metti|Sposta"))).count(), "no button of an action")
        page.sideways(e2e, "rooms of a gathering that is over, no rooms")
        assertAccessible(page, "rooms of a gathering that is over, no rooms (${e2e.profile.label})")

        // With rooms the card is as it was: only the house, with the children that are still in the rooms not counted (the job deletes them the day after).
        val one = e2e.room(w.id, "Camera 1", 4)
        e2e.room(w.id, "Stanza azzurra", 2)
        e2e.put(e2e.child(w, "Luca", "Rossi", LocalDate.of(2016, 4, 4)), w.id, one)
        e2e.open(detail)
        assertThat(card).isVisible()
        assertThat(card).containsText("Vedi le camere")
        assertThat(card).containsText("2 camere · 6 posti")
        assertThat(card).not().containsText("senza camera")
        assertThat(card).not().containsText("Luca")
        page.sideways(e2e, "detail of a gathering that is over, with rooms")
        assertAccessible(page, "detail of a gathering that is over, with rooms (${e2e.profile.label})")
        e2e.touch(card.locator("a").first())
        assertThat(page).hasURL(e2e.url("$detail/camere"))
        assertThat(page.locator("main")).containsText("La convivenza è finita: le camere non si cambiano più.")
        assertThat(page.locator("#camera-$one")).containsText("Luca Rossi")
        assertEquals(0, page.locator("select, details, form").count(), "no choice, no form")
    }

    @E2eTest
    fun `t11 c16 a gathering of one day with no rooms has no card Camere, before or after the end, and with a room it has it`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering(first = 0, last = 0)
        val detail = "/convivenza/${w.id}"
        val card = page.locator("section[aria-labelledby=camere]")

        e2e.open(detail)
        assertEquals(0, card.count(), "a day needs no rooms")
        assertThat(page.locator("section[aria-labelledby=iscrizioni]")).isVisible()
        e2e.end(w)
        e2e.open(detail)
        assertEquals(0, card.count(), "nor after its end")
        assertAccessible(page, "detail of a gathering of one day that is over (${e2e.profile.label})")

        e2e.room(w.id, "Camera 1", 3)
        e2e.open(detail)
        assertThat(card).containsText("1 camera · 3 posti")
    }

    // ---- Criterion 5: the page of confirmation of a room that has been emptied --------------------------------------------------------------

    @E2eTest
    fun `t11 c05 the page of confirmation of a room where nobody sleeps says it in words, never 0 persone, and an empty room from the page of the rooms has no dialog`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val full = e2e.room(w.id, "Camera 1", 4)
        val empty = e2e.room(w.id, "Stanza azzurra", 2)
        e2e.put(e2e.child(w, "Luca", "Rossi", LocalDate.of(2016, 4, 4)), w.id, full)
        val base = "/convivenza/${w.id}/camere"

        // From the page of the rooms an empty room has no dialog: «Togli» is a button that sends the request at once; the room with people has the link to the dialog.
        e2e.open(base)
        assertEquals(0, page.locator("#camera-$empty").linkNamed("Togli Stanza azzurra").count(), "no dialog for an empty room")
        assertThat(page.locator("#camera-$empty").buttonNamed("Togli Stanza azzurra")).isVisible()
        assertThat(page.locator("#camera-$full").linkNamed("Togli Camera 1")).isVisible()

        // The address of the page of confirmation, opened by hand for the empty room: the zero is in words.
        e2e.open("$base/$empty/togli")
        assertThat(page.locator("h1")).hasText("Togliere Stanza azzurra?")
        assertThat(page.locator("main")).containsText("Stanza azzurra sparisce dall’elenco. Nessuno ci dorme.")
        assertThat(page.locator("main")).not().containsText("0 persone")
        assertThat(page.locator("main")).not().containsText("Le 0")
        assertThat(page.locator("main")).not().containsText("torna fra quelle senza camera")
        page.sideways(e2e, "confirmation of an empty room")
        assertAccessible(page, "page to confirm taking away an empty room (${e2e.profile.label})")
        e2e.touch(page.locator("main").linkNamed("Annulla"))
        assertThat(page).hasURL(e2e.url(base))
        assertEquals(1, page.locator("#camera-$empty").count(), "«Annulla» leaves the room")

        // Confirming from that page takes the room away, and the people of the other room stay where they are.
        e2e.open("$base/$empty/togli")
        e2e.press(page.locator("main").buttonNamed("Togli la camera"))
        assertThat(page.locator("main")).containsText("Camera tolta.")
        assertEquals(0, page.locator("#camera-$empty").count())
        assertThat(page.locator("#camera-$full")).containsText("Luca Rossi")

        // One person says «La persona che ci dorme…» (the text did not change).
        e2e.open("$base/$full/togli")
        assertThat(page.locator("main")).containsText("Camera 1 sparisce dall’elenco. La persona che ci dorme torna fra quelle senza camera.")
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `t11 c05 without JavaScript a link of Togli drawn when the room had a person leads to the page that says that nobody sleeps there, once another teacher has taken the person out`(e2e: E2e) {
        val w = e2e.arrivalsGathering()
        val one = e2e.room(w.id, "Camera 1", 4)
        val luca = e2e.child(w, "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        e2e.put(luca, w.id, one)
        val plain = e2e.newPage(customize = { setJavaScriptEnabled(false) })
        fun touch(target: Locator) {
            plain.settled()
            e2e.touch(target)
            plain.waitForLoadState()
            plain.settled()
        }
        plain.navigate(e2e.url("/convivenza/${w.id}/camere"))
        plain.settled()
        assertThat(plain.locator("#camera-$one")).containsText("Luca Rossi")
        val link = plain.locator("#camera-$one").linkNamed("Togli Camera 1")
        assertThat(link).isVisible()

        // Another teacher takes the person out while this page is open.
        e2e.dsl().deleteFrom(ROOM_CHILD).where(ROOM_CHILD.REGISTRATION_ID.eq(luca)).execute()
        touch(link)
        assertThat(plain.locator("h1")).hasText("Togliere Camera 1?")
        assertThat(plain.locator("main")).containsText("Camera 1 sparisce dall’elenco. Nessuno ci dorme.")
        assertThat(plain.locator("main")).not().containsText("0 persone")
        touch(plain.locator("main").buttonNamed("Togli la camera"))
        assertThat(plain.locator("main")).containsText("Camera tolta.")
        assertEquals(0, plain.locator("#camera-$one").count())
        assertEquals(0, e2e.dsl().fetchCount(ROOM, ROOM.GATHERING_ID.eq(w.id)))
    }

    // ---- Criterion 4: two copies at the same moment ------------------------------------------------------------------------------------------

    @E2eTest
    fun `t11 c04 a copy that comes second while another has written the rooms and not yet committed is told that there are rooms already, not that none was copied`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val before = e2e.dsl().insertInto(GATHERING)
            .set(GATHERING.PUBLIC_CODE, GatheringSupport.nextCode()).set(GATHERING.TITLE, "Convivenza di settembre 2026")
            .set(GATHERING.START_DATE, w.today.minusDays(400)).set(GATHERING.END_DATE, w.today.minusDays(398)).set(GATHERING.PLACE, "Casa per ritiri di esempio").set(GATHERING.FULL_FEE_CENTS, 6000)
            .set(GATHERING.STATUS, GatheringStatus.closed).returningResult(GATHERING.ID).fetchSingle().value1()!!
        e2e.room(before, "Camera 1", 4)
        e2e.room(before, "Camera 2", 4)
        e2e.open("/convivenza/${w.id}/camere")
        val copy = page.locator("main").buttonNamed("Copia le camere di Convivenza di settembre 2026")
        assertThat(copy).isVisible()

        val dsl = e2e.dsl()
        val pool = Executors.newSingleThreadExecutor()
        try {
            // The other teacher's copy has written the rooms and has not committed yet.
            val written = CountDownLatch(1)
            val commit = CountDownLatch(1)
            val other = pool.submit {
                dsl.transaction { configuration ->
                    val tx = org.jooq.impl.DSL.using(configuration)
                    tx.insertInto(ROOM, ROOM.GATHERING_ID, ROOM.NAME, ROOM.BEDS).values(w.id, "Camera 1", 4.toShort()).values(w.id, "Camera 2", 4.toShort()).execute()
                    written.countDown()
                    commit.await(30, TimeUnit.SECONDS)
                }
            }
            assertTrue(written.await(30, TimeUnit.SECONDS))
            // This teacher touches «Copia»: the page asked while the rooms were not there, and the request waits for the other copy to end.
            e2e.press(copy)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (dsl.fetch("select pid from pg_stat_activity where wait_event_type = 'Lock' and state = 'active'").isEmpty()) {
                assertTrue(System.nanoTime() < deadline && !other.isDone, "the request of the copy should wait for the other copy")
                Thread.sleep(20)
            }
            commit.countDown()
            other.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }

        assertThat(page.locator("main")).containsText("Ci sono già delle camere: non ho copiato niente.")
        assertThat(page.locator("main")).not().containsText("Camere copiate.")
        assertThat(page.locator("main")).not().containsText("0 camere")
        assertThat(page.locator("section[id^=camera-] h3")).hasText(arrayOf("Camera 1", "Camera 2"))
        assertEquals(0, page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(Pattern.compile("Copia le camere"))).count(), "the button is gone: there are rooms")
        assertEquals(0, page.locator("#messaggi [role=alert]").count(), "no error: it is a notice")
        assertEquals(2, e2e.dsl().fetchCount(ROOM, ROOM.GATHERING_ID.eq(w.id)), "the rooms of the first copy, and nothing more")
        page.sideways(e2e, "rooms after a copy that came second")
        assertAccessible(page, "rooms after a copy that came second (${e2e.profile.label})")
    }
}
