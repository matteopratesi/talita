package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import talita.LockWait
import talita.gathering.GatheringFormat
import talita.gathering.GatheringSupport
import talita.gathering.MenuSamples
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationAttendance
import talita.jooq.enums.RegistrationDayKind
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.GATHERING_MENU
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.REGISTRATION_DAY
import talita.jooq.tables.references.ROOM
import talita.jooq.tables.references.ROOM_CHILD
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three points that the review of CodeRabbit changed in the rooms (TALITA-11), checked again by QA on a phone and on a desktop with axe: the card «Camere» of the detail of a gathering
 * that is over (criteria 16 and 13), the page of confirmation of a room that was emptied meanwhile (criterion 5), and two copies of the rooms at the same moment (criterion 4).
 * The second pass (after the fix of D1): the pages, the print and the card of Arrivi of a gathering that is over with no rooms and with rooms nobody sleeps in, the gathering that ends while the
 * page is open, the order of the cards of the detail after the merge with the menu (TALITA-12), the icons and the voice «Convivenza» of the menu.
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
            LockWait.awaitBlocked(dsl, "the request of the copy should wait for the other copy", finished = { if (other.isDone) "the other copy has ended" else null }) { e2e.press(copy) }
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

    // ---- Second pass: criteria 13 and 15 once the gathering is over (D1) ------------------------------------------------------------------

    private fun Page.sentenceOnce(sentence: String): Int = Regex(Regex.escape(sentence)).findAll(locator("main").innerText()).count()

    @E2eTest
    fun `t11 c13 c15 over with no rooms the page, the print and the card of the child say that it is over, and with rooms and nobody in them they say who slept where was deleted`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val luca = e2e.child(w, "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        val base = "/convivenza/${w.id}/camere"
        val deletedOn = GatheringFormat.date(w.today)
        val finished = "La convivenza è finita: le camere non si cambiano più."
        val card = page.locator("section[aria-labelledby=camera-titolo]")

        // During the gathering, with no rooms: the child is «Senza camera», with the way to the page.
        e2e.open("/arrivi/$luca")
        assertThat(card).containsText("Senza camera")
        assertThat(card.linkNamed("Vai alle camere")).isVisible()

        // Over, with no rooms: the same sentence in the page, in the print and in the card, and none about deleting.
        e2e.end(w)
        e2e.open(base)
        assertEquals(1, page.sentenceOnce(finished), "the page says it once")
        assertThat(page.locator("main")).containsText("In questa convivenza non sono state scritte camere.")
        assertThat(page.locator("main")).not().containsText("cancellato")
        assertThat(page.locator("main")).not().containsText("copiare")
        assertThat(page.locator("main")).not().containsText("Scrivi le camere")
        assertThat(page.locator("main")).not().containsText("Metti ogni bambino")
        assertThat(page.locator("#riepilogo")).hasCount(0)

        val print = e2e.open("$base/stampa")
        assertEquals(200, print.status())
        assertThat(page.locator("main h2:visible").first()).hasText("Nessuna camera da stampare")
        assertThat(page.locator("main")).containsText("In questa convivenza non sono state scritte camere.")
        assertThat(page.locator("main")).not().containsText("cancellato")
        assertThat(page.locator("main")).not().containsText("Niente da stampare")
        assertThat(page.locator("main")).not().containsText("Scrivi le camere")
        page.sideways(e2e, "print of a gathering that is over, no rooms")
        assertAccessible(page, "print of a gathering that is over, no rooms (${e2e.profile.label})")

        e2e.open("/arrivi/$luca")
        assertThat(card).containsText("Senza camera")
        assertThat(card).not().containsText("cancellato")
        page.sideways(e2e, "card of a child, over, no rooms")
        assertAccessible(page, "card of a child, over, no rooms (${e2e.profile.label})")

        // Over, with rooms and nobody in them: «Chi dormiva dove è stato cancellato…», in the page, in the print and in the card.
        val one = e2e.room(w.id, "Camera 1", 4)
        e2e.room(w.id, "Stanza azzurra", 2)
        e2e.open(base)
        assertThat(page.locator("main")).containsText("Chi dormiva dove è stato cancellato il $deletedOn, alla fine della convivenza.")
        assertThat(page.locator("main")).containsText("Le camere restano, senza nomi: le puoi copiare nella convivenza dell’anno dopo.")
        assertThat(page.locator("section[id^=camera-] h3")).hasText(arrayOf("Camera 1", "Stanza azzurra"))
        assertEquals(0, page.locator("select, details, form").count(), "no action")
        page.sideways(e2e, "rooms of a gathering that is over, rooms and nobody in them")
        assertAccessible(page, "rooms of a gathering that is over, rooms and nobody in them (${e2e.profile.label})")

        e2e.open("$base/stampa")
        assertThat(page.locator("main h2:visible").first()).hasText("Niente da stampare")
        assertThat(page.locator("main")).containsText("Chi dormiva dove è stato cancellato il $deletedOn")
        assertThat(page.locator("main")).not().containsText("In questa convivenza non sono state scritte camere.")
        assertThat(page.locator("main")).not().containsText("Luca")
        page.sideways(e2e, "print, rooms and nobody in them")
        assertAccessible(page, "print, over, rooms and nobody in them (${e2e.profile.label})")

        e2e.open("/arrivi/$luca")
        assertThat(card).containsText("Chi dormiva dove è stato cancellato il $deletedOn.")
        assertThat(card).not().containsText("Senza camera")
        page.sideways(e2e, "card of a child, over, rooms and nobody in them")
        assertAccessible(page, "card of a child, over, rooms and nobody in them (${e2e.profile.label})")

        // Over, with the child still in the room (the job has not run): the room, with the link to its card, and the print has the name.
        e2e.put(luca, w.id, one)
        e2e.open("/arrivi/$luca")
        assertThat(card.linkNamed("Camera 1")).isVisible()
        e2e.touch(card.linkNamed("Camera 1"))
        assertThat(page).hasURL(Pattern.compile(".*/convivenza/${w.id}/camere#camera-$one"))
        assertThat(page.locator("main")).containsText(finished)
        assertThat(page.locator("#camera-$one")).containsText("Luca Rossi")
        e2e.open("$base/stampa")
        assertThat(page.locator("main")).containsText("Luca Rossi")
        assertThat(page.locator("main")).not().containsText("cancellato")
    }

    @E2eTest
    fun `t11 c13 the gathering ends while the page of the empty rooms is open, and a touch on Copia answers that it is over, with nothing copied`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val before = e2e.dsl().insertInto(GATHERING)
            .set(GATHERING.PUBLIC_CODE, GatheringSupport.nextCode()).set(GATHERING.TITLE, "Convivenza di settembre 2026")
            .set(GATHERING.START_DATE, w.today.minusDays(400)).set(GATHERING.END_DATE, w.today.minusDays(398)).set(GATHERING.PLACE, "Casa per ritiri di esempio").set(GATHERING.FULL_FEE_CENTS, 6000)
            .set(GATHERING.STATUS, GatheringStatus.closed).returningResult(GATHERING.ID).fetchSingle().value1()!!
        e2e.room(before, "Camera 1", 4)
        e2e.open("/convivenza/${w.id}/camere")
        assertThat(page.locator("main")).containsText("Scrivi le camere della casa")
        val copy = page.locator("main").buttonNamed("Copia le camere di Convivenza di settembre 2026")
        assertThat(copy).isVisible()
        assertThat(page.locator("main").linkNamed("Aggiungi una camera")).isVisible()

        e2e.end(w)
        e2e.press(copy)

        // The block is replaced by what the page says now: over, no rooms, no invitation, no button.
        assertThat(page.locator("main")).containsText("La convivenza è finita: le camere non si cambiano più.")
        // Once in the block and once in the region that a screen reader listens to (`#esito`, out of sight).
        assertEquals(1, Regex("La convivenza è finita: le camere non si cambiano più\\.").findAll(page.locator("#camere-contenuto").innerText()).count(), "said once in the block")
        assertThat(page.locator("#esito")).hasText("La convivenza è finita: le camere non si cambiano più.")
        assertThat(page.locator("main")).containsText("In questa convivenza non sono state scritte camere.")
        assertThat(page.locator("main")).not().containsText("Camere copiate")
        assertThat(page.locator("main")).not().containsText("Scrivi le camere")
        assertThat(page.locator("main")).not().containsText("cancellato")
        assertEquals(0, page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(Pattern.compile("Copia|Aggiungi"))).count() + page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName(Pattern.compile("Copia|Aggiungi"))).count(), "no action left")
        assertEquals(0, e2e.dsl().fetchCount(ROOM, ROOM.GATHERING_ID.eq(w.id)), "nothing was copied")
        page.sideways(e2e, "empty rooms, the gathering ended with the page open")
        assertAccessible(page, "empty rooms, the gathering ended with the page open (${e2e.profile.label})")

        // The address of the form of a new room, opened by hand, is refused: the page of the answer says that it is over too.
        val response = e2e.open("/convivenza/${w.id}/camere/nuova")
        assertEquals(409, response.status())
        assertThat(page.locator("main")).containsText("La convivenza è finita")
        assertEquals(0, e2e.dsl().fetchCount(ROOM, ROOM.GATHERING_ID.eq(w.id)))
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `t11 c13 without JavaScript the same, Copia sent after the end gives the page that says that it is over`(e2e: E2e) {
        val w = e2e.arrivalsGathering()
        val before = e2e.dsl().insertInto(GATHERING)
            .set(GATHERING.PUBLIC_CODE, GatheringSupport.nextCode()).set(GATHERING.TITLE, "Convivenza di settembre 2026")
            .set(GATHERING.START_DATE, w.today.minusDays(400)).set(GATHERING.END_DATE, w.today.minusDays(398)).set(GATHERING.PLACE, "Casa per ritiri di esempio").set(GATHERING.FULL_FEE_CENTS, 6000)
            .set(GATHERING.STATUS, GatheringStatus.closed).returningResult(GATHERING.ID).fetchSingle().value1()!!
        e2e.room(before, "Camera 1", 4)
        val plain = e2e.newPage(customize = { setJavaScriptEnabled(false) })
        plain.navigate(e2e.url("/convivenza/${w.id}/camere"))
        plain.settled()
        val copy = plain.locator("main").buttonNamed("Copia le camere di Convivenza di settembre 2026")
        assertThat(copy).isVisible()

        e2e.end(w)
        plain.settled()
        e2e.touch(copy)
        plain.waitForLoadState()
        plain.settled()

        assertThat(plain.locator("main")).containsText("La convivenza è finita: le camere non si cambiano più.")
        assertThat(plain.locator("main")).containsText("In questa convivenza non sono state scritte camere.")
        assertThat(plain.locator("main")).not().containsText("cancellato")
        assertEquals(0, plain.locator("form, select").count(), "no form")
        assertEquals(0, e2e.dsl().fetchCount(ROOM, ROOM.GATHERING_ID.eq(w.id)), "nothing was copied")
        assertTrue(plain.noHorizontalScroll())
    }

    // ---- Second pass: criterion 16 and the detail after the merge with the menu ------------------------------------------------------------

    private fun E2e.menu(gathering: Long, name: String = "menu-settembre-2027.pdf", type: String = "application/pdf", content: ByteArray = MenuSamples.pdf()) {
        dsl().deleteFrom(GATHERING_MENU).where(GATHERING_MENU.GATHERING_ID.eq(gathering)).execute()
        dsl().insertInto(GATHERING_MENU)
            .set(GATHERING_MENU.GATHERING_ID, gathering).set(GATHERING_MENU.FILE_NAME, name).set(GATHERING_MENU.MEDIA_TYPE, type)
            .set(GATHERING_MENU.CONTENT, content).set(GATHERING_MENU.UPLOADED_AT, OffsetDateTime.now()).execute()
    }

    /** The cards of the detail that this test is about, in the order of the page (the others are there too: Stato, the link, the quotes…). */
    private fun Page.cards(): List<String> =
        (evaluate("[...document.querySelectorAll('main section[aria-labelledby]')].map(s => s.getAttribute('aria-labelledby'))") as List<*>)
            .map { it as String }.filter { it in setOf("iscrizioni", "promemoria-t", "camere", "menu", "quando") }

    @E2eTest
    fun `t11 c16 t12 t13 the cards of the detail are Iscrizioni, Promemoria, Camere, Menù, Quando e dove in a draft, open, closed and over, and after the end with no rooms only Camere goes, the Menù stays`(e2e: E2e) {
        val page = e2e.page
        for ((label, state) in listOf(
            "a draft" to Triple(GatheringStatus.draft, 30L, 32L),
            "open" to Triple(GatheringStatus.`open`, -1L, 1L),
            "closed" to Triple(GatheringStatus.closed, -1L, 1L),
            "over" to Triple(GatheringStatus.closed, -10L, -8L),
        )) {
            val w = e2e.arrivalsGathering(first = state.second, last = state.third, status = state.first)
            val over = label == "over"
            val all = listOf("iscrizioni", "promemoria-t", "camere", "menu", "quando")
            val noRooms = if (over) listOf("iscrizioni", "promemoria-t", "menu", "quando") else all

            // No rooms, no menu.
            e2e.open("/convivenza/${w.id}")
            assertEquals(noRooms, page.cards(), "$label, no rooms, no menu")
            assertThat(page.locator("section[aria-labelledby=menu]")).containsText("Nessun menù caricato.")
            page.sideways(e2e, "detail, $label, no rooms")
            assertAccessible(page, "detail, $label, no rooms, no menu (${e2e.profile.label})")

            // No rooms, a menu: the Menù is there after the end, with its file.
            e2e.menu(w.id)
            e2e.open("/convivenza/${w.id}")
            assertEquals(noRooms, page.cards(), "$label, no rooms, a menu")
            assertThat(page.locator("section[aria-labelledby=menu]")).containsText("menu-settembre-2027.pdf")
            assertThat(page.locator("section[aria-labelledby=menu]").linkNamed("Scarica il menù")).isVisible()
            assertEquals(if (over) 0 else 1, page.locator("section[aria-labelledby=camere]").count(), "$label: the card of the rooms")
            assertAccessible(page, "detail, $label, no rooms, a menu (${e2e.profile.label})")

            // A room: the five cards in the same order, also after the end, and the card of the rooms has no names.
            e2e.room(w.id, "Camera 1", 4)
            e2e.open("/convivenza/${w.id}")
            assertEquals(all, page.cards(), "$label, a room, a menu")
            assertThat(page.locator("section[aria-labelledby=camere]")).containsText("1 camera · 4 posti")
            assertThat(page.locator("section[aria-labelledby=menu]")).containsText("menu-settembre-2027.pdf")
            page.sideways(e2e, "detail, $label, a room, a menu")
            assertAccessible(page, "detail, $label, a room, a menu (${e2e.profile.label})")
        }
    }

    // ---- Second pass: icons and the current voice ---------------------------------------------------------------------------------------

    private fun Page.svgWith(selector: String): Locator = locator("svg[aria-hidden=true]:has($selector)")

    private fun Page.iconsOf(selector: String, what: String) {
        val svg = svgWith(selector).first()
        assertThat(svg).isVisible()
        val box = svg.boundingBox()
        assertTrue(box != null && box.width > 8 && box.height > 8, "$what: the icon is drawn (${box?.width} x ${box?.height})")
    }

    private val bedDouble = "path[d^='M2 20v-8a2 2 0 0 1 2-2h16']"
    private val upload = "path[d^='M12 3v12']"
    private val download = "path[d^='M12 15V3']"
    private val image = "circle[cx='9'][cy='9'][r='2']"
    private val utensils = "path[d^='M3 2v7c0 1.1.9 2 2 2h4']"

    @E2eTest
    fun `t11 t12 the icons bed-double, upload, download, image and utensils are drawn where the design puts them`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val luca = e2e.child(w, "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        val base = "/convivenza/${w.id}"

        // The card of the rooms in the detail (with a room), the upload in the card of the menu without a file.
        e2e.room(w.id, "Camera 1", 4)
        e2e.open(base)
        page.iconsOf(bedDouble, "card Camere")
        assertThat(page.locator("section[aria-labelledby=camere]").locator("svg").first()).isVisible()
        assertThat(page.locator("section[aria-labelledby=menu]").locator("svg:has($upload)")).isVisible()
        page.iconsOf(upload, "card Menù without a file: Carica il menù")

        // The card of the menu with a PNG: image, download, upload (Sostituisci).
        e2e.menu(w.id, name = "menu-foto.png", type = "image/png", content = MenuSamples.png(20))
        e2e.open(base)
        assertThat(page.locator("section[aria-labelledby=menu]").locator("svg:has($image)")).isVisible()
        assertThat(page.locator("section[aria-labelledby=menu]").locator("svg:has($download)")).isVisible()
        assertThat(page.locator("section[aria-labelledby=menu]").locator("svg:has($upload)")).isVisible()
        page.iconsOf(image, "card Menù: image")
        page.iconsOf(download, "card Menù: Scarica")
        page.iconsOf(upload, "card Menù: Sostituisci")
        assertAccessible(page, "detail with the icons (${e2e.profile.label})")

        // The page of the menu (replace) and Arrivi: utensils and download in the row Menù, bed-double in the card of the child without a room.
        e2e.open("$base/menu")
        assertEquals("Convivenza", page.locator("nav a[aria-current=page]").innerText().trim())
        page.iconsOf(image, "page of the menu: image")
        e2e.open("/arrivi")
        assertThat(page.locator("#menu-arrivi").locator("svg:has($utensils)")).isVisible()
        assertThat(page.locator("#menu-arrivi").locator("svg:has($download)")).isVisible()
        page.iconsOf(utensils, "Arrivi: utensils")
        page.iconsOf(download, "Arrivi: download")
        e2e.open("/arrivi/$luca")
        assertThat(page.locator("section[aria-labelledby=camera-titolo]").locator("svg:has($bedDouble)")).isVisible()
        page.iconsOf(bedDouble, "card of the child: Vai alle camere")

        // The empty state of the rooms: the big icon.
        e2e.dsl().deleteFrom(ROOM).where(ROOM.GATHERING_ID.eq(w.id)).execute()
        e2e.open("$base/camere")
        val big = page.locator("main svg:has($bedDouble)").first()
        assertThat(big).isVisible()
        assertTrue(big.boundingBox()!!.width >= 31, "the icon of the empty state is 32 px (size-8)")
        assertAccessible(page, "empty rooms with the icon (${e2e.profile.label})")
    }

    @E2eTest
    fun `t11 t12 the voice Convivenza is the current one on every page of the rooms and of the menu`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val one = e2e.room(w.id, "Camera 1", 4)
        e2e.put(e2e.child(w, "Luca", "Rossi", LocalDate.of(2016, 4, 4)), w.id, one)
        e2e.menu(w.id)
        val base = "/convivenza/${w.id}"
        for (path in listOf(base, "$base/camere", "$base/camere/nuova", "$base/camere/$one/modifica", "$base/camere/$one/togli", "$base/menu", "$base/menu/togli")) {
            e2e.open(path)
            assertEquals(1, page.locator("nav a[aria-current=page]").count(), path)
            assertEquals("Convivenza", page.locator("nav a[aria-current=page]").innerText().trim(), path)
            assertTrue(page.noHorizontalScroll(), path)
        }
        e2e.open("$base/camere")
        assertThat(page.locator("nav").linkNamed("Convivenza")).isVisible()
        page.setViewportSize(320, 800)
        assertThat(page.locator("nav").linkNamed("Convivenza")).isVisible()
        assertTrue(page.noHorizontalScroll(), "320 px")
    }

    // ---- Third pass: lunch only in Arrivi (criteria 15, 6, 12) and the card «Conservazione dei dati» of a gathering that is over (criteria 13, 20) ----------------------

    /** The registration becomes lunch only on [day], the way the correction of a teacher leaves it: the room, if it had one, stays (criterion 12). */
    private fun E2e.lunchOnly(registration: Long, day: LocalDate) {
        dsl().update(REGISTRATION).set(REGISTRATION.ATTENDANCE, RegistrationAttendance.days).where(REGISTRATION.ID.eq(registration)).execute()
        dsl().insertInto(REGISTRATION_DAY).set(REGISTRATION_DAY.REGISTRATION_ID, registration).set(REGISTRATION_DAY.DAY, day).set(REGISTRATION_DAY.KIND, RegistrationDayKind.lunch).execute()
    }

    @E2eTest
    fun `t11 c15 c06 c12 in Arrivi who comes for lunch only and has no room has no card Camera, who has a night is Senza camera as the page of the rooms says, and who was in a room keeps it`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val rows = e2e.rows()
        val base = "/convivenza/${w.id}/camere"
        val card = page.locator("section[aria-labelledby=camera-titolo]")
        val room = e2e.room(w.id, "Camera 2", 4)
        val nicolo = rows.register(w.id, null, "Nicolò", "Ferri", LocalDate.of(2015, 6, 2), enteredBy = w.martaId, days = mapOf(w.today to "lunch"))
        val samuele = rows.register(w.id, null, "Samuele", "Moretti", LocalDate.of(2016, 9, 9), enteredBy = w.martaId, days = mapOf(w.yesterday to "full", w.tomorrow to "lunch"))
        val luca = e2e.child(w, "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        val anna = e2e.child(w, "Anna", "Rossi", LocalDate.of(2018, 1, 9))
        e2e.put(luca, w.id, room)

        // The page of the rooms (criterion 6): Samuele (a whole day among the lunches) and Anna (every day) have no room; Nicolò (lunch only) is not listed at all.
        e2e.open(base)
        assertThat(page.locator("#senza-camera")).containsText("Samuele Moretti")
        assertThat(page.locator("#senza-camera")).containsText("Anna Rossi")
        assertThat(page.locator("main")).not().containsText("Nicolò Ferri")
        assertThat(page.locator("#camera-$room")).containsText("Luca Rossi")

        // Lunch only and no room: the card of the child is there, and has no card «Camera», so no «Senza camera» and no way to a list where he is not.
        e2e.open("/arrivi/$nicolo")
        assertThat(page.locator("main")).containsText("Nicolò Ferri")
        assertEquals(0, card.count(), "no card of the room")
        assertEquals(0, page.locator("#camera-titolo").count())
        assertThat(page.locator("main")).not().containsText("Senza camera")
        assertThat(page.locator("main")).not().containsText("Vai alle camere")
        assertEquals(0, page.locator("main a[href*='/camere']").count(), "no way to the rooms")
        page.sideways(e2e, "card of a child for lunch only, no room")
        assertAccessible(page, "card of a child for lunch only, no room (${e2e.profile.label})")

        // One whole day and a lunch, no room: «Senza camera», and the way leads to the list where he is.
        e2e.open("/arrivi/$samuele")
        assertThat(card).containsText("Senza camera")
        assertThat(card.linkNamed("Vai alle camere")).isVisible()
        e2e.touch(card.linkNamed("Vai alle camere"))
        assertThat(page).hasURL(Pattern.compile(".*/convivenza/${w.id}/camere#senza-camera"))
        assertThat(page.locator("#senza-camera")).containsText("Samuele Moretti")
        // Every day, no room: the same.
        e2e.open("/arrivi/$anna")
        assertThat(card).containsText("Senza camera")
        assertThat(card.linkNamed("Vai alle camere")).isVisible()

        // Both children become lunch only on a day. The one in the room keeps it, with the card and the way to it (criterion 12); the one with none loses the card, and the page of the
        // rooms stops listing her: the card and the page say the same.
        e2e.lunchOnly(luca, w.today)
        e2e.lunchOnly(anna, w.today)
        e2e.open("/arrivi/$luca")
        assertThat(card).containsText("Camera 2")
        assertThat(card).not().containsText("Senza camera")
        page.sideways(e2e, "card of a child in a room that became lunch only")
        assertAccessible(page, "card of a child in a room that became lunch only (${e2e.profile.label})")
        e2e.touch(card.linkNamed("Camera 2"))
        assertThat(page).hasURL(Pattern.compile(".*/convivenza/${w.id}/camere#camera-$room"))
        assertThat(page.locator("#camera-$room")).containsText("Luca Rossi")
        assertThat(page.locator("#camera-$room")).containsText("pranzo")
        assertThat(page.locator("#senza-camera")).containsText("Samuele Moretti")
        assertThat(page.locator("#senza-camera")).not().containsText("Anna Rossi")
        e2e.open("/arrivi/$anna")
        assertEquals(0, card.count(), "lunch only and no room: no card of the room")
        assertThat(page.locator("main")).not().containsText("Vai alle camere")

        // After the end, with the rooms and nobody in them (what the job leaves): who slept there is told that it was deleted, and who came for lunch only has no card (he did not sleep there).
        e2e.end(w)
        e2e.dsl().deleteFrom(ROOM_CHILD).where(ROOM_CHILD.GATHERING_ID.eq(w.id)).execute()
        val deletedOn = GatheringFormat.date(w.today)
        e2e.open("/arrivi/$samuele")
        assertThat(card).containsText("Chi dormiva dove è stato cancellato il $deletedOn.")
        assertThat(card).not().containsText("Senza camera")
        page.sideways(e2e, "card of a child that slept there, over, rooms and nobody in them")
        assertAccessible(page, "card of a child that slept there, over, rooms and nobody in them (${e2e.profile.label})")
        for ((id, who) in listOf(nicolo to "Nicolò Ferri", anna to "Anna Rossi", luca to "Luca Rossi")) {
            e2e.open("/arrivi/$id")
            assertThat(page.locator("main")).containsText(who)
            assertEquals(0, card.count(), "$who came for lunch only: no card of the room after the end")
            assertThat(page.locator("main")).not().containsText("cancellato")
            assertThat(page.locator("main")).not().containsText("Senza camera")
        }
        page.sideways(e2e, "card of a child for lunch only, over, rooms and nobody in them")
        assertAccessible(page, "card of a child for lunch only, over, rooms and nobody in them (${e2e.profile.label})")
        e2e.open(base)
        assertThat(page.locator("main")).containsText("Chi dormiva dove è stato cancellato il $deletedOn")
        assertThat(page.locator("main")).not().containsText("Nicolò Ferri")
    }

    @E2eTest
    fun `t11 c13 c20 the card Conservazione dei dati has the line Chi dorme dove while the gathering goes on with or without rooms, and after the end only if it has a room, with the other lines where they were`(e2e: E2e) {
        val page = e2e.page
        val card = page.locator("section[aria-labelledby=conservazione]")
        val others = listOf("Segnalazioni", "Iscrizioni, manleve e registro arrivi", "Su carta")
        val all = listOf("Segnalazioni", "Chi dorme dove", "Iscrizioni, manleve e registro arrivi", "Su carta")
        fun labels(): List<String> = card.locator("dt").allInnerTexts().map { it.trim() }
        fun line(): Locator = card.locator("div:has(> dt:text-is('Chi dorme dove'))")

        // Going on, with no rooms: the line is there and says what will happen (the teachers can still write the rooms).
        val going = e2e.arrivalsGathering()
        val detail = "/convivenza/${going.id}"
        e2e.open(detail)
        assertEquals(all, labels(), "going on, no rooms")
        assertThat(line()).containsText("Si cancella il ${GatheringFormat.date(going.today.plusDays(2))}")
        assertThat(line()).containsText("Le camere restano, senza nomi, per copiarle l’anno dopo.")
        page.sideways(e2e, "retention of a gathering that goes on, no rooms")
        assertAccessible(page, "detail, going on, no rooms (${e2e.profile.label})")
        // With a room, and a person in it: the same.
        val room = e2e.room(going.id, "Camera 1", 4)
        e2e.put(e2e.child(going, "Luca", "Rossi", LocalDate.of(2016, 4, 4)), going.id, room)
        e2e.open(detail)
        assertEquals(all, labels(), "going on, a room")
        assertThat(line()).containsText("Si cancella il ${GatheringFormat.date(going.today.plusDays(2))}")

        // The last day is today: it is not over yet, and the rooms can be written until the end of the day. With no rooms the line is still there.
        val last = e2e.arrivalsGathering(first = -2, last = 0)
        e2e.open("/convivenza/${last.id}")
        assertEquals(all, labels(), "the last day, no rooms")
        assertThat(line()).containsText("Si cancella il ${GatheringFormat.date(last.today.plusDays(1))}")

        // The day after the last, with no rooms: nothing was written, nothing was deleted and no room stays. The line goes, the other three stay in their order, with their dates.
        val over = e2e.arrivalsGathering()
        e2e.end(over)
        e2e.open("/convivenza/${over.id}")
        assertEquals(others, labels(), "over, no rooms")
        assertThat(card).not().containsText("Chi dorme dove")
        assertThat(card).not().containsText("Le camere restano")
        assertThat(card).not().containsText("È stato cancellato")
        assertThat(card).not().containsText("cancellato il")
        assertThat(card).containsText("Talità cancella da sola i dati di questa convivenza, ogni notte. Qui vedi quando.")
        assertThat(card.locator("div:has(> dt:text-is('Segnalazioni'))")).containsText("Si cancellano il ${GatheringFormat.date(over.today.plusMonths(1))}")
        assertThat(card.locator("div:has(> dt:text-is('Iscrizioni, manleve e registro arrivi'))")).containsText("Si cancellano il")
        assertThat(card.locator("div:has(> dt:text-is('Su carta'))")).containsText("Da distruggere a partire dal")
        assertEquals(0, page.locator("section[aria-labelledby=camere]").count(), "and the card of the rooms is not there either")
        page.sideways(e2e, "retention of a gathering that is over, no rooms")
        assertAccessible(page, "detail, over, no rooms (${e2e.profile.label})")

        // Over, with a room even if nobody is in it (what the job leaves): the line is there and says that it was deleted and that the rooms stay.
        val kept = e2e.room(over.id, "Camera 1", 4)
        e2e.open("/convivenza/${over.id}")
        assertEquals(all, labels(), "over, a room")
        assertThat(line()).containsText("È stato cancellato il ${GatheringFormat.date(over.today)}")
        assertThat(line()).containsText("Le camere restano, senza nomi, per copiarle l’anno dopo.")
        page.sideways(e2e, "retention of a gathering that is over, a room")
        assertAccessible(page, "detail, over, a room (${e2e.profile.label})")
        // With the person still in it (the job has not run yet): the same.
        e2e.put(e2e.child(over, "Anna", "Rossi", LocalDate.of(2018, 1, 9)), over.id, kept)
        e2e.open("/convivenza/${over.id}")
        assertEquals(all, labels(), "over, a room with a person")
        // The room goes again: the line goes with it.
        e2e.dsl().deleteFrom(ROOM_CHILD).where(ROOM_CHILD.GATHERING_ID.eq(over.id)).execute()
        e2e.dsl().deleteFrom(ROOM).where(ROOM.GATHERING_ID.eq(over.id)).execute()
        e2e.open("/convivenza/${over.id}")
        assertEquals(others, labels(), "over, the rooms gone")
    }
}
