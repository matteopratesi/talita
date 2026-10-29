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
import talita.checkin.CheckinSetup
import talita.checkin.htmx
import talita.checkin.words
import talita.gathering.GatheringFormat
import talita.gathering.body
import talita.gathering.heading
import talita.gathering.location
import talita.gathering.status
import talita.gathering.title
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.registration.DayKind
import talita.registration.Presence
import talita.registration.RegistrationFormat
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The page «Camere» for the teachers (criteria 1, 4-10, 13): what it says, the choices (with htmx and without), the notices, the answers that are always the whole block and the
 * states of the gathering. Names, rooms and numbers are synthetic; the dates come from the day of the test, and the ages from the dates with the same formatter that the pages use.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RoomPagesTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = RoomTestKit(mockMvc, tokens, dsl, clock)

    /** The world of the examples: three rooms, Luca and Anna Rossi every day, Nicolò Ferri for lunch only, Sara Neri in doubt, and the custodians Marta Bianchi and Paolo Verdi. */
    private inner class World(withPeople: Boolean = true) {
        val setup: CheckinSetup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        val two = kit.room(setup.id, "Camera 2", 4)
        val azzurra = kit.room(setup.id, "Stanza azzurra", 2)
        val birth = LocalDate.of(2016, 4, 4)
        val parent = kit.checkin.account()
        val luca = kit.rows.register(setup.id, parent, "Luca", "Rossi", birth)
        val anna = kit.rows.register(setup.id, parent, "Anna", "Rossi", birth.plusYears(2))
        val nicolo = kit.rows.register(setup.id, null, "Nicolò", "Ferri", birth.plusYears(1), enteredBy = setup.martaId, days = mapOf(setup.tomorrow to "lunch"))
        val sara = kit.rows.register(setup.id, kit.checkin.account(), "Sara", "Neri", birth.plusYears(1), status = RegistrationStatus.maybe, days = mapOf(setup.today to "full", setup.tomorrow to "full"))
        val davide = kit.rows.register(setup.id, kit.checkin.account(), "Davide", "Costa", birth.plusYears(3))
        val marta = setup.marta
        val page = "/convivenza/${setup.id}/camere"

        init {
            if (withPeople) {
                kit.put(luca, setup.id, one)
                kit.put(anna, setup.id, two)
                kit.putTeacher(setup.id, setup.paoloId, azzurra)
            }
        }

        fun get(): String = marta.get(page).body()

        fun age(birth: LocalDate): String = RegistrationFormat.ageText(birth, setup.yesterday)
    }

    /** A touch as the page sends it: the code against forgery comes from a page that has a form, and htmx says that the answer is the block. */
    private fun MockBrowser.touch(world: World, tail: String, fields: Map<String, String>, viaHtmx: Boolean = true): MvcResult =
        submit("/profilo", world.page + tail, fields, if (viaHtmx) htmx() else ({}))

    private fun roomCard(html: String, name: String): String =
        Regex("""<section id="camera-\d+"[^>]*>.*?</section>""", RegexOption.DOT_MATCHES_ALL).findAll(html).map { it.value }.first { Regex("""<h3[^>]*>$name</h3>""").containsMatchIn(it) }

    private fun freeSection(html: String): String = Regex("""<section id="senza-camera".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value

    // ---- The page (criterion 1) ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `t11 c01 the page says the summary, the rooms in the order they were written with who sleeps there, and who has no room at the bottom`() {
        val w = World()
        val response = w.marta.get(w.page)
        val html = response.body()
        val text = words(html)

        assertEquals(200, response.status())
        assertEquals("Camere", heading(html))
        assertEquals("Camere · Talità", title(html))
        assertTrue(text.contains("Torna alla convivenza"), text)
        assertTrue(text.contains("Convivenza di settembre 2027 · ${GatheringFormat.range(w.setup.yesterday, w.setup.tomorrow)}"), text)
        assertTrue(text.contains("Metti ogni bambino e ogni maestro nella sua camera. Le camere le vedono solo i maestri."), text)
        // The summary: the numbers, and «bambini e maestri» under the people in the rooms.
        val summary = words(Regex("""<section id="riepilogo".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value)
        assertTrue(summary.contains("Camere 3 Posti letto 10 Persone in camera 3 bambini e maestri Bambini senza camera 2"), summary)
        assertTrue(summary.contains("Stampa"), summary)
        // The rooms, in the order they were written, each with name, people and beds, and who sleeps there: children by surname and name with age and what they come for, then teachers.
        assertTrue(Regex("""id="camera-${w.one}".*id="camera-${w.two}".*id="camera-${w.azzurra}"""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(html))
        assertTrue(words(roomCard(html, "Camera 1")).contains("Camera 1 1 persona · 4 posti Luca Rossi ${w.age(w.birth)} · Tutti i giorni"), words(roomCard(html, "Camera 1")))
        assertTrue(words(roomCard(html, "Stanza azzurra")).contains("Stanza azzurra 1 persona · 2 posti Paolo Verdi Maestro"), words(roomCard(html, "Stanza azzurra")))
        // Who has no room: the children (not Nicolò, who comes for lunch), Sara «In forse», and then the custodians who have none.
        val free = words(freeSection(html))
        assertTrue(free.contains("Senza camera 3"), free)
        assertTrue(free.contains("Bambini · 2"), free)
        val saraDays = RoomFormat.presence(Presence(false, mapOf(w.setup.today to DayKind.FULL, w.setup.tomorrow to DayKind.FULL)))
        assertTrue(free.contains("Sara Neri In forse ${w.age(w.birth.plusYears(1))} · $saraDays"), free)
        assertTrue(free.contains("Davide Costa ${w.age(w.birth.plusYears(3))} · Tutti i giorni"), free)
        assertTrue(free.indexOf("Davide Costa") < free.indexOf("Sara Neri"), "by surname and name")
        assertFalse(free.contains("Nicolò"), "lunch only is not in the list")
        assertTrue(free.contains("Maestri affidatari · 1") && free.contains("Marta Bianchi Maestro"), free)
        assertTrue(html.indexOf("id=\"senza-camera\"") > html.indexOf("id=\"camera-${w.azzurra}\""), "who has no room is at the bottom")
        assertFalse(free.contains("Luca Rossi") || free.contains("Anna Rossi"), "who has a room is not in the list")
    }

    @Test
    fun `t11 c01 the zero is in words, a room with nobody says so, and a gathering that has no room yet has the empty state`() {
        val setup = kit.start()
        val page = "/convivenza/${setup.id}/camere"

        val empty = words(setup.marta.get(page).body())
        assertTrue(empty.contains("Nessuna camera Scrivi le camere della casa, poi metti ogni bambino e ogni maestro nella sua. Le camere le vedono solo i maestri."), empty)
        assertTrue(empty.contains("Aggiungi una camera") && !empty.contains("Copia le camere"), "with nothing to copy only «Aggiungi una camera»")

        kit.room(setup.id, "Camera 1", 4)
        val html = setup.marta.get(page).body()
        val summary = words(Regex("""<section id="riepilogo".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value)
        assertTrue(summary.contains("Camere 1 Posti letto 4 Persone in camera Nessuna bambini e maestri Bambini senza camera Nessuno"), summary)
        assertTrue(words(roomCard(html, "Camera 1")).contains("Camera 1 Vuota · 4 posti Nessuno ci dorme, per ora."), words(roomCard(html, "Camera 1")))
        assertTrue(words(freeSection(html)).contains("I bambini compaiono qui quando i genitori li iscrivono."), "no child is registered yet")
    }

    @Test
    fun `t11 c01 a child, a teacher and a room with a name that has markup are shown as text`() {
        val setup = kit.start()
        val room = kit.room(setup.id, "<b>Camera</b> & co", 3)
        val id = kit.rows.register(setup.id, kit.checkin.account(), "<i>Anna</i>", "R<script>alert(1)</script>ossi")
        kit.put(id, setup.id, room)

        val html = setup.marta.get("/convivenza/${setup.id}/camere").body()
        assertFalse(html.contains("<b>Camera") || html.contains("<i>Anna") || html.contains("<script>alert"), "markup that was written is not markup in the page")
        assertTrue(html.contains("&lt;b&gt;Camera&lt;/b&gt; &amp; co"), "it is shown as the text that was written")
        assertTrue(html.contains("&lt;i&gt;Anna&lt;/i&gt;"))
    }

    // ---- The copy (criterion 4) ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `t11 c04 with a gathering before that has rooms the empty state offers to copy them, with the name that says which, and copying makes the same rooms empty`() {
        val setup = kit.start()
        val before = kit.otherGathering("Convivenza di settembre 2026", daysFromToday = -400)
        kit.room(before, "Camera 1", 4)
        kit.room(before, "Camera 2", 4)
        kit.room(before, "Stanza azzurra", 2)
        val page = "/convivenza/${setup.id}/camere"

        val html = setup.marta.get(page).body()
        val empty = words(html)
        assertTrue(empty.contains("Nella «Convivenza di settembre 2026» c’erano 3 camere e 10 posti. Le copiamo senza le persone: poi le sistemi come vuoi."), empty)
        assertTrue(html.contains("""aria-label="Copia le camere di Convivenza di settembre 2026""""), "the name of the button says which gathering")
        assertTrue(empty.contains("Copia le camere") && empty.contains("Aggiungi una camera"))

        val answer = setup.marta.submit("/profilo", "$page/copia", emptyMap(), htmx())
        assertEquals(200, answer.status())
        val block = words(answer.body())
        assertTrue(block.contains("Camere copiate."), block)
        assertTrue(block.contains("Camere 3 Posti letto 10"), block)
        assertEquals(listOf("Camera 1" to 4, "Camera 2" to 4, "Stanza azzurra" to 2), kit.roomsOf(setup.id))
        assertEquals(0, kit.peopleCount(setup.id), "copied without the people")
        // With rooms there the button is not there, and a request by hand changes nothing.
        val after = setup.marta.get(page).body()
        assertFalse(words(after).contains("Copia le camere"), "the button is not there with rooms already written")
        val state = kit.state()
        val again = setup.marta.submit("/profilo", "$page/copia", emptyMap(), htmx())
        assertEquals(200, again.status())
        assertEquals(state, kit.state())
    }

    @Test
    fun `t11 c04 without JavaScript the copy answers with a redirect to the page and the message there once`() {
        val setup = kit.start()
        val before = kit.otherGathering("Convivenza di settembre 2026", daysFromToday = -400)
        kit.room(before, "Camera 1", 4)
        val page = "/convivenza/${setup.id}/camere"

        val answer = setup.marta.submit("/profilo", "$page/copia", emptyMap())
        assertEquals(303, answer.status())
        assertEquals(page, answer.location())
        assertTrue(words(setup.marta.get(page).body()).contains("Camere copiate."))
        assertFalse(words(setup.marta.get(page).body()).contains("Camere copiate."), "the message is read once")
    }

    // ---- The choices (criteria 6, 7, 8, 10) ----------------------------------------------------------------------------------------------------

    @Test
    fun `t11 c06 each child without a room has a select named after the child and a button, and a choice answers with the whole block, the message in the row and the numbers new`() {
        val w = World()
        val html = w.get()
        // The select and the button of Sara are in her row, with the name in the accessible name, and nothing is sent when the choice changes.
        val row = Regex("""<li id="libero-b${w.sara}".*?</li>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        assertTrue(row.contains("""<label for="camera-b${w.sara}" class="sr-only">Camera di Sara Neri</label>"""), row)
        assertTrue(row.contains("<option value=\"\">Scegli la camera</option>"), row)
        assertTrue(row.contains("Camera 1 · 1 su 4 posti") && row.contains("Stanza azzurra · 1 su 2 posti"), row)
        assertTrue(row.contains(">Metti<span class=\"sr-only\"> Sara Neri in camera</span>"), row)
        assertFalse(row.contains("hx-trigger"), "the select does not send by itself")
        assertTrue(row.contains("""hx-post="/convivenza/${w.setup.id}/camere/scegli"""") && row.contains("""hx-target="#camere-contenuto""""), row)
        assertTrue(row.contains("""action="/convivenza/${w.setup.id}/camere/scegli"""") && row.contains("""method="post""""), "without JavaScript it is a form with its button")
        assertFalse(Regex("""/camere/[^"]*b${w.sara}""").containsMatchIn(html) || html.contains("persona=b"), "no person in an address")

        val answer = w.marta.touch(w, "/scegli", mapOf("persona" to "b${w.sara}", "camera" to w.two.toString()))
        assertEquals(200, answer.status())
        val block = answer.body()
        val text = words(block)
        assertFalse(block.contains("<html"), "only the block, not the page")
        assertTrue(block.trimStart().startsWith("<div id=\"camere-contenuto\">"), block.take(80))
        assertEquals(w.two, kit.roomOfChild(w.sara))
        // The message is in the row, which stays where it was without the choice, and the summary and the room are already new.
        val rowAfter = Regex("""<li id="libero-b${w.sara}".*?</li>""", RegexOption.DOT_MATCHES_ALL).find(block)!!.value
        assertTrue(words(rowAfter).contains("Sara Neri In forse") && words(rowAfter).contains("Sara Neri è in Camera 2."), words(rowAfter))
        assertTrue(rowAfter.contains("role=\"status\"") && rowAfter.contains("data-fuoco"), "the message takes the focus")
        assertFalse(rowAfter.contains("<select"), "the row has no choice any more")
        assertTrue(text.contains("Persone in camera 4") && text.contains("Bambini senza camera 1"), text)
        assertTrue(words(roomCard(block, "Camera 2")).contains("Camera 2 2 persone · 4 posti"), words(roomCard(block, "Camera 2")))
        assertTrue(block.contains("""<p hx-swap-oob="innerHTML:#esito">Sara Neri è in Camera 2.</p>"""), "the sentence for the screen reader, in the region that stays in the page")
        // At the next load she is a person of the room.
        assertFalse(words(freeSection(w.get())).contains("Sara Neri"))
    }

    @Test
    fun `t11 c06 a choice without a room says so under the select with a 422 and writes nothing, and without JavaScript the form is an ordinary post that redirects`() {
        val w = World()
        val state = kit.state()

        val answer = w.marta.touch(w, "/scegli", mapOf("persona" to "b${w.sara}", "camera" to ""))
        assertEquals(422, answer.status())
        val row = Regex("""<li id="libero-b${w.sara}".*?</li>""", RegexOption.DOT_MATCHES_ALL).find(answer.body())!!.value
        assertTrue(row.contains("aria-invalid=\"true\"") && row.contains("Scegli la camera."), row)
        assertTrue(row.contains("aria-describedby=\"camera-b${w.sara}-errore\""), row)
        assertEquals(state, kit.state())

        val plain = w.marta.touch(w, "/scegli", mapOf("persona" to "b${w.sara}", "camera" to w.two.toString()), viaHtmx = false)
        assertEquals(303, plain.status())
        assertEquals("${w.page}#senza-camera", plain.location())
        assertEquals(w.two, kit.roomOfChild(w.sara))
        assertTrue(words(w.get()).contains("Sara Neri è in Camera 2."), "the message is left for the page, at the top")
        // A form without JavaScript that comes back with the error is the whole page at the address of the form.
        val whole = w.marta.touch(w, "/scegli", mapOf("persona" to "b${w.luca}", "camera" to ""), viaHtmx = false)
        assertEquals(422, whole.status())
        assertTrue(whole.body().contains("<html") && words(whole.body()).contains("Scegli la camera."))
        // Reloaded with a GET, the address of a form leads to the page.
        assertEquals(303, w.marta.get("${w.page}/scegli").status())
        assertEquals(w.page, w.marta.get("${w.page}/scegli").location())
    }

    @Test
    fun `t11 c07 a person of a room is moved to another or taken out with the form that Cambia opens, and the message is in the room that they left`() {
        val w = World()
        val html = w.get()
        val person = Regex("""<li id="persona-b${w.luca}".*?</li>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        assertTrue(person.contains("Cambia<span class=\"sr-only\"> la camera di Luca Rossi</span>") && person.contains("Chiudi<span class=\"sr-only\"> le scelte per Luca Rossi</span>"), person)
        assertTrue(person.contains("""<label for="sposta-b${w.luca}" class="block font-semibold">Camera di Luca Rossi</label>"""), person)
        assertTrue(person.contains("name=\"azione\" value=\"sposta\"") && person.contains("name=\"azione\" value=\"togli\"") && person.contains("Togli dalla camera"), person)
        assertFalse(person.contains("Camera 1 ·"), "the rooms to choose are the other ones")
        assertTrue(person.contains("Camera 2 · 1 su 4 posti") && person.contains("Stanza azzurra · 1 su 2 posti"), person)

        val moved = w.marta.touch(w, "/sposta", mapOf("persona" to "b${w.luca}", "azione" to "sposta", "camera" to w.azzurra.toString()))
        assertEquals(200, moved.status())
        assertEquals(w.azzurra, kit.roomOfChild(w.luca))
        val block = moved.body()
        assertTrue(words(roomCard(block, "Camera 1")).contains("Luca Rossi è in Stanza azzurra."), words(roomCard(block, "Camera 1")))
        assertFalse(words(roomCard(block, "Camera 1")).contains("Prima era"), "the message is in the room that he left: it does not say where he was")
        assertTrue(words(roomCard(block, "Camera 1")).contains("Camera 1 Vuota · 4 posti"), "the room that was left is already empty")
        assertFalse(roomCard(block, "Stanza azzurra").contains("role=\"status\""), "the message is only in the room that was left")
        assertEquals(w.two, kit.roomOfChild(w.anna), "only Luca changed")

        val out = w.marta.touch(w, "/sposta", mapOf("persona" to "b${w.luca}", "azione" to "togli"))
        assertEquals(200, out.status())
        assertNull(kit.roomOfChild(w.luca))
        assertTrue(words(roomCard(out.body(), "Stanza azzurra")).contains("Luca Rossi è senza camera."), words(roomCard(out.body(), "Stanza azzurra")))
        assertTrue(words(freeSection(out.body())).contains("Luca Rossi"), "taken out, he is among those without a room")
        // Taken out again: the state as it is, the message at the top, no error.
        val twice = w.marta.touch(w, "/sposta", mapOf("persona" to "b${w.luca}", "azione" to "togli"))
        assertEquals(200, twice.status())
        assertTrue(words(twice.body()).contains("Luca Rossi era già senza camera."))
        // Moved without a room chosen: the error is under the select of the person, in the form that stays open.
        val none = w.marta.touch(w, "/sposta", mapOf("persona" to "b${w.anna}", "azione" to "sposta", "camera" to ""))
        assertEquals(422, none.status())
        val open = Regex("""<li id="persona-b${w.anna}".*?</li>""", RegexOption.DOT_MATCHES_ALL).find(none.body())!!.value
        assertTrue(open.contains("<details class=\"group\" open>") || Regex("""<details[^>]*\bopen\b""").containsMatchIn(open), open)
        assertTrue(open.contains("aria-invalid=\"true\"") && open.contains("Scegli la camera."), open)
        assertEquals(w.two, kit.roomOfChild(w.anna))
    }

    @Test
    fun `t11 c07 without JavaScript moving a person redirects to the room that they left, and the message is there at the top`() {
        val w = World()

        val plain = w.marta.touch(w, "/sposta", mapOf("persona" to "b${w.luca}", "azione" to "sposta", "camera" to w.azzurra.toString()), viaHtmx = false)
        assertEquals(303, plain.status())
        assertEquals("${w.page}#camera-${w.one}", plain.location())
        assertEquals(w.azzurra, kit.roomOfChild(w.luca))
        assertTrue(words(w.get()).contains("Luca Rossi è in Stanza azzurra."))
    }

    @Test
    fun `t11 c08 the custodians without a room are at the end of the list, any teacher is added from a room and taken out from it, and a teacher is in one room only`() {
        val w = World()
        val html = w.get()
        val free = freeSection(html)
        assertTrue(words(free).contains("Maestri affidatari · 1") && words(free).contains("Marta Bianchi Maestro"), words(free))
        val add = roomCard(html, "Camera 1")
        // «Aggiungi un maestro» lists every teacher who has no room, Giulia Serra too (she is not a custodian).
        assertTrue(add.contains("Aggiungi un maestro<span class=\"sr-only\"> a Camera 1</span>"), add)
        assertTrue(add.contains("<option value=\"\">Scegli il maestro</option>") && add.contains(">Giulia Serra</option>") && add.contains(">Marta Bianchi</option>"), add)
        assertFalse(add.contains(">Paolo Verdi</option>"), "Paolo is in a room already")

        val answer = w.marta.touch(w, "/maestri", mapOf("camera" to w.one.toString(), "persona" to "m${w.setup.giuliaId}"))
        assertEquals(200, answer.status())
        assertEquals(w.one, kit.roomOfTeacher(w.setup.id, w.setup.giuliaId))
        assertTrue(words(roomCard(answer.body(), "Camera 1")).contains("Giulia Serra è in Camera 1."), words(roomCard(answer.body(), "Camera 1")))
        assertTrue(words(roomCard(answer.body(), "Camera 1")).contains("Giulia Serra Maestro"), "she is among the people of the room")
        // One room only: adding Paolo to Camera 2 moves him, and the answer says where he was.
        val moved = w.marta.touch(w, "/maestri", mapOf("camera" to w.two.toString(), "persona" to "m${w.setup.paoloId}"))
        assertEquals(200, moved.status())
        assertEquals(w.two, kit.roomOfTeacher(w.setup.id, w.setup.paoloId))
        assertTrue(words(roomCard(moved.body(), "Camera 2")).contains("Paolo Verdi è in Camera 2. Prima era in Stanza azzurra."), words(roomCard(moved.body(), "Camera 2")))
        // Taken out, he is among the custodians without a room.
        val out = w.marta.touch(w, "/sposta", mapOf("persona" to "m${w.setup.paoloId}", "azione" to "togli"))
        assertEquals(200, out.status())
        assertNull(kit.roomOfTeacher(w.setup.id, w.setup.paoloId))
        assertTrue(words(freeSection(out.body())).contains("Maestri affidatari · 2"), words(freeSection(out.body())))
        // Without a teacher chosen: the error is under the select of the room.
        val none = w.marta.touch(w, "/maestri", mapOf("camera" to w.one.toString(), "persona" to ""))
        assertEquals(422, none.status())
        assertTrue(roomCard(none.body(), "Camera 1").contains("Scegli il maestro.") && roomCard(none.body(), "Camera 1").contains("aria-invalid=\"true\""))
    }

    @Test
    fun `t11 c08 the entry to add a teacher is not in a room when every teacher has one`() {
        val w = World()
        kit.putTeacher(w.setup.id, w.setup.martaId, w.one)
        kit.putTeacher(w.setup.id, w.setup.giuliaId, w.two)

        val html = w.get()
        assertFalse(html.contains("Aggiungi un maestro"), "every teacher has a room")
        assertFalse(words(html).contains("Maestri affidatari"), "and no custodian is without one")
    }

    @Test
    fun `t11 c09 a room with more people than beds says so, and so does the summary, with a notice that blocks nothing and the children in doubt counted`() {
        val w = World()
        // Camera 2 has 4 beds: Anna, then Sara (in doubt), Marta, Giulia and Federico make five in a room of four.
        kit.put(w.sara, w.setup.id, w.two)
        kit.putTeacher(w.setup.id, w.setup.martaId, w.two)
        kit.putTeacher(w.setup.id, w.setup.giuliaId, w.two)
        kit.put(kit.rows.register(w.setup.id, kit.checkin.account(), "Federico", "Longo"), w.setup.id, w.two)
        val html = w.get()

        assertTrue(words(roomCard(html, "Camera 2")).contains("Camera 2 5 persone · 4 posti 5 persone per 4 posti."), words(roomCard(html, "Camera 2")))
        val summary = words(Regex("""<section id="riepilogo".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value)
        assertTrue(summary.contains("Più persone dei posti. Puoi tenerle così, ma controlla. Camera 2 : 5 persone per 4 posti."), summary)
        assertTrue(html.contains("""<a href="#camera-${w.two}""""), "the name in the notice is a link to the room")
        // The options of a choice say it too, and it does not stop the choice.
        assertTrue(html.contains("Camera 2 · 5 su 4 posti"), "the room that is over is still a choice")
        val answer = w.marta.touch(w, "/sposta", mapOf("persona" to "b${w.luca}", "azione" to "sposta", "camera" to w.two.toString()))
        assertEquals(200, answer.status())
        assertEquals(w.two, kit.roomOfChild(w.luca))
        assertTrue(words(answer.body()).contains("Camera 2 6 persone · 4 posti 6 persone per 4 posti."))
        // Without people over the beds there is no notice.
        assertFalse(words(w.get().substringAfter("id=\"camera-${w.one}\"").substringBefore("id=\"camera-${w.two}\"")).contains("persone per"), "Camera 1 has room")
    }

    @Test
    fun `t11 c10 a child that another teacher had put in a room is moved by a choice that says where they are now and where they were, with no error`() {
        val w = World()
        // Another teacher put Sara in Camera 2 while this page was open: the choice of this one, for Camera 1, finds her there.
        kit.put(w.sara, w.setup.id, w.two)
        val answer = w.marta.touch(w, "/scegli", mapOf("persona" to "b${w.sara}", "camera" to w.one.toString()))

        assertEquals(200, answer.status())
        assertEquals(w.one, kit.roomOfChild(w.sara))
        val row = Regex("""<li id="libero-b${w.sara}".*?</li>""", RegexOption.DOT_MATCHES_ALL).find(answer.body())!!.value
        assertTrue(words(row).contains("Sara Neri è in Camera 1. Prima era in Camera 2."), words(row))
        // The same choice again: the state as it is, and the room is the same.
        val again = w.marta.touch(w, "/scegli", mapOf("persona" to "b${w.sara}", "camera" to w.one.toString()))
        assertEquals(200, again.status())
        assertTrue(words(again.body()).contains("Sara Neri è già in Camera 1."))
        assertEquals(4, kit.peopleCount(w.setup.id), "Luca, Anna, Paolo and now Sara: one row for each, never two for Sara")
    }

    // ---- The states of the gathering (criterion 13) --------------------------------------------------------------------------------------------

    @Test
    fun `t11 c13 in a draft the page says so, rooms and teachers are chosen and there are no children`() {
        val setup = kit.start(first = 30, last = 32, status = GatheringStatus.draft)
        val room = kit.room(setup.id, "Camera 1", 4)
        val page = "/convivenza/${setup.id}/camere"

        val html = setup.marta.get(page).body()
        val text = words(html)
        assertTrue(text.contains("La convivenza è ancora in bozza: le camere si scrivono già, i bambini compaiono quando i genitori li iscrivono."), text)
        assertTrue(text.contains("I bambini compaiono qui quando i genitori li iscrivono."), text)
        assertTrue(text.contains("Maestri affidatari · 2") && text.contains("Aggiungi una camera") && text.contains("Aggiungi un maestro"), text)
        val answer = setup.marta.submit("/profilo", "$page/scegli", mapOf("persona" to "m${setup.martaId}", "camera" to room.toString()), htmx())
        assertEquals(200, answer.status())
        assertEquals(room, kit.roomOfTeacher(setup.id, setup.martaId))
    }

    @Test
    fun `t11 c13 the day after the end the page has no actions and says so, a request by hand is refused with 409 and nothing changes, and until the last day everything changes`() {
        val w = World()
        // The last day is today: everything is still possible.
        kit.endedDaysAgo(w.setup.id, 0)
        assertFalse(words(w.get()).contains("La convivenza è finita"), "the last day is not the end")
        assertEquals(200, w.marta.touch(w, "/sposta", mapOf("persona" to "b${w.luca}", "azione" to "sposta", "camera" to w.two.toString())).status())

        kit.endedDaysAgo(w.setup.id, 1)
        val html = w.get()
        val text = words(html)
        assertTrue(text.contains("La convivenza è finita: le camere non si cambiano più."), text)
        assertFalse(text.contains("Metti ogni bambino e ogni maestro nella sua camera."), "no introduction")
        for (action in listOf("<select", "Cambia", "Aggiungi un maestro", "Modifica", "Togli", "Aggiungi una camera", "Metti", "Sposta")) assertFalse(html.contains(action), "no «$action» after the end")
        assertTrue(text.contains("Stampa"), "the print is still there")
        // The people are there until the job deletes them, with no choice; who has no room is listed.
        assertTrue(text.contains("Luca Rossi") && text.contains("Anna Rossi") && text.contains("Paolo Verdi"), text)
        assertTrue(text.contains("Senza camera") && text.contains("Sara Neri"), text)

        val state = kit.state()
        for ((tail, fields) in listOf(
            "/scegli" to mapOf("persona" to "b${w.sara}", "camera" to w.one.toString()),
            "/sposta" to mapOf("persona" to "b${w.luca}", "azione" to "togli"),
            "/maestri" to mapOf("camera" to w.one.toString(), "persona" to "m${w.setup.giuliaId}"),
            "/nuova" to mapOf("name" to "Camera 9", "beds" to "2"),
            "/${w.one}/modifica" to mapOf("name" to "Camera 1 bis", "beds" to "2"),
            "/${w.one}/togli" to emptyMap(),
            "/copia" to emptyMap(),
        )) {
            val answer = w.marta.touch(w, tail, fields)
            assertEquals(409, answer.status(), tail)
            assertTrue(words(answer.body()).contains("La convivenza è finita: le camere non si cambiano più."), tail)
            assertEquals(409, w.marta.touch(w, tail, fields, viaHtmx = false).status(), "$tail without JavaScript")
        }
        assertEquals(state, kit.state(), "nothing changed")
        // The pages of the forms are refused too.
        for (tail in listOf("/nuova", "/${w.one}/modifica", "/${w.one}/togli")) assertEquals(409, w.marta.get(w.page + tail).status(), tail)
    }

    @Test
    fun `t11 c13 an answer of the block after the end is the block in the state of the end, with the sentence where the focus goes`() {
        val w = World()
        kit.endedDaysAgo(w.setup.id, 1)

        val answer = w.marta.touch(w, "/sposta", mapOf("persona" to "b${w.luca}", "azione" to "togli"))
        assertEquals(409, answer.status())
        val block = answer.body()
        assertTrue(block.trimStart().startsWith("<div id=\"camere-contenuto\">"))
        assertEquals(1, Regex("""La convivenza è finita: le camere non si cambiano più\.""").findAll(block.substringBefore("<p hx-swap-oob")).count(), "said once in the block")
        assertTrue(Regex("""role="status"[^>]*tabindex="-1"[^>]*data-fuoco|data-fuoco""").containsMatchIn(block.substringBefore("La convivenza è finita")), "the notice takes the focus")
    }

    // ---- The card in the detail of the gathering (criterion 16) -----------------------------------------------------------------------------------

    @Test
    fun `t11 c16 the detail of the gathering has the card Camere with rooms, beds and children without a room, and no name`() {
        val w = World()
        val html = w.marta.get("/convivenza/${w.setup.id}").body()
        val card = Regex("""<section[^>]*aria-labelledby="camere".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        val text = words(card)

        assertTrue(text.contains("Camere Vedi le camere 3 camere · 10 posti · 2 bambini senza camera"), text)
        assertTrue(card.contains("""href="/convivenza/${w.setup.id}/camere""""))
        for (name in listOf("Luca", "Anna", "Sara", "Paolo", "Marta", "Camera 1", "Stanza azzurra")) assertFalse(text.contains(name), "the card has no name: $name")
        assertTrue(html.indexOf("aria-labelledby=\"iscrizioni\"") < html.indexOf("aria-labelledby=\"camere\""), "after «Iscrizioni»")
        // One is left, then everyone has a room: the card says so.
        kit.put(w.sara, w.setup.id, w.two)
        assertTrue(words(w.marta.get("/convivenza/${w.setup.id}").body()).contains("3 camere · 10 posti · 1 bambino senza camera"))
        kit.put(w.davide, w.setup.id, w.one)
        assertTrue(words(w.marta.get("/convivenza/${w.setup.id}").body()).contains("3 camere · 10 posti · Tutti hanno una camera"))
    }

    @Test
    fun `t11 c16 the card says to write the rooms when there are none, only the house in a draft, and a gathering of one day without rooms has no card`() {
        val setup = kit.start()
        val detail = "/convivenza/${setup.id}"
        assertTrue(words(setup.marta.get(detail).body()).contains("Camere Scrivi le camere Nessuna camera per ora."))

        val room = kit.room(setup.id, "Camera 1", 4)
        kit.rows.register(setup.id, kit.checkin.account(), "Luca", "Rossi")
        kit.rows.register(setup.id, kit.checkin.account(), "Anna", "Rossi")
        assertTrue(words(setup.marta.get(detail).body()).contains("1 camera · 4 posti · 2 bambini senza camera"))
        kit.put(kit.rows.register(setup.id, kit.checkin.account(), "Sara", "Neri"), setup.id, room)

        // Over: the house only.
        kit.endedDaysAgo(setup.id, 1)
        assertTrue(words(setup.marta.get(detail).body()).contains("1 camera · 4 posti") && !words(setup.marta.get(detail).body()).contains("senza camera"))

        // A day only, with no rooms: no card. With a room: the card.
        val day = kit.start(first = 0, last = 0)
        assertFalse(day.marta.get("/convivenza/${day.id}").body().contains("aria-labelledby=\"camere\""), "a day needs no rooms")
        kit.room(day.id, "Camera 1", 4)
        assertTrue(day.marta.get("/convivenza/${day.id}").body().contains("aria-labelledby=\"camere\""))
        // A draft has no children to count.
        val draft = kit.start(first = 30, last = 32, status = GatheringStatus.draft)
        kit.room(draft.id, "Camera 1", 4)
        kit.room(draft.id, "Camera 2", 4)
        val text = words(draft.marta.get("/convivenza/${draft.id}").body())
        assertTrue(text.contains("2 camere · 8 posti") && !text.contains("senza camera"), text)
    }

    @Test
    fun `t11 c16 the page of the rooms is not found for a gathering that does not exist`() {
        val setup = kit.start()
        assertEquals(404, setup.marta.get("/convivenza/${setup.id + 100_000}/camere").status())
        assertNotNull(setup.marta.get("/convivenza/${setup.id}/camere").body())
    }
}
