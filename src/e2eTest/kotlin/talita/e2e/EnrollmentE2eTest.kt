package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.REGISTRATION
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
}
