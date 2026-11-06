package talita.venue

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.checkin.htmx
import talita.checkin.words
import talita.gathering.body
import talita.gathering.errorOf
import talita.gathering.heading
import talita.gathering.location
import talita.gathering.status
import talita.gathering.title
import talita.gathering.valueOf
import talita.jooq.tables.references.VENUE
import talita.room.RoomTestKit
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The form of a room and the removal of a room, in the page of the venue (TALITA-20, criteria 13 and 16; the forms of TALITA-11, criteria 2, 3, 5 and 9, moved here with the rooms): the words of the page, the errors under
 * the fields with the 422 and the fields as they were written, the name that says who sleeps there, the dialog with how many people go back among those without a room and in how many gatherings, and what is said without
 * JavaScript. The rooms are written **at any moment**, whatever the state of the gatherings. Names are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class VenueRoomFormsTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = RoomTestKit(mockMvc, tokens, dsl, clock)

    private fun MockBrowser.send(path: String, fields: Map<String, String>, viaHtmx: Boolean = false): MvcResult = submit("/profilo", path, fields, if (viaHtmx) htmx() else ({}))

    private fun venueName(venue: Long): String = dsl.select(VENUE.NAME).from(VENUE).where(VENUE.ID.eq(venue)).fetchSingle(VENUE.NAME)!!

    // ---- The form of a new room (criteria 13) ------------------------------------------------------------------------------------------------------

    @Test
    fun `t20 c13 the page of a new room has a name and the beds, with their help, no browser checks and the two buttons, and the venue under the title`() {
        val setup = kit.start()
        val venue = kit.venueOf(setup.id)
        val response = setup.marta.get("/strutture/$venue/camere/nuova")
        val html = response.body()
        val text = words(html)

        assertEquals(200, response.status())
        assertEquals("Nuova camera", heading(html))
        assertEquals("Nuova camera · Talità", title(html))
        assertTrue(text.contains("Torna alla struttura"), text)
        assertTrue(text.contains(venueName(venue)), "the venue is under the title: $text")
        assertTrue(text.contains("Nome della camera Scrivi il nome della camera nella casa, per esempio «Camera 3» o «Stanza azzurra». Non scrivere chi ci dorme."), text)
        assertTrue(text.contains("Posti letto Quanti letti ci sono nella camera."), text)
        assertTrue(Regex("""<form[^>]*\snovalidate""").containsMatchIn(html), "the errors are ours")
        for (attribute in listOf("required", "maxlength", "pattern")) assertFalse(Regex("""<input[^>]*\s$attribute\b""").containsMatchIn(html), "no $attribute")
        assertTrue(Regex("""<input id="posti"[^>]*inputmode="numeric"[^>]*autocomplete="off"|<input id="posti"[^>]*autocomplete="off"[^>]*inputmode="numeric"""").containsMatchIn(html), "numeric keyboard on the phone")
        assertTrue(Regex("""<input id="nome"[^>]*autocomplete="off"[^>]*autocapitalize="sentences"""").containsMatchIn(html))
        assertTrue(text.contains("Salva la camera") && text.contains("Annulla"), text)
        assertTrue(html.contains("""href="/strutture/$venue#camere"""") && html.contains("""action="/strutture/$venue/camere/nuova""""))
        // The page of a venue that is not there is not found, and so is the form of its rooms.
        assertEquals(404, setup.marta.get("/strutture/${venue + 100_000}/camere/nuova").status())
    }

    @Test
    fun `t20 c13 a good form makes the room, with the name without the spaces at the sides, and the page of the venue says Camera salvata once`() {
        val setup = kit.start()
        val venue = kit.venueOf(setup.id)
        val page = "/strutture/$venue"

        val answer = setup.marta.send("$page/camere/nuova", mapOf("name" to "  Stanza azzurra ", "beds" to " 2 "))
        assertEquals(303, answer.status())
        assertEquals("$page#camere", answer.location())
        assertEquals(listOf("Stanza azzurra" to 2), kit.roomsOf(setup.id))
        val saved = words(setup.marta.get(page).body())
        assertTrue(saved.contains("Camera salvata."), saved)
        assertTrue(saved.contains("Stanza azzurra 2 posti"), saved)
        assertFalse(words(setup.marta.get(page).body()).contains("Camera salvata."), "read once")
        // Names that are the house are all fine, whatever they look like.
        for (name in listOf("Camera 3", "Primo piano, a destra")) assertEquals(303, setup.marta.send("$page/camere/nuova", mapOf("name" to name, "beds" to "4")).status(), name)
        assertEquals(3, kit.roomCount(setup.id))
        // A gathering that uses the venue finds the rooms in its page «Camere», without copying anything.
        assertTrue(words(setup.marta.get("/convivenza/${setup.id}/camere").body()).contains("Primo piano, a destra"))
    }

    @Test
    fun `t20 c13 the rooms are written at any moment, whatever the state of the gatherings that use the venue, also when they are over`() {
        val setup = kit.start()
        val venue = kit.venueOf(setup.id)
        kit.endedDaysAgo(setup.id, 5)

        assertEquals(303, setup.marta.send("/strutture/$venue/camere/nuova", mapOf("name" to "Camera 1", "beds" to "4")).status())
        val room = kit.roomId(setup.id, "Camera 1")
        assertEquals(303, setup.marta.send("/strutture/$venue/camere/$room/modifica", mapOf("name" to "Camera 1 bis", "beds" to "6")).status())
        assertEquals(listOf("Camera 1 bis" to 6), kit.roomsOf(setup.id))
        assertEquals(303, setup.marta.send("/strutture/$venue/camere/$room/togli", emptyMap()).status())
        assertEquals(0, kit.roomCount(setup.id))
    }

    @Test
    fun `t20 c13 a form with a wrong field comes back with the 422, the error under the field and the fields as they were written, and nothing is written`() {
        val setup = kit.start()
        val page = "/strutture/${kit.venueOf(setup.id)}/camere"
        kit.room(setup.id, "Camera 1", 4)
        val state = kit.state()

        fun check(name: String, beds: String, nameError: String?, bedsError: String?) {
            val answer = setup.marta.send("$page/nuova", mapOf("name" to name, "beds" to beds))
            val html = answer.body()
            assertEquals(422, answer.status(), "«$name» «$beds»")
            assertEquals(nameError, errorOf(html, "nome"), "«$name»")
            assertEquals(bedsError, errorOf(html, "posti"), "«$beds»")
            assertEquals(name.trim(), valueOf(html, "nome"), "the field is as it was written")
            assertEquals(beds.trim(), valueOf(html, "posti"), "the field is as it was written")
            assertEquals(nameError != null, Regex("""<input id="nome"[^>]*aria-invalid="true"""").containsMatchIn(html), "the invalid field says so")
            assertEquals(bedsError != null, Regex("""<input id="posti"[^>]*aria-invalid="true"""").containsMatchIn(html), "the invalid field says so")
            assertEquals(state, kit.state(), "nothing was written")
        }
        check("", "4", "Scrivi il nome della camera.", null)
        check("   ", "4", "Scrivi il nome della camera.", null)
        check("a".repeat(51), "4", "Usa al massimo 50 caratteri.", null)
        check("camera 1", "4", "C’è già una camera con questo nome.", null)
        check("  CAMERA 1 ", "4", "C’è già una camera con questo nome.", null)
        check("Camera 2", "", null, "Scrivi quanti posti ci sono, da 1 a 50.")
        check("Camera 2", "0", null, "Scrivi quanti posti ci sono, da 1 a 50.")
        check("Camera 2", "51", null, "Scrivi quanti posti ci sono, da 1 a 50.")
        check("Camera 2", "tanti", null, "Scrivi quanti posti ci sono, da 1 a 50.")
        // Two wrong fields: a notice on top that counts them, and the focus goes to the first wrong field.
        val both = setup.marta.send("$page/nuova", mapOf("name" to "", "beds" to "x"))
        assertEquals(422, both.status())
        assertTrue(words(both.body()).contains("Controlla i 2 campi segnati in rosso."), words(both.body()))
        assertTrue(both.body().contains("""role="alert""""), "the notice is announced")
        // One wrong field: no notice on top, the error is under the field.
        assertFalse(words(setup.marta.send("$page/nuova", mapOf("name" to "", "beds" to "4")).body()).contains("Controlla i"))
    }

    @Test
    fun `t20 c13 a name that says the sex of who sleeps there is refused with the sentence of the house, the field stays as it was written and nothing is written`() {
        val setup = kit.start()
        val page = "/strutture/${kit.venueOf(setup.id)}/camere"

        for (bad in listOf("Maschi", "camera delle femmine", "Bambine")) {
            val answer = setup.marta.send("$page/nuova", mapOf("name" to bad, "beds" to "4"))
            assertEquals(422, answer.status(), bad)
            assertEquals("Scrivi il nome della camera nella casa, non chi ci dorme.", errorOf(answer.body(), "nome"), bad)
            assertEquals(bad, valueOf(answer.body(), "nome"), "the field is as it was written")
            assertEquals(0, kit.roomCount(setup.id), "nothing is written")
        }
        // The same net on a change.
        val room = kit.room(setup.id, "Camera 1", 4)
        val change = setup.marta.send("$page/$room/modifica", mapOf("name" to "Maschi", "beds" to "4"))
        assertEquals(422, change.status())
        assertEquals("Scrivi il nome della camera nella casa, non chi ci dorme.", errorOf(change.body(), "nome"))
        assertEquals(listOf("Camera 1" to 4), kit.roomsOf(setup.id))
        // What is the house goes, and a name of a person is not recognised: the help under the field is the only defence.
        assertEquals(303, setup.marta.send("$page/nuova", mapOf("name" to "Primo piano, a destra", "beds" to "2")).status())
    }

    // ---- The form of a room to change (criteria 13) ------------------------------------------------------------------------------------------------

    @Test
    fun `t20 c13 the form of a room to change has its name and beds, and says how many people sleep there now`() {
        val setup = kit.start()
        val room = kit.room(setup.id, "Camera 1", 4)
        val page = "/strutture/${kit.venueOf(setup.id)}/camere"

        val empty = setup.marta.get("$page/$room/modifica").body()
        assertEquals("Modifica la camera", heading(empty))
        assertEquals("Modifica la camera · Talità", title(empty))
        assertEquals("Camera 1", valueOf(empty, "nome"))
        assertEquals("4", valueOf(empty, "posti"))
        assertFalse(words(empty).contains("Ora ci dorm"), "nobody sleeps there")
        assertTrue(empty.contains("""action="$page/$room/modifica""""))

        kit.put(kit.rows.register(setup.id, kit.checkin.account(), "Luca", "Rossi"), setup.id, room)
        assertTrue(words(setup.marta.get("$page/$room/modifica").body()).contains("Quanti letti ci sono nella camera. Ora ci dorme 1 persona."))
        for (name in listOf("Anna", "Sara", "Elena", "Matilde")) kit.put(kit.rows.register(setup.id, kit.checkin.account(), name, "Rossi"), setup.id, room)
        assertTrue(words(setup.marta.get("$page/$room/modifica").body()).contains("Quanti letti ci sono nella camera. Ora ci dormono 5 persone."))
    }

    @Test
    fun `t20 c13 a change keeps its own name with other capitals, takes none of another room, and lowering the beds under the people is saved with the room of the gathering saying so`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        kit.room(setup.id, "Camera 2", 4)
        for (name in listOf("Luca", "Anna", "Sara", "Elena")) kit.put(kit.rows.register(setup.id, kit.checkin.account(), name, "Rossi"), setup.id, one)
        val venue = kit.venueOf(setup.id)
        val page = "/strutture/$venue/camere"

        assertEquals(303, setup.marta.send("$page/$one/modifica", mapOf("name" to "CAMERA 1", "beds" to "6")).status())
        assertEquals(listOf("CAMERA 1" to 6, "Camera 2" to 4), kit.roomsOf(setup.id))
        val taken = setup.marta.send("$page/$one/modifica", mapOf("name" to "camera 2", "beds" to "6"))
        assertEquals(422, taken.status())
        assertEquals("C’è già una camera con questo nome.", errorOf(taken.body(), "nome"))
        assertEquals(listOf("CAMERA 1" to 6, "Camera 2" to 4), kit.roomsOf(setup.id))
        // Lower than the four people who sleep there: saved, with no other confirmation than the room that says it, in the page of the gathering.
        assertEquals(303, setup.marta.send("$page/$one/modifica", mapOf("name" to "CAMERA 1", "beds" to "3")).status())
        assertTrue(words(setup.marta.get("/strutture/$venue").body()).contains("Camera salvata."), "the confirmation of the save is in the page of the venue")
        val html = setup.marta.get("/convivenza/${setup.id}/camere").body()
        val card = Regex("""<section id="camera-$one".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        assertTrue(words(card).contains("4 persone · 3 posti 4 persone per 3 posti."), words(card))
        assertEquals(4, kit.peopleCount(setup.id), "nobody was taken out")
    }

    // ---- Taking a room away (criterion 13) -----------------------------------------------------------------------------------------------------------

    @Test
    fun `t20 c13 a room with people has a dialog that says how many go back among those without a room and in how many gatherings, and a room that is empty is taken away at once with no dialog`() {
        val setup = kit.start()
        val venue = kit.venueOf(setup.id)
        val one = kit.room(setup.id, "Camera 1", 4)
        val two = kit.room(setup.id, "Camera 2", 4)
        val empty = kit.room(setup.id, "Camera 3", 2)
        for (name in listOf("Luca", "Anna", "Sara", "Elena")) kit.put(kit.rows.register(setup.id, kit.checkin.account(), name, "Rossi"), setup.id, one)
        kit.putTeacher(setup.id, setup.paoloId, one)
        kit.put(kit.rows.register(setup.id, kit.checkin.account(), "Federico", "Longo"), setup.id, two)
        // The gathering of the year after, in the same house, has somebody in Camera 1 too: the dialog counts them, and says in how many gatherings.
        val next = kit.otherGathering(venue = venue)
        kit.put(kit.rows.register(next, kit.checkin.account(), "Marco", "Esposito"), next, one)
        val html = setup.marta.get("/strutture/$venue").body()

        val dialog = Regex("""<dialog id="togli-camera-$one".*?</dialog>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        assertTrue(words(dialog).contains("Togliere Camera 1? Camera 1 sparisce dalla struttura. Le 6 persone che ci dormono tornano fra quelle senza camera. Succede in 2 convivenze. Togli la camera Tolgo… Annulla"), words(dialog))
        assertTrue(dialog.contains("""action="/strutture/$venue/camere/$one/togli"""") && dialog.contains("""hx-target="#camere-struttura""""), dialog)
        val single = Regex("""<dialog id="togli-camera-$two".*?</dialog>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        assertTrue(words(single).contains("Camera 2 sparisce dalla struttura. La persona che ci dorme torna fra quelle senza camera."), words(single))
        assertFalse(words(single).contains("Succede in"), "one gathering only: nothing more is said")
        assertFalse(dialog.contains("bg-danger"), "the button is the main one, not the one of danger: nothing is lost that is not put back with two touches")
        assertFalse(html.contains("""id="togli-camera-$empty""""), "a room where nobody sleeps has no dialog")
        // The button of «Togli» of a room with people opens the dialog (and, without JavaScript, leads to its page); the one of an empty room sends the request.
        assertTrue(Regex("""<a href="/strutture/$venue/camere/$one/togli" data-apri="togli-camera-$one" aria-label="Togli Camera 1"""").containsMatchIn(html))
        assertTrue(html.contains("""action="/strutture/$venue/camere/$empty/togli"""") && html.contains("""aria-label="Togli Camera 3""""), "the form of the empty room is the button")
    }

    @Test
    fun `t20 c13 the people of a gathering that is over do not count in the dialog, and a room where nobody sleeps any more says so with the zero in words`() {
        val setup = kit.start()
        val venue = kit.venueOf(setup.id)
        val one = kit.room(setup.id, "Camera 1", 4)
        val two = kit.room(setup.id, "Camera 2", 4)
        kit.put(kit.rows.register(setup.id, kit.checkin.account(), "Luca", "Rossi"), setup.id, one)
        val page = "/strutture/$venue/camere"

        // The link of «Togli» was drawn when Camera 2 had a person and is followed after the person has been taken out.
        val empty = words(setup.marta.get("$page/$two/togli").body())
        assertTrue(empty.contains("Togliere Camera 2? Camera 2 sparisce dalla struttura. Nessuno ci dorme. Togli la camera Tolgo… Annulla"), empty)
        assertFalse(empty.contains("0 persone") || empty.contains("Le 0"), empty)
        // One person and several are as they were.
        assertTrue(words(setup.marta.get("$page/$one/togli").body()).contains("Camera 1 sparisce dalla struttura. La persona che ci dorme torna fra quelle senza camera."))
        kit.put(kit.rows.register(setup.id, kit.checkin.account(), "Anna", "Rossi"), setup.id, one)
        assertTrue(words(setup.marta.get("$page/$one/togli").body()).contains("Camera 1 sparisce dalla struttura. Le 2 persone che ci dormono tornano fra quelle senza camera."))
        // The gathering is over: its people are deleted in the night of the end and nobody counts them, so the dialog has gone and the room is taken away at once.
        kit.endedDaysAgo(setup.id, 1)
        assertTrue(words(setup.marta.get("$page/$one/togli").body()).contains("Camera 1 sparisce dalla struttura. Nessuno ci dorme."))
        assertFalse(setup.marta.get("/strutture/$venue").body().contains("""id="togli-camera-$one""""), "a gathering that is over has no people to count")
    }

    @Test
    fun `t20 c13 taking a room away answers with the block, Camera tolta and the people among those without a room, and an empty room goes at once`() {
        val setup = kit.start()
        val venue = kit.venueOf(setup.id)
        val one = kit.room(setup.id, "Camera 1", 4)
        val empty = kit.room(setup.id, "Camera 2", 4)
        val luca = kit.rows.register(setup.id, kit.checkin.account(), "Luca", "Rossi")
        kit.put(luca, setup.id, one)
        kit.putTeacher(setup.id, setup.paoloId, one)
        val page = "/strutture/$venue"

        val answer = setup.marta.send("$page/camere/$one/togli", mapOf("camera" to one.toString()), viaHtmx = true)
        assertEquals(200, answer.status())
        val text = words(answer.body())
        assertTrue(answer.body().trimStart().startsWith("<div id=\"camere-struttura\">"))
        assertTrue(text.contains("Camera tolta."), text)
        assertFalse(text.contains("Camera 1 "), "the room is gone from the list")
        assertTrue(text.contains("Camera 2 4 posti") && text.contains("1 camera · 4 posti."), text)
        assertTrue(answer.body().contains("""<p hx-swap-oob="innerHTML:#esito">Camera tolta.</p>"""), "the sentence for a screen reader")
        assertNull(kit.roomOfChild(luca))
        // Who was in it is among those without a room, in the page «Camere» of the gathering.
        val free = words(setup.marta.get("/convivenza/${setup.id}/camere").body())
        assertTrue(free.contains("Luca Rossi") && free.contains("Paolo Verdi"), free)
        // Already taken away by another teacher: the state as it is, with its own words and no error.
        val again = setup.marta.send("$page/camere/$one/togli", mapOf("camera" to one.toString()), viaHtmx = true)
        assertEquals(200, again.status())
        assertTrue(words(again.body()).contains("La camera era già stata tolta."), words(again.body()))
        // The empty one goes with no dialog, and without JavaScript with a redirect.
        val plain = setup.marta.send("$page/camere/$empty/togli", emptyMap())
        assertEquals(303, plain.status())
        assertEquals("$page#camere", plain.location())
        assertEquals(0, kit.roomCount(setup.id))
        assertTrue(words(setup.marta.get(page).body()).contains("Camera tolta."))
        assertFalse(words(setup.marta.get(page).body()).contains("Camera tolta."), "read once")
        // The same with a room that is not there any more, without JavaScript: the state as it is.
        assertEquals(303, setup.marta.send("$page/camere/$empty/togli", emptyMap()).status())
        assertTrue(words(setup.marta.get(page).body()).contains("La camera era già stata tolta."))
    }

    @Test
    fun `t20 c13 without JavaScript the page of confirmation has the words of the dialog and Annulla leads back to the rooms of the venue`() {
        val setup = kit.start()
        val venue = kit.venueOf(setup.id)
        val one = kit.room(setup.id, "Camera 1", 4)
        kit.put(kit.rows.register(setup.id, kit.checkin.account(), "Luca", "Rossi"), setup.id, one)
        val page = "/strutture/$venue/camere"

        val response = setup.marta.get("$page/$one/togli")
        val html = response.body()
        assertEquals(200, response.status())
        assertEquals("Togliere Camera 1?", heading(html))
        assertTrue(words(html).contains("Camera 1 sparisce dalla struttura. La persona che ci dorme torna fra quelle senza camera. Togli la camera Tolgo… Annulla"), words(html))
        assertTrue(html.contains("""action="$page/$one/togli"""") && html.contains("""href="/strutture/$venue#camere""""))
        // The page of a room that is not there, or of another venue, is not found, whatever the address says.
        assertEquals(404, setup.marta.get("$page/${one + 100_000}/togli").status())
        val elsewhere = kit.room(kit.otherGathering(), "Camera 1", 4)
        assertEquals(404, setup.marta.get("$page/$elsewhere/togli").status())
        assertEquals(404, setup.marta.get("$page/$elsewhere/modifica").status())
        assertEquals(404, setup.marta.send("$page/$elsewhere/modifica", mapOf("name" to "Camera 9", "beds" to "2")).status())
        assertEquals(404, setup.marta.send("$page/$elsewhere/togli", emptyMap()).status())
        assertEquals(1, kit.roomsOfVenue(kit.venueOfRoom(elsewhere)).size, "the room of the other venue is still there")
    }

    @Test
    fun `t20 c13 a registration that was withdrawn is not a person of the room when the room is taken away or changed`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        val withdrawn = kit.rows.register(setup.id, kit.checkin.account(), "Chiara", "Fontana", status = talita.jooq.enums.RegistrationStatus.withdrawn)
        // A row that a backup brought back for a registration that is withdrawn: the dialog does not count it among the people.
        kit.put(withdrawn, setup.id, one)
        val html = setup.marta.get("/strutture/${kit.venueOf(setup.id)}").body()
        assertFalse(words(html).contains("Chiara Fontana"), "a withdrawn registration is no one of the room")
        assertFalse(html.contains("""id="togli-camera-$one""""), "nobody sleeps there: no dialog")
    }
}
