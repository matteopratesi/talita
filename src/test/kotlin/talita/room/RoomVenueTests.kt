package talita.room

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.LockWait
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.checkin.card
import talita.checkin.words
import talita.gathering.ArrivalUsage
import talita.gathering.DatesResult
import talita.gathering.GatheringReader
import talita.gathering.Gatherings
import talita.gathering.RegistrationUsage
import talita.gathering.RoomAssignments
import talita.gathering.body
import talita.gathering.heading
import talita.gathering.location
import talita.gathering.status
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.ROOM
import talita.jooq.tables.references.VENUE
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rooms are the venue's (TALITA-20, part 2): the change of the venue of a gathering that has people in the rooms (criterion 12: the dialog, the page that stands in for it, the count again under the lock, the people
 * who leave in the same transaction), a choice of a room and a change of venue at the same moment in the two orders (criterion 16), a gathering with no venue or with a venue with no rooms (criterion 15), and a room that
 * changes during a gathering (criterion 16). Names, rooms and numbers are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RoomVenueTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val rooms: Rooms,
    @Autowired private val gatherings: Gatherings,
    @Autowired private val reader: GatheringReader,
    @Autowired private val usage: RegistrationUsage,
    @Autowired private val arrivals: ArrivalUsage,
    @Autowired private val assignments: RoomAssignments,
) {

    private val kit = RoomTestKit(mockMvc, tokens, dsl, clock)

    private fun venueName(venue: Long): String = dsl.select(VENUE.NAME).from(VENUE).where(VENUE.ID.eq(venue)).fetchSingle(VENUE.NAME)!!

    private fun venuePlace(venue: Long): String = dsl.select(VENUE.PLACE).from(VENUE).where(VENUE.ID.eq(venue)).fetchSingle(VENUE.PLACE)!!

    /** The fields of «Modifica titolo, date e luogo» of the gathering, with the venue chosen and, if given, the number of people that the teacher was told about. */
    private fun datesFields(gathering: Long, venue: Long, change: Int? = null): Map<String, String> {
        val row = checkNotNull(dsl.select(GATHERING.START_DATE, GATHERING.END_DATE, GATHERING.TITLE).from(GATHERING).where(GATHERING.ID.eq(gathering)).fetchOne())
        val fields = mapOf("title" to row[GATHERING.TITLE]!!, "startDate" to row[GATHERING.START_DATE].toString(), "endDate" to row[GATHERING.END_DATE].toString(), "venue" to venue.toString())
        return if (change == null) fields else fields + ("cambio" to change.toString())
    }

    private fun MockBrowser.saveDates(gathering: Long, venue: Long, change: Int? = null) =
        submit("/convivenza/$gathering/date", "/convivenza/$gathering/date", datesFields(gathering, venue, change))

    /** A gathering held in the house with a room «Camera 1», Luca Rossi in it and the teacher Paolo Verdi (two people), and another venue to change to. */
    private inner class World {
        val setup = kit.start()
        val from = kit.venueOf(setup.id)
        val to = kit.venueOf(kit.otherGathering())
        val one = kit.room(setup.id, "Camera 1", 4)
        val luca = kit.rows.register(setup.id, kit.checkin.account(), "Luca", "Rossi")
        val anna = kit.rows.register(setup.id, kit.checkin.account(), "Anna", "Rossi")
        val page = "/convivenza/${setup.id}/date"

        init {
            kit.put(luca, setup.id, one)
            kit.putTeacher(setup.id, setup.paoloId, one)
        }

        fun venue(): Long = kit.venueOf(setup.id)
    }

    // ---- The dialog and the page that stands in for it (criterion 12) ---------------------------------------------------------------------------------

    @Test
    fun `t20 c12 the form of the dates has the dialog Cambiare la struttura when somebody is in a room of the venue of now, with how many, and has none when nobody is`() {
        val w = World()

        val html = w.setup.marta.get(w.page).body()
        val dialog = Regex("""<dialog id="cambia-struttura".*?</dialog>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        assertTrue(words(dialog).contains("Cambiare la struttura? Le 2 persone nelle camere di ${venueName(w.from)} restano senza camera: le camere sono quelle della struttura."), words(dialog))
        assertTrue(words(dialog).contains("Per i genitori nome e luogo diventano"), "the second sentence is filled by the script with the venue that is chosen")
        assertTrue(words(dialog).contains("Cambia la struttura Annulla"), words(dialog))
        assertTrue(dialog.contains("""data-conferma-cambio="2""""), "the number of people that the dialog says is the one that the button sends")
        assertTrue(html.contains("""data-valore-struttura="${w.from}""""), "the form knows the venue of now, to see that the choice changes it")
        assertTrue(Regex("""<input type="hidden" name="cambio" value="">""").containsMatchIn(html), "the field that carries the number is there, empty")
        assertFalse(dialog.contains("style="), "no style attribute")

        // One person says «La persona», nobody has no dialog.
        dsl.deleteFrom(talita.jooq.tables.references.ROOM_TEACHER).execute()
        assertTrue(words(w.setup.marta.get(w.page).body()).contains("La persona che dorme in una camera di ${venueName(w.from)} resta senza camera"))
        dsl.deleteFrom(talita.jooq.tables.references.ROOM_CHILD).execute()
        val none = w.setup.marta.get(w.page).body()
        assertFalse(none.contains("cambia-struttura") || none.contains("data-conferma-cambio") || none.contains("""name="cambio""""), "nobody in the rooms: nothing to confirm")
    }

    @Test
    fun `t20 c12 saving another venue with people in the rooms does not save and answers with the page of the dialog, the form again with the number as hidden fields, and nothing changes`() {
        val w = World()
        val state = kit.state()

        val answer = w.setup.marta.saveDates(w.setup.id, w.to)
        val html = answer.body()
        assertEquals(200, answer.status())
        assertEquals("Cambiare la struttura?", heading(html))
        val text = words(html)
        assertTrue(text.contains("Le 2 persone nelle camere di ${venueName(w.from)} restano senza camera: le camere sono quelle della struttura."), text)
        assertTrue(text.contains("Per i genitori nome e luogo diventano «${venueName(w.to)}» e «${venuePlace(w.to)}»."), text)
        assertTrue(text.contains("Cambia la struttura") && text.contains("Annulla"), text)
        assertTrue(html.contains("""<input type="hidden" name="cambio" value="2">""") && html.contains("""<input type="hidden" name="venue" value="${w.to}">"""), "the form again, with the number")
        assertTrue(html.contains("""href="/convivenza/${w.setup.id}/date""""), "«Annulla» goes back to the form")
        assertEquals(w.from, w.venue(), "nothing was changed")
        assertEquals(state, kit.state())
        assertEquals(2, kit.peopleCount(w.setup.id))
        // A number that is not one, or less than the people, asks again, whatever it is.
        for (bad in listOf("", "x", "-1", "1", "0")) {
            val again = w.setup.marta.submit(w.page, w.page, datesFields(w.setup.id, w.to) + ("cambio" to bad))
            assertEquals(200, again.status(), "«$bad»")
            assertEquals("Cambiare la struttura?", heading(again.body()), "«$bad»")
        }
        assertEquals(w.from, w.venue())
    }

    @Test
    fun `t20 c12 with the number that the dialog said the venue changes and the people leave their rooms in the same transaction, the notice says how many, and the rooms of the venue before stay`() {
        val w = World()

        val saved = w.setup.marta.saveDates(w.setup.id, w.to, change = 2)
        assertEquals(303, saved.status())
        assertEquals("/convivenza/${w.setup.id}", saved.location())
        assertEquals(w.to, w.venue())
        assertEquals(0, kit.peopleCount(w.setup.id), "they are without a room")
        assertNull(kit.roomOfChild(w.luca))
        assertNull(kit.roomOfTeacher(w.setup.id, w.setup.paoloId))
        assertEquals(listOf("Camera 1" to 4), kit.roomsOfVenue(w.from), "the rooms of the venue before are as they were")
        val detail = words(w.setup.marta.get("/convivenza/${w.setup.id}").body())
        assertTrue(detail.contains("Titolo, date e luogo salvati. Le 2 persone nelle camere di ${venueName(w.from)} sono senza camera."), detail)
        assertFalse(words(w.setup.marta.get("/convivenza/${w.setup.id}").body()).contains("sono senza camera."), "read once")
        // The page «Camere» is the one of the new venue now, without rooms, and the child is where the page of the rooms of the venue before says nothing.
        assertTrue(words(w.setup.marta.get("/convivenza/${w.setup.id}/camere").body()).contains("${venueName(w.to)} non ha ancora camere."))
        // Back to the venue before: nobody to ask about, nobody put back in a room.
        assertEquals(303, w.setup.marta.saveDates(w.setup.id, w.from).status())
        assertEquals(w.from, w.venue())
        assertEquals(0, kit.peopleCount(w.setup.id))
        assertEquals(listOf("Camera 1" to 4), kit.roomsOf(w.setup.id))
    }

    @Test
    fun `t20 c12 one person leaves with a notice that says La persona, and more people than the dialog said ask again with the new number`() {
        val w = World()
        dsl.deleteFrom(talita.jooq.tables.references.ROOM_TEACHER).execute()

        // The dialog said one person; meanwhile another teacher put one more in the room: the server counts again under the lock and asks again with 2.
        kit.put(w.anna, w.setup.id, w.one)
        val again = w.setup.marta.saveDates(w.setup.id, w.to, change = 1)
        assertEquals(200, again.status())
        assertTrue(again.body().contains("""<input type="hidden" name="cambio" value="2">"""), "the number of now")
        assertEquals(w.from, w.venue())
        assertEquals(2, kit.peopleCount(w.setup.id))
        // With the number of now it is saved; with a number that is more than the people it is saved too (nobody is asked about people that are not there).
        assertEquals(303, w.setup.marta.saveDates(w.setup.id, w.to, change = 5).status())
        assertEquals(0, kit.peopleCount(w.setup.id))
        // One person says «La persona che era in una camera di … è senza camera.»
        val one = kit.start().let { other ->
            val room = kit.room(other.id, "Camera 1", 4)
            kit.put(kit.rows.register(other.id, kit.checkin.account(), "Sara", "Neri"), other.id, room)
            other
        }
        assertEquals(303, one.marta.saveDates(one.id, kit.venueOf(kit.otherGathering(title = "Un'altra convivenza", daysFromToday = 120)), change = 1).status())
        assertTrue(words(one.marta.get("/convivenza/${one.id}").body()).contains("La persona che era in una camera di"), "one person")
    }

    @Test
    fun `t20 c12 the same venue, a venue chosen when nobody is in the rooms, and a draft with no venue save at once, with no question and nobody taken out`() {
        val w = World()

        // The same venue, with people in the rooms: nothing to ask.
        assertEquals(303, w.setup.marta.saveDates(w.setup.id, w.from).status())
        assertEquals(2, kit.peopleCount(w.setup.id), "the people stay")
        assertEquals(w.from, w.venue())
        // A draft that has no venue takes one at once (there is nobody to ask about).
        val draft = kit.start(first = 30, last = 32, status = GatheringStatus.draft)
        dsl.update(GATHERING).set(GATHERING.VENUE_ID, null as Long?).where(GATHERING.ID.eq(draft.id)).execute()
        val chosen = kit.venueOf(kit.otherGathering())
        assertEquals(303, draft.marta.saveDates(draft.id, chosen).status())
        assertEquals(chosen, kit.venueOf(draft.id))
    }

    // ---- A choice and a change of venue at the same moment (criteria 12 and 16) ------------------------------------------------------------------------

    @Test
    fun `t20 c16 a choice of a room that comes after the change of venue is refused with the room of another venue, and nobody is left in it`() {
        val w = World()
        dsl.deleteFrom(talita.jooq.tables.references.ROOM_CHILD).execute()
        dsl.deleteFrom(talita.jooq.tables.references.ROOM_TEACHER).execute()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            // The change of venue is first and has not finished: it holds the gathering for update (as `saveDates` does) and has written the new venue.
            val changing = pool.submit {
                dsl.transaction { configuration ->
                    val tx = DSL.using(configuration)
                    tx.select(GATHERING.ID).from(GATHERING).where(GATHERING.ID.eq(w.setup.id)).forUpdate().fetch()
                    tx.update(GATHERING).set(GATHERING.VENUE_ID, w.to).where(GATHERING.ID.eq(w.setup.id)).execute()
                    held.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
            }
            held.await(20, TimeUnit.SECONDS)
            val choosing = LockWait.awaitBlocked(dsl, "the choice should wait for the change of venue, which holds the gathering") {
                pool.submit<PlaceResult> { rooms.place(w.setup.id, PersonRef.Child(w.luca), w.one) }
            }
            release.countDown()
            changing.get(20, TimeUnit.SECONDS)
            assertEquals(PlaceResult.OtherVenue, choosing.get(20, TimeUnit.SECONDS), "the room is of the venue that the gathering had")
        } finally {
            pool.shutdownNow()
        }
        assertNull(kit.roomOfChild(w.luca))
        assertEquals(w.to, w.venue())
        assertEquals(0, kit.peopleCount(w.setup.id))
    }

    @Test
    fun `t20 c16 a change of venue that comes after a choice counts the person that the choice wrote, and asks again, so nobody is left in a room of another venue`() {
        val w = World()
        dsl.deleteFrom(talita.jooq.tables.references.ROOM_CHILD).execute()
        dsl.deleteFrom(talita.jooq.tables.references.ROOM_TEACHER).execute()
        val info = reader.byId(w.setup.id)!!
        val pool = Executors.newFixedThreadPool(2)
        try {
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            // The choice is first and has not finished: the gathering is locked for sharing, as `Rooms.place` does, and the person is written.
            val choosing = pool.submit {
                dsl.transaction { configuration ->
                    val tx = DSL.using(configuration)
                    tx.select(GATHERING.ID).from(GATHERING).where(GATHERING.ID.eq(w.setup.id)).forShare().fetch()
                    tx.insertInto(talita.jooq.tables.references.ROOM_CHILD).set(talita.jooq.tables.references.ROOM_CHILD.REGISTRATION_ID, w.luca)
                        .set(talita.jooq.tables.references.ROOM_CHILD.GATHERING_ID, w.setup.id).set(talita.jooq.tables.references.ROOM_CHILD.VENUE_ID, w.from)
                        .set(talita.jooq.tables.references.ROOM_CHILD.ROOM_ID, w.one).execute()
                    held.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
            }
            held.await(20, TimeUnit.SECONDS)
            // The teacher saw «nobody» (the dialog was not there) and sends no number: the change waits for the choice, then counts and finds one person.
            val changing = LockWait.awaitBlocked(dsl, "the change of venue should wait for the choice, which holds the gathering") {
                pool.submit<DatesResult> { gatherings.saveDates(w.setup.id, info.title, w.setup.yesterday, w.setup.tomorrow, w.to, usage, arrivals, assignments, confirmedPeople = 0) }
            }
            release.countDown()
            choosing.get(20, TimeUnit.SECONDS)
            assertEquals(DatesResult.PeopleInRooms(1, venueName(w.from)), changing.get(20, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(w.from, w.venue(), "the venue did not change")
        assertEquals(w.one, kit.roomOfChild(w.luca), "the person is where the choice put them, in a room of the venue of the gathering")
    }

    // ---- A gathering with no venue, a venue with no rooms (criterion 15) ----------------------------------------------------------------------------

    @Test
    fun `t20 c15 a gathering with no venue says to choose it in Camere and in its card, each with its link, and a venue with no rooms says to write them`() {
        val setup = kit.start(first = 30, last = 32, status = GatheringStatus.draft)
        val venue = kit.venueOf(setup.id)
        dsl.update(GATHERING).set(GATHERING.VENUE_ID, null as Long?).where(GATHERING.ID.eq(setup.id)).execute()
        val page = "/convivenza/${setup.id}/camere"

        val html = setup.marta.get(page).body()
        val text = words(html)
        assertTrue(text.contains("Scegli la struttura Le camere sono quelle della struttura: scegli prima dove si fa la convivenza. Scegli la struttura"), text)
        assertTrue(html.contains("""href="/convivenza/${setup.id}/date#struttura""""), "the way to the field of the venue")
        assertFalse(text.contains("Aggiungi una camera") || text.contains("Apri la struttura") || text.contains("Copia le camere"), text)
        val card = Regex("""<section[^>]*aria-labelledby="camere".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(setup.marta.get("/convivenza/${setup.id}").body())!!.value
        assertTrue(words(card).contains("Camere Scegli la struttura Le camere sono quelle della struttura."), words(card))
        assertTrue(card.contains("""href="/convivenza/${setup.id}/date#struttura""""), card)
        // The print has nothing to print and says the same thing is missing: the house has no rooms to print.
        assertEquals(200, setup.marta.get("$page/stampa").status())

        // With a venue that has no rooms: the page and the card say to write them there.
        dsl.update(GATHERING).set(GATHERING.VENUE_ID, venue).where(GATHERING.ID.eq(setup.id)).execute()
        val name = venueName(venue)
        val empty = words(setup.marta.get(page).body())
        assertTrue(empty.contains("Nessuna camera $name non ha ancora camere. Scrivile nella pagina della struttura: poi le trovi qui. Scrivi le camere"), empty)
        val emptyCard = Regex("""<section[^>]*aria-labelledby="camere".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(setup.marta.get("/convivenza/${setup.id}").body())!!.value
        assertTrue(words(emptyCard).contains("Camere Scrivi le camere $name non ha ancora camere."), words(emptyCard))
        assertTrue(emptyCard.contains("""href="/strutture/$venue#camere""""), emptyCard)
        // The rooms are written there and come back here without a copy.
        kit.roomOfVenue(venue, "Camera azzurra", 3)
        assertTrue(words(setup.marta.get(page).body()).contains("Camera azzurra Vuota · 3 posti"))
    }

    // ---- A room changed during a gathering (criterion 16) -------------------------------------------------------------------------------------------

    @Test
    fun `t20 c16 a room changed during the gathering has its new name and beds in the page, the print and the card of the child in Arrivi, and the beds are counted again`() {
        val setup = kit.start()
        val room = kit.room(setup.id, "Camera 1", 4)
        val luca = kit.rows.register(setup.id, kit.checkin.account(), "Luca", "Rossi")
        kit.put(luca, setup.id, room)
        val venue = kit.venueOf(setup.id)
        val page = "/convivenza/${setup.id}/camere"
        assertTrue(words(setup.marta.get(page).body()).contains("Camera 1 1 persona · 4 posti"))

        // A teacher changes the room in the page of the venue.
        assertEquals(303, setup.marta.submit("/profilo", "/strutture/$venue/camere/$room/modifica", mapOf("name" to "Stanza azzurra", "beds" to "2")).status())

        val after = words(setup.marta.get(page).body())
        assertTrue(after.contains("Stanza azzurra 1 persona · 2 posti") && !after.contains("Camera 1 "), after)
        assertTrue(after.contains("Camere 1 Posti letto 2"), "the beds are counted again: $after")
        assertTrue(words(setup.marta.get("$page/stampa").body()).contains("Stanza azzurra"), "the print has the new name")
        assertFalse(words(setup.marta.get("$page/stampa").body()).contains("Camera 1 "), "and not the old one")
        assertTrue(words(setup.marta.card(luca)).contains("Camera Stanza azzurra"), "the card of the child in Arrivi has the new name")
        assertTrue(words(setup.marta.get("/convivenza/${setup.id}").body()).contains("1 camera · 2 posti"), "and the card of the detail the new beds")
        // The same room has the same id: whoever was in it is still in it.
        assertEquals(room, kit.roomOfChild(luca))
        assertEquals(1, dsl.fetchCount(ROOM, ROOM.VENUE_ID.eq(venue)))
    }

    @Test
    fun `t20 c16 two gatherings in the same venue share its rooms and not who sleeps in them, and taking a room away leaves its people of both without a room`() {
        val setup = kit.start()
        val venue = kit.venueOf(setup.id)
        val room = kit.room(setup.id, "Camera 1", 4)
        val next = kit.otherGathering("Convivenza dell'anno dopo", daysFromToday = 400, venue = venue)
        val luca = kit.rows.register(setup.id, kit.checkin.account(), "Luca", "Rossi")
        val sara = kit.rows.register(next, kit.checkin.account(), "Sara", "Neri")
        kit.put(luca, setup.id, room)
        kit.put(sara, next, room)

        assertEquals(1, kit.peopleCount(setup.id))
        assertEquals(1, kit.peopleCount(next))
        assertTrue(words(setup.marta.get("/convivenza/${setup.id}/camere").body()).contains("Luca Rossi") && !words(setup.marta.get("/convivenza/${setup.id}/camere").body()).contains("Sara Neri"))
        assertTrue(words(setup.marta.get("/convivenza/$next/camere").body()).contains("Sara Neri") && !words(setup.marta.get("/convivenza/$next/camere").body()).contains("Luca Rossi"))
        // The room goes, with the dialog that counted the two people in the two gatherings, and both are without a room.
        val removed = rooms.remove(venue, room)
        assertEquals(RemoveResult.Removed("Camera 1", 2, 2), removed)
        assertNull(kit.roomOfChild(luca))
        assertNull(kit.roomOfChild(sara))
        assertIs<RemoveResult.Removed>(removed)
    }
}
