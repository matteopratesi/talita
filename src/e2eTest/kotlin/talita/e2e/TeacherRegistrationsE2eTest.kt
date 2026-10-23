package talita.e2e

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import talita.account.TestAccounts
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.HEALTH_NOTE
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.REGISTRATION
import talita.registration.Notes
import java.time.LocalDate
import java.util.regex.Pattern
import kotlin.test.assertEquals
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
}
