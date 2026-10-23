package talita.e2e

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import talita.Addresses
import talita.account.TestAccounts
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.HEALTH_NOTE
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.TEACHER
import talita.registration.Notes
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The registrations as the teachers see them, in a real browser, on a phone and on a desktop, with axe (criteria 20-26, 30, 31, 38): the list, the detail
 * with the notes that come only on request (and what happens without a network), the paper form, the correction, the withdrawal, the notes of a paper
 * registration, and the forms of the gathering with the notices and the errors of the rules about fees and days. Names and numbers are synthetic.
 */
@E2eApplication
class TeacherRegistrationsE2eTest {

    /** The person of the test stays a teacher («Elisa Conti»): the gathering is made with Marta Bianchi and Paolo Verdi as custodians. */
    private fun E2e.gathering(): OpenGathering {
        val gathering = openGathering()
        TestAccounts.makeTeacher(dsl(), checkNotNull(signedInAs), "Elisa", "Conti")
        return gathering
    }

    private fun E2e.parent(): Long {
        val email = talita.Addresses.unique("e2e-genitore")
        dsl().insertInto(talita.jooq.tables.references.ACCOUNT).set(talita.jooq.tables.references.ACCOUNT.EMAIL, email).execute()
        return accountId(email)
    }

    // ---- The list (criteria 20, 24) ---------------------------------------------------------------------------------------------------------

    @E2eTest
    fun `c20 c24 the list shows the counts and a row for each child with the labels, with nothing about health, and a tap leads to the detail`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.gathering()
        val owner = e2e.parent()
        e2e.rows().register(gathering.id, owner, "Luca", "Rossi")
        e2e.rows().register(gathering.id, owner, "Anna", "Rossi", LocalDate.of(2019, 1, 9), status = RegistrationStatus.maybe, days = mapOf(gathering.saturday to "full", gathering.sunday to "lunch"), notes = Notes("Arachidi", null, null))
        e2e.rows().register(gathering.id, null, "Marco", "Bianchi", LocalDate.of(2015, 6, 1), enteredBy = gathering.martaId, paperReceived = true)
        e2e.rows().register(gathering.id, e2e.parent(), "luca", "ROSSI", LocalDate.of(2016, 4, 4))
        e2e.rows().register(gathering.id, owner, "Zoe", "Zani", LocalDate.of(2014, 9, 9), status = RegistrationStatus.withdrawn)

        e2e.open("/iscrizioni")

        assertThat(page).hasTitle("Iscrizioni · Talità")
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        assertThat(page.locator("main")).containsText("Convivenza di settembre 2027 · ")
        assertThat(page.locator("main").linkNamed("Inserisci un’iscrizione")).isVisible()
        assertThat(page.locator("section[aria-labelledby=in-breve]")).hasText(Pattern.compile("In breve\\s*Confermate\\s*3\\s*In forse\\s*1\\s*Ritirate\\s*1\\s*Quota delle confermate\\s*180\\s*€\\s*Quota di chi è in forse\\s*35\\s*€"))
        assertThat(page.locator("main h2:visible")).hasText(arrayOf("In breve", "I bambini"))
        assertThat(page.locator("main li a")).hasCount(5)
        assertThat(page.locator("main li a").first()).containsText("Marco Bianchi")
        assertThat(page.locator("main li a").first()).containsText("Cartacea ricevuta")
        assertThat(page.locator("main li a").nth(1)).containsText("Anna Rossi")
        assertThat(page.locator("main li a").nth(1)).containsText("Accettata online")
        assertThat(page.locator("main")).containsText("Possibile doppione")
        assertThat(page.locator("main")).not().containsText("Arachidi")
        assertThat(page.locator("main li a").last()).containsText("Non dovuta")
        assertThat(page.locator("main li a").first()).hasAttribute("href", Pattern.compile("/iscrizioni/\\d+"))
        assertTrue(page.locator("main li a").first().box().height >= 63.9, "a row is at least 64 px high")
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px")
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "list of the teachers (${e2e.profile.label})")

        e2e.touch(page.locator("main li a").filter(com.microsoft.playwright.Locator.FilterOptions().setHasText("Anna Rossi")))
        assertThat(page.locator("h1")).hasText("Anna Rossi")
    }

    // ---- The detail and the notes (criteria 21, 22) ---------------------------------------------------------------------------------------

    @E2eTest
    fun `c21 c22 the detail shows the notes only after the button, which writes the reading, and a lost network shows nothing and writes nothing`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.gathering()
        val id = e2e.rows().register(gathering.id, e2e.parent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", null, "Antistaminico in caso di reazione"), phone = "+39 333 123 4567")

        e2e.open("/iscrizioni/$id")

        assertThat(page).hasTitle("Anna Rossi · Talità")
        assertThat(page.locator("h1")).hasText("Anna Rossi")
        assertThat(page.locator("main h2:visible")).hasText(arrayOf("Bambino", "Iscrizione", "Segnalazioni", "Manleva", "Letture delle segnalazioni"))
        assertThat(page.locator("main").linkNamed("+39 333 123 4567")).hasAttribute("href", "tel:+393331234567")
        assertTrue(page.locator("main").linkNamed("+39 333 123 4567").box().height >= 43.9, "the phone is a target of 44 px")
        assertThat(page.locator("#segnalazioni")).containsText("Sono dati sulla salute. Ogni volta che le mostri, resta scritto chi le ha lette e quando.")
        assertThat(page.locator("main")).not().containsText("Arachidi")
        assertThat(page.locator("#letture")).containsText("Nessuno le ha ancora lette.")
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "detail of a registration (${e2e.profile.label})")

        // Without a network: the card is as it was, a message says so with no «Riprova», and nothing was written.
        e2e.browserContext.setOffline(true)
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.messages()).containsText("Nessuna connessione")
        assertThat(page.messages().locator("button:has-text('Riprova')")).hasCount(0)
        assertThat(page.locator("main")).not().containsText("Arachidi")
        assertThat(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni")).isEnabled()
        e2e.browserContext.setOffline(false)
        assertEquals(0, e2e.dsl().fetchCount(HEALTH_NOTE_ACCESS))

        // With it: the reading is written, the notes appear in the same card, and the readings have the new row.
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Allergie")
        assertThat(page.locator("#segnalazioni")).containsText("Arachidi")
        assertThat(page.locator("#segnalazioni")).containsText("Antistaminico in caso di reazione")
        assertThat(page.locator("#segnalazioni")).containsText("Lettura registrata a tuo nome")
        assertThat(page.locator("#segnalazioni")).containsText("Consenso dato dal genitore il")
        assertThat(page.locator("#letture")).containsText("Elisa Conti")
        assertThat(page.locator("#letture")).containsText("Sei tu")
        assertThat(page.locator("#segnalazioni").linkNamed("Correggi le segnalazioni")).hasCount(0)
        assertEquals(1, e2e.dsl().fetchCount(HEALTH_NOTE_ACCESS))
        assertAccessible(page, "detail with the notes shown (${e2e.profile.label})")

        // «Nascondi» is a page again: the notes are not in it, and the reading stays in the register.
        e2e.press(page.locator("#segnalazioni").linkNamed("Nascondi le segnalazioni"))
        assertThat(page.locator("main")).not().containsText("Arachidi")
        assertThat(page.locator("#letture")).containsText("Sei tu")
    }

    @E2eTest
    fun `c22 when the reading cannot be written the card does not change and a message says that something went wrong`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.gathering()
        val id = e2e.rows().register(gathering.id, e2e.parent(), "Anna", "Rossi", notes = Notes("Arachidi", null, null))
        e2e.open("/iscrizioni/$id")
        e2e.dsl().execute("alter table health_note_access add constraint e2e_cannot_write check (false) not valid")
        try {
            e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
            assertThat(page.messages()).containsText("Qualcosa non ha funzionato")
            assertThat(page.locator("main")).not().containsText("Arachidi")
            assertThat(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni")).isEnabled()
        } finally {
            e2e.dsl().execute("alter table health_note_access drop constraint e2e_cannot_write")
        }
        assertEquals(0, e2e.dsl().fetchCount(HEALTH_NOTE_ACCESS))
    }

    // ---- The paper form, the correction, the withdrawal (criteria 25, 26) -----------------------------------------------------------------

    @E2eTest
    fun `c25 c26 a teacher types in a paper form, marks the manleva and corrects it, the notes of a paper form need a page that shows them, and the withdrawal has its dialog`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.gathering()

        e2e.open("/iscrizioni")
        assertThat(page.locator("main h2")).hasText("Nessuna iscrizione")
        e2e.touch(page.locator("main").linkNamed("Inserisci un’iscrizione"))
        assertThat(page).hasURL(e2e.url("/iscrizioni/nuova?convivenza=${gathering.id}"))
        assertThat(page.locator("h1")).hasText("Inserisci un’iscrizione")
        assertThat(page.locator("main")).containsText("Per chi ha compilato il modulo di carta.")
        assertThat(page.locator("main")).containsText("Manleva cartacea ricevuta")
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "paper form (${e2e.profile.label})")

        // Notes with no consent on the paper form: the error is on the box, with the words for the teachers.
        page.locator("#telefono").fill("333 987 6543")
        page.locator("#b0-nome").fill("Elena")
        page.locator("#b0-cognome").fill("Marino")
        page.locator("#b0-giorno").fill("1")
        page.locator("#b0-mese").fill("10")
        page.locator("#b0-anno").fill("2016")
        page.locator("input[name='children[0].presence'][value='all_days']").check()
        assertThat(page.locator("#quota-0")).hasText(Pattern.compile("Quota\\s*60\\s*€"))
        e2e.press(page.locator("summary:has-text('Allergie, intolleranze, farmaci')"))
        page.locator("#b0-allergie").fill("Arachidi")
        e2e.press(page.locator("main").buttonNamed("Inserisci l’iscrizione"))
        assertThat(page.locator("#b0-consenso-errore")).hasText("Per salvare le segnalazioni serve il consenso: spunta la casella. Se il genitore non l’ha dato sul modulo, svuota i campi.")
        page.focusIs("document.activeElement.id === 'b0-consenso'")
        assertAccessible(page, "paper form with an error (${e2e.profile.label})")
        page.locator("#b0-consenso").check()
        e2e.press(page.locator("main").buttonNamed("Inserisci l’iscrizione"))

        assertThat(page.locator("[role=status]")).containsText("Iscrizione inserita.")
        val id = e2e.dsl().select(REGISTRATION.ID).from(REGISTRATION).fetchSingle(REGISTRATION.ID)!!
        assertThat(page).hasURL(e2e.url("/iscrizioni/$id"))
        assertThat(page.locator("main")).containsText("Da ricevere")
        assertThat(page.locator("main")).containsText(Pattern.compile("Inserita da\\s*Elisa Conti"))
        assertEquals(0, e2e.dsl().fetchCount(HEALTH_NOTE_ACCESS), "typing in the notes reads nothing")
        assertAccessible(page, "detail of a paper registration (${e2e.profile.label})")

        // The signed form arrives.
        e2e.press(page.locator("main").buttonNamed("Segna come ricevuta"))
        assertThat(page.locator("[role=status]")).containsText("Manleva segnata come ricevuta.")
        assertThat(page.locator("main")).containsText("Ricevuto")
        assertThat(page.locator("main").buttonNamed("Segna come da ricevere")).isVisible()

        // The correction of the data: the box of the manleva is there; the notes have a page of their own that shows them.
        e2e.press(page.locator("main").linkNamed("Correggi l’iscrizione"))
        assertThat(page.locator("h1")).hasText("Correggi l’iscrizione")
        assertThat(page.locator("input[name=paperReceived]")).isChecked()
        assertThat(page.locator("main")).containsText("Ci sono segnalazioni. Per cambiarle mostrale prima dal dettaglio: la lettura resta registrata.")
        assertAccessible(page, "correction of a paper registration (${e2e.profile.label})")
        page.locator("#telefono").fill("333 000 1111")
        e2e.press(page.locator("main").buttonNamed("Salva le correzioni"))
        assertThat(page.locator("[role=status]")).containsText("Iscrizione salvata.")
        assertThat(page.locator("main")).containsText("333 000 1111")

        e2e.press(page.locator("main").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Il genitore ha dato il consenso sul modulo cartaceo.")
        e2e.press(page.locator("#segnalazioni").linkNamed("Correggi le segnalazioni"))
        assertThat(page.locator("h1")).hasText("Correggi le segnalazioni")
        assertThat(page.locator("main")).containsText("Questa pagina mostra le segnalazioni: la lettura è stata registrata a tuo nome.")
        assertThat(page.locator("#b0-allergie")).hasValue("Arachidi")
        assertAccessible(page, "notes of a paper registration (${e2e.profile.label})")
        page.locator("#b0-allergie").fill("Polline")
        e2e.press(page.locator("main").buttonNamed("Salva le segnalazioni"))
        assertThat(page.locator("[role=status]")).containsText("Segnalazioni salvate.")
        assertEquals("Polline", e2e.dsl().select(HEALTH_NOTE.ALLERGIES).from(HEALTH_NOTE).fetchSingle(HEALTH_NOTE.ALLERGIES))
        assertEquals(3, e2e.dsl().fetchCount(HEALTH_NOTE_ACCESS), "the button, the page and the saving")

        // The withdrawal: the dialog with the words of the teachers; the notes go, the card stays as «Ritirata», and it can be put back.
        e2e.press(page.locator("main").linkNamed("Ritira l’iscrizione"))
        page.waitForSettledDialog()
        assertThat(page.openDialog()).containsText("L’iscrizione di Elena Marino risulterà ritirata: la quota non è più dovuta e i giorni non contano più.")
        assertAccessible(page, "dialog of the withdrawal of the teachers (${e2e.profile.label})")
        e2e.touch(page.openDialog().buttonNamed("Ritira l’iscrizione"))
        assertThat(page.locator("[role=status]")).containsText("Iscrizione ritirata.")
        assertThat(page.locator("main")).containsText("Rimetti l’iscrizione")
        assertEquals(0, e2e.dsl().fetchCount(HEALTH_NOTE))
        e2e.press(page.locator("main").linkNamed("Rimetti l’iscrizione"))
        assertThat(page.locator("h1")).hasText("Rimetti l’iscrizione")
        e2e.press(page.locator("main").buttonNamed("Rimetti l’iscrizione"))
        assertThat(page.locator("[role=status]")).containsText("Iscrizione rimessa.")
    }

    // ---- The forms of the gathering with the registrations (criteria 30, 31, 32) -------------------------------------------------------------

    @E2eTest
    fun `c30 c31 c32 the forms of the fees, of the dates and of the custodians say what registrations change and refuse what they cannot`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.gathering()
        val owner = e2e.parent()
        e2e.rows().register(gathering.id, owner, "Luca", "Rossi", days = mapOf(gathering.friday to "full"))
        e2e.rows().register(gathering.id, owner, "Anna", "Rossi", LocalDate.of(2019, 1, 9))

        // The detail of the gathering has the card of the registrations, with the counts.
        e2e.open("/convivenza/${gathering.id}")
        assertThat(page.locator("section[aria-labelledby=iscrizioni]")).containsText("Vedi le iscrizioni")
        assertThat(page.locator("section[aria-labelledby=iscrizioni]")).containsText("2 confermate")

        // Fees: the notice, and a fee in use that is emptied.
        e2e.open("/convivenza/${gathering.id}/quote")
        assertThat(page.locator("main")).containsText("Hai già 2 iscrizioni: se cambi le quote, la quota dovuta di ognuna si ricalcola. Quello che è già stato pagato non cambia.")
        assertAccessible(page, "fees with registrations (${e2e.profile.label})")
        page.locator("#quota-giornaliera").fill("")
        e2e.press(page.locator("main").buttonNamed("Salva le modifiche"))
        assertThat(page.locator("#quota-giornaliera-errore")).hasText("La usa 1 iscrizione: non puoi lasciarla vuota. Se diventa gratuita, scrivi 0.")
        page.focusIs("document.activeElement.id === 'quota-giornaliera'")
        assertAccessible(page, "fees with the fee in use emptied (${e2e.profile.label})")

        // Dates: a day that a registration has cannot be taken away.
        e2e.open("/convivenza/${gathering.id}/date")
        assertThat(page.locator("main")).containsText("Un giorno scelto da un’iscrizione non si può togliere. Se aggiungi un giorno, chi viene «tutti i giorni» viene anche quel giorno.")
        page.locator("#primo-giorno").fill(gathering.saturday.toString())
        e2e.press(page.locator("main").buttonNamed("Salva le modifiche"))
        assertThat(page.locator("#primo-giorno-errore")).containsText("Non puoi togliere venerdì")
        assertThat(page.locator("#primo-giorno-errore")).containsText("l’hanno scelto 2 iscrizioni.")
        page.focusIs("document.activeElement.id === 'primo-giorno'")
        assertAccessible(page, "dates with a day that cannot go (${e2e.profile.label})")

        // Custodians: the help says that what was accepted stays.
        e2e.open("/convivenza/${gathering.id}/affidatari")
        assertThat(page.locator("main")).containsText("Le iscrizioni già fatte conservano i nomi che hanno accettato.")
        assertAccessible(page, "custodians (${e2e.profile.label})")
    }

    // ---- What the QA of TALITA-5 added (criteria 20-26, 29, 30, 38): the notes behind the button, the register, the states, 320 px ----------

    @E2eTest
    fun `c21 c22 the pages have no trace of the notes until the button is touched, with or without notes they say the same, and the answer that carries them must not be stored`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.gathering()
        val id = e2e.rows().register(gathering.id, e2e.parent(), "Anna", "Rossi", notes = Notes("Arachidi-QA", "Lattosio-QA", "Antistaminico-QA"))
        val paper = e2e.rows().register(gathering.id, null, "Elena", "Marino", enteredBy = gathering.martaId, notes = Notes("Polline-QA", null, null), notesOnPaper = true)
        val without = e2e.rows().register(gathering.id, e2e.parent(), "Zoe", "Zani")
        val secrets = listOf("Arachidi-QA", "Lattosio-QA", "Antistaminico-QA", "Polline-QA")

        // The HTML, not only what is seen: a hidden element would not show in the text of the page.
        for (path in listOf("/iscrizioni", "/iscrizioni/$id", "/iscrizioni/$paper", "/iscrizioni/$id/correggi", "/iscrizioni/$paper/correggi", "/iscrizioni/$id/ritira", "/convivenza/${gathering.id}")) {
            e2e.open(path)
            page.settled()
            val html = page.content()
            for (secret in secrets) assertFalse(html.contains(secret), "$path has $secret before the button")
        }
        // A registration with notes and one without are the same card, before the button.
        e2e.open("/iscrizioni/$id")
        val withNotes = page.locator("#segnalazioni").innerText()
        e2e.open("/iscrizioni/$without")
        assertEquals(withNotes, page.locator("#segnalazioni").innerText())

        e2e.open("/iscrizioni/$id")
        page.settled()
        val answer = page.waitForResponse({ it.url().endsWith("/iscrizioni/$id/mostra-segnalazioni") }) {
            e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        }
        assertEquals(200, answer.status())
        assertTrue(answer.headers()["cache-control"].orEmpty().contains("no-store"), "the answer with the notes is not stored: ${answer.headers()["cache-control"]}")
        assertThat(page.locator("#segnalazioni")).containsText("Arachidi-QA")
        assertThat(page.locator("#segnalazioni")).containsText("Lattosio-QA")
        assertThat(page.locator("#segnalazioni")).containsText("Antistaminico-QA")
        assertEquals(1, e2e.dsl().fetchCount(HEALTH_NOTE_ACCESS))
        e2e.press(page.locator("#segnalazioni").linkNamed("Nascondi le segnalazioni"))
        page.settled()
        for (secret in secrets) assertFalse(page.content().contains(secret), "the page of «Nascondi» has $secret")

        // The notes shown, then another page, then back: the page is asked again (a second reading, if the button is touched), and the browser kept no copy.
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Arachidi-QA")
        e2e.touch(page.navLink("Arrivi"))
        assertThat(page.locator("h1")).hasText("Arrivi")
        page.goBack()
        assertThat(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni")).isVisible()
        page.settled()
        for (secret in secrets) assertFalse(page.content().contains(secret), "the page after the back has $secret")
        val stored = page.evaluate("JSON.stringify(localStorage) + JSON.stringify(sessionStorage)") as String
        for (secret in secrets) assertFalse(stored.contains(secret), "the browser keeps $secret")
        assertEquals(2, e2e.dsl().fetchCount(HEALTH_NOTE_ACCESS))
    }

    @E2eTest
    fun `c28 a teacher who is also a parent registers from the form of the parents, finds the child in the list of the teachers, and only the pages of the teachers write a reading`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.gathering()
        e2e.open("/info/${gathering.code}/iscrizione")
        page.settled()
        page.locator("#telefono").fill("333 123 4567")
        page.fillBlock(0, "Luca", "Rossi", "4", "4", "2016")
        page.locator("input[name='children[0].presence'][value='all_days']").check()
        e2e.press(page.locator("summary:has-text('Allergie, intolleranze, farmaci')"))
        page.locator("#b0-allergie").fill("Arachidi-QA")
        page.locator("#b0-consenso").check()
        e2e.press(page.locator("main").buttonNamed("Controlla e accetta"))
        page.locator("#accetto").check()
        page.locator("#altro-genitore").check()
        page.locator("#convinzioni").check()
        e2e.press(page.locator("main").buttonNamed("Accetta e iscrivi"))

        // The answer is the list of the teachers, with the notice on top, and the child is there like the others.
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        assertThat(page.locator("main [role=status]")).containsText("Iscrizione fatta.")
        assertThat(page.locator("section[aria-labelledby=in-breve]")).containsText(Pattern.compile("Confermate\\s*1"))
        assertThat(page.locator("main li a")).hasCount(1)
        assertThat(page.locator("main li a").first()).containsText("Luca Rossi")
        assertThat(page.locator("main li a").first()).containsText("Accettata online")
        assertAccessible(page, "list of the teachers after their own registration (${e2e.profile.label})")
        val id = e2e.dsl().select(REGISTRATION.ID).from(REGISTRATION).fetchSingle(REGISTRATION.ID)!!

        // Their own page of the notes, as a parent: the notes are in the fields and no reading is written.
        e2e.open("/iscrizioni/$id/segnalazioni")
        assertThat(page.locator("#b0-allergie")).hasValue("Arachidi-QA")
        assertEquals(0, e2e.dsl().fetchCount(HEALTH_NOTE_ACCESS))
        // The page of the teachers: the button writes the reading.
        e2e.open("/iscrizioni/$id")
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Arachidi-QA")
        assertEquals(1, e2e.dsl().fetchCount(HEALTH_NOTE_ACCESS))
        assertThat(page.locator("#letture")).containsText("Sei tu")
    }

    @E2eTest
    fun `c22 the register of the readings lists every reader, the latest first, with Sei tu only on the own ones, and says when a teacher is not one any more`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.gathering()
        val id = e2e.rows().register(gathering.id, e2e.parent(), "Anna", "Rossi", notes = Notes("Arachidi", null, null))
        val now = OffsetDateTime.now()
        fun reading(teacher: Long, daysAgo: Long) {
            e2e.dsl().insertInto(HEALTH_NOTE_ACCESS).set(HEALTH_NOTE_ACCESS.REGISTRATION_ID, id).set(HEALTH_NOTE_ACCESS.TEACHER_ACCOUNT_ID, teacher)
                .set(HEALTH_NOTE_ACCESS.ACCESSED_AT, now.minusDays(daysAgo)).execute()
        }
        reading(gathering.martaId, 3)
        reading(gathering.paoloId, 1)

        e2e.open("/iscrizioni/$id")
        page.settled()
        assertThat(page.locator("#letture li")).hasCount(2)
        assertThat(page.locator("#letture li").first()).containsText("Paolo Verdi")
        assertThat(page.locator("#letture li").last()).containsText("Marta Bianchi")
        assertThat(page.locator("#letture")).not().containsText("Sei tu")
        assertThat(page.locator("#letture")).not().containsText("Arachidi")

        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#letture li")).hasCount(3)
        assertThat(page.locator("#letture li").first()).containsText("Elisa Conti")
        assertThat(page.locator("#letture li").first()).containsText("Sei tu")
        assertThat(page.locator("#letture li").nth(1)).containsText("Paolo Verdi")
        assertThat(page.locator("#letture li").nth(1)).not().containsText("Sei tu")
        assertThat(page.locator("#letture li").last()).containsText("Marta Bianchi")
        assertAccessible(page, "detail with three readings (${e2e.profile.label})")

        // Marta is no longer among the teachers: her readings stay, with the words that say so.
        e2e.dsl().deleteFrom(TEACHER).where(TEACHER.ACCOUNT_ID.eq(gathering.martaId)).execute()
        e2e.open("/iscrizioni/$id")
        page.settled()
        assertThat(page.locator("#letture li")).hasCount(3)
        assertThat(page.locator("#letture li").last()).containsText("Un maestro che non è più tra i maestri")
        assertThat(page.locator("#letture")).not().containsText("Marta Bianchi")
        assertAccessible(page, "detail with a reader who left (${e2e.profile.label})")
    }

    @E2eTest
    fun `c25 c29 a teacher types in a paper form with the registrations closed and when the gathering is over, not in a draft, and marks the manleva received and not received`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.gathering()
        e2e.dsl().update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).execute()

        e2e.open("/iscrizioni/nuova?convivenza=${gathering.id}")
        page.settled()
        assertThat(page.locator("main")).containsText("Le iscrizioni sono chiuse: i genitori non possono più iscriversi, ma tu puoi inserire un modulo di carta.")
        page.locator("#telefono").fill("333 987 6543")
        page.fillBlock(0, "Elena", "Marino", "1", "10", "2016")
        page.locator("input[name='children[0].presence'][value='all_days']").check()
        page.locator("input[name=paperReceived]").check()
        e2e.press(page.locator("main").buttonNamed("Inserisci l’iscrizione"))
        assertThat(page.locator("[role=status]")).containsText("Iscrizione inserita.")
        assertThat(page.locator("main")).containsText("Ricevuto")
        assertThat(page.locator("main")).not().containsText("Da ricevere")

        e2e.press(page.locator("main").buttonNamed("Segna come da ricevere"))
        assertThat(page.locator("[role=status]")).containsText("Manleva segnata come da ricevere.")
        assertThat(page.locator("main")).containsText("Da ricevere")
        e2e.press(page.locator("main").buttonNamed("Segna come ricevuta"))
        assertThat(page.locator("[role=status]")).containsText("Manleva segnata come ricevuta.")
        assertThat(page.locator("main").buttonNamed("Segna come da ricevere")).isVisible()

        // The gathering is over: the form is there with the same words, and a form can still be typed in.
        e2e.dsl().update(GATHERING).set(GATHERING.START_DATE, e2e.today().minusDays(9)).set(GATHERING.END_DATE, e2e.today().minusDays(7)).execute()
        e2e.open("/iscrizioni/nuova?convivenza=${gathering.id}")
        page.settled()
        assertThat(page.locator("main")).containsText("Le iscrizioni sono chiuse: i genitori non possono più iscriversi, ma tu puoi inserire un modulo di carta.")
        page.locator("#telefono").fill("333 000 1234")
        page.fillBlock(0, "Marco", "Bianchi", "5", "5", "2015")
        page.locator("input[name='children[0].presence'][value='all_days']").check()
        e2e.press(page.locator("main").buttonNamed("Inserisci l’iscrizione"))
        assertThat(page.locator("[role=status]")).containsText("Iscrizione inserita.")
        assertEquals(2, e2e.dsl().fetchCount(REGISTRATION))

        // In a draft there is nothing to type in, and nothing to correct: the page that does not exist.
        val first = e2e.dsl().select(REGISTRATION.ID).from(REGISTRATION).orderBy(REGISTRATION.ID).limit(1).fetchSingle(REGISTRATION.ID)!!
        e2e.dsl().update(GATHERING).set(GATHERING.STATUS, GatheringStatus.draft).execute()
        for (path in listOf("/iscrizioni/nuova?convivenza=${gathering.id}", "/iscrizioni/$first/correggi")) {
            assertEquals(404, e2e.open(path).status(), path)
            assertThat(page.locator("h1")).hasText("Pagina non trovata")
        }
    }

    @E2eTest
    fun `c20 the list says what to do with no gathering, with only a draft and with no registration, the card of the gathering says the same, and none has a violation of axe`(e2e: E2e) {
        val page = e2e.page
        TestAccounts.makeTeacher(e2e.dsl(), checkNotNull(e2e.signedInAs), "Elisa", "Conti")
        val support = GatheringSupport(e2e.dsl(), e2e.application.getBean(Clock::class.java))
        support.reset()

        e2e.open("/iscrizioni")
        page.settled()
        assertThat(page.locator("main h2")).hasText("Nessuna convivenza in programma")
        assertThat(page.locator("main")).containsText("Le iscrizioni arrivano dopo che hai creato la convivenza e aperto le iscrizioni.")
        assertThat(page.locator("main").linkNamed("Crea la convivenza")).isVisible()
        assertAccessible(page, "list with no gathering (${e2e.profile.label})")

        val id = support.make(status = GatheringStatus.draft)
        e2e.open("/iscrizioni")
        page.settled()
        assertThat(page.locator("main")).containsText("La convivenza «Convivenza di settembre 2027» è ancora in bozza: le iscrizioni si fanno dopo l’apertura, anche quelle di carta. Apri le iscrizioni dalla pagina della convivenza.")
        assertThat(page.locator("main").linkNamed("Vai alla convivenza")).isVisible()
        assertThat(page.locator("main").linkNamed("Inserisci un’iscrizione")).hasCount(0)
        assertAccessible(page, "list with only a draft (${e2e.profile.label})")
        e2e.open("/convivenza/$id")
        page.settled()
        assertThat(page.locator("section[aria-labelledby=iscrizioni]")).containsText("Le iscrizioni arrivano dopo l’apertura: finché la convivenza è in bozza non ce ne sono, nemmeno da carta.")
        assertThat(page.locator("section[aria-labelledby=iscrizioni] a")).hasCount(0)
        assertAccessible(page, "detail of a draft with the card of the registrations (${e2e.profile.label})")

        e2e.dsl().update(GATHERING).set(GATHERING.STATUS, GatheringStatus.`open`).execute()
        e2e.open("/iscrizioni")
        page.settled()
        assertThat(page.locator("main h2")).hasText("Nessuna iscrizione")
        assertThat(page.locator("main")).containsText("Quando i genitori iscrivono i bambini, li trovi qui. Se un genitore ha compilato il modulo di carta, puoi inserirlo tu.")
        assertThat(page.locator("main").linkNamed("Inserisci un’iscrizione")).isVisible()
        assertAccessible(page, "list with no registration (${e2e.profile.label})")
    }

    @E2eTest
    fun `c30 when the teachers change the fee the parent and the list see the new amounts`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.gathering()
        val address = Addresses.unique("e2e-genitore-quota")
        val parentPage = e2e.newPage(signedIn = false, teacher = false)
        e2e.signIn(parentPage, address, teacher = false)
        e2e.rows().register(gathering.id, e2e.accountId(address), "Luca", "Rossi")
        parentPage.navigate(e2e.url("/iscrizioni"))
        parentPage.settled()
        assertThat(parentPage.locator("article")).hasText(Pattern.compile(".*Quota\\s*60\\s*€.*", Pattern.DOTALL))

        e2e.open("/convivenza/${gathering.id}/quote")
        page.settled()
        page.locator("#quota-intera").fill("70")
        e2e.press(page.locator("main").buttonNamed("Salva le modifiche"))
        assertThat(page.locator("main [role=status][data-fuoco]")).containsText("Quote salvate.")

        parentPage.navigate(e2e.url("/iscrizioni"))
        parentPage.settled()
        assertThat(parentPage.locator("article")).hasText(Pattern.compile(".*Quota\\s*70\\s*€.*", Pattern.DOTALL))
        assertThat(parentPage.locator("main")).containsText(Pattern.compile("Totale\\s*70\\s*€"))
        e2e.open("/iscrizioni")
        page.settled()
        assertThat(page.locator("section[aria-labelledby=in-breve]")).containsText(Pattern.compile("Quota delle confermate\\s*70\\s*€"))
        assertThat(page.locator("main li a").first()).containsText("70")
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `c38 the pages of the teachers with the longest names and words do not scroll sideways at 320 and have no violation of axe`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.gathering()
        val long = "Lunghissimo".repeat(9)
        val longNotes = Notes("Allergia".repeat(100), "Intolleranza".repeat(80), "Farmaco".repeat(100))
        val online = e2e.rows().register(gathering.id, e2e.parent(), long, long, phone = "+39 333 123 4567 890", notes = longNotes, days = mapOf(gathering.friday to "full", gathering.sunday to "lunch"))
        e2e.rows().register(gathering.id, e2e.parent(), long, long)
        val paper = e2e.rows().register(gathering.id, null, "Elena", long, enteredBy = gathering.martaId, notes = longNotes, notesOnPaper = true)
        page.setViewportSize(320, 640)

        for (path in listOf(
            "/iscrizioni", "/iscrizioni/$online", "/iscrizioni/$paper", "/iscrizioni/$online/correggi", "/iscrizioni/$paper/correggi", "/iscrizioni/$paper/correggi-segnalazioni",
            "/iscrizioni/$online/ritira", "/iscrizioni/nuova?convivenza=${gathering.id}", "/convivenza/${gathering.id}", "/convivenza/${gathering.id}/quote",
            "/convivenza/${gathering.id}/date", "/convivenza/${gathering.id}/affidatari",
        )) {
            e2e.open(path)
            page.settled()
            assertTrue(page.noHorizontalScroll(), "$path scrolls sideways at 320")
            assertAccessible(page, "$path at 320")
        }

        // The notes shown, with the longest words, and the open text of the acceptance.
        e2e.open("/iscrizioni/$online")
        page.settled()
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Lettura registrata a tuo nome")
        e2e.press(page.locator("summary:has-text('Testo accettato')"))
        assertTrue(page.noHorizontalScroll(), "the detail with the notes and the text scrolls sideways at 320")
        assertAccessible(page, "detail with the notes shown and the text open at 320")

        // The form of the paper registrations with every error at once.
        e2e.open("/iscrizioni/nuova?convivenza=${gathering.id}")
        page.settled()
        page.locator("#telefono").fill("1")
        page.fillBlock(0, long.repeat(2), "", "40", "13", "99")
        e2e.press(page.locator("main").buttonNamed("Inserisci l’iscrizione"))
        assertThat(page.locator("#telefono-errore")).isVisible()
        assertTrue(page.noHorizontalScroll(), "the paper form with its errors scrolls sideways at 320")
        assertAccessible(page, "paper form with every error at 320")
    }

    @org.junit.jupiter.api.Disabled("QA TALITA-5, difetto D1 (criterio 5, design A 1/2 punto 10): anche nel modulo del cartaceo la richiesta che aggiorna la quota manda al server le segnalazioni, perché htmx aggiunge ai POST tutti i campi del form e hx-include non toglie niente; da riattivare quando il developer lo corregge")
    @E2eTest
    fun `c05 c25 the request that updates the quota of the paper form does not carry the notes on health`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.gathering()
        val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        page.onRequest { if (it.method() == "POST" && it.url().endsWith("/quota")) bodies += it.postData().orEmpty() }
        e2e.open("/iscrizioni/nuova?convivenza=${gathering.id}")
        page.settled()
        e2e.press(page.locator("summary:has-text('Allergie, intolleranze, farmaci')"))
        page.locator("#b0-allergie").fill("SEGRETO-ALLERGIA")

        page.locator("input[name='children[0].presence'][value='all_days']").check()
        assertThat(page.locator("#quota-0")).hasText(Pattern.compile("Quota\\s*60\\s*€"))

        assertTrue(bodies.isNotEmpty(), "the page asked the server for the quota")
        for (body in bodies) {
            assertTrue(!body.contains("SEGRETO") && !body.contains("allergies") && !body.contains("medications"), "the request carries the notes: $body")
        }
    }
}
