package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import org.jooq.DSLContext
import talita.Addresses
import talita.account.EmailAddress
import talita.account.TestAccounts
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.TEACHER_INVITATION
import talita.teacher.Invitations
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How «Maestri» and the pages that carry an address or a name look with the real content, on a phone and on a desktop (QA of
 * TALITA-3, criteria 10, 40, 41, 43 and 45): the width that the address of an invitation gets next to its buttons, the dialogs
 * with a long address or name, a name with markup, the address of 254 characters in «Controlla la posta», and the notice that takes
 * the focus under the sticky header. The other e2e tests look at the words, the focus and axe; these look at the room.
 */
@E2eApplication
class MaestriLayoutE2eTest {

    private fun E2e.dsl(): DSLContext = application.getBean(DSLContext::class.java)

    /** Only these teachers and no invitation; the person of the test is one of them (the database is shared with the other tests). */
    private fun E2e.onlyTeachers(me: String = checkNotNull(signedInAs), others: List<Triple<String, String, String>> = emptyList()) {
        dsl().deleteFrom(TEACHER_INVITATION).execute()
        dsl().deleteFrom(TEACHER).execute()
        TestAccounts.makeTeacher(dsl(), me, "Elisa", "Conti")
        for ((email, first, last) in others) TestAccounts.makeTeacher(dsl(), email, first, last)
    }

    private fun E2e.invite(address: String) {
        application.getBean(Invitations::class.java).invite(EmailAddress.parse(address)!!)
    }

    private fun Page.row(text: String): Locator = locator("main li").filter(Locator.FilterOptions().setHasText(text))

    private fun Locator.buttonNamed(name: String): Locator = getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName(name))

    private fun Page.openDialog(): Locator = locator("dialog[open]")

    private fun Page.waitForSettledDialog() {
        waitUntil("document.querySelector('dialog[open]') && document.querySelector('dialog[open]').getAnimations().every(a => a.playState === 'finished')")
    }

    /** A button of a row, centred first: a button just above the bottom bar of the phone is «in view» for Playwright, and under the bar. */
    private fun E2e.press(button: Locator) {
        button.evaluate("e => e.scrollIntoView({ block: 'center' })")
        touch(button)
    }

    private fun Page.sidewaysScroll(): Int = (evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number).toInt()

    /** Whether the content of [this] is wider than its box (a dialog that scrolls sideways, a line that is cut). */
    private fun Locator.contentIsWiderThanTheBox(): Boolean = evaluate("e => e.scrollWidth > e.clientWidth + 1") as Boolean

    private fun Locator.lines(): Double = (evaluate("e => e.getBoundingClientRect().height / parseFloat(getComputedStyle(e).lineHeight)") as Number).toDouble()

    // ---- c40: the row of an invitation -----------------------------------------------------------------------------------------

    /**
     * The address of an invitation of ordinary length (34 characters) gets room: at most two lines, whatever the width of the
     * phone, with «Manda di nuovo» and «Ritira» under it (design of «Maestri», section 2: «sotto sul telefono, a destra da 640 px»).
     * A row where the address is squeezed to a few pixels between the icon and the buttons is a column of single letters.
     */
    @E2eTest
    fun `c40 the address of an invitation gets room, with its buttons under it on a phone and beside it from 640 px`(e2e: E2e) {
        val page = e2e.page
        e2e.onlyTeachers()
        val address = Addresses.unique("e2e-normale")
        e2e.invite(address)
        e2e.open("/maestri")

        val widths = if (e2e.profile.isPhone) listOf(360, 320) else listOf(1280)
        for (width in widths) {
            page.setViewportSize(width, 800)
            page.waitForSettledContent()
            val where = "$width px"
            val row = page.row(address)
            val text = row.locator("span.wrap-anywhere")
            val box = text.box()
            assertTrue(box.width >= 150, "$where: the address has ${box.width} px, between the icon and the buttons")
            // 34 characters: two lines on a phone of 360 px (the one of the design), three on the narrowest one of 320 px.
            val mostLines = if (width >= 360) 2.01 else 3.01
            assertTrue(text.lines() <= mostLines, "$where: the address takes ${text.lines()} lines, in ${box.width} px")
            val again = row.buttonNamed("Manda di nuovo").box()
            val withdraw = row.buttonNamed("Ritira").box()
            if (width < 640) {
                assertTrue(again.y >= box.y + box.height - 1, "$where: «Manda di nuovo» (y ${again.y}) is not under the address (ends at ${box.y + box.height})")
                assertTrue(withdraw.y >= box.y + box.height - 1, "$where: «Ritira» is not under the address")
            } else {
                assertTrue(again.x >= box.x + box.width - 1, "$where: «Manda di nuovo» is not beside the address")
            }
            assertEquals(0, page.sidewaysScroll(), "$where: the page scrolls sideways")
            for (button in row.locator("button").all()) assertTrue(button.box().height >= 44, "$where: a button is ${button.box().height} px high")
        }
        assertAccessible(page, "Maestri with an invitation (${e2e.profile.label})")
    }

    // ---- c40: the row of a teacher with a name in one long word ----------------------------------------------------------------

    /**
     * A name made of one long word (a real one of 24 letters, or the 100 that the form allows) must break inside the column of the
     * name: not run under «Togli» (the column is about 178 px wide on a phone) and not go out of the row, where the list cuts it.
     */
    @E2eTest
    fun `c40 the name of a teacher in one long word stays in its column, off the button Togli and inside the row`(e2e: E2e) {
        val page = e2e.page
        for (first in listOf("Wolfeschlegelsteinhausen", "Bartolomea".repeat(10))) {
            e2e.onlyTeachers(others = listOf(Triple(Addresses.unique("e2e-parola-lunga"), first, "Rossi")))
            e2e.open("/maestri")
            page.waitForSettledContent()
            val where = "'${first.take(24)}' (${first.length} letters)"

            val row = page.row("$first Rossi")
            val name = row.getByText("$first Rossi", Locator.GetByTextOptions().setExact(true))
            val rowBox = row.box()
            val nameBox = name.box()
            val button = row.buttonNamed("Togli").box()

            assertTrue(nameBox.x + nameBox.width <= rowBox.x + rowBox.width + 1, "$where: the name ends at ${nameBox.x + nameBox.width} px and the row at ${rowBox.x + rowBox.width}")
            assertTrue(!name.contentIsWiderThanTheBox(), "$where: the text of the name is wider than its box")
            val sideBySide = nameBox.x < button.x + button.width && button.x < nameBox.x + nameBox.width
            val sameHeight = nameBox.y < button.y + button.height && button.y < nameBox.y + nameBox.height
            assertTrue(!(sideBySide && sameHeight), "$where: the name (x ${nameBox.x}, ${nameBox.width} px wide) is under «Togli» (x ${button.x})")
            assertEquals(0, page.sidewaysScroll(), "$where: the page scrolls sideways")
        }
        assertAccessible(page, "Maestri with a long name (${e2e.profile.label})")
    }

    // ---- c43 and c45: the dialogs with a long address or name ------------------------------------------------------------------

    /**
     * A long address (72 characters, which email allows) or a long name (100 characters, the maximum, in one piece) must stay inside
     * the dialog and inside the screen: a dialog that scrolls sideways cuts the very thing that it asks to confirm.
     */
    @E2eTest
    fun `c43 c45 the dialogs of Ritira and Togli keep a long address and a long name inside the screen`(e2e: E2e) {
        val page = e2e.page
        val longName = "Bartolomea".repeat(10)
        e2e.onlyTeachers(others = listOf(Triple(Addresses.unique("e2e-nome-lungo"), longName, longName)))
        val address = Addresses.unique("e2e-" + "a".repeat(45))
        e2e.invite(address)
        e2e.open("/maestri")
        assertEquals(0, page.sidewaysScroll(), "Maestri scrolls sideways")

        e2e.press(page.row(address).buttonNamed("Ritira"))
        var dialog = page.openDialog()
        page.waitForSettledDialog()
        assertThat(dialog).containsText(address)
        assertTrue(!dialog.contentIsWiderThanTheBox(), "the dialog of Ritira scrolls sideways: the address is cut")
        assertEquals(0, page.sidewaysScroll(), "the page scrolls sideways with the dialog of Ritira open")
        page.keyboard().press("Escape")

        e2e.press(page.row(longName).buttonNamed("Togli"))
        dialog = page.openDialog()
        page.waitForSettledDialog()
        assertThat(dialog.locator("h2")).containsText(longName)
        assertTrue(!dialog.contentIsWiderThanTheBox(), "the dialog of Togli scrolls sideways: the name is cut")
        assertEquals(0, page.sidewaysScroll(), "the page scrolls sideways with the dialog of Togli open")
        assertAccessible(page, "dialog Togli with a long name (${e2e.profile.label})")
    }

    // ---- c40 and c45: a name with markup ---------------------------------------------------------------------------------------

    /** A name is text, whatever is in it: in the list, in the dialog, and in the notice that says who was taken away. */
    @E2eTest
    fun `c40 c45 a name with markup is shown as text in the list, in the dialog and in the notice`(e2e: E2e) {
        val page = e2e.page
        val first = "<b>Paolo</b>"
        val last = "<img src=x onerror=alert(1)>"
        e2e.onlyTeachers(others = listOf(Triple(Addresses.unique("e2e-marcato"), first, last)))
        e2e.open("/maestri")

        assertThat(page.row("$first $last")).isVisible()
        assertEquals(0, page.locator("main b, main img").count(), "the markup of the name became elements of the list")

        e2e.press(page.row("$first $last").buttonNamed("Togli"))
        val dialog = page.openDialog()
        assertThat(dialog.locator("h2")).hasText("Togliere $first $last dai maestri?")
        assertEquals(0, dialog.locator("b, img").count(), "the markup of the name became elements of the dialog")
        e2e.touch(dialog.buttonNamed("Togli dai maestri"))

        assertThat(page.locator("#maestri-contenuto [role=status]")).hasText("$first $last non è più tra i maestri.")
        assertEquals(0, page.locator("main b, main img").count(), "the markup of the name became elements of the notice")
    }

    // ---- c10: the longest address ------------------------------------------------------------------------------------------------

    /** An address of 254 characters, the longest that email allows, is written in «Controlla la posta» without scrolling the page sideways. */
    @E2eTest(signedIn = false)
    fun `c09 c10 an address of 254 characters is accepted and shown in full in Controlla la posta, without scrolling the page sideways`(e2e: E2e) {
        val page = e2e.page
        val address = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(57) + ".org"
        assertEquals(254, address.length)
        e2e.open("/accedi")
        page.waitForSettledContent()
        page.getByLabel("Indirizzo email").fill(address)
        assertEquals(0, page.sidewaysScroll(), "the access page scrolls sideways with the long address in the field")

        e2e.touch(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Ricevi il link")))

        assertThat(page.locator("h1")).hasText("Controlla la posta")
        page.waitForSettledContent()
        assertThat(page.locator("main")).containsText(address)
        assertEquals(0, page.sidewaysScroll(), "Controlla la posta scrolls sideways with the long address")
        assertAccessible(page, "Controlla la posta with the longest address (${e2e.profile.label})")
    }

    // ---- c41: the notice under the header --------------------------------------------------------------------------------------

    /**
     * The notice that takes the focus after an action is scrolled into view: on the phone it must not stay under the sticky
     * header (focus not obscured, WCAG 2.2 criterion 2.4.11). When the card of the invitation is a little above the top of the screen
     * the browser puts the notice at the very top edge, and the bar (56 px) covers it: the person who just sent the invitation sees nothing.
     * The fix is a `scroll-padding-top` on the page as high as the bar.
     */
    @E2eTest(profiles = [Profile.PHONE])
    fun `c41 the notice that takes the focus after an invitation is not under the sticky header`(e2e: E2e) {
        val page = e2e.page
        e2e.onlyTeachers()
        // A page long enough to be read from the middle, as a person who has an invitation list and is writing the next address.
        repeat(6) { e2e.invite(Addresses.unique("e2e-in-attesa-$it")) }
        e2e.open("/maestri")
        page.waitForSettledContent()
        // Scrolled so that the card of the invitation is a little above the top of the screen, with the field still in view: the notice
        // that appears at the top of the card is then above the screen, and the browser has to bring it in.
        page.evaluate("() => window.scrollTo(0, document.querySelector('#maestri-contenuto form').getBoundingClientRect().top + window.scrollY + 20)")
        page.getByLabel("Indirizzo email").fill(Addresses.unique("e2e-avviso"))

        e2e.touch(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Manda l’invito")))

        val notice = page.locator("#maestri-contenuto [role=status]")
        assertThat(notice).hasText("Invito mandato.")
        page.focusIs("document.activeElement.getAttribute('role') === 'status'")
        // The scroll to the notice, if it is smooth, has to finish: the position is a condition that is waited for, with the last values in the message.
        awaitCondition("the notice to be under the header and not behind it") {
            val header = page.locator("header").first().box()
            val box = notice.box()
            if (box.y >= header.y + header.height - 1) null
            else "the notice starts at ${box.y} px and the header reaches ${header.y + header.height} px: the notice (${box.height} px high) is under the bar"
        }
    }
}
