package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.Media
import talita.gathering.GatheringFormat
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.ROOM
import talita.jooq.tables.references.ROOM_CHILD
import talita.jooq.tables.references.ROOM_TEACHER
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rooms on a real browser, on a phone and on a desktop, with axe (criterion 23; design TALITA-11, part 3, 2): a day of work (write the rooms, copy them, put four children and a teacher
 * in them, move one, take a room away with the dialog, print), the notice of the beds that appears and goes, what two teachers do at the same moment, a lost network, the page without
 * JavaScript, the states of the gathering, and the print as Chromium makes it. No sideways scrolling at 320 px. Names and numbers are synthetic; the days are those around the day the test runs.
 */
@E2eApplication
class RoomsE2eTest {

    private fun E2e.room(gathering: Long, name: String, beds: Int): Long =
        dsl().insertInto(ROOM).set(ROOM.GATHERING_ID, gathering).set(ROOM.NAME, name).set(ROOM.BEDS, beds.toShort()).returningResult(ROOM.ID).fetchSingle().value1()!!

    private fun E2e.roomId(name: String): Long = dsl().select(ROOM.ID).from(ROOM).where(ROOM.NAME.eq(name)).fetchSingle(ROOM.ID)!!

    private fun E2e.put(registration: Long, gathering: Long, room: Long) {
        dsl().insertInto(ROOM_CHILD).set(ROOM_CHILD.REGISTRATION_ID, registration).set(ROOM_CHILD.GATHERING_ID, gathering).set(ROOM_CHILD.ROOM_ID, room).execute()
    }

    private fun E2e.putTeacher(gathering: Long, account: Long, room: Long) {
        dsl().insertInto(ROOM_TEACHER).set(ROOM_TEACHER.GATHERING_ID, gathering).set(ROOM_TEACHER.TEACHER_ACCOUNT_ID, account).set(ROOM_TEACHER.ROOM_ID, room).execute()
    }

    /** The children of the examples: Luca and Anna Rossi every day, Nicolò Ferri for lunch only, Sara Neri in doubt, and Davide Costa. */
    private data class Children(val luca: Long, val anna: Long, val nicolo: Long, val sara: Long, val davide: Long)

    private fun E2e.children(w: ArrivalsWorld): Children {
        val parent = someParent()
        val rows = rows()
        return Children(
            luca = rows.register(w.id, parent, "Luca", "Rossi", LocalDate.of(2016, 4, 4)),
            anna = rows.register(w.id, parent, "Anna", "Rossi", LocalDate.of(2018, 1, 9)),
            nicolo = rows.register(w.id, null, "Nicolò", "Ferri", LocalDate.of(2015, 6, 2), enteredBy = w.martaId, days = mapOf(w.tomorrow to "lunch")),
            sara = rows.register(w.id, someParent(), "Sara", "Neri", LocalDate.of(2017, 3, 3), status = RegistrationStatus.maybe, days = mapOf(w.today to "full", w.tomorrow to "full")),
            davide = rows.register(w.id, someParent(), "Davide", "Costa", LocalDate.of(2017, 8, 8)),
        )
    }

    private fun Page.sheets(): Locator = locator("#camere-contenuto")

    private fun Page.summary(): String = locator("#riepilogo").innerText().replace(Regex("\\s+"), " ").trim()

    /** The page has not been loaded again since the marker was written: a touch replaced a piece of it. */
    private fun Page.mark() = evaluate("() => { window.__marker = 'stesso'; }")

    private fun Page.sameDocument(): Boolean = evaluate("window.__marker") == "stesso"

    /** The focus goes to the notice when the page has settled after the answer: it is waited for, not read at once. */
    private fun Page.focusOnNotice(): Boolean {
        focusIs("document.activeElement.hasAttribute('data-fuoco')")
        return true
    }

    private fun Page.choose(person: String, room: String, name: String) {
        getByLabel("Camera di $name").selectOption(room)
        locator("main").buttonNamed("Metti $name in camera").let { button ->
            button.evaluate("e => e.scrollIntoView({ block: 'center' })")
            button.click()
        }
    }

    // ---- The page (criteria 1, 6, 23) -----------------------------------------------------------------------------------------------

    @E2eTest
    fun `t11 c01 c23 with thirty-five children and ten rooms the page is light, does not scroll sideways, also at 320 px, and has no violation of axe`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val rooms = (1..10).map { e2e.room(w.id, "Camera $it", 4) }
        val rows = e2e.rows()
        val ids = (1..35).map { number ->
            rows.register(w.id, e2e.someParent(), if (number == 7) "Maria Concetta Immacolata Addolorata" else "Bambino", if (number == 7) "Montefiore-Castelnuovo-della-Daunia" else "Prova%02d".format(number), LocalDate.of(2016, 1, 1).plusDays(number.toLong()))
        }
        ids.take(8).forEachIndexed { index, id -> e2e.put(id, w.id, rooms[index / 2]) }
        e2e.putTeacher(w.id, w.martaId, rooms[0])

        val response = e2e.open("/convivenza/${w.id}/camere")

        assertThat(page).hasTitle("Camere · Talità")
        assertThat(page.locator("h1")).hasText("Camere")
        assertThat(page.locator("main h2:visible")).hasText(arrayOf(Pattern.compile("Riepilogo"), Pattern.compile("Le camere"), Pattern.compile("Senza camera\\s*28")))
        assertEquals("Riepilogo Stampa Camere 10 Posti letto 40 Persone in camera 9 bambini e maestri Bambini senza camera 27 Vai a chi è senza camera", page.summary())
        assertThat(page.locator("section[id^=camera-]")).hasCount(10)
        // About 190 KB of markup that is written out whole (a select for each of the thirty children), and about 10 KB on the wire: the page stays light where it matters.
        val gzipped = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(response.body()) } }.size()
        assertTrue(response.body().size < 220_000 && gzipped < 16_000, "the first visit is ${response.body().size} bytes, ${gzipped} compressed")
        assertTrue(page.noHorizontalScroll())
        for (width in listOf(320, 360)) {
            page.setViewportSize(width, 800)
            page.waitForSettledContent()
            assertTrue(page.noHorizontalScroll(), "no sideways scrolling at $width px, with a name that is very long")
        }
        // A target that is touched is at least 44 px high: the select and the button of a row are 48, «Cambia» is 44.
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        val select = page.getByLabel("Camera di Bambino Prova09")
        assertTrue(select.box().height >= 47.9, "the select is ${select.box()}")
        assertTrue(page.locator("main").buttonNamed("Metti Bambino Prova09 in camera").box().height >= 47.9)
        assertTrue(page.locator("#persona-b${ids[0]} summary").box().height >= 43.9, "«Cambia» is ${page.locator("#persona-b${ids[0]} summary").box()}")
        assertAccessible(page, "rooms with many children (${e2e.profile.label})")
    }

    @E2eTest
    fun `t11 c01 c06 c23 the page with the form of Cambia open, a choice that was not made and the quiet states have no violation of axe and do not scroll sideways at 320 px`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        val one = e2e.room(w.id, "Camera 1", 4)
        val two = e2e.room(w.id, "Stanza azzurra", 2)
        e2e.put(c.luca, w.id, one)
        e2e.putTeacher(w.id, w.paoloId, two)
        e2e.open("/convivenza/${w.id}/camere")

        assertThat(page.locator("main").buttonNamed("Metti Sara Neri in camera")).isVisible()
        page.locator("#persona-b${c.luca} summary").click()
        assertThat(page.getByLabel("Camera di Luca Rossi")).isVisible()
        page.locator("#persona-m${w.paoloId} summary").click()
        page.locator("#camera-$one summary:has-text('Aggiungi un maestro')").click()
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px with the forms open")
        assertThat(page.locator("#persona-b${c.luca} summary")).containsText("Chiudi")
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "rooms with the forms open (${e2e.profile.label})")

        // A choice with no room: the error is under the select, it is invalid and the focus goes to it.
        e2e.press(page.locator("main").buttonNamed("Metti Sara Neri in camera"))
        assertThat(page.locator("#camera-b${c.sara}-errore")).hasText("Scegli la camera.")
        assertThat(page.getByLabel("Camera di Sara Neri")).hasAttribute("aria-invalid", "true")
        assertTrue(page.evaluate("document.activeElement && document.activeElement.id") == "camera-b${c.sara}", "the focus is on the select that is wrong")
        assertAccessible(page, "rooms with a choice that was not made (${e2e.profile.label})")
    }

    // ---- A day of work (criterion 23) -------------------------------------------------------------------------------------------------

    @E2eTest
    fun `t11 c23 a day of work the teacher writes the rooms, puts four children and a teacher in them, moves one, takes a room away with the dialog and prints`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        val others = (1..3).map { e2e.rows().register(w.id, e2e.someParent(), "Elena", "Prova$it", LocalDate.of(2016, 1, it)) }
        e2e.open("/convivenza/${w.id}/camere")

        // No rooms: the empty state, with only «Aggiungi una camera» (there is no gathering before this one).
        assertThat(page.locator("main h2:visible")).hasText("Nessuna camera")
        assertThat(page.locator("main").linkNamed("Aggiungi una camera")).isVisible()
        assertEquals(0, page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(Pattern.compile("Copia le camere"))).count())
        assertAccessible(page, "rooms, empty state (${e2e.profile.label})")
        e2e.touch(page.locator("main").linkNamed("Aggiungi una camera"))
        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}/camere/nuova"))
        assertThat(page.locator("h1")).hasText("Nuova camera")
        page.focusIs("document.activeElement.tagName === 'H1'")
        assertAccessible(page, "new room (${e2e.profile.label})")

        // A name that says who sleeps there is refused, with the field as it was written.
        page.getByLabel("Nome della camera").fill("Camera dei maschi")
        page.getByLabel("Posti letto").fill("4")
        e2e.press(page.locator("main").buttonNamed("Salva la camera"))
        assertThat(page.locator("#nome-errore")).hasText("Scrivi il nome della camera nella casa, non chi ci dorme.")
        assertThat(page.getByLabel("Nome della camera")).hasValue("Camera dei maschi")
        page.focusIs("document.activeElement.id === 'nome'")
        assertAccessible(page, "new room with the error (${e2e.profile.label})")
        // The house is fine.
        page.getByLabel("Nome della camera").fill("Camera 1")
        e2e.press(page.locator("main").buttonNamed("Salva la camera"))
        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}/camere"))
        assertThat(page.locator("main")).containsText("Camera salvata.")
        e2e.touch(page.locator("main").linkNamed("Aggiungi una camera"))
        page.getByLabel("Nome della camera").fill("Stanza azzurra")
        page.getByLabel("Posti letto").fill("2")
        e2e.press(page.locator("main").buttonNamed("Salva la camera"))
        assertThat(page.locator("main")).containsText("Camera salvata.")
        assertThat(page.locator("section[id^=camera-] h3")).hasText(arrayOf("Camera 1", "Stanza azzurra"))
        assertEquals("Riepilogo Stampa Camere 2 Posti letto 6 Persone in camera Nessuna bambini e maestri Bambini senza camera 7 Vai a chi è senza camera", page.summary())

        // Four children in Camera 1: each choice replaces the block, with the message in the row, and the page does not move or load again.
        val one = page.locator("section[id^=camera-]").first().getAttribute("id")!!.removePrefix("camera-")
        page.mark()
        for ((index, name) in listOf("Davide Costa", "Elena Prova1", "Elena Prova2", "Luca Rossi").withIndex()) {
            val before = (page.evaluate("window.scrollY") as Number).toDouble()
            page.getByLabel("Camera di $name").selectOption(one)
            val button = page.locator("main").buttonNamed("Metti $name in camera")
            button.evaluate("e => e.scrollIntoView({ block: 'center' })")
            val atStart = (page.evaluate("window.scrollY") as Number).toDouble()
            e2e.touch(button)
            assertThat(page.locator("#libero-b${listOf(c.davide, others[0], others[1], c.luca)[index]}")).containsText("$name è in Camera 1.")
            assertTrue(page.sameDocument(), "the page was not loaded again")
            assertTrue(page.focusOnNotice(), "the focus is on the message of the row")
            // The page did not go back to the top (the focus did not move it: the notice was in sight), and the notice that took the focus is on the screen.
            val after = (page.evaluate("window.scrollY") as Number).toDouble()
            val notice = page.locator("#libero-b${listOf(c.davide, others[0], others[1], c.luca)[index]}").box()
            assertTrue(after > 0 && notice.y >= 0 && notice.y + notice.height <= page.viewport().height, "scroll $after, notice $notice (was $before, then $atStart)")
            assertThat(page.locator("#esito")).hasText("$name è in Camera 1.")
        }
        assertEquals("Riepilogo Stampa Camere 2 Posti letto 6 Persone in camera 4 bambini e maestri Bambini senza camera 3 Vai a chi è senza camera", page.summary())
        assertThat(page.locator("#camera-$one")).containsText("4 persone · 4 posti")

        // A teacher is added from the room: five people in four beds, and the room and the summary say so.
        page.locator("#camera-$one summary:has-text('Aggiungi un maestro')").let { e2e.press(it) }
        page.locator("#maestro-$one").selectOption("m${w.martaId}")
        e2e.press(page.locator("#camera-$one").buttonNamed("Aggiungi"))
        assertThat(page.locator("#camera-$one")).containsText("Marta Bianchi è in Camera 1.")
        assertThat(page.locator("#camera-$one")).containsText("5 persone · 4 posti")
        assertThat(page.locator("#camera-$one")).containsText("5 persone per 4 posti.")
        assertThat(page.locator("#riepilogo")).containsText("Più persone dei posti. Puoi tenerle così, ma controlla.")
        assertThat(page.locator("#riepilogo").linkNamed("Camera 1")).hasAttribute("href", "#camera-$one")
        assertAccessible(page, "rooms with more people than beds (${e2e.profile.label})")

        // One child is moved to the other room: the notice goes, the message is in the room that was left, and the forms are closed again.
        e2e.press(page.locator("#persona-b${c.luca} summary"))
        page.getByLabel("Camera di Luca Rossi").selectOption(e2e.roomId("Stanza azzurra").toString())
        e2e.press(page.locator("#persona-b${c.luca}").buttonNamed("Sposta"))
        assertThat(page.locator("#camera-$one")).containsText("Luca Rossi è in Stanza azzurra.")
        assertThat(page.locator("#camera-$one")).containsText("4 persone · 4 posti")
        assertThat(page.locator("#camera-$one")).not().containsText("persone per")
        assertThat(page.locator("#riepilogo")).not().containsText("Più persone dei posti")
        assertEquals(0, page.locator("details[open]").count(), "the forms are closed after the answer")

        // «Togli» of a room with people opens the dialog: it says how many go back among those without a room.
        e2e.press(page.locator("#camera-$one").linkNamed("Togli Camera 1"))
        page.waitForSettledDialog()
        val dialog = page.openDialog()
        assertThat(dialog.locator("h2")).hasText("Togliere Camera 1?")
        assertThat(dialog).containsText("Camera 1 sparisce dall’elenco. Le 4 persone che ci dormono tornano fra quelle senza camera.")
        assertAccessible(page, "dialog to take a room away (${e2e.profile.label})")
        e2e.touch(dialog.buttonNamed("Togli la camera"))
        assertThat(page.locator("main")).containsText("Camera tolta.")
        assertThat(page.locator("section[id^=camera-] h3")).hasText(arrayOf("Stanza azzurra"))
        assertThat(page.locator("#senza-camera")).containsText("Davide Costa")
        assertThat(page.locator("#senza-camera")).containsText("Marta Bianchi")
        assertTrue(page.sameDocument(), "the whole day without loading the page again")

        // The print is one tap from the summary.
        e2e.press(page.locator("#riepilogo").linkNamed("Stampa le camere"))
        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}/camere/stampa"))
        assertThat(page.locator("h1")).hasText("Camere")
    }

    // ---- Copy, two teachers, no network, no JavaScript ------------------------------------------------------------------------------------

    @E2eTest
    fun `t11 c04 c23 the empty state offers the rooms of the gathering before, and copying them makes the same rooms empty`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val before = e2e.dsl().insertInto(GATHERING)
            .set(GATHERING.PUBLIC_CODE, talita.gathering.GatheringSupport.nextCode()).set(GATHERING.TITLE, "Convivenza di settembre 2026")
            .set(GATHERING.START_DATE, w.today.minusDays(400)).set(GATHERING.END_DATE, w.today.minusDays(398)).set(GATHERING.PLACE, "Casa per ritiri di esempio").set(GATHERING.FULL_FEE_CENTS, 6000)
            .set(GATHERING.STATUS, GatheringStatus.closed).returningResult(GATHERING.ID).fetchSingle().value1()!!
        e2e.room(before, "Camera 1", 4)
        e2e.room(before, "Camera 2", 4)
        e2e.room(before, "Stanza azzurra", 2)
        e2e.open("/convivenza/${w.id}/camere")

        assertThat(page.locator("main")).containsText("Nella «Convivenza di settembre 2026» c’erano 3 camere e 10 posti. Le copiamo senza le persone: poi le sistemi come vuoi.")
        assertAccessible(page, "rooms, empty state with the copy (${e2e.profile.label})")
        val copy = page.locator("main").buttonNamed("Copia le camere di Convivenza di settembre 2026")
        assertThat(copy).isVisible()
        e2e.press(copy)

        assertThat(page.locator("main")).containsText("Camere copiate.")
        assertThat(page.locator("section[id^=camera-] h3")).hasText(arrayOf("Camera 1", "Camera 2", "Stanza azzurra"))
        assertEquals(0, page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(Pattern.compile("Copia le camere"))).count(), "the button is not there with rooms written")
        assertThat(page.locator("section[id^=camera-]").locator("text=Vuota")).hasCount(3)
    }

    @E2eTest
    fun `t11 c10 c23 a child that another teacher put in a room is moved by a choice that says where they are now and where they were, with no error`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        val one = e2e.room(w.id, "Camera 1", 4)
        val two = e2e.room(w.id, "Camera 2", 4)
        e2e.open("/convivenza/${w.id}/camere")
        // The other teacher, on another phone, has put Sara in Camera 2 while this page was open.
        val other = e2e.newPage()
        other.navigate(e2e.url("/convivenza/${w.id}/camere"))
        other.getByLabel("Camera di Sara Neri").selectOption(two.toString())
        other.locator("main").buttonNamed("Metti Sara Neri in camera").click()
        assertThat(other.locator("#libero-b${c.sara}")).containsText("Sara Neri è in Camera 2.")

        page.mark()
        page.getByLabel("Camera di Sara Neri").selectOption(one.toString())
        e2e.press(page.locator("main").buttonNamed("Metti Sara Neri in camera"))

        assertThat(page.locator("#libero-b${c.sara}")).containsText("Sara Neri è in Camera 1. Prima era in Camera 2.")
        assertThat(page.locator("#camera-$one")).containsText("1 persona · 4 posti")
        assertThat(page.locator("#camera-$two")).containsText("Vuota · 4 posti")
        assertTrue(page.sameDocument() && page.locator("#messaggi [role=alert]").count() == 0, "no error and no message of failure")
        // The other page, asking the database now, finds her where she is.
        other.reload()
        assertThat(other.locator("#camera-$one")).containsText("Sara Neri")
    }

    @E2eTest
    fun `t11 c23 without the network nothing changes, the select keeps the choice and a message says so, and with the network back the same touch works`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        val one = e2e.room(w.id, "Camera 1", 4)
        e2e.open("/convivenza/${w.id}/camere")
        val summary = page.summary()

        page.route("**/camere/scegli") { it.abort() }
        page.getByLabel("Camera di Sara Neri").selectOption(one.toString())
        e2e.press(page.locator("main").buttonNamed("Metti Sara Neri in camera"))

        assertThat(page.locator("#messaggi")).containsText("Nessuna connessione")
        assertEquals(0, page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Riprova")).count(), "no «Riprova»: it is a send")
        assertEquals(summary, page.summary(), "nothing changed on the page")
        assertThat(page.getByLabel("Camera di Sara Neri")).hasValue(one.toString())
        assertThat(page.locator("main").buttonNamed("Metti Sara Neri in camera")).isEnabled()
        page.unroute("**/camere/scegli")
        e2e.press(page.locator("main").buttonNamed("Metti Sara Neri in camera"))
        assertThat(page.locator("#libero-b${c.sara}")).containsText("Sara Neri è in Camera 1.")
        assertThat(page.locator("#messaggi [role=alert]")).hasCount(0)
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `t11 c06 c23 without JavaScript every choice is a form with its button, Cambia opens by itself, and the answer is the page with the message`(e2e: E2e) {
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        val one = e2e.room(w.id, "Camera 1", 4)
        val two = e2e.room(w.id, "Camera 2", 4)
        e2e.put(c.luca, w.id, one)
        val plain = e2e.newPage(customize = { setJavaScriptEnabled(false) })
        // With JavaScript off Playwright cannot tell when the entrance of the content is over, so every page is let to settle before it is touched.
        fun touch(target: Locator) {
            plain.settled()
            e2e.touch(target)
            plain.waitForLoadState()
            plain.settled()
        }
        plain.navigate(e2e.url("/convivenza/${w.id}/camere"))
        plain.settled()

        plain.getByLabel("Camera di Sara Neri").selectOption(two.toString())
        touch(plain.locator("main").buttonNamed("Metti Sara Neri in camera"))
        assertThat(plain).hasURL(Pattern.compile(".*/camere#senza-camera"))
        assertThat(plain.locator("main")).containsText("Sara Neri è in Camera 2.")
        assertThat(plain.locator("#camera-$two")).containsText("Sara Neri")
        // «Cambia» is a native details: it opens without a script and sends its form.
        touch(plain.locator("#persona-b${c.luca} summary"))
        assertThat(plain.getByLabel("Camera di Luca Rossi")).isVisible()
        plain.getByLabel("Camera di Luca Rossi").selectOption(two.toString())
        touch(plain.locator("#persona-b${c.luca}").buttonNamed("Sposta"))
        assertThat(plain).hasURL(Pattern.compile(".*/camere#camera-$one"))
        assertThat(plain.locator("main")).containsText("Luca Rossi è in Camera 2.")
        // A choice with no room comes back as the whole page, with the error under the select.
        touch(plain.locator("main").buttonNamed("Metti Davide Costa in camera"))
        assertThat(plain.locator("#camera-b${c.davide}-errore")).hasText("Scegli la camera.")
    }

    // ---- The states of the gathering (criterion 23) --------------------------------------------------------------------------------------

    @E2eTest
    fun `t11 c13 c23 the day after the end the page has no actions and says so, a touch on a page that was left open answers with the page as it is, and the draft and the deleted states have no violation of axe`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        val one = e2e.room(w.id, "Camera 1", 4)
        e2e.put(c.luca, w.id, one)
        e2e.putTeacher(w.id, w.paoloId, one)
        e2e.open("/convivenza/${w.id}/camere")
        assertThat(page.locator("main").buttonNamed("Metti Sara Neri in camera")).isVisible()

        // The gathering ends while the page is open: a touch is refused with the page as it is now (409), and nothing changes.
        e2e.dsl().update(GATHERING).set(GATHERING.START_DATE, w.today.minusDays(3)).set(GATHERING.END_DATE, w.today.minusDays(1)).where(GATHERING.ID.eq(w.id)).execute()
        page.mark()
        page.getByLabel("Camera di Sara Neri").selectOption(one.toString())
        e2e.press(page.locator("main").buttonNamed("Metti Sara Neri in camera"))

        assertThat(page.locator("main")).containsText("La convivenza è finita: le camere non si cambiano più.")
        assertEquals(1, Regex("La convivenza è finita: le camere non si cambiano più\\.").findAll(page.locator("#camere-contenuto").innerText()).count(), "said once")
        assertTrue(page.focusOnNotice(), "the focus is on the sentence")
        assertTrue(page.sameDocument() && page.locator("#messaggi [role=alert]").count() == 0)
        assertEquals(0, page.locator("select").count() + page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(Pattern.compile("Metti|Sposta|Aggiungi"))).count(), "no actions")
        assertEquals(0, e2e.dsl().fetchCount(ROOM_CHILD, ROOM_CHILD.REGISTRATION_ID.eq(c.sara)), "nothing was written")
        assertThat(page.locator("#riepilogo").linkNamed("Stampa le camere")).isVisible()
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px")
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "rooms, finished (${e2e.profile.label})")

        // The job has deleted who slept where: only the rooms and their beds.
        e2e.dsl().deleteFrom(ROOM_CHILD).execute()
        e2e.dsl().deleteFrom(ROOM_TEACHER).execute()
        e2e.open("/convivenza/${w.id}/camere")
        assertThat(page.locator("main")).containsText("Chi dormiva dove è stato cancellato il ${GatheringFormat.date(w.today)}, alla fine della convivenza.")
        assertThat(page.locator("main")).containsText("Le camere restano, senza nomi: le puoi copiare nella convivenza dell’anno dopo.")
        assertEquals(0, page.locator("#senza-camera").count())
        assertAccessible(page, "rooms, deleted (${e2e.profile.label})")

        // A draft: rooms and teachers, no children (a draft has no registrations: R1 of TALITA-4).
        e2e.dsl().deleteFrom(REGISTRATION).execute()
        e2e.dsl().update(GATHERING).set(GATHERING.STATUS, GatheringStatus.draft).set(GATHERING.START_DATE, w.today.plusDays(30)).set(GATHERING.END_DATE, w.today.plusDays(32)).where(GATHERING.ID.eq(w.id)).execute()
        e2e.open("/convivenza/${w.id}/camere")
        assertThat(page.locator("main")).containsText("La convivenza è ancora in bozza: le camere si scrivono già, i bambini compaiono quando i genitori li iscrivono.")
        assertThat(page.locator("#senza-camera")).containsText("I bambini compaiono qui quando i genitori li iscrivono.")
        assertAccessible(page, "rooms, draft (${e2e.profile.label})")
    }

    @E2eTest
    fun `t11 c15 c16 c23 the card of the gathering and the card of the child have the room, and the card of the gathering is the one that leads to the page`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        val one = e2e.room(w.id, "Camera 1", 4)
        e2e.put(c.luca, w.id, one)

        e2e.open("/convivenza/${w.id}")
        val card = page.locator("section[aria-labelledby=camere]")
        assertThat(card).containsText("1 camera · 4 posti · 3 bambini senza camera")
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "detail of the gathering with the card of the rooms (${e2e.profile.label})")
        e2e.touch(card.locator("a").first())
        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}/camere"))

        e2e.open("/arrivi/${c.luca}")
        assertThat(page.locator("section[aria-labelledby=camera-titolo]")).containsText("Camera 1")
        assertAccessible(page, "card of a child with a room (${e2e.profile.label})")
        e2e.touch(page.locator("section[aria-labelledby=camera-titolo]").linkNamed("Camera 1"))
        assertThat(page).hasURL(Pattern.compile(".*/convivenza/${w.id}/camere#camera-$one"))
        e2e.open("/arrivi/${c.anna}")
        assertThat(page.locator("section[aria-labelledby=camera-titolo]")).containsText("Senza camera")
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "card of a child without a room (${e2e.profile.label})")
    }

    // ---- The print (criterion 14, 23) ----------------------------------------------------------------------------------------------------------

    private fun Page.print() = emulateMedia(Page.EmulateMediaOptions().setMedia(Media.PRINT))

    /** The pages of a PDF that Chromium wrote (the page objects are in the clear: Skia writes no object streams). */
    private fun pagesOf(pdf: Path): Int = Regex("/Type\\s*/Page[^s]").findAll(String(Files.readAllBytes(pdf), Charsets.ISO_8859_1)).count()

    @E2eTest
    fun `t11 c14 c23 the print has a button that prints, no sideways scrolling and no violation of axe, and as Chromium prints it is black on white on A4 with every room in a block that does not split`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val rooms = (1..8).map { e2e.room(w.id, "Camera $it", 4) }
        val rows = e2e.rows()
        val ids = (1..40).map { rows.register(w.id, e2e.someParent(), "Bambino", "Prova%02d".format(it), LocalDate.of(2016, 1, 1).plusDays(it.toLong()), status = if (it % 9 == 0) RegistrationStatus.maybe else RegistrationStatus.confirmed) }
        ids.take(24).forEachIndexed { index, id -> e2e.put(id, w.id, rooms[index / 3]) }
        e2e.putTeacher(w.id, w.paoloId, rooms[0])
        e2e.putTeacher(w.id, w.meId, rooms[3])
        val folder = Files.createTempDirectory("talita-stampa-camere")
        try {
            e2e.open("/convivenza/${w.id}/camere/stampa")
            assertThat(page).hasTitle("Camere · stampa · Talità")
            assertThat(page.locator("h1")).hasText("Camere")
            val button = page.locator("main button[data-azione=stampa]")
            assertThat(button).isVisible()
            assertTrue(button.box().height >= 47.9, "the button is ${button.box()}")
            page.evaluate("() => { window.__printed = 0; window.print = () => { window.__printed += 1; }; }")
            e2e.press(button)
            assertEquals(1, (page.evaluate("window.__printed") as Number).toInt(), "one tap on Stampa prints once")
            assertTrue(page.noHorizontalScroll())
            page.setViewportSize(320, 800)
            assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px")
            page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
            assertAccessible(page, "print of the rooms (${e2e.profile.label})")

            if (e2e.profile == Profile.DESKTOP) {
                // The PDF first: after emulating the media the page would be printed with the screen CSS, on a Letter sheet.
                val pdf = folder.resolve("camere.pdf")
                page.pdf(Page.PdfOptions().setPath(pdf).setPreferCSSPageSize(true).setPrintBackground(false))
                val sheets = pagesOf(pdf)
                assertTrue(sheets in 2..4, "eight rooms and forty children are $sheets sheets")
                val box = Regex("/MediaBox\\s*\\[\\s*0\\s+0\\s+([0-9.]+)\\s+([0-9.]+)").find(String(Files.readAllBytes(pdf), Charsets.ISO_8859_1))!!.groupValues
                assertTrue(box[1].toDouble() in 594.0..596.0 && box[2].toDouble() in 841.0..843.0, "A4 upright is 595 by 842 points: $box")
            }
            page.print()
            assertThat(page.locator("main button[data-azione=stampa]")).not().isVisible()
            assertEquals("rgb(0, 0, 0)", page.locator("main h1").evaluate("e => getComputedStyle(e).color"))
            assertEquals("rgb(255, 255, 255)", page.locator("body").evaluate("e => getComputedStyle(e).backgroundColor"))
            assertEquals(0, page.locator("body > header:visible, body > nav:visible").count(), "no header and no navigation on the sheet")
            for (room in page.locator("section[id^=stampa-camera-]").all()) {
                assertEquals("avoid", room.evaluate("e => getComputedStyle(e).breakInside"), "a room can be cut between two sheets")
                assertEquals("rgb(0, 0, 0)", room.evaluate("e => getComputedStyle(e).borderTopColor"), "the border of a room is not black")
            }
            // The block of who has no room can split, with its lines in two columns and each line whole.
            assertEquals("auto", page.locator("#stampa-senza-camera").evaluate("e => getComputedStyle(e).breakInside"))
            assertEquals("2", page.locator("#stampa-senza-camera ul").first().evaluate("e => getComputedStyle(e).columnCount"))
            assertEquals("avoid", page.locator("#stampa-senza-camera li").first().evaluate("e => getComputedStyle(e).breakInside"))
            assertTrue(page.noHorizontalScroll(), "the sheet is cut on the right")
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `t11 c21 c23 the pages of the rooms with the longest names do not scroll sideways at 320 px and have no violation of axe`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val parent = e2e.someParent()
        val room = e2e.room(w.id, "S".repeat(50), 4)
        val long = e2e.rows().register(w.id, parent, "N".repeat(100), "C".repeat(100), LocalDate.of(2016, 4, 4))
        e2e.put(long, w.id, room)
        e2e.rows().register(w.id, e2e.someParent(), "M".repeat(100), "L".repeat(100), LocalDate.of(2016, 4, 4))
        page.setViewportSize(320, 800)

        for (path in listOf("/convivenza/${w.id}/camere", "/convivenza/${w.id}/camere/stampa", "/convivenza/${w.id}/camere/$room/modifica", "/convivenza/${w.id}/camere/$room/togli", "/arrivi/$long")) {
            e2e.open(path)
            assertTrue(page.noHorizontalScroll(), "$path scrolls sideways at 320 px")
            assertAccessible(page, "$path with long names")
        }
    }

    @E2eTest
    fun `t11 c02 c23 the form of a room to change has the people that sleep there in its help, and the page of confirmation without JavaScript has the words of the dialog`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        val one = e2e.room(w.id, "Camera 1", 4)
        e2e.put(c.luca, w.id, one)
        e2e.put(c.anna, w.id, one)

        e2e.open("/convivenza/${w.id}/camere/$one/modifica")
        assertThat(page.locator("h1")).hasText("Modifica la camera")
        assertThat(page.getByLabel("Nome della camera")).hasValue("Camera 1")
        assertThat(page.locator("main")).containsText("Quanti letti ci sono nella camera. Ora ci dormono 2 persone.")
        assertAccessible(page, "change a room (${e2e.profile.label})")
        page.getByLabel("Posti letto").fill("1")
        e2e.press(page.locator("main").buttonNamed("Salva la camera"))
        assertThat(page.locator("main")).containsText("Camera salvata.")
        assertThat(page.locator("#camera-$one")).containsText("2 persone · 1 posto")
        assertThat(page.locator("#camera-$one")).containsText("2 persone per 1 posto.")

        // The page that stands in for the dialog.
        e2e.open("/convivenza/${w.id}/camere/$one/togli")
        assertThat(page.locator("h1")).hasText("Togliere Camera 1?")
        assertThat(page.locator("main")).containsText("Le 2 persone che ci dormono tornano fra quelle senza camera.")
        assertAccessible(page, "page to confirm taking a room away (${e2e.profile.label})")
        e2e.touch(page.locator("main").linkNamed("Annulla"))
        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}/camere"))
    }
}
