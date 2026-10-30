package talita.e2e

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.SameSiteAttribute
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest
import org.springframework.security.authentication.ott.OneTimeTokenService
import talita.Addresses
import talita.account.Phone
import talita.account.TestAccounts
import talita.jooq.tables.references.REMEMBERED_DEVICE
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * «Ricordami su questo telefono» on a real browser, on a phone and on a desktop (TALITA-15, criterion 36): the box that is not ticked, the cookie, the way back into the app when the session is gone
 * (two hours without use, a restart of the application or a browser that was closed: the e2e takes the cookie of the session away, which is what the server sees in all three), the value that
 * changes every time, the exit, and «Esci da tutti gli altri dispositivi» and «Togli» seen from the other phones. axe and 320 px. Addresses and names are synthetic.
 */
@E2eApplication
class RememberE2eTest {

    private fun Page.exact(name: String) = getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(name).setExact(true))

    private fun E2e.link(email: String): String =
        application.getBean(OneTimeTokenService::class.java).generate(GenerateOneTimeTokenRequest(email, Duration.ofMinutes(15))).tokenValue

    /** Opens the link of [email] on [on] and taps «Entra», with the box ticked or not. A new address is a parent. */
    private fun E2e.enter(on: Page = page, email: String = Addresses.unique("persona"), remember: Boolean, teacher: Boolean = false): String {
        if (teacher) TestAccounts.makeTeacher(dsl(), email)
        on.navigate(url("/accedi/conferma?token=${link(email)}"))
        on.waitForSettledContent()
        if (remember) on.getByLabel("Ricordami su questo telefono").check()
        touch(on.exact("Entra"))
        on.waitForURL(url("/"))
        on.waitForLoadState()
        return email
    }

    private fun BrowserContext.deviceCookie() = cookies().firstOrNull { it.name == "ricordami" }

    /** The session of the server is gone and the browser has only what it keeps for a long time. */
    private fun BrowserContext.loseTheSession() = clearCookies(BrowserContext.ClearCookiesOptions().setName("JSESSIONID"))

    /** The session of the server is over but the browser still sends its cookie (what two hours without use look like): the page of access says why. */
    private fun BrowserContext.expireTheSession(url: String) {
        loseTheSession()
        addCookies(listOf(com.microsoft.playwright.options.Cookie("JSESSIONID", "0000000000000000000000000000000A").setUrl(url)))
    }

    private fun E2e.devices(email: String): Int = dsl().fetchCount(REMEMBERED_DEVICE, REMEMBERED_DEVICE.ACCOUNT_ID.eq(accountId(email)))

    @E2eTest(signedIn = false)
    fun `t15 c36 c1 c2 the box is not ticked, says how long it lasts and tells not to use it on the phone of others, and without it the person is not remembered`(e2e: E2e) {
        val page = e2e.page
        val email = Addresses.unique("persona")
        val devicesBefore = e2e.dsl().fetchCount(REMEMBERED_DEVICE)
        page.navigate(e2e.url("/accedi/conferma?token=${e2e.link(email)}"))
        page.waitForSettledContent()

        val box = page.getByLabel("Ricordami su questo telefono")
        assertThat(box).not().isChecked()
        assertThat(page.locator("main")).containsText("30 giorni (7 se sei tra i maestri)")
        assertThat(page.locator("main")).containsText("Non spuntarla su un telefono o un computer di altri")
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px")
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "page of the link with the box (${e2e.profile.label})")
        assertEquals(devicesBefore, e2e.dsl().fetchCount(REMEMBERED_DEVICE), "opening the page writes nothing")

        // Not ticked: the same as always, no cookie and no row. When the session is gone the person is sent to the access page, with the notice.
        e2e.touch(page.exact("Entra"))
        page.waitForURL(e2e.url("/"))
        assertEquals(null, e2e.browserContext.deviceCookie())
        assertEquals(0, e2e.devices(email))
        e2e.browserContext.expireTheSession(e2e.url("/"))
        e2e.open("/iscrizioni")
        assertThat(page).hasURL(e2e.url("/accedi"))
        assertThat(page.locator("main [role=status][data-fuoco]")).containsText("L’accesso si chiude da solo dopo 2 ore che non usi Talità, e comunque dopo 12 ore dall’entrata.")
        assertAccessible(page, "access page after the session ended (${e2e.profile.label})")
    }

    @E2eTest(signedIn = false)
    fun `t15 c36 c3 c4 c13 with the box ticked the person comes back without a link when the session is gone, the value changes every time, and the exit takes the device away`(e2e: E2e) {
        val page = e2e.page
        val email = e2e.enter(remember = true)

        val first = checkNotNull(e2e.browserContext.deviceCookie()) { "the cookie of the box" }
        assertEquals(true, first.httpOnly, "HttpOnly")
        assertEquals(SameSiteAttribute.LAX, first.sameSite)
        assertEquals("/", first.path)
        assertEquals(1, e2e.devices(email))
        assertFalse(first.value.contains(email), "the cookie does not carry the address")
        val (selector, firstValue) = Phone.decode(first.value)

        // The session is gone (two hours, a restart, a closed browser): the next page is the page that was asked for, with no passing from the access page.
        e2e.browserContext.loseTheSession()
        e2e.open("/iscrizioni")
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        val second = checkNotNull(e2e.browserContext.deviceCookie())
        val (secondSelector, secondValue) = Phone.decode(second.value)
        assertEquals(selector, secondSelector, "the row is the same")
        assertNotEquals(firstValue, secondValue, "the value changes at every way back in")
        assertEquals(1, e2e.devices(email), "still one row")

        // And once more, from a page of htmx (the way back is the same for a piece of a page).
        e2e.browserContext.loseTheSession()
        e2e.open("/profilo")
        assertThat(page.locator("h1")).hasText("Profilo")
        assertNotEquals(secondValue, Phone.decode(checkNotNull(e2e.browserContext.deviceCookie()).value).second)

        // The profile has the device, with «Questo dispositivo», and the exit takes it away with the cookie.
        assertThat(page.locator("#dispositivi-contenuto")).containsText("Questo dispositivo")
        e2e.touch(page.exact("Esci"))
        assertThat(page).hasURL(e2e.url("/accedi"))
        assertEquals(null, e2e.browserContext.deviceCookie(), "the cookie is gone")
        assertEquals(0, e2e.devices(email), "and the row")
        e2e.open("/iscrizioni")
        assertThat(page).hasURL(e2e.url("/accedi"))
    }

    @E2eTest(signedIn = false)
    fun `t15 c36 c16 Esci da tutti gli altri dispositivi closes the others, which find the access page with a notice, and this one stays inside`(e2e: E2e) {
        val page = e2e.page
        val email = e2e.enter(remember = true, teacher = false)
        val withoutBox = e2e.newPage(signedIn = false)
        e2e.enter(withoutBox, email, remember = false)
        val withBox = e2e.newPage(signedIn = false)
        e2e.enter(withBox, email, remember = true)
        assertEquals(2, e2e.devices(email))

        e2e.open("/profilo")
        val block = page.locator("#dispositivi-contenuto")
        assertThat(block).containsText("Dispositivi collegati")
        assertThat(block.locator("li")).hasCount(2)
        assertThat(block.locator("li").first()).containsText("Questo dispositivo")
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px")
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "profile with the devices (${e2e.profile.label})")

        e2e.press(block.buttonNamed("Esci da tutti gli altri dispositivi"))
        page.waitForSettledDialog()
        val dialog = page.openDialog()
        assertThat(dialog).containsText("Uscire da tutti gli altri dispositivi?")
        assertThat(dialog).containsText("Questo dispositivo resta dentro.")
        assertAccessible(page, "dialog of the exit from all the others (${e2e.profile.label})")
        e2e.touch(dialog.buttonNamed("Esci da tutti gli altri dispositivi"))

        assertThat(block.locator("[role=status]").first()).containsText("Gli altri dispositivi sono usciti.")
        assertThat(block.locator("li")).hasCount(1)
        assertEquals(1, e2e.devices(email))
        for (other in listOf(withoutBox, withBox)) {
            other.navigate(e2e.url("/iscrizioni"))
            other.waitForSettledContent()
            assertThat(other).hasURL(e2e.url("/accedi"))
            assertThat(other.locator("main [role=status][data-fuoco]")).containsText("L’accesso su questo telefono è stato chiuso.")
        }
        e2e.open("/iscrizioni")
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
    }

    @E2eTest(signedIn = false)
    fun `t15 c36 c15 Togli takes a device away, and that phone finds the access page at its next request`(e2e: E2e) {
        val page = e2e.page
        val email = e2e.enter(remember = true)
        val other = e2e.newPage(signedIn = false)
        e2e.enter(other, email, remember = true)
        assertEquals(2, e2e.devices(email))

        e2e.open("/profilo")
        val block = page.locator("#dispositivi-contenuto")
        assertThat(block.locator("li")).hasCount(2)
        e2e.press(block.getByRole(AriaRole.BUTTON, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Togli il dispositivo collegato dal")))
        page.waitForSettledDialog()
        val dialog = page.openDialog()
        assertThat(dialog).containsText("Togliere il dispositivo collegato dal")
        assertThat(dialog).containsText("Gli altri dispositivi restano collegati.")
        e2e.touch(dialog.buttonNamed("Togli il dispositivo"))

        assertThat(block.locator("[role=status]").first()).containsText("Dispositivo tolto.")
        assertThat(block.locator("li")).hasCount(1)
        assertEquals(1, e2e.devices(email))
        other.navigate(e2e.url("/iscrizioni"))
        other.waitForSettledContent()
        assertThat(other).hasURL(e2e.url("/accedi"))
        // The one that is inside stays inside.
        e2e.open("/iscrizioni")
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
    }
}
