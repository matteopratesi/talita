package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import org.jooq.DSLContext
import talita.account.TestAccounts
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.TEACHER_INVITATION
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the QA of TALITA-4 found weak or missing in the e2e of the gathering: the pages of the teachers at 320 px with the longest words (the tests
 * of the developer try them at 360 and 1280), and axe on the dialogs of closing and reopening, on the detail of a closed gathering and on the preview
 * in every state (axe was run on the dialog of opening, on one detail and on one preview).
 */
@E2eApplication
class GatheringQaE2eTest {

    private fun E2e.dsl(): DSLContext = application.getBean(DSLContext::class.java)

    private fun E2e.gatherings(): GatheringSupport = GatheringSupport(dsl(), application.getBean(Clock::class.java))

    /** Only the person of the test is a teacher, with no invitation and no gathering. Returns the id of the person's account. */
    private fun E2e.onlyMe(): Long {
        dsl().deleteFrom(TEACHER_INVITATION).execute()
        dsl().deleteFrom(TEACHER).execute()
        val me = TestAccounts.makeTeacher(dsl(), checkNotNull(signedInAs), "Elisa", "Conti")
        gatherings().reset()
        return me
    }

    private fun Locator.buttonNamed(name: String): Locator = getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName(name))

    private fun Locator.linkNamed(name: String): Locator = getByRole(AriaRole.LINK, Locator.GetByRoleOptions().setName(name))

    private fun Page.noHorizontalScroll() = (evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number).toInt() <= 0

    private fun Page.settled() {
        waitForLoadState()
        waitForSettledContent()
    }

    private fun Page.openDialog(): Locator = locator("dialog[open]")

    private fun Page.waitForSettledDialog() {
        waitUntil("document.querySelector('dialog[open]') && document.querySelector('dialog[open]').getAnimations().every(a => a.playState === 'finished')")
    }

    private fun E2e.press(target: Locator) {
        target.evaluate("e => e.scrollIntoView({ block: 'center' })")
        touch(target)
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `qa c32 the pages of the teachers with the longest words do not scroll sideways at 320 and have no violation of axe`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.onlyMe()
        val id = e2e.gatherings().make(
            title = "L".repeat(100),
            place = "P".repeat(200),
            status = GatheringStatus.`open`,
            custodians = listOf(me),
            info = "I".repeat(300),
            whatToBring = "C".repeat(200),
        )
        page.setViewportSize(320, 640)

        for (path in listOf(
            "/convivenza", "/convivenza/$id", "/convivenza/$id/date", "/convivenza/$id/quote", "/convivenza/$id/affidatari",
            "/convivenza/$id/informazioni", "/convivenza/$id/anteprima", "/convivenza/nuova", "/maestri",
        )) {
            e2e.open(path)
            page.settled()
            assertTrue(page.noHorizontalScroll(), "$path scrolls sideways at 320")
            assertAccessible(page, "$path at 320")
        }
    }

    @E2eTest
    fun `qa the dialogs of closing and reopening, the detail of a closed gathering and the preview in every state have no violation of axe`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.onlyMe()
        val gatherings = e2e.gatherings()
        val id = gatherings.make(status = GatheringStatus.`open`, custodians = listOf(me), info = "Un testo\n\nche va a capo", whatToBring = "Una cosa\nUn'altra")
        e2e.open("/convivenza/$id")
        page.settled()

        e2e.press(page.locator("main").linkNamed("Chiudi le iscrizioni"))
        assertThat(page.openDialog().locator("h2")).hasText("Chiudere le iscrizioni?")
        page.waitForSettledDialog()
        assertAccessible(page, "dialog Chiudi (${e2e.profile.label})")
        page.keyboard().press("Escape")
        assertThat(page.openDialog()).hasCount(0)
        assertEquals(GatheringStatus.`open`, gatherings.statusOf(id))

        e2e.open("/convivenza/$id/anteprima")
        page.settled()
        assertAccessible(page, "preview, open (${e2e.profile.label})")

        e2e.open("/convivenza/$id")
        e2e.press(page.locator("main").linkNamed("Chiudi le iscrizioni"))
        e2e.touch(page.openDialog().buttonNamed("Chiudi le iscrizioni"))
        assertThat(page.locator("main [role=status][data-fuoco]")).hasText("Iscrizioni chiuse.")
        assertEquals(GatheringStatus.closed, gatherings.statusOf(id))
        page.settled()
        assertAccessible(page, "detail, closed (${e2e.profile.label})")

        e2e.press(page.locator("main").linkNamed("Riapri le iscrizioni"))
        assertThat(page.openDialog().locator("h2")).hasText("Riaprire le iscrizioni?")
        page.waitForSettledDialog()
        assertAccessible(page, "dialog Riapri (${e2e.profile.label})")
        page.keyboard().press("Escape")
        assertThat(page.openDialog()).hasCount(0)
        assertEquals(GatheringStatus.closed, gatherings.statusOf(id))

        e2e.open("/convivenza/$id/anteprima")
        page.settled()
        assertThat(page.locator("main")).containsText("Così vede la pagina chi apre il link adesso.")
        assertAccessible(page, "preview, closed (${e2e.profile.label})")

        // The page of confirmation that stands in for the dialog, also with JavaScript on: it is what a link opened by hand shows.
        e2e.open("/convivenza/$id/riapri")
        page.settled()
        assertThat(page.locator("h1")).hasText("Riaprire le iscrizioni?")
        assertAccessible(page, "page of confirmation Riapri (${e2e.profile.label})")

        // A draft and a gathering that is over, in the preview.
        gatherings.reset()
        val draft = gatherings.make(status = GatheringStatus.draft, custodians = listOf(me), info = "Un testo", whatToBring = "Una cosa")
        e2e.open("/convivenza/$draft/anteprima")
        page.settled()
        assertThat(page.locator("main")).containsText("Così apparirà la pagina quando aprirai le iscrizioni.")
        assertAccessible(page, "preview, draft (${e2e.profile.label})")

        gatherings.reset()
        val over = gatherings.make(
            status = GatheringStatus.closed, custodians = listOf(me),
            start = gatherings.today().minusDays(5), end = gatherings.today().minusDays(3),
        )
        e2e.open("/convivenza/$over/anteprima")
        page.settled()
        assertThat(page.locator("main")).containsText("la convivenza è finita")
        assertAccessible(page, "preview, over (${e2e.profile.label})")
    }
}
