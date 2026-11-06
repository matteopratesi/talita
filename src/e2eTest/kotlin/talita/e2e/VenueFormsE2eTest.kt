package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.VENUE_FORM
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The forms of a venue in a real browser, on a phone and on a desktop, with axe (TALITA-20, part 3, criterion 20): the card «Moduli» in the page of the venue, «Nuovo modulo» with its error and its four boxes (never ticked),
 * «Modifica il modulo», and «Togli il modulo» with its dialog. No page scrolls sideways at 320 px. Names and venues are synthetic.
 */
@E2eApplication
class VenueFormsE2eTest {

    private fun E2e.venueOf(gathering: Long): Long = dsl().select(GATHERING.VENUE_ID).from(GATHERING).where(GATHERING.ID.eq(gathering)).fetchSingle(GATHERING.VENUE_ID)!!

    private fun Page.openDialog(): Locator = locator("dialog[open]")

    private fun Page.waitForSettledDialog() {
        waitUntil("document.querySelector('dialog[open]') && document.querySelector('dialog[open]').getAnimations().every(a => a.playState === 'finished')")
    }

    private fun Page.sideways(e2e: E2e, what: String) {
        assertTrue(noHorizontalScroll(), "$what: no sideways scrolling")
        setViewportSize(320, 800)
        assertTrue(noHorizontalScroll(), "$what: no sideways scrolling at 320 px")
        setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
    }

    private fun E2e.rows(venue: Long): List<List<Any?>> =
        dsl().select(VENUE_FORM.NAME, VENUE_FORM.WITH_AGE, VENUE_FORM.WITH_DAYS, VENUE_FORM.WITH_TEACHER_DOCUMENT, VENUE_FORM.WITH_TEACHER_TAX_CODE).from(VENUE_FORM).where(VENUE_FORM.VENUE_ID.eq(venue)).orderBy(VENUE_FORM.ID)
            .fetch().map { it.intoList() }

    @E2eTest
    fun `t20 c20 the teacher writes a form in the page of the venue, with the error under the name, changes it and takes it away with the dialog`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val venue = e2e.venueOf(w.id)
        e2e.open("/strutture/$venue")

        // The empty card, and the way to the form.
        assertThat(page.locator("#moduli-struttura")).containsText("Nessun modulo.")
        assertThat(page.locator("#moduli-struttura")).containsText("Se la struttura chiede un registro dei presenti, scrivilo qui: lo stampi da Arrivi.")
        page.sideways(e2e, "the venue with no form")
        assertAccessible(page, "venue with no form (${e2e.profile.label})")
        e2e.press(page.locator("#moduli-struttura").linkNamed("Aggiungi un modulo"))
        assertThat(page).hasURL(e2e.url("/strutture/$venue/moduli/nuovo"))
        assertThat(page.locator("h1")).hasText("Nuovo modulo")
        page.focusIs("document.activeElement.tagName === 'H1'")
        for (box in listOf("Età al primo giorno", "Giorni di presenza", "Documento d’identità", "Codice fiscale")) assertThat(page.getByLabel(box)).not().isChecked()
        page.sideways(e2e, "new form")
        assertAccessible(page, "new form (${e2e.profile.label})")

        // The error is under the name, the focus is on it, and the boxes stay as they were ticked.
        page.getByLabel("Giorni di presenza").check()
        e2e.press(page.locator("main").buttonNamed("Salva il modulo"))
        assertThat(page.locator("#nome-modulo-errore")).hasText("Scrivi il nome del modulo.")
        page.focusIs("document.activeElement.id === 'nome-modulo'")
        assertThat(page.getByLabel("Giorni di presenza")).isChecked()
        assertAccessible(page, "new form with the error (${e2e.profile.label})")
        page.getByLabel("Nome del modulo").fill("Registro per la tassa di soggiorno")
        page.getByLabel("Età al primo giorno").check()
        page.getByLabel("Codice fiscale").check()
        e2e.press(page.locator("main").buttonNamed("Salva il modulo"))
        assertThat(page).hasURL(Pattern.compile(".*/strutture/$venue(#moduli)?$"))
        assertThat(page.locator("#moduli-struttura")).containsText("Modulo salvato.")
        assertThat(page.locator("#moduli-struttura")).containsText("Registro per la tassa di soggiorno")
        assertThat(page.locator("#moduli-struttura")).containsText("Nome e cognome, età al primo giorno e giorni di presenza. Maestri: codice fiscale.")
        assertEquals(listOf(listOf("Registro per la tassa di soggiorno", true, true, false, true)), e2e.rows(venue))
        page.sideways(e2e, "the venue with a form")
        assertAccessible(page, "venue with a form (${e2e.profile.label})")

        // The change: the boxes are as they are now.
        e2e.press(page.locator("#moduli-struttura").linkNamed("Modifica Registro per la tassa di soggiorno"))
        assertThat(page.locator("h1")).hasText("Modifica il modulo")
        assertThat(page.getByLabel("Nome del modulo")).hasValue("Registro per la tassa di soggiorno")
        assertThat(page.getByLabel("Età al primo giorno")).isChecked()
        assertThat(page.getByLabel("Documento d’identità")).not().isChecked()
        assertAccessible(page, "change a form (${e2e.profile.label})")
        page.getByLabel("Documento d’identità").check()
        page.getByLabel("Codice fiscale").uncheck()
        e2e.press(page.locator("main").buttonNamed("Salva il modulo"))
        assertThat(page.locator("#moduli-struttura")).containsText("Maestri: documento.")
        assertEquals(listOf(listOf("Registro per la tassa di soggiorno", true, true, true, false)), e2e.rows(venue))

        // «Togli il modulo»: the dialog, with the words of the design, and the block that answers.
        e2e.press(page.locator("#moduli-struttura").linkNamed("Togli Registro per la tassa di soggiorno"))
        page.waitForSettledDialog()
        val dialog = page.openDialog()
        assertThat(dialog.locator("h2")).hasText("Togliere «Registro per la tassa di soggiorno»?")
        assertThat(dialog).containsText("Il modulo sparisce dalla struttura e non si stampa più da Arrivi. I fogli già stampati restano a chi li ha. Potrai riscriverlo quando vuoi.")
        assertAccessible(page, "dialog to take a form away (${e2e.profile.label})")
        e2e.touch(dialog.buttonNamed("Togli il modulo"))
        assertThat(page.locator("#moduli-struttura")).containsText("Modulo tolto.")
        assertThat(page.locator("#esito")).hasText("Modulo tolto.")
        assertThat(page.locator("#moduli-struttura")).containsText("Nessun modulo.")
        assertEquals(emptyList(), e2e.rows(venue))
    }
}
