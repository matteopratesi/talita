package talita.e2e

import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Moving around the app: htmx navigation, history, no JavaScript, loading and no network (criteria 21-24, 27). */
@E2eApplication
class NavigationE2eTest {

    private val sections = listOf(
        // label, path, title of the tab
        Triple("Convivenza", "/convivenza", "Convivenza · Talità"),
        Triple("Iscrizioni", "/iscrizioni", "Iscrizioni · Talità"),
        Triple("Arrivi", "/arrivi", "Arrivi · Talità"),
    )

    @E2eTest
    fun `c21 touching an entry changes content, title and address without reloading the page`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.evaluate("window.__marker = 'still-here'")

        for ((label, path, title) in sections + Triple("Home", "/", "Talità")) {
            e2e.touch(page.navLink(label))

            assertThat(page).hasURL(e2e.url(path))
            assertThat(page).hasTitle(title)
            assertThat(page.locator("h1")).hasText(if (label == "Home") "Ti diamo il benvenuto" else label)
            // Only the touched entry is the current one.
            assertThat(page.locator("nav [aria-current]")).hasCount(1)
            assertThat(page.locator("nav a[aria-current=page]")).hasText(label)
            // The page was not reloaded: what was put in window before the touch is still there.
            assertEquals("still-here", page.evaluate("window.__marker"), "$label: the page was reloaded")
        }
    }

    @E2eTest
    fun `c22 the back button returns to the previous page and nothing is kept in the browser storage`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        for ((label, path, _) in sections) {
            e2e.touch(page.navLink(label))
            assertThat(page).hasURL(e2e.url(path))
        }

        page.goBack()
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertThat(page).hasTitle("Iscrizioni · Talità")
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        assertThat(page.locator("nav a[aria-current=page]")).hasText("Iscrizioni")

        page.goBack()
        assertThat(page).hasURL(e2e.url("/convivenza"))
        assertThat(page).hasTitle("Convivenza · Talità")
        assertThat(page.locator("h1")).hasText("Convivenza")
        assertThat(page.locator("nav a[aria-current=page]")).hasText("Convivenza")

        assertEquals(0, (page.evaluate("localStorage.length") as Number).toInt(), "localStorage is not empty")
        assertEquals(
            emptyList<String>(),
            page.evaluate("Object.keys(sessionStorage)") as List<*>,
            "sessionStorage is not empty",
        )
    }

    @E2eTest
    fun `c23 without JavaScript touching an entry loads the page of the section`(e2e: E2e) {
        val page = e2e.newPage { setJavaScriptEnabled(false) }
        page.navigate(e2e.url("/"))

        e2e.touch(page.navLink("Convivenza"))

        assertThat(page).hasURL(e2e.url("/convivenza"))
        assertThat(page.locator("h1")).hasText("Convivenza")
        assertThat(page.locator("nav a[aria-current=page]")).hasText("Convivenza")
    }

    @E2eTest
    fun `c24 while the answer is late a loading indicator is visible, then it goes away`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        val indicatorOpacity = "getComputedStyle(document.getElementById('caricamento')).opacity"

        // The test holds the answer back, as a slow network would.
        val held = AtomicReference<Route>()
        page.route("**/convivenza") { route -> held.set(route) }
        e2e.touch(page.navLink("Convivenza"))

        page.waitForFunction("$indicatorOpacity === '1'")
        // Until the content arrives, the old one stays (a little dimmed) and so does the address.
        page.waitForTimeout(1000.0)
        assertNotNull(held.get(), "the request never left")
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")
        assertEquals(e2e.url("/"), page.url())
        assertEquals("1", page.evaluate(indicatorOpacity))

        held.get().resume()

        assertThat(page.locator("h1")).hasText("Convivenza")
        page.waitForFunction("$indicatorOpacity === '0'")
    }

    @E2eTest
    fun `c27 without network the message appears, the content stays and the navigation works again when the network is back`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")

        e2e.browserContext.setOffline(true)
        e2e.touch(page.navLink("Iscrizioni"))

        assertThat(page.messages()).hasCount(1)
        assertThat(page.messages()).containsText("Nessuna connessione")
        assertThat(page.messages()).containsText("Controlla di avere campo o il Wi-Fi e riprova.")
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")
        assertEquals(e2e.url("/"), page.url())
        page.waitForFunction("getComputedStyle(document.getElementById('caricamento')).opacity === '0'")

        // "Riprova" repeats the request: still offline, so the message is back, not doubled.
        page.messages().getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Riprova")).click()
        assertThat(page.messages()).hasCount(1)

        e2e.browserContext.setOffline(false)
        e2e.touch(page.navLink("Iscrizioni"))

        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        assertThat(page.messages()).hasCount(0)
    }

    @E2eTest
    fun `the message of a lost connection can be closed`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        e2e.browserContext.setOffline(true)
        e2e.touch(page.navLink("Iscrizioni"))
        assertThat(page.messages()).hasCount(1)

        e2e.touch(page.messages().getByLabel("Chiudi"))

        assertThat(page.messages()).hasCount(0)
    }

    @E2eTest
    fun `touching two entries in a row, the last touch wins and nothing is reported as an error`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")

        // The first answer is held back, so the second touch comes while it is still on its way.
        val held = AtomicReference<Route>()
        page.route("**/convivenza") { route -> held.set(route) }
        e2e.touch(page.navLink("Convivenza"))
        page.waitForFunction("document.querySelector('a.htmx-request') !== null")
        e2e.touch(page.navLink("Iscrizioni"))

        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        held.get()?.resume()
        page.waitForTimeout(500.0)

        assertEquals(e2e.url("/iscrizioni"), page.url(), "the late answer of the first touch took over")
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        assertThat(page.messages()).hasCount(0)
        assertTrue(page.locator("nav a[aria-current=page]").innerText().contains("Iscrizioni"))
    }
}
