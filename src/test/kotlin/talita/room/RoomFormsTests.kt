package talita.room

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
import talita.jooq.enums.RegistrationStatus
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The form of a room and the removal of a room (criteria 2, 3, 5, 9): the words of the page, the errors under the fields with the 422 and the fields as they were written, the
 * name that says who sleeps there, the dialog with how many people go back among those without a room, and what is said without JavaScript. Names are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RoomFormsTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = RoomTestKit(mockMvc, tokens, dsl, clock)

    private fun MockBrowser.send(path: String, fields: Map<String, String>, viaHtmx: Boolean = false): MvcResult = submit("/profilo", path, fields, if (viaHtmx) htmx() else ({}))

    // ---- The form of a new room (criteria 2, 3) -----------------------------------------------------------------------------------------------------

    @Test
    fun `t11 c02 the page of a new room has a name and the beds, with their help, no browser checks and the two buttons`() {
        val setup = kit.start()
        val response = setup.marta.get("/convivenza/${setup.id}/camere/nuova")
        val html = response.body()
        val text = words(html)

        assertEquals(200, response.status())
        assertEquals("Nuova camera", heading(html))
        assertEquals("Nuova camera · Talità", title(html))
        assertTrue(text.contains("Torna alle camere"), text)
        assertTrue(text.contains("Convivenza di settembre 2027 ·"), text)
        assertTrue(text.contains("Nome della camera Scrivi il nome della camera nella casa, per esempio «Camera 3» o «Stanza azzurra». Non scrivere chi ci dorme."), text)
        assertTrue(text.contains("Posti letto Quanti letti ci sono nella camera."), text)
        assertTrue(Regex("""<form[^>]*\snovalidate""").containsMatchIn(html), "the errors are ours")
        for (attribute in listOf("required", "maxlength", "pattern")) assertFalse(Regex("""<input[^>]*\s$attribute\b""").containsMatchIn(html), "no $attribute")
        assertTrue(Regex("""<input id="posti"[^>]*inputmode="numeric"[^>]*autocomplete="off"|<input id="posti"[^>]*autocomplete="off"[^>]*inputmode="numeric"""").containsMatchIn(html), "numeric keyboard on the phone")
        assertTrue(Regex("""<input id="nome"[^>]*autocomplete="off"[^>]*autocapitalize="sentences"""").containsMatchIn(html))
        assertTrue(text.contains("Salva la camera") && text.contains("Annulla"), text)
        assertTrue(html.contains("""href="/convivenza/${setup.id}/camere"""") && html.contains("""action="/convivenza/${setup.id}/camere/nuova""""))
    }

    @Test
    fun `t11 c02 a good form makes the room, with the name without the spaces at the sides, and the page says Camera salvata once`() {
        val setup = kit.start()
        val page = "/convivenza/${setup.id}/camere"

        val answer = setup.marta.send("$page/nuova", mapOf("name" to "  Stanza azzurra ", "beds" to " 2 "))
        assertEquals(303, answer.status())
        assertEquals(page, answer.location())
        assertEquals(listOf("Stanza azzurra" to 2), kit.roomsOf(setup.id))
        val saved = words(setup.marta.get(page).body())
        assertTrue(saved.contains("Camera salvata."), saved)
        assertTrue(saved.contains("Stanza azzurra") && saved.contains("Vuota · 2 posti"), saved)
        assertFalse(words(setup.marta.get(page).body()).contains("Camera salvata."), "read once")
        // Names that are the house are all fine, whatever they look like.
        for (name in listOf("Camera 3", "Primo piano, a destra")) assertEquals(303, setup.marta.send("$page/nuova", mapOf("name" to name, "beds" to "4")).status(), name)
        assertEquals(3, kit.roomCount(setup.id))
    }

    @Test
    fun `t11 c02 a form with a wrong field comes back with the 422, the error under the field and the fields as they were written, and nothing is written`() {
        val setup = kit.start()
        val page = "/convivenza/${setup.id}/camere"
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
    fun `t11 c03 a name that says the sex of who sleeps there is refused with the sentence of the house, the field stays as it was written and nothing is written`() {
        val setup = kit.start()
        val page = "/convivenza/${setup.id}/camere"

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

    // ---- The form of a room to change (criteria 2, 9) ------------------------------------------------------------------------------------------------

    @Test
    fun `t11 c02 the form of a room to change has its name and beds, and says how many people sleep there now`() {
        val setup = kit.start()
        val room = kit.room(setup.id, "Camera 1", 4)
        val page = "/convivenza/${setup.id}/camere"

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
    fun `t11 c02 a change keeps its own name with other capitals, takes none of another room, and lowering the beds under the people is saved with the room saying so`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        kit.room(setup.id, "Camera 2", 4)
        for (name in listOf("Luca", "Anna", "Sara", "Elena")) kit.put(kit.rows.register(setup.id, kit.checkin.account(), name, "Rossi"), setup.id, one)
        val page = "/convivenza/${setup.id}/camere"

        assertEquals(303, setup.marta.send("$page/$one/modifica", mapOf("name" to "CAMERA 1", "beds" to "6")).status())
        assertEquals(listOf("CAMERA 1" to 6, "Camera 2" to 4), kit.roomsOf(setup.id))
        val taken = setup.marta.send("$page/$one/modifica", mapOf("name" to "camera 2", "beds" to "6"))
        assertEquals(422, taken.status())
        assertEquals("C’è già una camera con questo nome.", errorOf(taken.body(), "nome"))
        assertEquals(listOf("CAMERA 1" to 6, "Camera 2" to 4), kit.roomsOf(setup.id))
        // Lower than the four people who sleep there: saved, with no other confirmation than the room that says it.
        assertEquals(303, setup.marta.send("$page/$one/modifica", mapOf("name" to "CAMERA 1", "beds" to "3")).status())
        val html = setup.marta.get(page).body()
        assertTrue(words(html).contains("Camera salvata."), "the confirmation of the save")
        val card = Regex("""<section id="camera-$one".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        assertTrue(words(card).contains("4 persone · 3 posti 4 persone per 3 posti."), words(card))
        assertEquals(4, kit.peopleCount(setup.id), "nobody was taken out")
    }

    // ---- Taking a room away (criterion 5) ----------------------------------------------------------------------------------------------------------

    @Test
    fun `t11 c05 a room with people has a dialog that says how many go back among those without a room, and a room that is empty is taken away at once with no dialog`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        val two = kit.room(setup.id, "Camera 2", 4)
        val empty = kit.room(setup.id, "Camera 3", 2)
        for (name in listOf("Luca", "Anna", "Sara", "Elena")) kit.put(kit.rows.register(setup.id, kit.checkin.account(), name, "Rossi"), setup.id, one)
        kit.putTeacher(setup.id, setup.paoloId, one)
        kit.put(kit.rows.register(setup.id, kit.checkin.account(), "Federico", "Longo"), setup.id, two)
        val html = setup.marta.get("/convivenza/${setup.id}/camere").body()

        val dialog = Regex("""<dialog id="togli-$one".*?</dialog>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        assertTrue(words(dialog).contains("Togliere Camera 1? Camera 1 sparisce dall’elenco. Le 5 persone che ci dormono tornano fra quelle senza camera. Togli la camera Tolgo… Annulla"), words(dialog))
        assertTrue(dialog.contains("""action="/convivenza/${setup.id}/camere/$one/togli"""") && dialog.contains("""hx-target="#camere-contenuto""""), dialog)
        val single = Regex("""<dialog id="togli-$two".*?</dialog>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        assertTrue(words(single).contains("Camera 2 sparisce dall’elenco. La persona che ci dorme torna fra quelle senza camera."), words(single))
        assertFalse(dialog.contains("bg-danger"), "the button is the main one, not the one of danger: nothing is lost that is not put back with two touches")
        assertFalse(html.contains("""id="togli-$empty""""), "a room where nobody sleeps has no dialog")
        // The button of «Togli» of a room with people opens the dialog (and, without JavaScript, leads to its page); the one of an empty room sends the request.
        assertTrue(Regex("""<a href="/convivenza/${setup.id}/camere/$one/togli" data-apri="togli-$one" aria-label="Togli Camera 1"""").containsMatchIn(html))
        val emptyCard = Regex("""<section id="camera-$empty".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        assertTrue(emptyCard.contains("""action="/convivenza/${setup.id}/camere/$empty/togli"""") && emptyCard.contains("""aria-label="Togli Camera 3""""), emptyCard)
    }

    @Test
    fun `t11 c05 taking a room away answers with the block, Camera tolta and the people among those without a room, and an empty room goes at once`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        val empty = kit.room(setup.id, "Camera 2", 4)
        val luca = kit.rows.register(setup.id, kit.checkin.account(), "Luca", "Rossi")
        kit.put(luca, setup.id, one)
        kit.putTeacher(setup.id, setup.paoloId, one)
        val page = "/convivenza/${setup.id}/camere"

        val answer = setup.marta.send("$page/$one/togli", mapOf("camera" to one.toString()), viaHtmx = true)
        assertEquals(200, answer.status())
        val text = words(answer.body())
        assertTrue(answer.body().trimStart().startsWith("<div id=\"camere-contenuto\">"))
        assertTrue(text.contains("Camera tolta."), text)
        assertFalse(text.contains("Camera 1 "), "the room is gone from the list")
        assertTrue(text.contains("Camere 1 Posti letto 4") && text.contains("Bambini senza camera 1"), text)
        assertTrue(text.contains("Luca Rossi") && text.contains("Paolo Verdi"), "who was in it is among those without a room")
        assertNull(kit.roomOfChild(luca))
        // Already taken away by another teacher: the state as it is, with the same words and no error.
        val again = setup.marta.send("$page/$one/togli", mapOf("camera" to one.toString()), viaHtmx = true)
        assertEquals(200, again.status())
        assertTrue(words(again.body()).contains("Camera tolta."))
        // The empty one goes with no dialog, and without JavaScript with a redirect.
        val plain = setup.marta.send("$page/$empty/togli", emptyMap())
        assertEquals(303, plain.status())
        assertEquals(page, plain.location())
        assertEquals(0, kit.roomCount(setup.id))
        assertTrue(words(setup.marta.get(page).body()).contains("Camera tolta."))
    }

    @Test
    fun `t11 c05 without JavaScript the page of confirmation has the words of the dialog and Annulla leads back to the rooms`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        kit.put(kit.rows.register(setup.id, kit.checkin.account(), "Luca", "Rossi"), setup.id, one)
        val page = "/convivenza/${setup.id}/camere"

        val response = setup.marta.get("$page/$one/togli")
        val html = response.body()
        assertEquals(200, response.status())
        assertEquals("Togliere Camera 1?", heading(html))
        assertTrue(words(html).contains("Camera 1 sparisce dall’elenco. La persona che ci dorme torna fra quelle senza camera. Togli la camera Tolgo… Annulla"), words(html))
        assertTrue(html.contains("""action="$page/$one/togli"""") && html.contains("""href="$page""""))
        // The page of a room that is not there, or of another gathering, is not found.
        assertEquals(404, setup.marta.get("$page/${one + 100_000}/togli").status())
        val elsewhere = kit.room(kit.otherGathering(), "Camera 1", 4)
        assertEquals(404, setup.marta.get("$page/$elsewhere/togli").status())
        assertEquals(404, setup.marta.get("$page/$elsewhere/modifica").status())
    }

    @Test
    fun `t11 c05 a registration that was withdrawn is not a person of the room when the room is taken away or changed`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        val withdrawn = kit.rows.register(setup.id, kit.checkin.account(), "Chiara", "Fontana", status = RegistrationStatus.withdrawn)
        // A row that a backup brought back for a registration that is withdrawn: the page does not count it among the people.
        kit.put(withdrawn, setup.id, one)
        val html = setup.marta.get("/convivenza/${setup.id}/camere").body()
        assertFalse(words(html).contains("Chiara Fontana"), "a withdrawn registration is no one of the room")
    }
}
