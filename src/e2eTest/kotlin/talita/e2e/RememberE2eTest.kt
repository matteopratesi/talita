package talita.e2e

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.APIResponse
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.Cookie
import com.microsoft.playwright.options.FormData
import com.microsoft.playwright.options.RequestOptions
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

    // ---- What only a real browser and a real server can tell (QA of TALITA-15) ----------------------------------------------------------------------------------------

    /** A POST of a form of the page [on] as htmx does it, with the CSRF code of the page: what a person who writes the request by hand sends. */
    private fun E2e.post(on: Page, path: String, form: Map<String, String> = emptyMap()): APIResponse {
        val data = FormData.create().set("_csrf", checkNotNull(on.locator("input[name=_csrf]").first().getAttribute("value")))
        form.forEach { (name, value) -> data.set(name, value) }
        return on.request().post(url(path), RequestOptions.create().setForm(data).setHeader("HX-Request", "true").setMaxRedirects(0))
    }

    private fun E2e.deviceIds(email: String): List<Long> =
        dsl().select(REMEMBERED_DEVICE.ID).from(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.eq(accountId(email))).orderBy(REMEMBERED_DEVICE.ID).fetch(REMEMBERED_DEVICE.ID).map { it!! }

    private fun Page.sessionCookie(): String? = context().cookies().firstOrNull { it.name == "JSESSIONID" }?.value

    @E2eTest(signedIn = false)
    fun `t15 c36 c4 after the two hours without use the person with the box is still inside and the one without goes to the access page`(e2e: E2e) {
        val page = e2e.page
        val email = e2e.enter(remember = true)
        val before = checkNotNull(page.sessionCookie())
        val valueBefore = Phone.decode(checkNotNull(e2e.browserContext.deviceCookie()).value).second

        // `/test/sessione-breve` leaves the session one second of life: the container treats it as gone after that, exactly as after two hours.
        e2e.open("/test/sessione-breve")
        page.waitForTimeout(2_300.0)
        e2e.open("/profilo")
        assertThat(page).hasURL(e2e.url("/profilo"))
        assertThat(page.locator("h1")).hasText("Profilo")
        assertNotEquals(before, page.sessionCookie(), "a new session, with a new id")
        assertNotEquals(valueBefore, Phone.decode(checkNotNull(e2e.browserContext.deviceCookie()).value).second, "and a new value of the cookie")
        assertEquals(1, e2e.devices(email))
        assertThat(page.locator("#dispositivi-contenuto")).containsText("Questo dispositivo")

        // A piece of a page (htmx) and the request of the back button, with no session and the cookie: the same way back in, the page they asked for and never the access page.
        for (header in listOf("HX-Request", "HX-History-Restore-Request")) {
            e2e.browserContext.loseTheSession()
            val answer = e2e.browserContext.request().get(e2e.url("/profilo"), RequestOptions.create().setHeader(header, "true").setMaxRedirects(0))
            assertEquals(200, answer.status(), header)
            assertEquals(null, answer.headers()["hx-redirect"], header)
            assertTrue(answer.text().contains("Dispositivi collegati"), header)
        }
        assertEquals(1, e2e.devices(email))

        // The same without the box: the access page, and the notice says why (and does not say that the cookie was refused).
        val without = e2e.newPage(signedIn = false)
        e2e.enter(without, email, remember = false)
        without.navigate(e2e.url("/test/sessione-breve"))
        without.waitForTimeout(2_300.0)
        without.navigate(e2e.url("/iscrizioni"))
        without.waitForSettledContent()
        assertThat(without).hasURL(e2e.url("/accedi"))
        assertThat(without.locator("main [role=status][data-fuoco]")).containsText("L’accesso si chiude da solo dopo 2 ore che non usi Talità, e comunque dopo 12 ore dall’entrata.")
        assertEquals(1, e2e.devices(email), "the browser without the box left no row, and the other one is untouched")
    }

    /**
     * Two tabs that the phone reopens together after the session ended (decision of Matteo, 31 October): the same cookie goes in both requests. The first tab is a page; the second request is made by
     * the browser context with the old value in the `Cookie` header, because a real second tab would carry it too (it was sent before the answer of the first arrived).
     */
    @E2eTest(signedIn = false, profiles = [Profile.DESKTOP])
    fun `t15 c36 c5 two tabs that the phone reopens together with the same cookie both come in without unlinking the device, and the old value after 60 seconds is a copy`(e2e: E2e) {
        val page = e2e.page
        val email = e2e.enter(remember = true)
        val old = checkNotNull(e2e.browserContext.deviceCookie()).value
        e2e.browserContext.loseTheSession()

        // The first tab comes in with the cookie and gets the new value.
        e2e.open("/iscrizioni")
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        val renewed = checkNotNull(e2e.browserContext.deviceCookie()).value
        assertNotEquals(old, renewed)

        // The second tab arrives with the same old cookie, a moment later (the jar is emptied, so that the only cookie of the request is the one that the test says): it comes in too, with a
        // session of its own, and nothing is set for the cookie of the device.
        e2e.browserContext.clearCookies()
        val together = e2e.browserContext.request().get(e2e.url("/iscrizioni"), RequestOptions.create().setHeader("Cookie", "ricordami=$old").setMaxRedirects(0))
        assertEquals(200, together.status(), "the second tab comes in: a page, not the access page")
        assertTrue(together.text().contains("Iscrizioni"), "the page of the registrations")
        assertFalse(together.headers()["set-cookie"].orEmpty().contains("ricordami="), "the answer does not touch the cookie of the device")
        assertEquals(1, e2e.devices(email), "the device is still there")

        // After the 60 seconds the old value is a copy: the access page, the cookie is cancelled and the row goes.
        e2e.dsl().execute("update remembered_device set rotated_at = rotated_at - interval '61 seconds' where account_id = ?", e2e.accountId(email))
        e2e.browserContext.clearCookies()
        val late = e2e.browserContext.request().get(e2e.url("/iscrizioni"), RequestOptions.create().setHeader("Cookie", "ricordami=$old").setMaxRedirects(0))
        assertEquals(302, late.status(), "after the margin it is a copy")
        assertTrue(late.headers()["location"].orEmpty().endsWith("/accedi"), late.headers().toString())
        assertTrue(late.headers()["set-cookie"].orEmpty().contains("ricordami=") && late.headers()["set-cookie"].orEmpty().contains("1970"), "the cookie is cancelled")
        assertEquals(0, e2e.devices(email), "the device is unlinked")
    }

    @E2eTest(signedIn = false, profiles = [Profile.DESKTOP])
    fun `t15 c36 c5 a copied cookie unlinks the device and cancels both cookies, and junk is cancelled without an error`(e2e: E2e) {
        val page = e2e.page
        val email = e2e.enter(remember = true)
        val copy = checkNotNull(e2e.browserContext.deviceCookie()).value

        // The owner comes back: the value is not the one that was copied any more.
        e2e.browserContext.loseTheSession()
        e2e.open("/iscrizioni")
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        val current = checkNotNull(e2e.browserContext.deviceCookie()).value
        assertNotEquals(copy, current)
        // The margin of 60 seconds is over (the clock of the application is shared and does not go back, so the row says it).
        e2e.dsl().execute("update remembered_device set rotated_at = rotated_at - interval '61 seconds' where account_id = ?", e2e.accountId(email))

        // Someone who has the copy comes in another browser: nobody enters, the row goes, the cookie is cancelled, there is no error, and the page says that the access was closed.
        val thief = e2e.newPage(signedIn = false)
        thief.context().addCookies(listOf(Cookie("ricordami", copy).setUrl(e2e.url("/"))))
        val answer = thief.navigate(e2e.url("/iscrizioni"))
        thief.waitForSettledContent()
        assertEquals(200, answer?.status())
        assertThat(thief).hasURL(e2e.url("/accedi"))
        assertThat(thief.locator("main [role=status][data-fuoco]")).containsText("L’accesso su questo telefono è stato chiuso.")
        assertEquals(null, thief.context().cookies().firstOrNull { it.name == "ricordami" }, "the cookie of the copy is cancelled")
        assertEquals(0, e2e.devices(email), "the device is unlinked")

        // The browser of the owner has the new value, which is worth nothing now: it goes to the access page too, and its cookie is cancelled.
        e2e.browserContext.loseTheSession()
        e2e.open("/iscrizioni")
        assertThat(page).hasURL(e2e.url("/accedi"))
        assertEquals(null, e2e.browserContext.deviceCookie(), "and the one of the owner")

        // Junk: no selector like that, a cookie that is not even base64, nothing at all.
        val unknown = java.util.Base64.getEncoder().encodeToString("sceltaCheNonEsiste1234:valoreCheNonEsiste".toByteArray())
        for (junk in listOf(unknown, "not-a-cookie!!", "%%%")) {
            val browser = e2e.newPage(signedIn = false)
            browser.context().addCookies(listOf(Cookie("ricordami", junk).setUrl(e2e.url("/"))))
            val reply = browser.navigate(e2e.url("/iscrizioni"))
            browser.waitForSettledContent()
            assertEquals(200, reply?.status(), "no error for the cookie $junk")
            assertThat(browser).hasURL(e2e.url("/accedi"))
            assertThat(browser.locator("main")).not().containsText("Qualcosa non ha funzionato")
            assertEquals(null, browser.context().cookies().firstOrNull { it.name == "ricordami" }, "cancelled: $junk")
        }
    }

    @E2eTest(signedIn = false, profiles = [Profile.DESKTOP])
    fun `t15 c36 c17 c18 nobody takes away or sees the devices of another and a second Togli is an information notice`(e2e: E2e) {
        val owner = e2e.page
        val email = e2e.enter(remember = true)
        val elsewhere = e2e.newPage(signedIn = false)
        e2e.enter(elsewhere, email, remember = true)
        val ids = e2e.deviceIds(email)
        assertEquals(2, ids.size)
        val theirs = ids.last()

        for (teacher in listOf(false, true)) {
            val intruder = e2e.newPage(signedIn = true, teacher = teacher)
            intruder.navigate(e2e.url("/profilo"))
            intruder.waitForSettledContent()
            // Nobody sees the devices of another, not even a teacher: the profile of the intruder is its own.
            assertThat(intruder.locator("#dispositivi-contenuto")).containsText("Nessun dispositivo collegato.")
            assertThat(intruder.locator("#dispositivi-contenuto li")).hasCount(0)

            val reply = e2e.post(intruder, "/profilo/dispositivi/togli", mapOf("dispositivo" to "$theirs"))
            assertEquals(200, reply.status())
            assertTrue(reply.text().contains("Questo dispositivo era già stato tolto."), "answered as an id that does not exist")
            assertFalse(reply.text().contains("Dispositivo tolto."))
            assertEquals(ids, e2e.deviceIds(email), "nothing was taken away")
            // «Esci da tutti» of the intruder is the intruder's: the sessions of the owner stay.
            assertEquals(200, e2e.post(intruder, "/profilo/dispositivi/esci-altri").status())
            assertEquals(ids, e2e.deviceIds(email))
            elsewhere.navigate(e2e.url("/iscrizioni"))
            elsewhere.waitForSettledContent()
            assertThat(elsewhere).hasURL(e2e.url("/iscrizioni"))
        }

        // The owner: the first Togli takes the device away, the second (a double tap, or a page that was left open) says that it was gone, with a status 200.
        e2e.open("/profilo")
        val first = e2e.post(owner, "/profilo/dispositivi/togli", mapOf("dispositivo" to "$theirs"))
        assertTrue(first.text().contains("Dispositivo tolto."))
        val second = e2e.post(owner, "/profilo/dispositivi/togli", mapOf("dispositivo" to "$theirs"))
        assertEquals(200, second.status())
        assertTrue(second.text().contains("Questo dispositivo era già stato tolto."))
        assertEquals(listOf(ids.first()), e2e.deviceIds(email))
        // The same for what is not a number at all.
        assertTrue(e2e.post(owner, "/profilo/dispositivi/togli", mapOf("dispositivo" to "not-a-number")).text().contains("Questo dispositivo era già stato tolto."))
    }

    @E2eTest(signedIn = false)
    fun `t15 c36 c18 without a network Togli and Esci da tutti show the message and change nothing`(e2e: E2e) {
        val page = e2e.page
        val email = e2e.enter(remember = true)
        val other = e2e.newPage(signedIn = false)
        e2e.enter(other, email, remember = true)
        e2e.open("/profilo")
        val block = page.locator("#dispositivi-contenuto")

        e2e.browserContext.setOffline(true)
        e2e.press(block.getByRole(AriaRole.BUTTON, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Togli il dispositivo collegato dal")))
        page.waitForSettledDialog()
        e2e.touch(page.openDialog().buttonNamed("Togli il dispositivo"))
        assertThat(page.messages()).containsText("Nessuna connessione")
        assertEquals(0, page.messages().locator("button:has-text('Riprova')").count(), "a touch is not repeated for the person")
        assertEquals(2, e2e.devices(email))

        e2e.press(block.buttonNamed("Esci da tutti gli altri dispositivi"))
        page.waitForSettledDialog()
        e2e.touch(page.openDialog().buttonNamed("Esci da tutti gli altri dispositivi"))
        assertThat(page.messages()).containsText("Nessuna connessione")
        assertEquals(2, e2e.devices(email))
        e2e.browserContext.setOffline(false)
        other.navigate(e2e.url("/iscrizioni"))
        other.waitForSettledContent()
        assertThat(other).hasURL(e2e.url("/iscrizioni"))
    }

    @E2eTest(signedIn = false, profiles = [Profile.DESKTOP])
    fun `t15 c36 c10 one row for a browser, and the eleventh device takes away the oldest`(e2e: E2e) {
        val page = e2e.page
        val email = e2e.enter(remember = true)
        val firstSelector = Phone.decode(checkNotNull(e2e.browserContext.deviceCookie()).value).first
        e2e.enter(page, email, remember = true)
        assertEquals(1, e2e.devices(email), "this browser has one row only")
        assertNotEquals(firstSelector, Phone.decode(checkNotNull(e2e.browserContext.deviceCookie()).value).first, "the row of the old cookie went and a new one was made")

        repeat(10) { e2e.enter(e2e.newPage(signedIn = false), email, remember = true) }
        assertEquals(10, e2e.devices(email), "at most ten")
        // The first browser (the oldest) was the one that went: it finds the access page.
        e2e.browserContext.loseTheSession()
        e2e.open("/iscrizioni")
        assertThat(page).hasURL(e2e.url("/accedi"))
    }

    @E2eTest(signedIn = false, teacher = false)
    fun `t15 c36 c8 a form of a page left open keeps the person inside with the cookie, with and without JavaScript`(e2e: E2e) {
        val page = e2e.page
        val email = e2e.enter(remember = true)
        e2e.open("/profilo")
        val block = page.locator("#dispositivi-contenuto")
        e2e.press(block.buttonNamed("Esci da tutti gli altri dispositivi"))
        page.waitForSettledDialog()
        // The session of the server is gone (the page is the one that was open for hours): the form that is sent has a code that nobody knows any more.
        e2e.browserContext.loseTheSession()
        e2e.touch(page.openDialog().buttonNamed("Esci da tutti gli altri dispositivi"))
        page.waitForLoadState()
        page.waitForSettledContent()
        assertThat(page).hasURL(e2e.url("/profilo"))
        assertThat(page.locator("h1")).hasText("Profilo")
        assertEquals(1, e2e.devices(email), "nothing changed")
        assertThat(page.locator("main")).not().containsText("chiedi un nuovo link")

        // Without JavaScript: the home, with the notice, and the person is still inside.
        val plain = e2e.newPage(signedIn = false) { setJavaScriptEnabled(false) }
        e2e.enter(plain, email, remember = true)
        plain.navigate(e2e.url("/profilo"))
        plain.waitForSettledContent()
        e2e.touch(plain.locator("#dispositivi-contenuto").buttonNamed("Esci da tutti gli altri dispositivi"))
        assertThat(plain.locator("h1")).hasText("Uscire da tutti gli altri dispositivi?")
        plain.waitForSettledContent()
        // The page that stands in for the dialog (a GET-less POST of a form): its own form has the code of the session that is about to go.
        plain.context().clearCookies(BrowserContext.ClearCookiesOptions().setName("JSESSIONID"))
        e2e.touch(plain.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Esci da tutti gli altri dispositivi").setExact(true)))
        plain.waitForLoadState()
        plain.waitForSettledContent()
        assertThat(plain).not().hasURL(e2e.url("/accedi"))
        assertThat(plain.locator("main")).containsText("La pagina era aperta da troppo tempo. Riprova.")
        assertThat(plain.locator("main")).not().containsText("chiedi un nuovo link")
    }
}
