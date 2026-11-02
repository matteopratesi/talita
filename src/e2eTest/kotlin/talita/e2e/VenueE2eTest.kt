package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import org.jooq.DSLContext
import talita.account.TestAccounts
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.TEACHER_INVITATION
import talita.jooq.tables.references.VENUE
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The venues (TALITA-20, part 1) in a real browser, on a phone and on a desktop, with axe: the list and its empty state, the way in from «Convivenza», the form with its errors and the focus, the
 * notice that says how to use a new venue, the dialog of «Togli la struttura», the choice of the venue in «Modifica titolo, date e luogo» and the page for the parents with the name and the place; and no
 * page scrolls sideways at 360 (or 1280) and at 320 px, with a name and a place as long as they can be. Names, places and numbers are synthetic.
 */
@E2eApplication
class VenueE2eTest {

    private fun E2e.dsl(): DSLContext = application.getBean(DSLContext::class.java)

    private fun E2e.gatherings(): GatheringSupport = GatheringSupport(dsl(), application.getBean(Clock::class.java))

    /** Only the person of the test is a teacher («Elisa Conti»), with Marta Bianchi, the child Luca Rossi, and no gathering and no venue. Returns the id of the account of the person. */
    private fun E2e.onlyMe(): Long {
        dsl().deleteFrom(TEACHER_INVITATION).execute()
        gatherings().reset()
        dsl().deleteFrom(TEACHER).execute()
        val me = TestAccounts.makeTeacher(dsl(), checkNotNull(signedInAs), "Elisa", "Conti")
        TestAccounts.makeTeacher(dsl(), talita.Addresses.unique("e2e-t20-marta"), "Marta", "Bianchi")
        dsl().insertInto(CHILD).set(CHILD.FIRST_NAME, "Luca").set(CHILD.LAST_NAME, "Rossi").set(CHILD.BIRTH_DATE, LocalDate.of(2016, 4, 4)).execute()
        return me
    }

    private fun E2e.press(target: Locator) {
        target.evaluate("e => e.scrollIntoView({ block: 'center' })")
        touch(target)
    }

    private fun Locator.buttonNamed(name: String): Locator = getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName(name))

    private fun Locator.linkNamed(name: String): Locator = getByRole(AriaRole.LINK, Locator.GetByRoleOptions().setName(name))

    private fun Page.settled() {
        waitForLoadState()
        waitForSettledContent()
    }

    private fun Page.noHorizontalScroll() = (evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number).toInt() <= 0

    private fun Page.openDialog(): Locator = locator("dialog[open]")

    private fun Page.waitForSettledDialog() {
        waitUntil("document.querySelector('dialog[open]') && document.querySelector('dialog[open]').getAnimations().every(a => a.playState === 'finished')")
    }

    /** The page looks as it must at 360 (or 1280) and at 320 px: no scrolling sideways, and axe finds nothing serious. */
    private fun assertPageFits(e2e: E2e, label: String) {
        val page = e2e.page
        page.settled()
        assertTrue(page.noHorizontalScroll(), "$label: the page scrolls sideways at ${page.viewport().width} px")
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "$label: the page scrolls sideways at 320 px")
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "$label (${e2e.profile.label})")
    }

    private val woodPlace = "Casa per ritiri del bosco, Località Esempio"

    // ---- The list and the way in (criterion 1) ---------------------------------------------------------------------------------

    @E2eTest
    fun `t20 c01 the section Convivenza leads to Strutture, which shows the empty state and then each venue with its place and its use, and is accessible`(e2e: E2e) {
        val page = e2e.page
        e2e.onlyMe()

        e2e.open("/convivenza")
        assertThat(page.locator("main h2").filter(Locator.FilterOptions().setHasText("Strutture"))).isVisible()
        e2e.press(page.locator("main").linkNamed("Strutture Le case dove si fa la convivenza, con luogo, camere e moduli."))

        assertThat(page).hasURL(e2e.url("/strutture"))
        assertThat(page.locator("h1")).hasText("Strutture")
        assertThat(page.locator("main")).containsText("Le case dove si fa la convivenza. Le vedono solo i maestri.")
        assertThat(page.locator("main h2")).hasText("Nessuna struttura")
        assertThat(page.locator("main")).containsText("Scrivi la casa dove fai la convivenza. Poi la scegli nella convivenza.")
        assertThat(page.mainNavigation().locator("a[aria-current=page]")).hasText("Convivenza")
        assertPageFits(e2e, "Strutture, empty")

        val wood = e2e.gatherings().makeVenue(woodPlace, "Casa del bosco")
        val lake = e2e.gatherings().makeVenue("Casa per ritiri sul lago, Località Esempio", "Casa sul lago")
        e2e.gatherings().make(status = GatheringStatus.draft, venue = wood)
        e2e.open("/strutture")
        val rows = page.locator("main ul li")
        assertThat(rows).hasCount(2)
        assertThat(rows.nth(0)).containsText("Casa del bosco")
        assertThat(rows.nth(0)).containsText(woodPlace)
        assertThat(rows.nth(0)).containsText("Usata da 1 convivenza")
        assertThat(rows.nth(1)).containsText("Nessuna convivenza la usa")
        assertPageFits(e2e, "Strutture, list")

        e2e.press(rows.nth(1).locator("a"))
        assertThat(page).hasURL(e2e.url("/strutture/$lake"))
        assertThat(page.locator("h1")).hasText("Casa sul lago")
    }

    // ---- «Nuova struttura» (criteria 2 and 3) -----------------------------------------------------------------------------------

    @E2eTest
    fun `t20 c02 c03 a venue is written with its errors under the fields and the focus on the first wrong one, a name of a person is refused, and the saved venue says how to use it`(e2e: E2e) {
        val page = e2e.page
        e2e.onlyMe()
        val gathering = e2e.gatherings().make(status = GatheringStatus.draft, place = null, full = null)

        e2e.open("/strutture/nuova")
        assertThat(page.locator("h1")).hasText("Nuova struttura")
        assertThat(page.locator("#nome-aiuto")).containsText("Compare nella pagina per i genitori e nell’anteprima del link.")
        assertThat(page.locator("#luogo-aiuto")).containsText("e nel testo dell’affidamento.")
        assertPageFits(e2e, "Nuova struttura")

        // Two empty fields: the notice above and the focus on the first.
        e2e.press(page.buttonNamed("Salva la struttura"))
        assertThat(page.locator("main [role=alert]")).hasText("Controlla i 2 campi segnati in rosso.")
        assertThat(page.locator("#nome-errore")).hasText("Scrivi il nome della struttura.")
        assertThat(page.locator("#luogo-errore")).hasText("Scrivi il luogo: la pagina per i genitori lo mostra.")
        page.focusIs("document.activeElement.id === 'nome'")
        assertEquals(0, e2e.dsl().fetchCount(VENUE), "nothing was saved")
        assertPageFits(e2e, "Nuova struttura, two errors")

        // The name of a child in the name, and a phone in the place: the sentences of the public texts, with the text as it was written.
        page.locator("#nome").fill("Casa di Luca Rossi")
        page.locator("#luogo").fill("Casa per ritiri, tel. 333 1234567")
        e2e.press(page.buttonNamed("Salva la struttura"))
        assertThat(page.locator("#nome-errore")).hasText("Qui ci sono nome e cognome di una persona. Toglili: la pagina la legge chiunque abbia il link.")
        assertThat(page.locator("#luogo-errore")).hasText("Qui c’è un numero di telefono. Toglilo: la pagina la legge chiunque abbia il link.")
        assertThat(page.locator("#nome")).hasValue("Casa di Luca Rossi")
        assertThat(page.locator("#nome")).hasAttribute("aria-invalid", "true")
        assertEquals(0, e2e.dsl().fetchCount(VENUE))

        page.locator("#nome").fill("Casa del bosco")
        page.locator("#luogo").fill(woodPlace)
        e2e.press(page.buttonNamed("Salva la struttura"))

        assertThat(page.locator("h1")).hasText("Casa del bosco")
        val notice = page.locator("main [role=status][data-fuoco]")
        assertThat(notice).containsText("Struttura salvata.")
        assertThat(notice).containsText("Per usarla, scegli «Casa del bosco» in «Modifica titolo, date e luogo» della convivenza.")
        page.focusIs("document.activeElement.getAttribute('role') === 'status'")
        assertEquals(listOf("Casa del bosco" to woodPlace), e2e.dsl().select(VENUE.NAME, VENUE.PLACE).from(VENUE).fetch { it[VENUE.NAME]!! to it[VENUE.PLACE]!! })
        assertPageFits(e2e, "Struttura, saved")

        // «Vai alla convivenza» leads to the gathering, and from there the venue is chosen.
        e2e.press(page.locator("main [role=status]").linkNamed("Vai alla convivenza"))
        assertThat(page).hasURL(e2e.url("/convivenza/$gathering"))
    }

    // ---- «Togli la struttura» (criterion 6) --------------------------------------------------------------------------------------

    @E2eTest
    fun `t20 c06 a venue that no gathering uses is taken away after the dialog, which opens at its top and goes away with Annulla, and one that is used has no button and says why`(e2e: E2e) {
        val page = e2e.page
        e2e.onlyMe()
        val lake = e2e.gatherings().makeVenue("Casa per ritiri sul lago, Località Esempio", "Casa sul lago")
        val wood = e2e.gatherings().makeVenue(woodPlace, "Casa del bosco")
        e2e.gatherings().make(status = GatheringStatus.draft, venue = wood)

        // The used one: no button, the reason.
        e2e.open("/strutture/$wood")
        assertThat(page.locator("main")).containsText("Questa struttura è usata da 1 convivenza, quindi non si toglie. Puoi cambiarle il nome e il luogo.")
        assertThat(page.locator("main").linkNamed("Togli la struttura")).hasCount(0)
        assertPageFits(e2e, "Struttura used")

        // The free one: the button opens a dialog of danger and takes nothing away by itself.
        e2e.open("/strutture/$lake")
        assertThat(page.locator("main")).containsText("Nessuna convivenza la usa.")
        e2e.press(page.locator("main").linkNamed("Togli la struttura"))
        val dialog = page.openDialog()
        assertThat(dialog).isVisible()
        assertThat(dialog.locator("h2")).hasText("Togliere Casa sul lago?")
        assertThat(dialog).containsText("Casa sul lago sparisce dall’elenco delle strutture. Nessuna convivenza la usa. Potrai scriverla di nuovo quando vuoi.")
        page.focusIs("document.activeElement.textContent.trim() === 'Annulla'")
        page.waitForSettledDialog()
        assertAccessible(page, "dialog Togli la struttura (${e2e.profile.label})")
        assertEquals(2, e2e.dsl().fetchCount(VENUE), "nothing was taken away")
        page.keyboard().press("Escape")
        assertThat(page.openDialog()).hasCount(0)
        e2e.press(page.locator("main").linkNamed("Togli la struttura"))
        e2e.touch(page.openDialog().buttonNamed("Annulla"))
        assertThat(page.openDialog()).hasCount(0)
        assertEquals(2, e2e.dsl().fetchCount(VENUE))

        e2e.press(page.locator("main").linkNamed("Togli la struttura"))
        e2e.touch(page.openDialog().buttonNamed("Togli la struttura"))

        assertThat(page).hasURL(e2e.url("/strutture"))
        assertThat(page.locator("main [role=status][data-fuoco]")).hasText("Struttura tolta.")
        assertThat(page.locator("main ul li")).hasCount(1)
        assertEquals(listOf(wood), e2e.dsl().select(VENUE.ID).from(VENUE).fetch(VENUE.ID).map { it!! })
    }

    // ---- «Modifica la struttura» and what the parents read (criteria 4 and 10) ------------------------------------------------------------

    @E2eTest
    fun `t20 c04 c10 the name and the place that change are read at once by the parents, in the page and in the preview of the link`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.onlyMe()
        val wood = e2e.gatherings().makeVenue(woodPlace, "Casa del bosco")
        val start = e2e.gatherings().today().plusDays(30)
        e2e.gatherings().make(status = GatheringStatus.`open`, venue = wood, start = start, end = start.plusDays(2), custodians = listOf(me), code = "q7Vd_3KmZ0xRb-Ew")

        e2e.open("/strutture/$wood/modifica")
        assertThat(page.locator("main")).containsText("Questa struttura è usata da 1 convivenza. Se cambi il nome o il luogo, lo leggono subito i genitori. Le iscrizioni già fatte restano con il testo di allora.")
        assertPageFits(e2e, "Modifica la struttura, used")
        page.locator("#nome").fill("Casa del bosco grande")
        page.locator("#luogo").fill("Casa per ritiri sul lago, Località Esempio")
        e2e.press(page.buttonNamed("Salva la struttura"))

        val notice = page.locator("main [role=status][data-fuoco]")
        assertThat(notice).containsText("Struttura salvata.")
        assertThat(notice).containsText("Nome e luogo nuovi sono già nella pagina per i genitori. Le iscrizioni già fatte restano con il testo di allora.")

        e2e.open("/info/q7Vd_3KmZ0xRb-Ew")
        assertThat(page.locator("section[aria-labelledby=quando]")).containsText("Casa del bosco grande")
        assertThat(page.locator("section[aria-labelledby=quando]")).containsText("Casa per ritiri sul lago, Località Esempio")
        assertPageFits(e2e, "Pagina per i genitori with the venue")
    }

    // ---- The choice in «Modifica titolo, date e luogo» (criteria 8 and 9) ---------------------------------------------------------------

    @E2eTest
    fun `t20 c08 c09 the venue is chosen in the form of the gathering, the error is under the select with the focus on it, and the link of the missing venue leads there`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.onlyMe()
        e2e.gatherings().makeVenue(woodPlace, "Casa del bosco")
        val lake = e2e.gatherings().makeVenue("Casa per ritiri sul lago, Località Esempio", "Casa sul lago")
        val id = e2e.gatherings().make(status = GatheringStatus.draft, place = null, custodians = listOf(me))

        // The draft lists what is missing: the venue first, with the way to the field.
        e2e.open("/convivenza/$id")
        assertThat(page.locator("main").getByText("Da completare").first()).isVisible()
        e2e.press(page.locator("main").buttonNamed("Apri le iscrizioni"))
        val alert = page.locator("main [role=alert]")
        assertThat(alert.locator("a").first()).hasText("Scegli la struttura")
        e2e.press(alert.linkNamed("Scegli la struttura"))
        assertThat(page).hasURL(e2e.url("/convivenza/$id/date#struttura"))
        page.focusIs("document.activeElement.id === 'struttura'")
        assertThat(page.locator("#struttura option")).hasText(arrayOf("Scegli la struttura", "Casa del bosco", "Casa sul lago"))
        assertThat(page.locator("main").linkNamed("Nuova struttura")).isVisible()
        assertPageFits(e2e, "Modifica titolo, date e luogo with the select")

        page.locator("#struttura").selectOption(lake.toString())
        e2e.press(page.buttonNamed("Salva le modifiche"))

        assertThat(page.locator("main [role=status][data-fuoco]")).containsText("Titolo, date e luogo salvati.")
        assertThat(page.locator("section[aria-labelledby=quando]")).containsText("Casa sul lago")
        assertThat(page.locator("section[aria-labelledby=quando]")).containsText("Casa per ritiri sul lago, Località Esempio")
        assertEquals(lake, e2e.gatherings().venueIdOf(id))

        // A gathering with the registrations open cannot go back to no venue: the error is under the select.
        e2e.dsl().update(GATHERING).set(GATHERING.STATUS, GatheringStatus.`open`).where(GATHERING.ID.eq(id)).execute()
        e2e.open("/convivenza/$id/date")
        page.locator("#struttura").selectOption("")
        e2e.press(page.buttonNamed("Salva le modifiche"))
        assertThat(page.locator("#struttura-errore")).hasText("Scegli la struttura.")
        assertThat(page.locator("#struttura")).hasAttribute("aria-invalid", "true")
        page.focusIs("document.activeElement.id === 'struttura'")
        assertEquals(lake, e2e.gatherings().venueIdOf(id), "nothing was saved")
        assertPageFits(e2e, "Modifica titolo, date e luogo with the error")
    }

    // ---- Long words (criterion 10) ------------------------------------------------------------------------------------------------

    @E2eTest
    fun `t20 c10 a name of 60 letters in one word and a place of 200 wrap in every page of the venues and of the gathering, with no horizontal scroll`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.onlyMe()
        val longName = "N".repeat(60)
        val longPlace = "P".repeat(200)
        val venue = e2e.gatherings().makeVenue(longPlace, longName)
        val start = e2e.gatherings().today().plusDays(30)
        val id = e2e.gatherings().make(status = GatheringStatus.`open`, venue = venue, start = start, end = start.plusDays(2), custodians = listOf(me), code = "q7Vd_3KmZ0xRb-Ew")

        for ((label, path) in listOf(
            "Strutture" to "/strutture",
            "the venue" to "/strutture/$venue",
            "Modifica la struttura" to "/strutture/$venue/modifica",
            "the detail" to "/convivenza/$id",
            "Modifica titolo, date e luogo" to "/convivenza/$id/date",
            "the preview" to "/convivenza/$id/anteprima",
            "the page for the parents" to "/info/q7Vd_3KmZ0xRb-Ew",
        )) {
            e2e.open(path)
            assertPageFits(e2e, label)
        }
        assertEquals(1, e2e.dsl().fetchCount(GATHERING))
    }
}
