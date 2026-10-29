package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Response
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import talita.Addresses
import talita.account.TestAccounts
import talita.gathering.GatheringFormat
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.RETENTION_LOG
import talita.jooq.tables.references.ROOM
import talita.jooq.tables.references.ROOM_CHILD
import talita.jooq.tables.references.ROOM_TEACHER
import talita.registration.Notes
import talita.retention.RetentionJob
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rooms as QA checks them (TALITA-11), on a phone and on a desktop: what a teacher does with the choices of who sleeps where, what the forms of a room refuse and accept, what a parent
 * cannot reach, what happens to the people when the registrations change, a lost page after another teacher or the end, and what the job of the retention leaves at the end of the gathering.
 * They add to `RoomsE2eTest`, which has the day of work, the states, the print and axe. Names and numbers are synthetic.
 */
@E2eApplication
class RoomsQaE2eTest {

    private fun E2e.room(gathering: Long, name: String, beds: Int): Long =
        dsl().insertInto(ROOM).set(ROOM.GATHERING_ID, gathering).set(ROOM.NAME, name).set(ROOM.BEDS, beds.toShort()).returningResult(ROOM.ID).fetchSingle().value1()!!

    private fun E2e.put(registration: Long, gathering: Long, room: Long) {
        dsl().insertInto(ROOM_CHILD).set(ROOM_CHILD.REGISTRATION_ID, registration).set(ROOM_CHILD.GATHERING_ID, gathering).set(ROOM_CHILD.ROOM_ID, room).execute()
    }

    private fun E2e.putTeacher(gathering: Long, account: Long, room: Long) {
        dsl().insertInto(ROOM_TEACHER).set(ROOM_TEACHER.GATHERING_ID, gathering).set(ROOM_TEACHER.TEACHER_ACCOUNT_ID, account).set(ROOM_TEACHER.ROOM_ID, room).execute()
    }

    private fun E2e.roomOf(registration: Long): Long? = dsl().select(ROOM_CHILD.ROOM_ID).from(ROOM_CHILD).where(ROOM_CHILD.REGISTRATION_ID.eq(registration)).fetchOne(ROOM_CHILD.ROOM_ID)

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

    private fun Page.summary(): String = locator("#riepilogo").innerText().replace(Regex("\\s+"), " ").trim()

    /** The page has not been loaded again since the marker was written: a touch replaced a piece of it. */
    private fun Page.mark() = evaluate("() => { window.__marker = 'stesso'; }")

    private fun Page.sameDocument(): Boolean = evaluate("window.__marker") == "stesso"

    /** «Metti» of a row of who has no room: the choice and the button, the way a person makes them. */
    private fun E2e.metti(key: String, name: String, room: Long) {
        page.getByLabel("Camera di $name").selectOption(room.toString())
        press(page.locator("main").buttonNamed("Metti $name in camera"))
        assertThat(page.locator("#libero-$key")).containsText("$name è in")
    }

    /** «Cambia» of a person in a room, then the form of the room, then the button (`Sposta` or `Togli dalla camera`). */
    private fun E2e.cambia(key: String, button: String, room: Long? = null) {
        press(page.locator("#persona-$key summary"))
        if (room != null) page.locator("#sposta-$key").selectOption(room.toString())
        press(page.locator("#persona-$key").buttonNamed(button))
    }

    private fun Locator.texts(): List<String> = allInnerTexts().map { it.replace(Regex("\\s+"), " ").trim() }

    // ---- Who sleeps where, in the browser (criteria 6-9) ---------------------------------------------------------------------------------

    @E2eTest
    fun `t11 c06 c07 c08 c09 the teacher puts, moves and takes out the people, a teacher that is no custodian goes in a room and out, the notice of the beds comes and goes, and the choices that were not made have their error`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        val one = e2e.room(w.id, "Camera 1", 4)
        val two = e2e.room(w.id, "Stanza azzurra", 2)
        e2e.open("/convivenza/${w.id}/camere")
        page.mark()

        // Who has no room: the children that sleep there, in doubt too (Nicolò, who comes for lunch only, is not in the list), then the custodians.
        assertEquals(listOf("Bambini · 4", "Maestri affidatari · 2"), page.locator("#senza-camera h3").texts())
        assertThat(page.locator("#senza-camera")).not().containsText("Nicolò")
        assertThat(page.locator("#senza-camera")).not().containsText("Giulia Serra")
        val freeChildren = page.locator("#senza-camera ul").first().locator("li span.wrap-anywhere").texts()
        assertEquals(listOf("Davide Costa", "Sara Neri", "Anna Rossi", "Luca Rossi"), freeChildren, "the children without a room are by surname and name")
        assertEquals(listOf("Marta Bianchi", "Paolo Verdi"), page.locator("#senza-camera ul").nth(1).locator("li span.wrap-anywhere").texts(), "the custodians are by surname")

        // «Metti» for four children: two in Stanza azzurra (Sara, in doubt, counts), two in Camera 1.
        e2e.metti("b${c.davide}", "Davide Costa", two)
        assertThat(page.locator("#libero-b${c.davide}")).containsText("Davide Costa è in Stanza azzurra.")
        e2e.metti("b${c.sara}", "Sara Neri", two)
        assertThat(page.locator("#libero-b${c.sara}")).containsText("Sara Neri è in Stanza azzurra.")
        e2e.metti("b${c.luca}", "Luca Rossi", one)
        e2e.metti("b${c.anna}", "Anna Rossi", one)
        assertEquals("Riepilogo Stampa Camere 2 Posti letto 6 Persone in camera 4 bambini e maestri Bambini senza camera Nessuno", page.summary().replace(" Vai a chi è senza camera", ""))
        assertThat(page.locator("#camera-$two")).containsText("2 persone · 2 posti")
        assertThat(page.locator("#camera-$two")).not().containsText("persone per")
        assertThat(page.locator("#senza-camera")).containsText("Tutti i bambini hanno una camera.")
        assertTrue(page.sameDocument(), "four choices and the page was not loaded again")

        // A teacher who is no custodian is added from the room: three people for two beds, the notice is in the room and in the summary, and the child in doubt counts.
        e2e.press(page.locator("#camera-$two summary:has-text('Aggiungi un maestro')"))
        assertEquals(listOf("Scegli il maestro", "Marta Bianchi", "Giulia Serra", "Paolo Verdi"), page.locator("#maestro-$two option").texts(), "every teacher with no room is offered, the custodians too")
        page.locator("#maestro-$two").selectOption("m${w.meId}")
        e2e.press(page.locator("#camera-$two").buttonNamed("Aggiungi"))
        assertThat(page.locator("#camera-$two")).containsText("Giulia Serra è in Stanza azzurra.")
        assertThat(page.locator("#camera-$two")).containsText("3 persone · 2 posti")
        assertThat(page.locator("#camera-$two")).containsText("3 persone per 2 posti.")
        assertThat(page.locator("#riepilogo")).containsText("Stanza azzurra: 3 persone per 2 posti.")
        assertThat(page.locator("#riepilogo")).containsText("Persone in camera 5")
        assertThat(page.locator("#persona-m${w.meId}")).containsText("Maestro")
        // A teacher is in one room only: she is no longer offered by the other room.
        e2e.press(page.locator("#camera-$one summary:has-text('Aggiungi un maestro')"))
        assertEquals(listOf("Scegli il maestro", "Marta Bianchi", "Paolo Verdi"), page.locator("#maestro-$one option").texts())
        // No teacher chosen: the error is under the select, with its words, and the form stays open.
        e2e.press(page.locator("#camera-$one").buttonNamed("Aggiungi"))
        assertThat(page.locator("#maestro-$one-errore")).hasText("Scegli il maestro.")
        assertThat(page.locator("#maestro-$one")).hasAttribute("aria-invalid", "true")
        assertThat(page.locator("#camera-$one details[open]")).hasCount(1)

        // Another person moved: «Sposta» changes only that one, the message is in the room that was left, and the notice goes with the third person.
        e2e.cambia("b${c.sara}", "Sposta", one)
        assertThat(page.locator("#camera-$two")).containsText("Sara Neri è in Camera 1.")
        assertThat(page.locator("#camera-$one")).containsText("3 persone · 4 posti")
        assertThat(page.locator("#camera-$two")).containsText("2 persone · 2 posti")
        assertThat(page.locator("#camera-$two")).not().containsText("persone per")
        assertThat(page.locator("#riepilogo")).not().containsText("Più persone dei posti")
        assertThat(page.locator("#camera-$one #persona-b${c.sara}")).containsText("In forse")
        assertThat(page.locator("#camera-$two #persona-b${c.davide}")).isVisible()
        assertThat(page.locator("#camera-$one #persona-b${c.luca}")).isVisible()

        // «Sposta» with no room chosen: the error is under the select of the form of that person.
        e2e.cambia("b${c.luca}", "Sposta")
        assertThat(page.locator("#sposta-b${c.luca}-errore")).hasText("Scegli la camera.")
        assertThat(page.locator("#persona-b${c.luca} details[open]")).hasCount(1)
        assertEquals(one, e2e.roomOf(c.luca), "nothing changed")

        // «Togli dalla camera» for a child: only that one comes back among the ones without a room, with its choice, and the others stay.
        e2e.press(page.locator("#persona-b${c.luca}").buttonNamed("Togli dalla camera"))
        assertThat(page.locator("#camera-$one")).containsText("Luca Rossi è senza camera.")
        assertThat(page.locator("#libero-b${c.luca}")).isVisible()
        assertThat(page.getByLabel("Camera di Luca Rossi")).isVisible()
        assertEquals(null, e2e.roomOf(c.luca))
        assertEquals(one, e2e.roomOf(c.anna))
        assertEquals(one, e2e.roomOf(c.sara))
        assertEquals(two, e2e.roomOf(c.davide))
        assertThat(page.locator("#riepilogo")).containsText("Bambini senza camera 1")
        // Taking out who was taken out already: the state as it is, with a note and no error.
        e2e.cambia("b${c.anna}", "Togli dalla camera")
        assertThat(page.locator("#camera-$one")).containsText("Anna Rossi è senza camera.")

        // The teacher that is no custodian goes out of the room, and does not come back among the ones without a room (she is not in custody); she is offered again by the rooms.
        e2e.cambia("m${w.meId}", "Togli dalla camera")
        assertThat(page.locator("#camera-$two")).containsText("Giulia Serra è senza camera.")
        assertThat(page.locator("#senza-camera")).not().containsText("Giulia Serra")
        assertEquals(null, e2e.dsl().select(ROOM_TEACHER.ROOM_ID).from(ROOM_TEACHER).where(ROOM_TEACHER.TEACHER_ACCOUNT_ID.eq(w.meId)).fetchOne(ROOM_TEACHER.ROOM_ID))
        e2e.press(page.locator("#camera-$two summary:has-text('Aggiungi un maestro')"))
        assertThat(page.locator("#maestro-$two option")).hasText(arrayOf("Scegli il maestro", "Marta Bianchi", "Giulia Serra", "Paolo Verdi"))

        // A custodian is put from the list of who has no room, and is a person of the room like the others (and so can be moved).
        e2e.metti("m${w.martaId}", "Marta Bianchi", one)
        assertThat(page.locator("#libero-m${w.martaId}")).containsText("Marta Bianchi è in Camera 1.")
        assertThat(page.locator("#camera-$one #persona-m${w.martaId}")).containsText("Maestro")
        assertThat(page.locator("#senza-camera h3")).hasText(arrayOf(Pattern.compile("Bambini · 2"), Pattern.compile("Maestri affidatari · 1")))
        assertTrue(page.sameDocument(), "the whole sequence without loading the page again")
        assertAccessible(page, "rooms after the choices (${e2e.profile.label})")
    }

    // ---- The form of a room (criteria 2, 3, 5) --------------------------------------------------------------------------------------------

    private fun E2e.save(form: String, name: String, beds: String): Response {
        page.getByLabel("Nome della camera").fill(name)
        page.getByLabel("Posti letto").fill(beds)
        return page.waitForResponse({ it.url().endsWith(form) && it.request().method() == "POST" }) { press(page.locator("main").buttonNamed("Salva la camera")) }
    }

    @E2eTest
    fun `t11 c02 c03 c05 the form of a room refuses what is not a name or not beds with one sentence, keeps what was written, and takes in the names of the house`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val one = e2e.room(w.id, "Camera 1", 4)
        val form = "/convivenza/${w.id}/camere/nuova"
        e2e.open(form)

        // Nothing written: two errors, the notice on top, the focus on the first wrong field, the answer is a 422.
        assertEquals(422, e2e.save(form, "", "").status())
        assertThat(page.locator("main")).containsText("Controlla i 2 campi segnati in rosso.")
        assertThat(page.locator("#nome-errore")).hasText("Scrivi il nome della camera.")
        assertThat(page.locator("#posti-errore")).hasText("Scrivi quanti posti ci sono, da 1 a 50.")
        page.focusIs("document.activeElement.id === 'nome'")
        assertAccessible(page, "new room with two errors (${e2e.profile.label})")

        // A name of 51 characters, and beds that are not beds: one sentence for each, the field as it was written.
        assertEquals(422, e2e.save(form, "S".repeat(51), "abc").status())
        assertThat(page.locator("#nome-errore")).hasText("Usa al massimo 50 caratteri.")
        assertThat(page.getByLabel("Nome della camera")).hasValue("S".repeat(51))
        assertThat(page.getByLabel("Posti letto")).hasValue("abc")
        for (beds in listOf("0", "51", "-3", "2,5", "4 letti", "")) {
            assertEquals(422, e2e.save(form, "Camera 9", beds).status(), "beds «$beds»")
            assertThat(page.locator("#posti-errore")).hasText("Scrivi quanti posti ci sono, da 1 a 50.")
            assertThat(page.getByLabel("Posti letto")).hasValue(beds)
            assertThat(page.getByLabel("Nome della camera")).hasValue("Camera 9")
        }
        assertEquals(1, e2e.dsl().fetchCount(ROOM, ROOM.GATHERING_ID.eq(w.id)), "nothing was written")

        // A name that is taken, whatever its capitals and the spaces at the sides.
        assertEquals(422, e2e.save(form, "  CAMERA 1 ", "3").status())
        assertThat(page.locator("#nome-errore")).hasText("C’è già una camera con questo nome.")
        assertEquals(1, e2e.dsl().fetchCount(ROOM, ROOM.GATHERING_ID.eq(w.id)))

        // A name that says who sleeps there is refused with the sentence of the house, which does not repeat the word.
        for (name in listOf("Maschi", "camera delle femmine", "Bambine", "FEMMINILE", "Stanza dei maschietti", "Camera Bambina 2")) {
            assertEquals(422, e2e.save(form, name, "4").status(), name)
            assertThat(page.locator("#nome-errore")).hasText("Scrivi il nome della camera nella casa, non chi ci dorme.")
            assertThat(page.getByLabel("Nome della camera")).hasValue(name)
        }
        assertEquals(1, e2e.dsl().fetchCount(ROOM, ROOM.GATHERING_ID.eq(w.id)), "nothing was written")

        // The names of the house go in, with the spaces at the sides taken away.
        for ((typed, saved) in listOf("Camera 3" to "Camera 3", "Stanza azzurra" to "Stanza azzurra", "Primo piano, a destra" to "Primo piano, a destra", "  Mansarda  " to "Mansarda", "C".repeat(50) to "C".repeat(50))) {
            assertEquals(303, e2e.save(form, typed, " 4 ").status(), typed)
            assertThat(page).hasURL(e2e.url("/convivenza/${w.id}/camere"))
            assertThat(page.locator("main")).containsText("Camera salvata.")
            assertThat(page.locator("section[id^=camera-] h3").last()).hasText(saved)
            e2e.open(form)
        }
        assertEquals(listOf("Camera 1", "Camera 3", "Stanza azzurra", "Primo piano, a destra", "Mansarda", "C".repeat(50)), e2e.dsl().select(ROOM.NAME).from(ROOM).where(ROOM.GATHERING_ID.eq(w.id)).orderBy(ROOM.ID).fetch(ROOM.NAME))
        assertEquals(setOf<Short>(4), e2e.dsl().select(ROOM.BEDS).from(ROOM).where(ROOM.GATHERING_ID.eq(w.id)).and(ROOM.ID.gt(one)).fetch(ROOM.BEDS).toSet(), "the spaces around the beds are taken away")

        // The form of a change: it keeps its own name with other capitals, refuses the name of another room, and a name that says who sleeps there.
        val three = e2e.dsl().select(ROOM.ID).from(ROOM).where(ROOM.NAME.eq("Camera 3")).fetchSingle(ROOM.ID)!!
        val change = "/convivenza/${w.id}/camere/$three/modifica"
        e2e.open(change)
        assertEquals(303, e2e.save(change, "CAMERA 3", "5").status())
        assertThat(page.locator("#camera-$three")).containsText("CAMERA 3")
        e2e.open(change)
        assertEquals(422, e2e.save(change, "camera 1", "5").status())
        assertThat(page.locator("#nome-errore")).hasText("C’è già una camera con questo nome.")
        assertEquals(422, e2e.save(change, "Camera delle ragazze", "5").status())
        assertThat(page.locator("#nome-errore")).hasText("Scrivi il nome della camera nella casa, non chi ci dorme.")
        assertEquals("CAMERA 3", e2e.dsl().select(ROOM.NAME).from(ROOM).where(ROOM.ID.eq(three)).fetchSingle(ROOM.NAME))
        // The rooms stay in the order they were written, also the one that was renamed.
        e2e.open("/convivenza/${w.id}/camere")
        assertThat(page.locator("section[id^=camera-] h3").first()).hasText("Camera 1")
        assertThat(page.locator("section[id^=camera-] h3").nth(1)).hasText("CAMERA 3")
    }

    @E2eTest
    fun `t11 c05 a room with nobody is taken away at once, one with people has the dialog that can be cancelled with Escape, and a room already taken away by another teacher answers as it is`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        val empty = e2e.room(w.id, "Camera vuota", 2)
        val full = e2e.room(w.id, "Camera 1", 4)
        e2e.put(c.luca, w.id, full)
        e2e.put(c.anna, w.id, full)
        e2e.open("/convivenza/${w.id}/camere")
        page.mark()

        // A room where nobody sleeps: no dialog, the request goes at once.
        assertEquals(0, page.locator("#camera-$empty").linkNamed("Togli Camera vuota").count(), "no dialog for it")
        e2e.press(page.locator("#camera-$empty").buttonNamed("Togli Camera vuota"))
        assertThat(page.locator("main")).containsText("Camera tolta.")
        assertEquals(0, page.locator("#camera-$empty").count())

        // A room with people: the dialog says how many go back; Escape and «Annulla» leave everything as it was.
        e2e.press(page.locator("#camera-$full").linkNamed("Togli Camera 1"))
        page.waitForSettledDialog()
        assertThat(page.openDialog()).containsText("Le 2 persone che ci dormono tornano fra quelle senza camera.")
        page.keyboard().press("Escape")
        assertEquals(0, page.locator("dialog[open]").count(), "Escape closes the dialog")
        assertEquals(full, e2e.roomOf(c.luca))
        e2e.press(page.locator("#camera-$full").linkNamed("Togli Camera 1"))
        page.waitForSettledDialog()
        e2e.touch(page.openDialog().buttonNamed("Annulla"))
        assertEquals(0, page.locator("dialog[open]").count())
        assertEquals(full, e2e.roomOf(c.anna))

        // Another teacher takes the room away while this page is open: the touch on «Togli la camera» answers as it is, with the same words, and nobody gets an error.
        e2e.press(page.locator("#camera-$full").linkNamed("Togli Camera 1"))
        page.waitForSettledDialog()
        e2e.dsl().deleteFrom(ROOM).where(ROOM.ID.eq(full)).execute()
        e2e.touch(page.openDialog().buttonNamed("Togli la camera"))
        assertThat(page.locator("main")).containsText("Camera tolta.")
        assertEquals(0, page.locator("#messaggi [role=alert]").count(), "no error")
        assertEquals(null, e2e.roomOf(c.luca))
        assertEquals(null, e2e.roomOf(c.anna))
        assertTrue(page.sameDocument())
        assertThat(page.locator("main h2:visible").first()).hasText("Nessuna camera")
        assertEquals(0, page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Stampa le camere")).count(), "nothing to print without rooms")
    }

    // ---- What happens to the people when a registration changes (criteria 11, 12) -----------------------------------------------------------

    /** A gathering of three days (Friday to Sunday) a month away, with the person of the test as a teacher («Giulia Serra») who is no custodian. */
    private fun E2e.futureGathering(): OpenGathering {
        val gathering = openGathering()
        TestAccounts.makeTeacher(dsl(), checkNotNull(signedInAs), "Giulia", "Serra")
        return gathering
    }

    @E2eTest
    fun `t11 c11 c12 the withdrawal by the parent or by a teacher takes the child out of its room, a registration put back is among the ones without a room, a child that becomes lunch only stays in its room, and a paper registration is among the ones without a room`(e2e: E2e) {
        val page = e2e.page
        val g = e2e.futureGathering()
        val parentPage = e2e.newPage(signedIn = false)
        val parentEmail = Addresses.unique("e2e-genitore")
        e2e.signIn(parentPage, parentEmail, teacher = false)
        val parent = e2e.accountId(parentEmail)
        val rows = e2e.rows()
        val luca = rows.register(g.id, parent, "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        val anna = rows.register(g.id, parent, "Anna", "Rossi", LocalDate.of(2018, 1, 9))
        val davide = rows.register(g.id, e2e.someParent(), "Davide", "Costa", LocalDate.of(2017, 8, 8))
        val one = e2e.room(g.id, "Camera 1", 4)
        val two = e2e.room(g.id, "Stanza azzurra", 2)
        e2e.put(luca, g.id, one)
        e2e.put(anna, g.id, one)
        e2e.put(davide, g.id, two)

        // The parent withdraws Luca from the page of the registrations (the real dialog): he is out of the room, and the page of the rooms does not say him anywhere.
        parentPage.navigate(e2e.url("/iscrizioni"))
        parentPage.waitForLoadState()
        e2e.press(parentPage.locator("main").linkNamed("Ritira l’iscrizione di Luca Rossi"))
        parentPage.waitForSettledDialog()
        e2e.touch(parentPage.openDialog().buttonNamed("Ritira l’iscrizione"))
        assertThat(parentPage.locator("[role=status]")).containsText("Iscrizione ritirata.")
        assertEquals(null, e2e.roomOf(luca), "the withdrawal took him out of the room")
        assertEquals(one, e2e.roomOf(anna), "and nobody else")
        e2e.open("/convivenza/${g.id}/camere")
        assertThat(page.locator("#camera-$one")).containsText("1 persona · 4 posti")
        assertThat(page.locator("main")).not().containsText("Luca Rossi")
        assertThat(page.locator("#riepilogo")).containsText("Persone in camera 2")

        // A teacher puts the registration back (the real page): Luca is among the ones without a room, and not in Camera 1.
        e2e.open("/iscrizioni/$luca")
        e2e.press(page.locator("main").linkNamed("Rimetti l’iscrizione"))
        e2e.press(page.locator("main").buttonNamed("Rimetti l’iscrizione"))
        assertThat(page.locator("[role=status]")).containsText("Iscrizione rimessa.")
        e2e.open("/convivenza/${g.id}/camere")
        assertThat(page.locator("#senza-camera")).containsText("Luca Rossi")
        assertEquals(0, page.locator("#camera-$one #persona-b$luca").count(), "put back, he is not in the room he had")
        assertThat(page.locator("#camera-$one")).containsText("1 persona · 4 posti")

        // A teacher withdraws Davide (the real dialog): Stanza azzurra is empty.
        e2e.open("/iscrizioni/$davide")
        e2e.press(page.locator("main").linkNamed("Ritira l’iscrizione"))
        page.waitForSettledDialog()
        e2e.touch(page.openDialog().buttonNamed("Ritira l’iscrizione"))
        assertThat(page.locator("[role=status]")).containsText("Iscrizione ritirata.")
        e2e.open("/convivenza/${g.id}/camere")
        assertThat(page.locator("#camera-$two")).containsText("Vuota · 2 posti")
        assertThat(page.locator("main")).not().containsText("Davide Costa")

        // Anna becomes lunch only (the real correction of a teacher): she stays in her room, with what she comes for beside her name, and only «Togli dalla camera» takes her out.
        e2e.open("/iscrizioni/$anna")
        e2e.press(page.locator("main").linkNamed("Correggi l’iscrizione"))
        page.locator("input[name='children[0].presence'][value='days']").check()
        for (day in listOf("fri", "sat", "sun")) page.locator("input[name='children[0].day.$day'][value='lunch']").check()
        e2e.press(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(Pattern.compile("Salva le (correzioni|modifiche)"))))
        assertThat(page.locator("[role=status]")).containsText("Iscrizione salvata.")
        assertEquals(one, e2e.roomOf(anna), "lunch only does not take her out of the room")
        e2e.open("/convivenza/${g.id}/camere")
        assertThat(page.locator("#camera-$one #persona-b$anna")).containsText("Anna Rossi")
        assertThat(page.locator("#camera-$one #persona-b$anna")).containsText("pranzo")
        assertThat(page.locator("#camera-$one #persona-b$anna")).not().containsText("Tutti i giorni")
        e2e.cambia("b$anna", "Togli dalla camera")
        assertThat(page.locator("#camera-$one")).containsText("Anna Rossi è senza camera.")
        e2e.open("/convivenza/${g.id}/camere")
        assertThat(page.locator("main")).not().containsText("Anna Rossi")

        // A paper registration typed in by a teacher is among the ones without a room.
        e2e.open("/iscrizioni/nuova?convivenza=${g.id}")
        page.locator("#telefono").fill("333 987 6543")
        page.locator("#b0-nome").fill("Elena")
        page.locator("#b0-cognome").fill("Marino")
        page.locator("#b0-giorno").fill("1")
        page.locator("#b0-mese").fill("10")
        page.locator("#b0-anno").fill("2016")
        page.locator("input[name='children[0].presence'][value='all_days']").check()
        e2e.press(page.locator("main").buttonNamed("Inserisci l’iscrizione"))
        assertThat(page.locator("[role=status]")).containsText("Iscrizione inserita.")
        e2e.open("/convivenza/${g.id}/camere")
        assertThat(page.locator("#senza-camera")).containsText("Elena Marino")
        assertThat(page.locator("#senza-camera")).containsText("Luca Rossi")
        assertThat(page.getByLabel("Camera di Elena Marino")).isVisible()
    }

    // ---- The end of the gathering (criterion 13) -------------------------------------------------------------------------------------------

    /** What a by-hand request answers: the status. The form is sent as the pages send it (with the code of the form, and as htmx when [htmx]). */
    private fun Page.csrf(): String = requireNotNull(locator("input[type=hidden][name=_csrf]").first().getAttribute("value"))

    private fun Page.byHand(token: String, method: String, path: String, fields: Map<String, String> = emptyMap(), htmx: Boolean = false): Int {
        return (evaluate(
            "async ([method, path, fields, token, htmx]) => { const body = new URLSearchParams(fields); body.set('_csrf', token); " +
                "const headers = htmx ? { 'HX-Request': 'true' } : {}; " +
                "const r = await fetch(path, method === 'POST' ? { method, headers, body, redirect: 'manual' } : { method, headers, redirect: 'manual' }); return r.status; }",
            listOf(method, path, fields, token, htmx),
        ) as Number).toInt()
    }

    @E2eTest
    fun `t11 c13 on the last day everything still changes, and from the day after every request by hand is refused with 409 and nothing changes`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering(first = -2, last = 0)
        val c = e2e.children(w)
        val one = e2e.room(w.id, "Camera 1", 4)
        val two = e2e.room(w.id, "Stanza azzurra", 2)
        e2e.put(c.luca, w.id, one)
        e2e.open("/convivenza/${w.id}/camere")
        val token = page.csrf()

        // The last day is a day like the others: the actions are there and they work.
        assertThat(page.locator("main")).not().containsText("La convivenza è finita")
        e2e.metti("b${c.sara}", "Sara Neri", one)
        assertThat(page.locator("#camera-$one")).containsText("2 persone · 4 posti")
        e2e.open("/convivenza/${w.id}/camere/nuova")
        assertEquals(200, page.evaluate("fetch(location.href).then(r => r.status)"))

        // The day after: the page has no actions and says so; every write by hand is refused with 409 and nothing changes (the same with htmx), and so are the pages of the forms.
        e2e.dsl().update(GATHERING).set(GATHERING.START_DATE, w.today.minusDays(3)).set(GATHERING.END_DATE, w.today.minusDays(1)).where(GATHERING.ID.eq(w.id)).execute()
        e2e.open("/convivenza/${w.id}/camere")
        val state = listOf(
            e2e.dsl().select(ROOM.ID, ROOM.NAME, ROOM.BEDS).from(ROOM).orderBy(ROOM.ID).fetch().map { it.intoList() },
            e2e.dsl().select(ROOM_CHILD.REGISTRATION_ID, ROOM_CHILD.ROOM_ID).from(ROOM_CHILD).orderBy(ROOM_CHILD.REGISTRATION_ID).fetch().map { it.intoList() },
            e2e.dsl().select(ROOM_TEACHER.TEACHER_ACCOUNT_ID, ROOM_TEACHER.ROOM_ID).from(ROOM_TEACHER).fetch().map { it.intoList() },
        )
        val base = "/convivenza/${w.id}/camere"
        val writes = listOf(
            "$base/scegli" to mapOf("persona" to "b${c.davide}", "camera" to two.toString()),
            "$base/sposta" to mapOf("persona" to "b${c.luca}", "azione" to "togli"),
            "$base/sposta" to mapOf("persona" to "b${c.luca}", "azione" to "sposta", "camera" to two.toString()),
            "$base/maestri" to mapOf("camera" to two.toString(), "persona" to "m${w.meId}"),
            "$base/nuova" to mapOf("name" to "Camera 9", "beds" to "3"),
            "$base/$one/modifica" to mapOf("name" to "Camera 1 bis", "beds" to "3"),
            "$base/$one/togli" to mapOf("camera" to one.toString()),
            "$base/$two/togli" to mapOf("camera" to two.toString()),
            "$base/copia" to emptyMap(),
        )
        for ((path, fields) in writes) {
            assertEquals(409, page.byHand(token, "POST", path, fields), "POST $path")
            assertEquals(409, page.byHand(token, "POST", path, fields, htmx = true), "POST (htmx) $path")
        }
        for (path in listOf("$base/nuova", "$base/$one/modifica", "$base/$one/togli")) assertEquals(409, page.byHand(token, "GET", path), "GET $path")
        assertEquals(200, page.byHand(token, "GET", base))
        assertEquals(200, page.byHand(token, "GET", "$base/stampa"))
        assertEquals(state, listOf(
            e2e.dsl().select(ROOM.ID, ROOM.NAME, ROOM.BEDS).from(ROOM).orderBy(ROOM.ID).fetch().map { it.intoList() },
            e2e.dsl().select(ROOM_CHILD.REGISTRATION_ID, ROOM_CHILD.ROOM_ID).from(ROOM_CHILD).orderBy(ROOM_CHILD.REGISTRATION_ID).fetch().map { it.intoList() },
            e2e.dsl().select(ROOM_TEACHER.TEACHER_ACCOUNT_ID, ROOM_TEACHER.ROOM_ID).from(ROOM_TEACHER).fetch().map { it.intoList() },
        ), "nothing was written")

        // What the page shows: the people are there until the job deletes them, with no action, and the print is still there.
        assertThat(page.locator("main")).containsText("La convivenza è finita: le camere non si cambiano più.")
        assertThat(page.locator("#camera-$one")).containsText("Luca Rossi")
        assertEquals(0, page.locator("select, details, form").count(), "no choice, no «Cambia», no form")
        assertEquals(0, page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName(Pattern.compile("Modifica|Togli|Aggiungi"))).count(), "no link of an action")
        assertThat(page.locator("#riepilogo").linkNamed("Stampa le camere")).isVisible()
    }

    // ---- What a parent can reach (criteria 17, 18) ------------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `t11 c17 c18 a parent, also the one whose child is in a room, is told 403 on every address of the rooms with a page and by hand, and the pages of the parent say no room`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering(meTeacher = false)
        val rows = e2e.rows()
        val luca = rows.register(w.id, w.meId, "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        val sara = rows.register(w.id, e2e.someParent(), "Sara", "Neri", LocalDate.of(2017, 3, 3))
        val one = e2e.room(w.id, "Camera 1", 4)
        val two = e2e.room(w.id, "Stanza azzurra", 2)
        e2e.put(luca, w.id, one)
        e2e.put(sara, w.id, two)
        e2e.putTeacher(w.id, w.paoloId, one)
        val base = "/convivenza/${w.id}/camere"
        val pages = listOf(base, "$base/stampa", "$base/nuova", "$base/$one/modifica", "$base/$one/togli", "$base/scegli", "$base/sposta", "$base/maestri", "$base/copia", "/arrivi/$luca", "/convivenza/${w.id}")
        val secrets = listOf("Camera 1", "Stanza azzurra", "Luca", "Sara", "Paolo", "Verdi", "Neri")

        // Every address of the rooms (and the card of the child in Arrivi that says the room): the 403 of the access, with nothing of the rooms in it.
        for (path in pages) {
            assertEquals(403, e2e.open(path).status(), path)
            assertThat(page.locator("h1")).hasText("Non puoi vedere questa pagina")
            assertThat(page.locator("main")).containsText("La pagina esiste, ma il tuo accesso non comprende questi dati.")
            val text = page.locator("body").innerText()
            for (secret in secrets) assertFalse(text.contains(secret), "$path says «$secret» to a parent")
            assertEquals(0, page.mainNavigation().getByText("Convivenza").count(), "«Convivenza» is not in the navigation of a parent")
        }
        assertAccessible(page, "403 of the rooms (${e2e.profile.label})")

        // The same by hand, as a person who knows the addresses writes them: no variant of the address gets a page.
        val variants = listOf("$base/", "/convivenza/${w.id}//camere", "/CONVIVENZA/${w.id}/CAMERE", "$base;x=1", "$base?x=1", "$base/%2e%2e/camere", "/convivenza/%2e%2e/convivenza/${w.id}/camere", "$base%2Fstampa", "$base.json", "$base/stampa/")
        e2e.open("/profilo")
        val token = page.csrf()
        for (path in variants) {
            val status = (page.evaluate("path => fetch(path, { redirect: 'manual' }).then(r => r.status).catch(() => -1)", path) as Number).toInt()
            assertTrue(status != 200, "GET $path answers 200 to a parent")
        }
        val before = listOf(e2e.dsl().fetchCount(ROOM), e2e.dsl().fetchCount(ROOM_CHILD), e2e.dsl().fetchCount(ROOM_TEACHER))
        val writes = listOf(
            "$base/scegli" to mapOf("persona" to "b${luca}", "camera" to two.toString()),
            "$base/sposta" to mapOf("persona" to "b$luca", "azione" to "togli"),
            "$base/maestri" to mapOf("camera" to two.toString(), "persona" to "m${w.paoloId}"),
            "$base/nuova" to mapOf("name" to "Camera 9", "beds" to "3"),
            "$base/$one/modifica" to mapOf("name" to "Camera 1 bis", "beds" to "3"),
            "$base/$one/togli" to mapOf("camera" to one.toString()),
            "$base/copia" to emptyMap(),
        )
        for ((path, fields) in writes) {
            assertEquals(403, page.byHand(token, "POST", path, fields), "POST $path")
            assertEquals(403, page.byHand(token, "POST", path, fields, htmx = true), "POST (htmx) $path")
        }
        assertEquals(before, listOf(e2e.dsl().fetchCount(ROOM), e2e.dsl().fetchCount(ROOM_CHILD), e2e.dsl().fetchCount(ROOM_TEACHER)), "nothing changed")
        assertEquals(one, e2e.roomOf(luca))
        assertEquals("Camera 1", e2e.dsl().select(ROOM.NAME).from(ROOM).where(ROOM.ID.eq(one)).fetchSingle(ROOM.NAME))

        // The pages of the parent: none says a room, of their child or of another.
        for (path in listOf("/", "/iscrizioni", "/profilo", "/iscrizioni/$luca/modifica")) {
            e2e.open(path)
            val text = page.locator("body").innerText()
            assertFalse(Regex("camer|stanz|dorm", RegexOption.IGNORE_CASE).containsMatchIn(text), "$path says a room to the parent: ${text.take(300)}")
        }
        e2e.open("/informativa")
        val notice = page.locator("body").innerText()
        assertFalse(notice.contains("Camera 1") || notice.contains("Stanza azzurra"), "the notice says no room, only that there are rooms")
    }

    // ---- The job at the end of the gathering (criterion 20) ------------------------------------------------------------------------------

    @E2eTest
    fun `t11 c20 the day after the end the job deletes who sleeps where and nothing of the rooms, the pages say it with the date, and the rooms are copied empty the year after`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering(first = -3, last = -1, status = GatheringStatus.closed)
        val c = e2e.children(w)
        val one = e2e.room(w.id, "Camera 1", 4)
        val two = e2e.room(w.id, "Camera 2", 4)
        val three = e2e.room(w.id, "Stanza azzurra", 2)
        e2e.put(c.luca, w.id, one)
        e2e.put(c.anna, w.id, one)
        e2e.put(c.sara, w.id, two)
        e2e.putTeacher(w.id, w.paoloId, one)
        e2e.putTeacher(w.id, w.meId, three)
        val deletedOn = GatheringFormat.date(w.today)

        // The gathering ended yesterday and the job has not run yet: the people are still there, with no action.
        e2e.open("/convivenza/${w.id}/camere")
        assertThat(page.locator("main")).containsText("La convivenza è finita: le camere non si cambiano più.")
        assertThat(page.locator("#camera-$one")).containsText("Luca Rossi")
        assertThat(page.locator("main")).not().containsText("cancellato")
        val logged = e2e.dsl().fetchCount(RETENTION_LOG, RETENTION_LOG.RULE.eq("rooms"))

        // The job: who sleeps where is deleted, children and teachers, and the rooms stay with their beds. The register has a row with the number, and nothing else.
        val report = e2e.application.getBean(RetentionJob::class.java).run()
        assertTrue(report.succeeded, "the job failed: ${report.failed}")
        assertEquals(5, report.deleted.single { it.rule == "rooms" }.rows)
        assertEquals(logged + 1, e2e.dsl().fetchCount(RETENTION_LOG, RETENTION_LOG.RULE.eq("rooms")))
        assertEquals(5, e2e.dsl().select(RETENTION_LOG.ROW_COUNT).from(RETENTION_LOG).where(RETENTION_LOG.RULE.eq("rooms")).orderBy(RETENTION_LOG.ID.desc()).limit(1).fetchSingle(RETENTION_LOG.ROW_COUNT))
        assertEquals(0, e2e.dsl().fetchCount(ROOM_CHILD) + e2e.dsl().fetchCount(ROOM_TEACHER))
        assertEquals(listOf("Camera 1" to 4, "Camera 2" to 4, "Stanza azzurra" to 2), e2e.dsl().select(ROOM.NAME, ROOM.BEDS).from(ROOM).where(ROOM.GATHERING_ID.eq(w.id)).orderBy(ROOM.ID).fetch { it[ROOM.NAME]!! to it[ROOM.BEDS]!!.toInt() })
        assertEquals(5, e2e.dsl().fetchCount(REGISTRATION, REGISTRATION.GATHERING_ID.eq(w.id)), "the registrations are still there")
        val again = e2e.application.getBean(RetentionJob::class.java).run()
        assertTrue(again.deleted.none { it.rule == "rooms" }, "a second run has nothing to delete")
        assertEquals(logged + 1, e2e.dsl().fetchCount(RETENTION_LOG, RETENTION_LOG.RULE.eq("rooms")), "and writes nothing in the register")

        // The page of the rooms: the sentence with the date, the rooms with their beds and nobody in them, no print.
        e2e.open("/convivenza/${w.id}/camere")
        assertThat(page.locator("main")).containsText("Chi dormiva dove è stato cancellato il $deletedOn, alla fine della convivenza.")
        assertThat(page.locator("main")).containsText("Le camere restano, senza nomi: le puoi copiare nella convivenza dell’anno dopo.")
        assertEquals("Riepilogo Camere 3 Posti letto 10", page.summary())
        assertThat(page.locator("section[id^=camera-] h3")).hasText(arrayOf("Camera 1", "Camera 2", "Stanza azzurra"))
        assertThat(page.locator("section[id^=camera-]")).hasText(arrayOf(Pattern.compile("Camera 1\\s*4 posti"), Pattern.compile("Camera 2\\s*4 posti"), Pattern.compile("Stanza azzurra\\s*2 posti")))
        assertEquals(0, page.locator("li[id^=persona-], #senza-camera, select, form").count())
        assertThat(page.locator("main")).not().containsText("Luca Rossi")
        e2e.open("/convivenza/${w.id}/camere/stampa")
        assertThat(page.locator("main")).containsText("Niente da stampare")
        assertThat(page.locator("main")).not().containsText("Luca Rossi")

        // The card of the child in Arrivi, the card of the retention, the card of the rooms, and the notice for the parents say the same.
        e2e.open("/arrivi/${c.luca}")
        assertThat(page.locator("section[aria-labelledby=camera-titolo]")).containsText("Chi dormiva dove è stato cancellato il $deletedOn.")
        e2e.open("/convivenza/${w.id}")
        assertThat(page.locator("section[aria-labelledby=conservazione]")).containsText("Chi dorme dove")
        assertThat(page.locator("section[aria-labelledby=conservazione]")).containsText("È stato cancellato il $deletedOn")
        assertThat(page.locator("section[aria-labelledby=camere]")).containsText("3 camere · 10 posti")
        assertThat(page.locator("section[aria-labelledby=camere]")).not().containsText("senza camera")
        e2e.open("/informativa")
        assertThat(page.locator("main")).containsText(Pattern.compile("Chi dorme in quale camera\\s*Alla fine della convivenza\\."))

        // The year after: the rooms of the gathering that ended are offered, and copying them makes the same rooms, empty.
        val next = e2e.dsl().insertInto(GATHERING)
            .set(GATHERING.PUBLIC_CODE, GatheringSupport.nextCode()).set(GATHERING.TITLE, "Convivenza di settembre 2028")
            .set(GATHERING.START_DATE, w.today.plusDays(300)).set(GATHERING.END_DATE, w.today.plusDays(302)).set(GATHERING.PLACE, "Casa per ritiri di esempio").set(GATHERING.FULL_FEE_CENTS, 6000)
            .set(GATHERING.STATUS, GatheringStatus.draft).returningResult(GATHERING.ID).fetchSingle().value1()!!
        e2e.open("/convivenza/$next/camere")
        assertThat(page.locator("main")).containsText("Nella «Convivenza di settembre 2027» c’erano 3 camere e 10 posti. Le copiamo senza le persone: poi le sistemi come vuoi.")
        e2e.press(page.locator("main").buttonNamed("Copia le camere di Convivenza di settembre 2027"))
        assertThat(page.locator("main")).containsText("Camere copiate.")
        assertThat(page.locator("section[id^=camera-] h3")).hasText(arrayOf("Camera 1", "Camera 2", "Stanza azzurra"))
        assertThat(page.locator("section[id^=camera-]").locator("text=Vuota")).hasCount(3)
        assertEquals(0, e2e.dsl().fetchCount(ROOM_CHILD, ROOM_CHILD.GATHERING_ID.eq(next)) + e2e.dsl().fetchCount(ROOM_TEACHER, ROOM_TEACHER.GATHERING_ID.eq(next)))
        assertEquals(3, e2e.dsl().fetchCount(ROOM, ROOM.GATHERING_ID.eq(w.id)), "the rooms of the first gathering are still there")
    }

    // ---- A page that was left open (criteria 6, 11) -----------------------------------------------------------------------------------------

    @E2eTest
    fun `t11 c06 c11 a touch on a page that was left open answers as things are now, when a room was taken away by another teacher or a registration was withdrawn`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        val one = e2e.room(w.id, "Camera 1", 4)
        val two = e2e.room(w.id, "Stanza azzurra", 2)
        e2e.open("/convivenza/${w.id}/camere")
        page.mark()

        // The room was taken away by another teacher: the touch finds nothing, a message says that something did not work, and the page stays as it is (nothing is shown as saved).
        e2e.dsl().deleteFrom(ROOM).where(ROOM.ID.eq(two)).execute()
        page.getByLabel("Camera di Sara Neri").selectOption(two.toString())
        e2e.press(page.locator("main").buttonNamed("Metti Sara Neri in camera"))
        assertThat(page.locator("#messaggi")).containsText("Qualcosa non ha funzionato")
        assertEquals(null, e2e.roomOf(c.sara))
        assertEquals(0, page.locator("#libero-b${c.sara}").getByText("è in").count(), "no message that says that it was done")
        assertThat(page.locator("main").buttonNamed("Metti Sara Neri in camera")).isEnabled()
        assertTrue(page.sameDocument())

        // The registration was withdrawn by someone else: the answer is the block as it is now, with the sentence in the row of the child and no choice there.
        e2e.dsl().update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.withdrawn).where(REGISTRATION.ID.eq(c.davide)).execute()
        page.getByLabel("Camera di Davide Costa").selectOption(one.toString())
        e2e.press(page.locator("main").buttonNamed("Metti Davide Costa in camera"))
        assertThat(page.locator("#libero-b${c.davide}")).containsText("L’iscrizione di Davide Costa è stata ritirata: non può avere una camera.")
        assertEquals(0, page.getByLabel("Camera di Davide Costa").count(), "no choice for a registration that was withdrawn")
        assertEquals(null, e2e.roomOf(c.davide))
        // The room that was taken away is not in the block any more: the select of Sara has only the room that is there.
        assertThat(page.locator("#camera-b${c.sara} option")).hasText(arrayOf("Scegli la camera", "Camera 1 · 0 su 4 posti"))
        assertTrue(page.sameDocument())
    }

    // ---- The print (criterion 14) ---------------------------------------------------------------------------------------------------------------

    /** The text of each sheet of a PDF, with `pdftotext` (null when the program is not there). */
    private fun sheetsOf(pdf: Path): List<String>? = try {
        val process = ProcessBuilder("pdftotext", "-layout", pdf.toString(), "-").redirectErrorStream(true).start()
        val text = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (process.waitFor() == 0) text.split('\u000c').filter { it.isNotBlank() } else null
    } catch (e: java.io.IOException) {
        null
    }

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t11 c14 the sheet has the names and nothing else, no age, no presence, no phone and no note on health, a room is never cut between two sheets and who has no room is whole`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val rooms = (1..8).map { e2e.room(w.id, "Camera $it", 4) }
        val rows = e2e.rows()
        val ids = (1..40).map { number ->
            rows.register(
                w.id, e2e.someParent(), "Bambino", "Prova%02d".format(number), LocalDate.of(2016, 1, 1).plusDays(number.toLong()),
                phone = "333 123 45%02d".format(number), status = if (number % 9 == 0) RegistrationStatus.maybe else RegistrationStatus.confirmed,
                notes = if (number % 5 == 0) Notes("Arachidi", null, null) else null,
            )
        }
        ids.take(24).forEachIndexed { index, id -> e2e.put(id, w.id, rooms[index / 3]) }
        e2e.putTeacher(w.id, w.paoloId, rooms[0])
        e2e.putTeacher(w.id, w.meId, rooms[3])
        val folder = Files.createTempDirectory("talita-stampa-camere-qa")
        try {
            e2e.open("/convivenza/${w.id}/camere/stampa")
            val screen = page.locator("main").innerText()
            for (forbidden in listOf("anni", "Tutti i giorni", "333", "Arachidi", "Sab", "pranzo")) assertFalse(screen.contains(forbidden), "the sheet says «$forbidden»")
            assertThat(page.locator("main")).containsText("Stampato il ${GatheringFormat.date(w.today)} alle")
            assertThat(page.locator("main")).containsText("Questo foglio dice dove dorme ogni persona. Tienilo fra i maestri e distruggilo alla fine della convivenza.")
            assertThat(page.locator("main")).containsText("Alla fine della convivenza questo foglio va distrutto. Non ha età, presenze, recapiti né segnalazioni.")
            assertEquals(8, page.locator("section[id^=stampa-camera-]").count())

            val pdf = folder.resolve("camere.pdf")
            page.pdf(Page.PdfOptions().setPath(pdf).setPreferCSSPageSize(true).setPrintBackground(false))
            val sheets = sheetsOf(pdf)
            assumeTrue(sheets != null, "pdftotext is not installed: the sheets of the PDF are not read")
            val text = sheets!!.joinToString("\n")
            for (forbidden in listOf("anni", "Tutti i giorni", "333", "Arachidi", "pranzo")) assertFalse(text.contains(forbidden), "the printed sheet says «$forbidden»")
            assertTrue(sheets.size in 2..4, "${sheets.size} sheets")
            assertTrue(sheets.all { it.lines().count { line -> line.isNotBlank() } >= 8 }, "a sheet is nearly empty: ${sheets.map { it.lines().count { line -> line.isNotBlank() } }}")
            // A room is whole on one sheet: its name and all its people.
            for (index in rooms.indices) {
                val members = (index * 3 + 1..index * 3 + 3).map { "Prova%02d".format(it) }
                val at = sheets.indexOfFirst { Regex("^\\s*Camera ${index + 1}\\s", RegexOption.MULTILINE).containsMatchIn(it) }
                assertTrue(at >= 0, "Camera ${index + 1} is not on any sheet")
                for (member in members) assertTrue(sheets[at].contains(member), "$member is not on the sheet of Camera ${index + 1} (sheet ${at + 1})")
            }
            // Who has no room: every name once, with the same count as the page.
            for (number in 25..40) assertEquals(1, Regex("Prova%02d".format(number)).findAll(text).count(), "Prova%02d is not once in the sheet".format(number))
            for (number in 1..24) assertEquals(1, Regex("Prova%02d".format(number)).findAll(text).count(), "Prova%02d is not once in the sheet".format(number))
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    // ---- Waiting, a refusal and a failure (the states of the design) ---------------------------------------------------------------------------

    @E2eTest
    fun `t11 c06 c23 the select does not send by itself, while a touch waits only its button says so, and a failure or a refusal of the server leaves the page as it is with the message of the guide`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        val one = e2e.room(w.id, "Camera 1", 4)
        e2e.put(c.luca, w.id, one)
        e2e.open("/convivenza/${w.id}/camere")
        page.mark()

        // Choosing a room sends nothing: it is the button that sends (two touches, the choice and «Metti»).
        val sent = java.util.concurrent.atomic.AtomicInteger(0)
        page.onRequest { if (it.method() == "POST") sent.incrementAndGet() }
        page.getByLabel("Camera di Davide Costa").selectOption(one.toString())
        page.waitForTimeout(500.0)
        assertEquals(0, sent.get(), "the select sent by itself")
        assertEquals(null, e2e.roomOf(c.davide))

        // A touch that waits: only its own button shows the wait and cannot be touched again, the other buttons stay as they are.
        val held = AtomicReference<Route>()
        page.route("**/camere/scegli") { held.set(it) }
        e2e.press(page.locator("main").buttonNamed("Metti Davide Costa in camera"))
        val button = page.locator("#libero-b${c.davide} button")
        assertThat(button.getByText("Metto…")).isVisible()
        assertThat(button.getByText("Metti", Locator.GetByTextOptions().setExact(false)).first()).not().isVisible()
        assertThat(button).isDisabled()
        assertThat(page.locator("main").buttonNamed("Metti Sara Neri in camera")).isEnabled()
        held.get().resume()
        assertThat(page.locator("#libero-b${c.davide}")).containsText("Davide Costa è in Camera 1.")
        page.unroute("**/camere/scegli")

        // The same wait on «Sposta», «Aggiungi» and «Togli la camera»: each says its own word.
        val heldMove = AtomicReference<Route>()
        page.route("**/camere/sposta") { heldMove.set(it) }
        e2e.press(page.locator("#persona-b${c.luca} summary"))
        e2e.press(page.locator("#persona-b${c.luca}").buttonNamed("Togli dalla camera"))
        assertThat(page.locator("#persona-b${c.luca} button[value=togli]").getByText("Tolgo…")).isVisible()
        assertThat(page.locator("#persona-b${c.luca} button[value=sposta]")).isDisabled()
        assertThat(page.locator("main").buttonNamed("Metti Sara Neri in camera")).isEnabled()
        heldMove.get().resume()
        assertThat(page.locator("#camera-$one")).containsText("Luca Rossi è senza camera.")
        page.unroute("**/camere/sposta")

        // A failure of the server (500): nothing changes, the choice stays in the select, the button works again, and the message is the one of the guide, with no «Riprova».
        val before = page.summary()
        page.route("**/camere/scegli") { it.fulfill(Route.FulfillOptions().setStatus(500).setBody("errore")) }
        page.getByLabel("Camera di Sara Neri").selectOption(one.toString())
        e2e.press(page.locator("main").buttonNamed("Metti Sara Neri in camera"))
        assertThat(page.locator("#messaggi")).containsText("Qualcosa non ha funzionato")
        assertEquals(before, page.summary(), "nothing changed on the page")
        assertThat(page.getByLabel("Camera di Sara Neri")).hasValue(one.toString())
        assertThat(page.locator("main").buttonNamed("Metti Sara Neri in camera")).isEnabled()
        assertEquals(null, e2e.roomOf(c.sara))
        assertEquals(0, page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Riprova")).count())
        // A refusal (403): the message of the guide, and nothing changes either.
        page.unroute("**/camere/scegli")
        page.route("**/camere/scegli") { it.fulfill(Route.FulfillOptions().setStatus(403).setBody("no")) }
        e2e.press(page.locator("main").buttonNamed("Metti Sara Neri in camera"))
        assertThat(page.locator("#messaggi")).containsText("Accesso negato")
        assertEquals(null, e2e.roomOf(c.sara))
        assertEquals(before, page.summary())
        page.unroute("**/camere/scegli")
        assertTrue(page.sameDocument())
        assertAccessible(page, "rooms after a refusal (${e2e.profile.label})")
    }

    // ---- Without JavaScript, and the way back (criteria 5, 6, 8) -----------------------------------------------------------------------------

    @E2eTest(profiles = [Profile.PHONE])
    fun `t11 c05 c07 c08 without JavaScript a teacher is added, a person is taken out, a room with people is taken away through the page of confirmation and an empty one at once, and the answer is the page with the message`(e2e: E2e) {
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        val one = e2e.room(w.id, "Camera 1", 4)
        val two = e2e.room(w.id, "Stanza azzurra", 2)
        val empty = e2e.room(w.id, "Camera vuota", 1)
        e2e.put(c.luca, w.id, one)
        e2e.put(c.anna, w.id, one)
        val plain = e2e.newPage(customize = { setJavaScriptEnabled(false) })
        fun touch(target: Locator) {
            plain.settled()
            e2e.touch(target)
            plain.waitForLoadState()
            plain.settled()
        }
        plain.navigate(e2e.url("/convivenza/${w.id}/camere"))
        plain.settled()

        // «Aggiungi un maestro»: the details open by themselves, and the answer is the page at the room with the message.
        touch(plain.locator("#camera-$two summary:has-text('Aggiungi un maestro')"))
        plain.locator("#maestro-$two").selectOption("m${w.paoloId}")
        touch(plain.locator("#camera-$two").buttonNamed("Aggiungi"))
        assertThat(plain).hasURL(Pattern.compile(".*/camere#camera-$two"))
        assertThat(plain.locator("main")).containsText("Paolo Verdi è in Stanza azzurra.")
        assertThat(plain.locator("#camera-$two #persona-m${w.paoloId}")).isVisible()
        // No teacher chosen: the whole page comes back with the error under the select.
        touch(plain.locator("#camera-$one summary:has-text('Aggiungi un maestro')"))
        touch(plain.locator("#camera-$one").buttonNamed("Aggiungi"))
        assertThat(plain.locator("#maestro-$one-errore")).hasText("Scegli il maestro.")

        // «Togli dalla camera» of a child: the page with the message in the room.
        touch(plain.locator("#persona-b${c.luca} summary"))
        touch(plain.locator("#persona-b${c.luca}").buttonNamed("Togli dalla camera"))
        assertThat(plain.locator("main")).containsText("Luca Rossi è senza camera.")
        assertEquals(null, e2e.roomOf(c.luca))

        // A room with people goes through the page of confirmation (with the words of the dialog), a room where nobody sleeps goes at once.
        touch(plain.locator("#camera-$one").linkNamed("Togli Camera 1"))
        assertThat(plain.locator("h1")).hasText("Togliere Camera 1?")
        assertThat(plain.locator("main")).containsText("La persona che ci dorme torna fra quelle senza camera.")
        touch(plain.locator("main").buttonNamed("Togli la camera"))
        assertThat(plain.locator("main")).containsText("Camera tolta.")
        assertEquals(0, plain.locator("#camera-$one").count())
        assertEquals(null, e2e.roomOf(c.anna))
        touch(plain.locator("#camera-$empty").buttonNamed("Togli Camera vuota"))
        assertThat(plain.locator("main")).containsText("Camera tolta.")
        assertEquals(0, plain.locator("#camera-$empty").count())
        assertEquals(1, e2e.dsl().fetchCount(ROOM, ROOM.GATHERING_ID.eq(w.id)))
    }

    @E2eTest
    fun `t11 c06 c23 after a choice the way to a form and back with the button of the browser shows the rooms as they are now`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        val one = e2e.room(w.id, "Camera 1", 4)
        e2e.open("/convivenza/${w.id}/camere")
        e2e.metti("b${c.davide}", "Davide Costa", one)
        e2e.press(page.locator("#camera-$one").linkNamed("Modifica Camera 1"))
        assertThat(page.locator("h1")).hasText("Modifica la camera")
        page.goBack()
        assertThat(page.locator("h1")).hasText("Camere")
        assertThat(page.locator("#camera-$one")).containsText("1 persona · 4 posti")
        assertThat(page.locator("#camera-$one #persona-b${c.davide}")).isVisible()
        assertThat(page.locator("#riepilogo")).containsText("Persone in camera 1")
        // The page that comes back works: a second choice is made on it.
        e2e.metti("b${c.sara}", "Sara Neri", one)
        assertThat(page.locator("#libero-b${c.sara}")).containsText("Sara Neri è in Camera 1.")
        assertEquals(one, e2e.roomOf(c.sara))
        assertEquals(one, e2e.roomOf(c.davide))
    }

    // ---- Nothing is kept, nothing is written without the code of the form (criteria 17, 21) ----------------------------------------------------

    @E2eTest
    fun `t11 c17 c21 the pages and the answers of the rooms are never kept by the browser, and a write without the code of the form is refused and changes nothing`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        val one = e2e.room(w.id, "Camera 1", 4)
        e2e.put(c.luca, w.id, one)
        e2e.open("/convivenza/${w.id}/camere")
        val token = page.csrf()
        val base = "/convivenza/${w.id}/camere"

        // The pages that say who sleeps where have no cache: the shared phone of the teachers keeps nothing.
        for (path in listOf(base, "$base/stampa", "$base/nuova", "$base/$one/modifica", "$base/$one/togli", "/arrivi/${c.luca}")) {
            val cache = page.evaluate("path => fetch(path).then(r => r.headers.get('cache-control'))", path) as String?
            assertTrue(cache != null && cache.contains("no-store"), "$path is kept: cache-control «$cache»")
        }
        val answer = page.evaluate(
            "([path, token]) => fetch(path, { method: 'POST', headers: { 'HX-Request': 'true' }, body: new URLSearchParams({ _csrf: token, persona: 'b${c.davide}', camera: '$one' }) }).then(r => r.status + ' ' + r.headers.get('cache-control'))",
            listOf("$base/scegli", token),
        ) as String
        assertTrue(answer.startsWith("200") && answer.contains("no-store"), "the answer of a choice is kept: $answer")
        assertEquals(one, e2e.roomOf(c.davide))

        // A write without the code of the form (or with a wrong one): the person is sent to the home as the access says (a page left open for too long), and nothing changes.
        val before = e2e.dsl().fetchCount(ROOM_CHILD)
        for (body in listOf("persona=b${c.sara}&camera=$one", "_csrf=sbagliato&persona=b${c.sara}&camera=$one")) {
            val where = page.evaluate(
                "([path, body]) => fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body }).then(r => r.redirected ? new URL(r.url).pathname : 'no redirect ' + r.status)",
                listOf("$base/scegli", body),
            ) as String
            assertEquals("/", where, body)
            val refresh = page.evaluate(
                "([path, body]) => fetch(path, { method: 'POST', headers: { 'HX-Request': 'true', 'Content-Type': 'application/x-www-form-urlencoded' }, body }).then(r => r.headers.get('HX-Refresh') + ' ' + r.status)",
                listOf("$base/scegli", body),
            ) as String
            assertEquals("true 200", refresh, "htmx: the page is loaded again, and nothing is replaced")
        }
        assertEquals(before, e2e.dsl().fetchCount(ROOM_CHILD))
        assertEquals(null, e2e.roomOf(c.sara))
    }

    // ---- Keyboard only (criteria 6, 7, 23) --------------------------------------------------------------------------------------------------

    /** What has the focus draws something around it (an outline or a shadow): a person who uses the keyboard can see where they are. */
    private fun Page.focusIsDrawn(): Boolean = evaluate(
        "() => { const s = getComputedStyle(document.activeElement); return (s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0) || (s.boxShadow !== 'none' && s.boxShadow !== ''); }",
    ) as Boolean

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t11 c06 c07 c23 a person who uses only the keyboard opens Cambia with Enter, chooses the room with the arrows, sees where the focus is and moves the child with Enter on Sposta`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        val one = e2e.room(w.id, "Camera 1", 4)
        val two = e2e.room(w.id, "Stanza azzurra", 2)
        e2e.put(c.luca, w.id, one)
        e2e.open("/convivenza/${w.id}/camere")
        val summary = page.locator("#persona-b${c.luca} summary")

        // «Cambia» is reached with Tab, has a visible focus, and Enter opens the form.
        summary.focus()
        page.keyboard().press("Shift+Tab")
        page.keyboard().press("Tab")
        assertTrue(page.evaluate("document.activeElement.tagName") == "SUMMARY", "the focus is on «Cambia»")
        assertTrue(page.focusIsDrawn(), "«Cambia» has no visible focus")
        page.keyboard().press("Enter")
        assertThat(page.getByLabel("Camera di Luca Rossi")).isVisible()
        // Tab goes to the select (its name says the child), the arrows choose the room, and nothing is sent by the arrows.
        page.keyboard().press("Tab")
        assertEquals("sposta-b${c.luca}", page.evaluate("document.activeElement.id"))
        assertTrue(page.focusIsDrawn(), "the select has no visible focus")
        page.keyboard().press("ArrowDown")
        page.keyboard().press("ArrowDown")
        page.waitForTimeout(400.0)
        assertEquals(one, e2e.roomOf(c.luca), "the arrows send nothing")
        assertEquals(two.toString(), page.evaluate("document.activeElement.value"), "the arrows chose the room")
        // Tab goes to «Sposta», its focus is drawn, Enter sends.
        page.keyboard().press("Tab")
        assertEquals("sposta", page.evaluate("document.activeElement.value"), "the focus is on the button «Sposta»")
        assertTrue(page.focusIsDrawn(), "«Sposta» has no visible focus")
        page.keyboard().press("Enter")
        assertThat(page.locator("#camera-$two #persona-b${c.luca}")).isVisible()
        assertEquals(two, e2e.roomOf(c.luca))
        assertThat(page.locator("#camera-$one")).containsText("Luca Rossi è in Stanza azzurra.")
        // After the answer the focus is on the message (a person with a screen reader hears it), and Tab goes on from there.
        page.focusIs("document.activeElement.hasAttribute('data-fuoco')")
    }
}
