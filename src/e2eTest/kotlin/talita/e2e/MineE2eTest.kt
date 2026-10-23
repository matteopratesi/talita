package talita.e2e

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import talita.Addresses
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.HEALTH_NOTE
import talita.jooq.tables.references.REGISTRATION
import talita.registration.Notes
import java.time.LocalDate
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * «Iscrizioni» of the parent in a real browser, on a phone and on a desktop, with axe (criteria 11-16, 19, 38): the cards, the dialogs of the withdrawal
 * and of taking the notes away (and the pages that stand in for them without JavaScript), the modification with its quota, the notes on health, what
 * closed registrations and a gathering that is over allow, and the home with the gatherings of the parent. Names and numbers are synthetic.
 */
@E2eApplication
class MineE2eTest {

    private val annaBirth = LocalDate.of(2019, 1, 9)

    /** The person of the test has two children registered: Luca every day, Anna on some days, in forse, with notes on health. */
    private fun E2e.twoChildren(gathering: OpenGathering): Pair<Long, Long> {
        val me = accountId(checkNotNull(signedInAs))
        val luca = rows().register(gathering.id, me, "Luca", "Rossi")
        val anna = rows().register(
            gathering.id, me, "Anna", "Rossi", annaBirth, status = RegistrationStatus.maybe,
            days = mapOf(gathering.saturday to "full", gathering.sunday to "lunch"), notes = Notes("Arachidi", null, "Antistaminico in caso di reazione"),
        )
        return luca to anna
    }

    // ---- The list (criteria 11, 14) ---------------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `c11 c14 the list shows a card for each child, the dialog of the withdrawal keeps the card as Ritirata, and the child is put back`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.openGathering()
        val (luca, anna) = e2e.twoChildren(gathering)

        e2e.open("/iscrizioni")

        assertThat(page).hasTitle("Iscrizioni · Talità")
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        assertThat(page.locator("nav a[aria-current=page]")).hasText("Iscrizioni")
        assertThat(page.locator("main h2").first()).hasText("Convivenza di settembre 2027")
        assertThat(page.locator("main")).containsText("Iscrizioni aperte")
        assertThat(page.locator("#scheda-$luca")).hasText("Luca Rossi")
        assertThat(page.locator("article").first()).containsText("Confermata")
        assertThat(page.locator("article").first()).hasText(Pattern.compile(".*Quota\\s*60\\s*€.*Telefono\\s*333 123 4567.*Segnalazioni\\s*Nessuna.*Affidamento accettato il .* alle \\d\\d:\\d\\d.*", Pattern.DOTALL))
        assertThat(page.locator("article").nth(1)).hasText(Pattern.compile(".*In forse.*Giornata: sabato \\d+\\. Solo pranzo: domenica \\d+\\..*Quota\\s*35\\s*€.*Segnalazioni\\s*Scritte.*", Pattern.DOTALL))
        assertThat(page.locator("main")).containsText("Totale")
        assertThat(page.locator("main").linkNamed("Iscrivi un altro figlio")).isVisible()
        assertThat(page.locator("main")).not().containsText("Arachidi")
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "registrations of the parent (${e2e.profile.label})")
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px")
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)

        // The dialog of the withdrawal: the first focus is on «Annulla», and the answer is a change of page with a notice.
        e2e.press(page.locator("main").linkNamed("Ritira l’iscrizione di Luca Rossi"))
        page.waitForSettledDialog()
        assertThat(page.openDialog()).containsText("Ritirare l’iscrizione?")
        assertThat(page.openDialog()).containsText("L’iscrizione di Luca Rossi alla Convivenza di settembre 2027 risulterà ritirata")
        page.focusIs("document.activeElement.textContent.trim() === 'Annulla'")
        assertAccessible(page, "dialog of the withdrawal (${e2e.profile.label})")
        e2e.touch(page.openDialog().buttonNamed("Ritira l’iscrizione"))

        assertThat(page.locator("[role=status]")).containsText("Iscrizione ritirata.")
        page.focusIs("document.activeElement.getAttribute('role') === 'status'")
        assertThat(page.locator("#scheda-$luca")).hasText("Luca Rossi")
        assertThat(page.locator("article").first()).containsText("Ritirata")
        assertThat(page.locator("article").first()).hasText(Pattern.compile(".*Quota\\s*Non dovuta.*", Pattern.DOTALL))
        assertThat(page.locator("article").first().linkNamed("Rimetti l’iscrizione di Luca Rossi")).isVisible()
        assertThat(page.locator("main")).containsText("Totale")
        assertEquals(RegistrationStatus.withdrawn, e2e.dsl().select(REGISTRATION.STATUS).from(REGISTRATION).where(REGISTRATION.ID.eq(luca)).fetchSingle(REGISTRATION.STATUS))
        assertAccessible(page, "registrations after a withdrawal (${e2e.profile.label})")

        // Put back: the page of the modification, with Confermata chosen, and the card is Confermata again.
        e2e.press(page.locator("main").linkNamed("Rimetti l’iscrizione di Luca Rossi"))
        assertThat(page.locator("h1")).hasText("Rimetti l’iscrizione")
        assertThat(page.locator("main")).containsText("L’iscrizione è ritirata. Scegli «Confermata» o «In forse»")
        assertThat(page.locator("input[name='children[0].status'][value='confirmed']")).isChecked()
        assertAccessible(page, "put back (${e2e.profile.label})")
        e2e.press(page.locator("main").buttonNamed("Rimetti l’iscrizione"))
        assertThat(page.locator("[role=status]")).containsText("Iscrizione rimessa.")
        assertThat(page.locator("article").first()).containsText("Confermata")
        assertEquals(RegistrationStatus.confirmed, e2e.dsl().select(REGISTRATION.STATUS).from(REGISTRATION).where(REGISTRATION.ID.eq(luca)).fetchSingle(REGISTRATION.STATUS))
        assertEquals(1, e2e.dsl().fetchCount(HEALTH_NOTE), "the notes of Anna are still there")
        assertTrue(anna > 0)
    }

    @E2eTest(teacher = false)
    fun `c14 without JavaScript the withdrawal is a page with the same words and a button`(e2e: E2e) {
        val gathering = e2e.openGathering()
        val address = Addresses.unique("e2e-senza-js")
        val plain = e2e.newPage(signedIn = false, teacher = false) { setJavaScriptEnabled(false) }
        e2e.signIn(plain, address, teacher = false)
        val luca = e2e.rows().register(gathering.id, e2e.accountId(address), "Luca", "Rossi")

        plain.navigate(e2e.url("/iscrizioni"))
        plain.settled()
        plain.locator("main").linkNamed("Ritira l’iscrizione di Luca Rossi").click()
        plain.waitForURL(e2e.url("/iscrizioni/$luca/ritira"))

        assertThat(plain.locator("h1")).hasText("Ritirare l’iscrizione?")
        assertThat(plain.locator("main")).containsText("risulterà ritirata: la quota non è dovuta e i giorni non contano più.")
        plain.settled()
        plain.locator("main").buttonNamed("Ritira l’iscrizione").click()
        plain.waitForURL(e2e.url("/iscrizioni"))
        assertThat(plain.locator("[role=status]")).containsText("Iscrizione ritirata.")
        assertEquals(RegistrationStatus.withdrawn, e2e.dsl().select(REGISTRATION.STATUS).from(REGISTRATION).where(REGISTRATION.ID.eq(luca)).fetchSingle(REGISTRATION.STATUS))

        // The page of confirmation, with JavaScript on, is a page like the others.
        e2e.page.let { main ->
            val other = e2e.rows().register(gathering.id, e2e.accountId(checkNotNull(e2e.signedInAs)), "Marco", "Verdi")
            e2e.open("/iscrizioni/$other/ritira")
            assertThat(main.locator("h1")).hasText("Ritirare l’iscrizione?")
            assertAccessible(main, "page of confirmation of the withdrawal (${e2e.profile.label})")
        }
    }

    // ---- The modification (criteria 12, 13) -----------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `c12 c13 the modification shows the quota that changes at once, keeps the notes out, and errors take the focus`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.openGathering()
        val (luca) = e2e.twoChildren(gathering)

        e2e.open("/iscrizioni")
        e2e.press(page.locator("main").linkNamed("Modifica l’iscrizione di Luca Rossi"))

        assertThat(page).hasURL(e2e.url("/iscrizioni/$luca/modifica"))
        assertThat(page.locator("h1")).hasText("Modifica l’iscrizione")
        assertThat(page.locator("main")).containsText("Luca Rossi · Convivenza di settembre 2027")
        assertThat(page.locator("main")).containsText("L’affidamento che hai accettato il")
        assertThat(page.locator("main")).containsText("Il telefono vale per tutte le tue iscrizioni a questa convivenza")
        assertThat(page.locator("summary")).hasCount(0)
        assertThat(page.locator("#b0-nome")).hasValue("Luca")
        assertThat(page.locator("#quota-0")).hasText(Pattern.compile("Quota\\s*60\\s*€"))
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "modification (${e2e.profile.label})")

        page.locator("input[name='children[0].presence'][value='days']").check()
        page.locator("input[name='children[0].day.fri'][value='full']").check()
        assertThat(page.locator("#quota-0")).hasText(Pattern.compile("Quota\\s*25\\s*€"))
        page.locator("input[name='children[0].day.sun'][value='lunch']").check()
        assertThat(page.locator("#quota-0")).hasText(Pattern.compile("Quota\\s*35\\s*€"))

        page.locator("#telefono").fill("12")
        e2e.press(page.locator("main").buttonNamed("Salva le modifiche"))
        assertThat(page.locator("#telefono-errore")).hasText("Scrivi un numero di telefono completo, per esempio 333 123 4567.")
        page.focusIs("document.activeElement.id === 'telefono'")
        assertEquals("Luca", page.locator("#b0-nome").inputValue())
        assertAccessible(page, "modification with an error (${e2e.profile.label})")

        page.locator("#telefono").fill("+39 333 999 8888")
        e2e.press(page.locator("main").buttonNamed("Salva le modifiche"))
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertThat(page.locator("[role=status]")).containsText("Iscrizione salvata.")
        assertThat(page.locator("article").first()).hasText(Pattern.compile(".*Giornata: venerdì \\d+\\. Solo pranzo: domenica \\d+\\..*Quota\\s*35\\s*€.*Telefono\\s*\\+39 333 999 8888.*", Pattern.DOTALL))
        assertEquals("+39 333 999 8888", e2e.dsl().select(REGISTRATION.PHONE).from(REGISTRATION).fetch(REGISTRATION.PHONE).toSet().single(), "one phone for the registrations of the parent")
    }

    // ---- Closed registrations, a gathering that is over (criteria 15, 19) --------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `c15 c19 with the registrations closed the cards only look, the notes can change, and when the gathering is over only the revocation is left`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.openGathering(status = GatheringStatus.closed)
        val (luca, anna) = e2e.twoChildren(gathering)

        e2e.open("/iscrizioni")

        assertThat(page.locator("main")).containsText("Iscrizioni chiuse")
        assertThat(page.locator("main")).containsText("Le iscrizioni sono chiuse. Per cambiare un’iscrizione o iscrivere un altro bambino, chiedi ai maestri.")
        assertThat(page.locator("main").linkNamed("Iscrivi un altro figlio")).hasCount(0)
        assertThat(page.locator("main").linkNamed("Modifica l’iscrizione di Luca Rossi")).hasCount(0)
        assertThat(page.locator("main").linkNamed("Ritira l’iscrizione di Luca Rossi")).hasCount(0)
        assertThat(page.locator("main").linkNamed("Segnalazioni di Anna Rossi")).isVisible()
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "registrations closed (${e2e.profile.label})")

        // The notes: the page says that they can change, and a change is saved.
        e2e.press(page.locator("main").linkNamed("Segnalazioni di Anna Rossi"))
        assertThat(page.locator("h1")).hasText("Segnalazioni")
        assertThat(page.locator("main")).containsText("Le iscrizioni sono chiuse, ma le segnalazioni puoi cambiarle fino alla fine della convivenza.")
        assertThat(page.locator("main")).containsText("Segnalazioni di Anna")
        assertThat(page.locator("#b0-allergie")).hasValue("Arachidi")
        assertThat(page.locator("#b0-consenso")).isChecked()
        assertThat(page.locator("main")).containsText("Consenso dato il")
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "notes on health, registrations closed (${e2e.profile.label})")
        page.locator("#b0-allergie").fill("Polline")
        e2e.press(page.locator("main").buttonNamed("Salva le segnalazioni"))
        assertThat(page.locator("[role=status]")).containsText("Segnalazioni salvate.")
        assertEquals("Polline", e2e.dsl().select(HEALTH_NOTE.ALLERGIES).from(HEALTH_NOTE).fetchSingle(HEALTH_NOTE.ALLERGIES))

        // The gathering is over: the card has «Togli le segnalazioni», with a dialog of danger, and nothing else.
        e2e.dsl().update(GATHERING).set(GATHERING.START_DATE, e2e.today().minusDays(9)).set(GATHERING.END_DATE, e2e.today().minusDays(7)).execute()
        e2e.open("/iscrizioni")
        assertThat(page.locator("main")).containsText("La convivenza è finita.")
        assertThat(page.locator("main")).containsText("Finita")
        assertThat(page.locator("main").linkNamed("Segnalazioni di Anna Rossi")).hasCount(0)
        assertAccessible(page, "registrations of a gathering that is over (${e2e.profile.label})")
        e2e.press(page.locator("main").linkNamed("Togli le segnalazioni di Anna Rossi"))
        page.waitForSettledDialog()
        assertThat(page.openDialog()).containsText("Togliere le segnalazioni?")
        assertThat(page.openDialog()).containsText("Cancelliamo subito le segnalazioni su allergie, intolleranze e farmaci di Anna Rossi e il tuo consenso")
        assertAccessible(page, "dialog of the revocation (${e2e.profile.label})")
        e2e.touch(page.openDialog().buttonNamed("Togli le segnalazioni"))
        assertThat(page.locator("[role=status]")).containsText("Segnalazioni tolte.")
        assertEquals(0, e2e.dsl().fetchCount(HEALTH_NOTE))
        assertThat(page.locator("main").linkNamed("Togli le segnalazioni di Anna Rossi")).hasCount(0)
        assertTrue(luca > 0 && anna > 0)
    }

    // ---- The notes on health (criteria 18, 19) -------------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `c18 c19 the notes of a child are written on their page with the consent, and taken away with the dialog of danger`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.openGathering()
        val me = e2e.accountId(checkNotNull(e2e.signedInAs))
        val anna = e2e.rows().register(gathering.id, me, "Anna", "Rossi", annaBirth)

        e2e.open("/iscrizioni")
        e2e.press(page.locator("main").linkNamed("Segnalazioni di Anna Rossi"))
        assertThat(page.locator("h1")).hasText("Segnalazioni")
        assertThat(page.locator("main")).containsText("Anna Rossi · Convivenza di settembre 2027")
        assertThat(page.locator("main").linkNamed("Togli le segnalazioni")).hasCount(0)
        assertAccessible(page, "notes on health, empty (${e2e.profile.label})")

        page.locator("#b0-intolleranze").fill("Lattosio")
        e2e.press(page.locator("main").buttonNamed("Salva le segnalazioni"))
        assertThat(page.locator("#b0-consenso-errore")).containsText("Per salvare le segnalazioni serve il tuo consenso: spunta la casella.")
        page.focusIs("document.activeElement.id === 'b0-consenso'")
        assertAccessible(page, "notes on health, without the consent (${e2e.profile.label})")
        page.locator("#b0-consenso").check()
        e2e.press(page.locator("main").buttonNamed("Salva le segnalazioni"))
        assertThat(page.locator("[role=status]")).containsText("Segnalazioni salvate.")
        assertThat(page.locator("article").first()).containsText("Scritte")
        assertEquals(1, e2e.dsl().fetchCount(HEALTH_NOTE))

        e2e.press(page.locator("main").linkNamed("Segnalazioni di Anna Rossi"))
        assertThat(page.locator("main")).containsText("Consenso dato il")
        e2e.press(page.locator("main").linkNamed("Togli le segnalazioni"))
        page.waitForSettledDialog()
        assertThat(page.openDialog()).containsText("Togliere le segnalazioni?")
        e2e.touch(page.openDialog().buttonNamed("Annulla"))
        assertEquals(1, e2e.dsl().fetchCount(HEALTH_NOTE), "Annulla takes nothing away")
        e2e.press(page.locator("main").linkNamed("Togli le segnalazioni"))
        page.waitForSettledDialog()
        e2e.touch(page.openDialog().buttonNamed("Togli le segnalazioni"))
        assertThat(page.locator("[role=status]")).containsText("Segnalazioni tolte.")
        assertEquals(0, e2e.dsl().fetchCount(HEALTH_NOTE))
        assertTrue(anna > 0)
    }

    // ---- The home (criterion 11) ---------------------------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `c11 the home of a parent lists the gatherings of their registrations, and says how to register when there are none`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.openGathering()

        e2e.open("/")
        assertThat(page.locator("main")).containsText("Non hai ancora iscritto nessun bambino. Per farlo, apri il link che ti hanno mandato i maestri.")
        assertThat(page.locator("main h2")).hasText("Cosa trovi qui")
        assertAccessible(page, "home of a parent with no registration (${e2e.profile.label})")

        e2e.twoChildren(gathering)
        e2e.open("/")
        assertThat(page.locator("main h2")).hasText(arrayOf("Le tue iscrizioni", "Cosa trovi qui"))
        assertThat(page.locator("main section").first()).containsText("Convivenza di settembre 2027")
        assertThat(page.locator("main section").first()).hasText(Pattern.compile(".*Iscrizioni aperte.*2 bambini · 95\\s*€.*", Pattern.DOTALL))
        assertAccessible(page, "home of a parent with registrations (${e2e.profile.label})")
        e2e.touch(page.locator("main section").first().locator("a").first())
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
    }
}
