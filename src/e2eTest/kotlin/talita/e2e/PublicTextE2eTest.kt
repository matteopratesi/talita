package talita.e2e

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import talita.account.TestAccounts
import talita.gathering.GatheringSupport
import talita.gathering.PublicText
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.TEACHER_INVITATION
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The four texts that anyone with the link can read, in a real browser on a phone and on a desktop, with axe (TALITA-16, criteria 10-12 and 16): the help under each field, the
 * pages that do not scroll sideways at 360 and at 320 px, and the error of the name under the field, with the text as it was written and the focus on the wrong field.
 * Names are synthetic.
 */
@E2eApplication
class PublicTextE2eTest {

    private fun E2e.gatherings(): GatheringSupport = GatheringSupport(dsl(), application.getBean(Clock::class.java))

    /** The person of the test is the only teacher («Elisa Conti») besides Marta Bianchi, with the child Luca Rossi, and there is no gathering. Returns the id of the account of the person. */
    private fun E2e.onlyMe(): Long {
        dsl().deleteFrom(TEACHER_INVITATION).execute()
        gatherings().reset()
        dsl().deleteFrom(TEACHER).execute()
        val me = TestAccounts.makeTeacher(dsl(), checkNotNull(signedInAs), "Elisa", "Conti")
        TestAccounts.makeTeacher(dsl(), talita.Addresses.unique("e2e-t16-marta"), "Marta", "Bianchi")
        dsl().insertInto(CHILD).set(CHILD.FIRST_NAME, "Luca").set(CHILD.LAST_NAME, "Rossi").set(CHILD.BIRTH_DATE, LocalDate.of(2016, 4, 4)).execute()
        return me
    }

    /** The page looks as it must at 360 (or 1280) and at 320 px: no scrolling sideways, and axe finds nothing serious. */
    private fun assertPageFits(e2e: E2e, label: String) {
        val page = e2e.page
        assertTrue(page.noHorizontalScroll(), "$label: the page scrolls sideways at ${page.viewport().width} px")
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "$label: the page scrolls sideways at 320 px")
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "$label (${e2e.profile.label})")
    }

    @E2eTest
    fun `t16 c10 c11 the four helps are under their fields and tied to them, and the pages do not scroll sideways at 360 or 320 px and have no violation of axe`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.onlyMe()

        // «Nuova convivenza».
        e2e.open("/convivenza/nuova")
        assertThat(page.locator("#titolo-aiuto")).hasText(PublicText.TITLE_HELP)
        assertThat(page.locator("#titolo")).hasAttribute("aria-describedby", "titolo-aiuto")
        page.settled()
        assertPageFits(e2e, "Nuova convivenza")

        // «Modifica titolo, date e luogo», from the draft and from the gathering that is over.
        val draft = e2e.gatherings().make(status = GatheringStatus.draft, custodians = listOf(me))
        e2e.open("/convivenza/$draft/date")
        assertThat(page.locator("#titolo-aiuto")).hasText(PublicText.TITLE_HELP)
        assertThat(page.locator("#luogo-aiuto")).hasText(PublicText.PLACE_HELP)
        assertThat(page.locator("#luogo")).hasAttribute("aria-describedby", "luogo-aiuto")
        page.settled()
        assertPageFits(e2e, "Modifica titolo, date e luogo")

        e2e.dsl().update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).set(GATHERING.START_DATE, e2e.today().minusDays(5)).set(GATHERING.END_DATE, e2e.today().minusDays(3)).execute()
        e2e.open("/convivenza/$draft/date")
        assertThat(page.locator("#titolo-aiuto")).hasText(PublicText.TITLE_HELP)
        assertThat(page.locator("#luogo-aiuto")).hasText(PublicText.PLACE_HELP)
        page.settled()
        assertPageFits(e2e, "Modifica titolo, date e luogo, convivenza finita")

        // «Modifica le informazioni».
        e2e.open("/convivenza/$draft/informazioni")
        assertThat(page.locator("#informazioni-aiuto")).hasText(PublicText.INFO_HELP)
        assertThat(page.locator("#cosa-portare-aiuto")).hasText(PublicText.BRING_HELP)
        assertThat(page.locator("#cosa-portare")).hasAttribute("aria-describedby", "cosa-portare-aiuto")
        page.settled()
        assertPageFits(e2e, "Modifica le informazioni")
    }

    @E2eTest
    fun `t16 c11 c12 c16 a name in the title is refused under the field with the text as it was written and the focus on the field, and the page is accessible with the error`(e2e: E2e) {
        val page = e2e.page
        e2e.onlyMe()
        val start = e2e.gatherings().today().plusDays(30)

        e2e.open("/convivenza/nuova")
        page.locator("#titolo").fill("Convivenza con Luca Rossi")
        page.locator("#primo-giorno").fill(start.toString())
        page.locator("#ultimo-giorno").fill(start.plusDays(2).toString())
        e2e.press(page.buttonNamed("Crea la convivenza"))

        assertThat(page.locator("#titolo-errore")).hasText("Qui ci sono nome e cognome di una persona. Toglili: la pagina la legge chiunque abbia il link.")
        assertThat(page.locator("#titolo")).hasValue("Convivenza con Luca Rossi")
        assertThat(page.locator("#titolo")).hasAttribute("aria-invalid", "true")
        assertThat(page.locator("#titolo")).hasAttribute("aria-describedby", "titolo-aiuto titolo-errore")
        assertThat(page.locator("main [role=alert]")).hasCount(0)
        page.focusIs("document.activeElement.id === 'titolo'")
        assertEquals(0, e2e.dsl().fetchCount(GATHERING), "nothing was saved")
        assertPageFits(e2e, "Nuova convivenza with the name in the title")

        // Written another way, the same form goes through.
        page.locator("#titolo").fill("Convivenza di settembre 2027")
        e2e.press(page.buttonNamed("Crea la convivenza"))
        assertThat(page.locator("h1")).hasText("Convivenza di settembre 2027")
        assertEquals(1, e2e.dsl().fetchCount(GATHERING))
    }

    @E2eTest
    fun `t16 c11 c15 the information with a name and a phone number has one error for the field with everything in it, two errors have the notice above and the focus on the first`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.onlyMe()
        val id = e2e.gatherings().make(status = GatheringStatus.draft, custodians = listOf(me))

        e2e.open("/convivenza/$id/informazioni")
        page.locator("#informazioni").fill("Chiedete a Luca Rossi, 333 123 4567.")
        e2e.press(page.buttonNamed("Salva le modifiche"))

        assertThat(page.locator("#informazioni-errore")).hasText("Qui ci sono nome e cognome di una persona e un numero di telefono. Toglili: la pagina la legge chiunque abbia il link.")
        assertThat(page.locator("#informazioni")).hasValue("Chiedete a Luca Rossi, 333 123 4567.")
        assertThat(page.locator("main [role=alert]")).hasCount(0)
        page.focusIs("document.activeElement.id === 'informazioni'")
        assertPageFits(e2e, "Modifica le informazioni with the name and a phone number")

        // Two fields wrong: the notice above, and the focus goes to the first wrong one.
        page.locator("#cosa-portare").fill("Il pallone di Luca Rossi")
        e2e.press(page.buttonNamed("Salva le modifiche"))
        assertThat(page.locator("main [role=alert]")).hasText("Controlla i 2 campi segnati in rosso.")
        assertThat(page.locator("#cosa-portare-errore")).hasText("Qui ci sono nome e cognome di una persona. Toglili: la pagina la legge chiunque abbia il link.")
        page.focusIs("document.activeElement.id === 'informazioni'")
        assertPageFits(e2e, "Modifica le informazioni with two errors")
    }
}
