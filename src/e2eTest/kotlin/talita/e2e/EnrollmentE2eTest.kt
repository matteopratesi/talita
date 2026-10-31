package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import talita.Addresses
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.HEALTH_NOTE
import talita.jooq.tables.references.REGISTRATION
import java.time.Clock
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The registration of the children by the parent in a real browser, on a phone and on a desktop, with axe (criteria 1-10, 18, 33, 34, 38): the first
 * step with its blocks and the quota that changes at once, the errors with the focus where it must be, the second step, the confirmation, a lost
 * connection that never offers «Riprova», and the complete notice without a script. Names and numbers are synthetic.
 */
@E2eApplication
class EnrollmentE2eTest {

    private fun Page.fillChild(index: Int, first: String, last: String, day: String, month: String, year: String) {
        locator("#b$index-nome").fill(first)
        locator("#b$index-cognome").fill(last)
        locator("#b$index-giorno").fill(day)
        locator("#b$index-mese").fill(month)
        locator("#b$index-anno").fill(year)
    }

    private fun Page.choose(name: String, value: String) {
        locator("input[name='$name'][value='$value']").check()
    }

    // ---- The first step (criteria 1, 4, 5) -----------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `c01 c05 the blocks come and go, the days appear with Alcuni giorni and the quota changes at once, and the page is accessible`(e2e: E2e) {
        val page = e2e.page
        val code = e2e.openGathering().code

        e2e.open("/info/$code/iscrizione")

        assertThat(page).hasTitle("Iscrizione · Talità")
        assertThat(page.locator("h1")).hasText("Iscrizione")
        assertThat(page.locator("main")).containsText("Passo 1 di 2 · I dati")
        assertThat(page.locator("nav a[aria-current=page]")).hasText("Iscrizioni")
        // The days are not there until «Alcuni giorni» is chosen, and no presence is chosen before the person chooses.
        assertThat(page.locator("legend:has-text('Venerdì')").first()).isHidden()
        assertThat(page.locator("input[name='children[0].presence']:checked")).hasCount(0)
        assertThat(page.locator("#quota-0")).isHidden()
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "first step, empty (${e2e.profile.label})")

        page.fillChild(0, "Luca", "Rossi", "4", "4", "2016")
        page.choose("children[0].presence", "all_days")
        assertThat(page.locator("#quota-0")).hasText(Pattern.compile("Quota\\s*60\\s*€"))
        page.choose("children[0].presence", "days")
        assertThat(page.locator("legend:has-text('Venerdì')").first()).isVisible()
        assertThat(page.locator("#quota-0")).isHidden()
        page.choose("children[0].day.fri", "full")
        page.choose("children[0].day.sat", "full")
        assertThat(page.locator("#quota-0")).hasText(Pattern.compile("Quota\\s*50\\s*€"))
        page.choose("children[0].day.sat", "lunch")
        page.choose("children[0].day.sun", "full")
        assertThat(page.locator("#quota-0")).hasText(Pattern.compile("Quota\\s*60\\s*€"))
        page.choose("children[0].day.fri", "")
        assertThat(page.locator("#quota-0")).hasText(Pattern.compile("Quota\\s*35\\s*€"))
        assertAccessible(page, "first step with the days (${e2e.profile.label})")

        // A second block: the page does not move to the top and what was written stays; the total appears with two children.
        e2e.press(page.locator("main").buttonNamed("Aggiungi un figlio"))
        assertThat(page.locator("#b1-nome")).isVisible()
        page.focusIs("document.activeElement.id === 'b1-nome'")
        assertEquals("Luca", page.locator("#b0-nome").inputValue())
        // With two blocks the card of the quota is there, and counts only the child that has a quota so far.
        assertThat(page.locator("#quota-totale")).hasText(Pattern.compile("Luca Rossi\\s*35\\s*€\\s*Totale\\s*35\\s*€.*"))
        page.fillChild(1, "Anna", "Rossi", "9", "1", "2019")
        page.choose("children[1].presence", "all_days")
        assertThat(page.locator("#quota-1")).hasText(Pattern.compile("Quota\\s*60\\s*€"))
        assertThat(page.locator("#quota-totale")).containsText("Totale")
        assertThat(page.locator("#quota-totale")).hasText(Pattern.compile("Luca Rossi\\s*35\\s*€\\s*Anna Rossi\\s*60\\s*€\\s*Totale\\s*95\\s*€\\s*La quota definitiva la vedi al passo 2\\."))
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "first step with two children (${e2e.profile.label})")

        // Taking the second away: the total goes, the focus is on the first.
        e2e.press(page.locator("main").buttonNamed("Togli il bambino 2"))
        assertThat(page.locator("#b1-nome")).hasCount(0)
        assertThat(page.locator("#quota-totale")).isHidden()
        page.focusIs("document.activeElement.id === 'b0-nome'")
        assertEquals("Luca", page.locator("#b0-nome").inputValue())
    }

    @E2eTest(teacher = false)
    fun `c01 c03 c18 a form with errors stays at step 1 with the focus on the first wrong field, and the notes on health open with their consent`(e2e: E2e) {
        val page = e2e.page
        val code = e2e.openGathering().code
        e2e.open("/info/$code/iscrizione")
        page.settled()

        e2e.press(page.locator("main").buttonNamed("Controlla e accetta"))

        assertThat(page).hasURL(e2e.url("/info/$code/iscrizione"))
        assertThat(page.locator("[role=alert]")).containsText("Controlla i 5 campi segnati in rosso.")
        page.focusIs("document.activeElement.id === 'telefono'")
        assertThat(page.locator("#telefono-errore")).hasText("Scrivi un numero di telefono.")
        assertThat(page.locator("#b0-nome-errore")).hasText("Scrivi il nome.")
        assertThat(page.locator("#b0-nascita-errore")).hasText("Scrivi giorno, mese e anno: per esempio 4 4 2016.")
        assertThat(page.locator("#b0-quando-errore")).hasText("Scegli quando viene.")
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "first step with errors (${e2e.profile.label})")

        // Notes without the box: the block opens and the error is on the box.
        page.locator("#telefono").fill("333 123 4567")
        page.fillChild(0, "Luca", "Rossi", "4", "4", "2016")
        page.choose("children[0].presence", "all_days")
        e2e.press(page.locator("summary:has-text('Allergie, intolleranze, farmaci')"))
        page.locator("#b0-allergie").fill("Arachidi")
        e2e.press(page.locator("main").buttonNamed("Controlla e accetta"))
        assertThat(page.locator("#b0-consenso-errore")).hasText("Per salvare le segnalazioni serve il tuo consenso: spunta la casella. Se non vuoi darlo, svuota i campi: l’iscrizione vale lo stesso.")
        page.focusIs("document.activeElement.id === 'b0-consenso'")
        assertEquals("Arachidi", page.locator("#b0-allergie").inputValue())
        assertAccessible(page, "first step, notes without consent (${e2e.profile.label})")
    }

    // ---- The second step and the confirmation (criteria 6-10, 33) ------------------------------------------------------------------

    private fun Page.toStepTwo(e2e: E2e, code: String) {
        e2e.open("/info/$code/iscrizione")
        settled()
        locator("#telefono").fill("333 123 4567")
        fillChild(0, "Luca", "Rossi", "4", "4", "2016")
        choose("children[0].presence", "all_days")
        e2e.press(locator("main").buttonNamed("Controlla e accetta"))
        assertThat(locator("h1")).hasText("Controlla e accetta")
    }

    @E2eTest(teacher = false)
    fun `c06 c07 c33 step 2 shows the recap, the notice and the text, asks for the three boxes and saves nothing before the confirmation, then it registers`(e2e: E2e) {
        val page = e2e.page
        val code = e2e.openGathering().code

        page.toStepTwo(e2e, code)

        assertThat(page).hasTitle("Controlla e accetta · Talità")
        assertThat(page).hasURL(e2e.url("/info/$code/iscrizione"))
        assertThat(page.locator("main")).containsText("Passo 2 di 2 · Controlla e accetta")
        assertThat(page.locator("main")).containsText("Luca Rossi")
        assertThat(page.locator("main")).containsText("Totale")
        assertThat(page.locator("main")).containsText("Il titolare è Titolare di esempio.")
        assertThat(page.locator("main")).containsText("Affidamento di figli minorenni")
        assertThat(page.locator("main")).containsText("alla responsabilità di Marta Bianchi e Paolo Verdi")
        assertThat(page.locator("input[type=checkbox]:checked")).hasCount(0)
        assertEquals(0, e2e.dsl().fetchCount(REGISTRATION))
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "second step (${e2e.profile.label})")

        // Without the boxes: the error is under each box, the boxes ticked stay ticked, and the focus is on the first wrong one.
        page.locator("#altro-genitore").check()
        e2e.press(page.locator("main").buttonNamed("Accetta e iscrivi"))
        assertThat(page.locator("#accetto-errore")).hasText("Per iscrivere il bambino devi accettare l’affidamento.")
        assertThat(page.locator("#convinzioni-errore")).hasText("Senza questa casella non possiamo fare l’iscrizione.")
        assertThat(page.locator("#altro-genitore")).isChecked()
        page.focusIs("document.activeElement.id === 'accetto'")
        assertEquals(0, e2e.dsl().fetchCount(REGISTRATION))
        assertAccessible(page, "second step with errors (${e2e.profile.label})")

        page.locator("#accetto").check()
        page.locator("#convinzioni").check()
        e2e.press(page.locator("main").buttonNamed("Accetta e iscrivi"))
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertEquals(1, e2e.dsl().fetchCount(REGISTRATION))
    }

    @E2eTest(teacher = false)
    fun `c01 c06 Enter in a field of the first step goes to step 2 and never adds or takes away a block, and Enter on a box of step 2 accepts and does not go back`(e2e: E2e) {
        val page = e2e.page
        val code = e2e.openGathering().code
        e2e.open("/info/$code/iscrizione")
        page.settled()
        page.locator("#telefono").fill("333 123 4567")
        page.fillChild(0, "Luca", "Rossi", "4", "4", "2016")
        page.choose("children[0].presence", "all_days")

        // One block: the first button of the page is «Aggiungi un figlio», and Enter must not click it.
        page.locator("#telefono").press("Enter")
        assertThat(page.locator("h1")).hasText("Controlla e accetta")
        assertThat(page.locator("main")).containsText("Luca Rossi")
        assertEquals(0, e2e.dsl().fetchCount(REGISTRATION))

        // Two blocks: the first button is «Togli il bambino 1», and Enter in the second block must not take the first away with what is written in it.
        e2e.press(page.locator("main").buttonNamed("Modifica i dati"))
        assertThat(page.locator("main")).containsText("Passo 1 di 2 · I dati")
        e2e.press(page.locator("main").buttonNamed("Aggiungi un figlio"))
        assertThat(page.locator("#b1-nome")).isVisible()
        page.fillChild(1, "Anna", "Rossi", "9", "1", "2019")
        page.choose("children[1].presence", "all_days")
        page.locator("#b1-nome").press("Enter")
        assertThat(page.locator("h1")).hasText("Controlla e accetta")
        assertThat(page.locator("main")).containsText("Luca Rossi")
        assertThat(page.locator("main")).containsText("Anna Rossi")
        assertEquals(0, e2e.dsl().fetchCount(REGISTRATION))

        // Step 2: «Modifica i dati» comes first in the page, and Enter on a box must not go back; with the three boxes ticked it is the acceptance.
        page.locator("#accetto").check()
        page.locator("#altro-genitore").check()
        page.locator("#convinzioni").check()
        page.locator("#accetto").press("Enter")
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertEquals(2, e2e.dsl().fetchCount(REGISTRATION))
    }

    @E2eTest(teacher = false)
    fun `c06 the first step comes back with what was written from «Modifica i dati» and from «Indietro»`(e2e: E2e) {
        val page = e2e.page
        val code = e2e.openGathering().code
        page.toStepTwo(e2e, code)

        e2e.press(page.locator("main").buttonNamed("Modifica i dati"))

        assertThat(page.locator("main")).containsText("Passo 1 di 2 · I dati")
        assertEquals("Luca", page.locator("#b0-nome").inputValue())
        assertEquals("333 123 4567", page.locator("#telefono").inputValue())
        assertThat(page.locator("input[name='children[0].presence'][value='all_days']")).isChecked()

        e2e.press(page.locator("main").buttonNamed("Controlla e accetta"))
        e2e.press(page.locator("main").buttonNamed("Indietro"))
        assertThat(page.locator("main")).containsText("Passo 1 di 2 · I dati")
        assertEquals("Luca", page.locator("#b0-nome").inputValue())
        assertEquals(0, e2e.dsl().fetchCount(REGISTRATION))
    }

    @E2eTest(teacher = false)
    fun `c10 without a network the second step stays as it is, with the boxes ticked and no Riprova, and touching again registers`(e2e: E2e) {
        val page = e2e.page
        val code = e2e.openGathering().code
        page.toStepTwo(e2e, code)
        page.locator("#accetto").check()
        page.locator("#altro-genitore").check()
        page.locator("#convinzioni").check()

        e2e.browserContext.setOffline(true)
        e2e.press(page.locator("main").buttonNamed("Accetta e iscrivi"))
        assertThat(page.messages()).containsText("Nessuna connessione")
        assertThat(page.messages().locator("button:has-text('Riprova')")).hasCount(0)
        assertThat(page.locator("#accetto")).isChecked()
        assertThat(page.locator("main")).containsText("Passo 2 di 2")
        assertEquals(0, e2e.dsl().fetchCount(REGISTRATION))

        e2e.browserContext.setOffline(false)
        e2e.press(page.locator("main").buttonNamed("Accetta e iscrivi"))
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertEquals(1, e2e.dsl().fetchCount(REGISTRATION))
    }

    @E2eTest(teacher = false)
    fun `c09 when the registrations close while the second step is open the page says so and nothing is saved`(e2e: E2e) {
        val page = e2e.page
        val code = e2e.openGathering().code
        page.toStepTwo(e2e, code)
        page.locator("#accetto").check()
        page.locator("#altro-genitore").check()
        page.locator("#convinzioni").check()
        e2e.dsl().update(talita.jooq.tables.references.GATHERING).set(talita.jooq.tables.references.GATHERING.STATUS, GatheringStatus.closed).execute()

        e2e.press(page.locator("main").buttonNamed("Accetta e iscrivi"))

        assertThat(page.locator("h1")).hasText("Le iscrizioni sono chiuse")
        assertThat(page.locator("main")).containsText("Mentre compilavi, le iscrizioni si sono chiuse: non abbiamo salvato niente.")
        assertEquals(0, e2e.dsl().fetchCount(REGISTRATION))
        assertAccessible(page, "registrations closed meanwhile (${e2e.profile.label})")
        e2e.touch(page.locator("main").linkNamed("Torna alle informazioni"))
        assertThat(page).hasURL(e2e.url("/info/$code"))
    }

    // ---- The complete notice (criterion 34) -----------------------------------------------------------------------------------------

    @E2eTest(signedIn = false)
    fun `c34 the complete notice opens without an account, has no script, and is accessible`(e2e: E2e) {
        val page = e2e.page
        val scripts = mutableListOf<String>()
        page.onRequest { if (it.resourceType() == "script") scripts += it.url() }

        e2e.open("/informativa")

        assertThat(page).hasTitle("Informativa sulla privacy · Talità")
        assertThat(page.locator("h1")).hasText("Informativa sulla privacy")
        assertThat(page.locator("main h2")).hasCount(10)
        assertThat(page.locator("main")).containsText("Il titolare del trattamento è Titolare di esempio")
        assertEquals(emptyList<String>(), scripts, "the page loads no script")
        assertEquals(0, (page.evaluate("document.querySelectorAll('script').length") as Number).toInt())
        assertEquals(0, e2e.browserContext.cookies().size, "the page sets no cookie")
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px")
        assertAccessible(page, "complete notice (${e2e.profile.label})")
    }

    // ---- What the QA of TALITA-5 added (criteria 1, 2, 3, 5-10, 18, 33): the flows that the tests above do not walk ------------------------

    private fun Page.tickTheThreeBoxes() {
        locator("#accetto").check()
        locator("#altro-genitore").check()
        locator("#convinzioni").check()
    }

    @E2eTest(teacher = false)
    fun `c01 c05 c06 c07 without JavaScript the blocks come and go with their buttons, the quota is seen at step 2 and two children are registered`(e2e: E2e) {
        val gathering = e2e.openGathering()
        val plain = e2e.newPage(signedIn = false, teacher = false) { setJavaScriptEnabled(false) }
        e2e.signIn(plain, Addresses.unique("e2e-senza-js"), teacher = false)

        plain.navigate(e2e.url("/info/${gathering.code}/iscrizione"))
        plain.settled()
        // The words for whoever has the script off are in a <noscript>, which Playwright leaves out of the text of an element.
        assertTrue(plain.locator("main noscript").innerHTML().contains("La quota la vedi al passo 2."))
        plain.locator("#telefono").fill("333 123 4567")
        plain.fillChild(0, "Luca", "Rossi", "4", "4", "2016")
        plain.choose("children[0].presence", "all_days")
        // The days are there only after «Alcuni giorni»: with the script off it is the style that decides.
        assertThat(plain.locator("legend:has-text('Venerdì')").first()).isHidden()

        // Another block, with a button that sends the form: what was written is still there, and the quota is not.
        plain.locator("main").buttonNamed("Aggiungi un figlio").click()
        assertThat(plain.locator("#b1-nome")).isVisible()
        plain.settled()
        assertEquals("Luca", plain.locator("#b0-nome").inputValue())
        assertEquals("333 123 4567", plain.locator("#telefono").inputValue())
        assertThat(plain.locator("input[name='children[0].presence'][value='all_days']")).isChecked()
        // «Togli» takes the block away, and the other one is as it was.
        plain.locator("main").buttonNamed("Togli il bambino 2").click()
        assertThat(plain.locator("#b1-nome")).hasCount(0)
        plain.settled()
        assertEquals("Luca", plain.locator("#b0-nome").inputValue())
        plain.locator("main").buttonNamed("Aggiungi un figlio").click()
        assertThat(plain.locator("#b1-nome")).isVisible()
        plain.settled()
        plain.fillChild(1, "Anna", "Rossi", "9", "1", "2019")
        plain.choose("children[1].presence", "days")
        assertThat(plain.locator("legend:has-text('Sabato')").last()).isVisible()
        plain.choose("children[1].day.sat", "full")
        plain.choose("children[1].day.sun", "lunch")
        assertTrue(plain.noHorizontalScroll())

        plain.locator("main").buttonNamed("Controlla e accetta").click()
        assertThat(plain.locator("h1")).hasText("Controlla e accetta")
        plain.settled()
        assertThat(plain.locator("main")).containsText("Luca Rossi")
        assertThat(plain.locator("main")).containsText("Giornata: sabato")
        assertThat(plain.locator("main")).containsText("Solo pranzo: domenica")
        assertThat(plain.locator("main")).containsText(Pattern.compile("Totale\\s*95\\s*€"))
        assertEquals(0, e2e.dsl().fetchCount(REGISTRATION))
        plain.tickTheThreeBoxes()
        plain.locator("main").buttonNamed("Accetta e iscrivi").click()
        plain.waitForURL(e2e.url("/iscrizioni"))
        assertThat(plain.locator("[role=status]")).containsText("Iscrizioni fatte.")
        assertEquals(2, e2e.dsl().fetchCount(REGISTRATION))
    }

    @E2eTest(teacher = false)
    fun `c06 c07 c18 c33 two children are registered together with one text in the plural, the notes are not in the recap and the complete notice opens in another tab`(e2e: E2e) {
        val page = e2e.page
        val code = e2e.openGathering().code
        e2e.open("/info/$code/iscrizione")
        page.settled()
        page.locator("#telefono").fill("333 123 4567")
        page.fillChild(0, "Luca", "Rossi", "4", "4", "2016")
        page.choose("children[0].presence", "all_days")
        e2e.press(page.locator("summary:has-text('Allergie, intolleranze, farmaci')"))
        page.locator("#b0-allergie").fill("Arachidi")
        page.locator("#b0-consenso").check()
        e2e.press(page.locator("main").buttonNamed("Aggiungi un figlio"))
        page.fillChild(1, "Anna", "Rossi", "9", "1", "2019")
        page.choose("children[1].presence", "all_days")
        e2e.press(page.locator("main").buttonNamed("Controlla e accetta"))

        assertThat(page.locator("h1")).hasText("Controlla e accetta")
        assertThat(page.locator("main")).containsText("Scritte, con il tuo consenso")
        assertThat(page.locator("main")).not().containsText("Arachidi")
        assertThat(page.locator("main")).containsText(Pattern.compile("Totale\\s*120\\s*€"))
        assertThat(page.locator("main")).containsText("Luca Rossi, nato/a il 4 aprile 2016")
        assertThat(page.locator("main")).containsText("Anna Rossi, nato/a il 9 gennaio 2019")
        assertThat(page.locator("main")).containsText("per il loro bene")
        assertThat(page.locator("main")).containsText("di quelli dei bambini")
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "second step, two children (${e2e.profile.label})")

        // The complete notice is in another tab, so that what was written is not lost.
        val notice = page.locator("main").linkNamed("Leggi l’informativa completa")
        assertThat(notice).hasAttribute("target", "_blank")
        assertThat(notice).hasAttribute("rel", Pattern.compile("noopener"))
        val popup = page.waitForPopup { e2e.press(notice) }
        popup.waitForLoadState()
        assertThat(popup.locator("h1")).hasText("Informativa sulla privacy")
        popup.close()
        assertThat(page.locator("h1")).hasText("Controlla e accetta")

        // Without the boxes the words are those of the plural; with them there is one registration for each child, with the same text.
        e2e.press(page.locator("main").buttonNamed("Accetta e iscrivi"))
        assertThat(page.locator("#accetto-errore")).hasText("Per iscrivere i bambini devi accettare l’affidamento.")
        assertEquals(0, e2e.dsl().fetchCount(REGISTRATION))
        page.tickTheThreeBoxes()
        e2e.press(page.locator("main").buttonNamed("Accetta e iscrivi"))
        assertThat(page.locator("[role=status]")).containsText("Iscrizioni fatte.")
        assertEquals(2, e2e.dsl().fetchCount(REGISTRATION))
        val texts = e2e.dsl().selectDistinct(REGISTRATION.CUSTODY_TEXT).from(REGISTRATION).fetch(REGISTRATION.CUSTODY_TEXT)
        assertEquals(1, texts.size, "the same text for the two registrations of one form")
        assertTrue(texts.single()!!.contains("Luca Rossi") && texts.single()!!.contains("Anna Rossi"))
        assertEquals(1, e2e.dsl().fetchCount(HEALTH_NOTE))
        assertThat(page.locator("main")).not().containsText("Arachidi")
    }

    @E2eTest(teacher = false)
    fun `c08 when the text changes while step 2 is open nothing is saved, the alert takes the focus, the boxes are to tick again and the new text is there`(e2e: E2e) {
        val page = e2e.page
        val code = e2e.openGathering().code
        page.toStepTwo(e2e, code)
        page.tickTheThreeBoxes()
        e2e.dsl().update(GATHERING).set(GATHERING.PLACE, "Una casa nuova per ritiri").execute()

        e2e.press(page.locator("main").buttonNamed("Accetta e iscrivi"))

        assertThat(page.locator("main [role=alert]")).containsText("Mentre leggevi, i maestri hanno cambiato il testo dell’affidamento (maestri affidatari, date o luogo).")
        page.focusIs("document.activeElement.getAttribute('data-fuoco') !== null")
        assertThat(page.locator("input[type=checkbox]:checked")).hasCount(0)
        assertThat(page.locator("main")).containsText("Una casa nuova per ritiri")
        assertEquals(0, e2e.dsl().fetchCount(REGISTRATION))
        assertAccessible(page, "second step, the text has changed (${e2e.profile.label})")

        page.tickTheThreeBoxes()
        e2e.press(page.locator("main").buttonNamed("Accetta e iscrivi"))
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertTrue(e2e.dsl().select(REGISTRATION.CUSTODY_TEXT).from(REGISTRATION).fetchSingle(REGISTRATION.CUSTODY_TEXT)!!.contains("Una casa nuova per ritiri"), "the accepted text is the new one")
    }

    @E2eTest(teacher = false)
    fun `c09 the closing of the registrations, and the end of the gathering, are found when the first step is sent`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.openGathering()
        val code = gathering.code

        fun firstStep() {
            e2e.open("/info/$code/iscrizione")
            page.settled()
            page.locator("#telefono").fill("333 123 4567")
            page.fillChild(0, "Luca", "Rossi", "4", "4", "2016")
            page.choose("children[0].presence", "all_days")
        }

        firstStep()
        e2e.dsl().update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).execute()
        e2e.press(page.locator("main").buttonNamed("Controlla e accetta"))
        assertThat(page.locator("h1")).hasText("Le iscrizioni sono chiuse")
        assertThat(page.locator("main")).containsText("Mentre compilavi, le iscrizioni si sono chiuse: non abbiamo salvato niente.")
        assertAccessible(page, "first step sent after the closing (${e2e.profile.label})")
        // The page of the step is not there for whoever opens it afterwards: the information page is.
        e2e.open("/info/$code/iscrizione")
        assertThat(page).hasURL(e2e.url("/info/$code"))

        e2e.dsl().update(GATHERING).set(GATHERING.STATUS, GatheringStatus.`open`).execute()
        firstStep()
        e2e.dsl().update(GATHERING).set(GATHERING.START_DATE, e2e.today().minusDays(5)).set(GATHERING.END_DATE, e2e.today().minusDays(3)).execute()
        e2e.press(page.locator("main").buttonNamed("Controlla e accetta"))
        assertThat(page.locator("h1")).hasText("La convivenza è finita")
        assertThat(page.locator("main")).containsText("La convivenza è finita: non abbiamo salvato niente. Per la prossima arriverà un link nuovo.")
        assertEquals(0, e2e.dsl().fetchCount(REGISTRATION))
    }

    @E2eTest(teacher = false)
    fun `c10 a double tap on the confirmation makes one registration and the person ends on the registrations`(e2e: E2e) {
        val page = e2e.page
        val code = e2e.openGathering().code
        page.toStepTwo(e2e, code)
        page.tickTheThreeBoxes()

        // Two touches before the answer comes: the button is disabled by the first, and if both get to the server there is still one registration.
        page.evaluate(
            """() => {
                const button = Array.from(document.querySelectorAll('button')).find(b => b.textContent.includes('Accetta e iscrivi'));
                button.click();
                button.click();
                button.form.requestSubmit();
            }""",
        )

        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertThat(page.locator("[role=status]")).containsText("Iscrizione fatta.")
        assertThat(page.locator("article")).hasCount(1)
        assertEquals(1, e2e.dsl().fetchCount(REGISTRATION))
        assertEquals(1, e2e.dsl().fetchCount(CHILD))
    }

    @E2eTest(teacher = false)
    fun `c02 the children of an earlier gathering are offered again, one tap fills the first block, and it is still the same child`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.openGathering()
        val me = e2e.accountId(checkNotNull(e2e.signedInAs))
        val earlier = GatheringSupport(e2e.dsl(), e2e.application.getBean(Clock::class.java))
            .make(title = "Convivenza dell’anno scorso", start = e2e.today().minusDays(100), end = e2e.today().minusDays(98), status = GatheringStatus.closed)
        e2e.rows().register(earlier, me, "Luca", "Rossi", phone = "333 000 0000")

        e2e.open("/info/${gathering.code}/iscrizione")
        page.settled()

        assertThat(page.locator("main h2:has-text('I tuoi figli')")).isVisible()
        assertThat(page.locator("main")).containsText("Hai già iscritto questi bambini a una convivenza. Tocca «Aggiungi» per ritrovare i loro dati: potrai correggerli.")
        assertThat(page.locator("main")).containsText("Luca Rossi")
        assertThat(page.locator("main")).containsText("Data di nascita 4 aprile 2016")
        assertEquals("", page.locator("#telefono").inputValue(), "the phone is not taken from the other gathering")
        assertAccessible(page, "first step with the children of before (${e2e.profile.label})")

        e2e.press(page.locator("main").buttonNamed("Aggiungi Luca Rossi"))
        assertThat(page.locator("#b0-nome")).hasValue("Luca")
        assertThat(page.locator("#b0-cognome")).hasValue("Rossi")
        assertThat(page.locator("#b0-giorno")).hasValue("4")
        assertThat(page.locator("#b0-mese")).hasValue("4")
        assertThat(page.locator("#b0-anno")).hasValue("2016")
        assertThat(page.locator("input[name='children[0].presence']:checked")).hasCount(0)
        assertThat(page.locator("main").buttonNamed("Aggiungi Luca Rossi")).hasCount(0)
        assertThat(page.locator("main h2:has-text('Bambino')")).hasCount(1)
        assertAccessible(page, "first step, a child of before added (${e2e.profile.label})")

        page.locator("#telefono").fill("333 123 4567")
        page.choose("children[0].presence", "all_days")
        e2e.press(page.locator("main").buttonNamed("Controlla e accetta"))
        page.tickTheThreeBoxes()
        e2e.press(page.locator("main").buttonNamed("Accetta e iscrivi"))

        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertEquals(1, e2e.dsl().fetchCount(CHILD), "the child is the same, not a new one")
        assertEquals(2, e2e.dsl().fetchCount(REGISTRATION))
        assertThat(page.locator("main h2").filter(Locator.FilterOptions().setHasText("Convivenza").setHasNotText("Avviso per la prossima"))).hasText(arrayOf("Convivenza di settembre 2027", "Convivenza dell’anno scorso"))
    }

    @E2eTest(teacher = false)
    fun `c03 c18 every wrong value of the first step has its own words under its field, the alert counts them, and what was written stays`(e2e: E2e) {
        val page = e2e.page
        val code = e2e.openGathering().code
        e2e.open("/info/$code/iscrizione")
        page.settled()

        page.locator("#telefono").fill("12")
        page.fillChild(0, "A".repeat(101), "", "31", "2", "2016")
        e2e.press(page.locator("summary:has-text('Allergie, intolleranze, farmaci')"))
        page.locator("#b0-allergie").fill("x".repeat(1001))
        page.locator("#b0-consenso").check()
        e2e.press(page.locator("main").buttonNamed("Aggiungi un figlio"))
        page.fillChild(1, "Zoe", "Neri", "1", "1", "2099")
        page.choose("children[1].presence", "days")

        val response = page.waitForResponse({ it.request().method() == "POST" && it.url().endsWith("/iscrizione") }) {
            e2e.press(page.locator("main").buttonNamed("Controlla e accetta"))
        }

        // The answer has arrived, and htmx has not yet put the page of the errors in place: the next reads (the number of errors, the focus) are of the page that is there when it has.
        page.waitForHtmxIdle()
        assertEquals(422, response.status())
        assertThat(page).hasURL(e2e.url("/info/$code/iscrizione"))
        assertThat(page.locator("#telefono-errore")).hasText("Scrivi un numero di telefono completo, per esempio 333 123 4567.")
        assertThat(page.locator("#b0-nome-errore")).hasText("Usa al massimo 100 caratteri.")
        assertThat(page.locator("#b0-cognome-errore")).hasText("Scrivi il cognome.")
        assertThat(page.locator("#b0-nascita-errore")).hasText("Scrivi una data vera, per esempio 4 4 2016.")
        assertThat(page.locator("#b0-quando-errore")).hasText("Scegli quando viene.")
        assertThat(page.locator("#b0-allergie-errore")).hasText("Usa al massimo 1.000 caratteri: ne hai scritti 1.001.")
        assertThat(page.locator("#b1-nascita-errore")).hasText("La data di nascita non può essere nel futuro.")
        assertThat(page.locator("#b1-quando-errore")).hasText("Scegli almeno un giorno.")
        val errors = page.locator("[id$='-errore']").count()
        assertThat(page.locator("main [role=alert]")).hasText("Controlla i $errors campi segnati in rosso.")
        page.focusIs("document.activeElement.id === 'telefono'")
        assertEquals("12", page.locator("#telefono").inputValue())
        assertEquals("A".repeat(101), page.locator("#b0-nome").inputValue())
        assertEquals("Zoe", page.locator("#b1-nome").inputValue())
        assertThat(page.locator("#b0-consenso")).isChecked()
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "first step with every kind of error (${e2e.profile.label})")
    }

    @E2eTest(teacher = false)
    fun `c03 a child who is 18 on the first day is refused, one who is 18 the day after is registered`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.openGathering()
        e2e.open("/info/${gathering.code}/iscrizione")
        page.settled()
        page.locator("#telefono").fill("333 123 4567")
        page.choose("children[0].presence", "all_days")
        val eighteen = gathering.friday.minusYears(18)
        page.fillChild(0, "Marco", "Verdi", eighteen.dayOfMonth.toString(), eighteen.monthValue.toString(), eighteen.year.toString())

        e2e.press(page.locator("main").buttonNamed("Controlla e accetta"))
        assertThat(page.locator("#b0-nascita-errore")).hasText("Controlla la data: il bambino ha già 18 anni il primo giorno della convivenza.")

        val almost = eighteen.plusDays(1)
        page.fillChild(0, "Marco", "Verdi", almost.dayOfMonth.toString(), almost.monthValue.toString(), almost.year.toString())
        e2e.press(page.locator("main").buttonNamed("Controlla e accetta"))
        assertThat(page.locator("h1")).hasText("Controlla e accetta")
        assertThat(page.locator("main")).containsText("Marco Verdi")
    }

    @E2eTest(teacher = false)
    fun `c07 when the session ends while step 2 is open the person is taken to the access page, nothing is saved, and the first step starts again empty`(e2e: E2e) {
        val page = e2e.page
        val code = e2e.openGathering().code
        page.toStepTwo(e2e, code)
        page.tickTheThreeBoxes()
        page.request().get(e2e.url("/test/sessione-breve"))
        page.waitForTimeout(1500.0)

        e2e.press(page.locator("main").buttonNamed("Accetta e iscrivi"))

        assertThat(page).hasURL(e2e.url("/accedi"))
        assertThat(page.locator("h1")).hasText("Accedi a Talità")
        assertEquals(0, e2e.dsl().fetchCount(REGISTRATION))

        e2e.signIn(teacher = false)
        e2e.open("/info/$code/iscrizione")
        page.settled()
        assertThat(page.locator("main")).containsText("Passo 1 di 2 · I dati")
        assertEquals("", page.locator("#b0-nome").inputValue())
        assertEquals("", page.locator("#telefono").inputValue())
    }

    @E2eTest(teacher = false)
    fun `c06 the back button of the phone leaves the registration from step 2, as the page says`(e2e: E2e) {
        val page = e2e.page
        val code = e2e.openGathering().code
        e2e.open("/info/$code")
        page.settled()
        e2e.touch(page.locator("main").linkNamed("Iscrivi i tuoi figli"))
        assertThat(page).hasURL(e2e.url("/info/$code/iscrizione"))
        page.settled()
        page.locator("#telefono").fill("333 123 4567")
        page.fillChild(0, "Luca", "Rossi", "4", "4", "2016")
        page.choose("children[0].presence", "all_days")
        e2e.press(page.locator("main").buttonNamed("Controlla e accetta"))
        assertThat(page.locator("h1")).hasText("Controlla e accetta")
        assertThat(page.locator("main")).containsText("il tasto indietro del telefono esce dall’iscrizione e quello che hai scritto si perde")

        page.goBack()

        assertThat(page).hasURL(e2e.url("/info/$code"))
        assertThat(page.locator("main").linkNamed("Iscrivi i tuoi figli")).isVisible()
        assertEquals(0, e2e.dsl().fetchCount(REGISTRATION))
    }

    @E2eTest(teacher = false)
    fun `c05 the request that updates the quota does not carry the notes on health, nor the name, the date of birth and the phone`(e2e: E2e) {
        val page = e2e.page
        val code = e2e.openGathering().code
        val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        page.onRequest { if (it.method() == "POST" && it.url().endsWith("/iscrizione/quota")) bodies += it.postData().orEmpty() }
        e2e.open("/info/$code/iscrizione")
        page.settled()
        page.locator("#telefono").fill("333 999 0000")
        page.fillChild(0, "Luca", "Rossi", "4", "4", "2016")
        e2e.press(page.locator("summary:has-text('Allergie, intolleranze, farmaci')"))
        page.locator("#b0-allergie").fill("SEGRETO-ALLERGIA")
        page.locator("#b0-farmaci").fill("SEGRETO-FARMACO")

        page.choose("children[0].presence", "all_days")
        assertThat(page.locator("#quota-0")).hasText(Pattern.compile("Quota\\s*60\\s*€"))

        assertTrue(bodies.isNotEmpty(), "the page asked the server for the quota")
        for (body in bodies) {
            assertTrue(!body.contains("SEGRETO") && !body.contains("allergies") && !body.contains("medications"), "the request carries the notes: $body")
            assertTrue(listOf("firstName", "lastName", "birth", "phone", "status", "Luca", "Rossi", "333+999").none { body.contains(it) }, "the request carries the child or the phone: $body")
            assertTrue(body.contains("presence=all_days") && body.contains("_csrf="), "the request carries what the quota needs: $body")
        }
    }
}
