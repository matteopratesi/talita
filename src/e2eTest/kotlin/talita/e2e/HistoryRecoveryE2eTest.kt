package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals

/**
 * What happens around the back button when something goes wrong (KOINE-11, after the defects found by
 * the QA): trying again, the forward button, a back while a page change is on its way.
 */
@E2eApplication
class HistoryRecoveryE2eTest {

    private fun retryButton(e2e: E2e) =
        e2e.page.messages().getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Riprova"))

    @E2eTest
    fun `after a failed back, Riprova leads to the page that the back button was going to once the network is back`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        e2e.touch(page.navLink("Iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        e2e.touch(page.navLink("Convivenza"))
        assertThat(page.locator("h1")).hasText("Convivenza")

        e2e.browserContext.setOffline(true)
        page.goBack()
        assertThat(page.messages()).containsText("Nessuna connessione")
        assertThat(page).hasURL(e2e.url("/convivenza"))

        // Still no network: the same message, once.
        e2e.touch(retryButton(e2e))
        assertThat(page.messages()).hasCount(1)
        assertThat(page).hasURL(e2e.url("/convivenza"))
        assertThat(page.locator("h1")).hasText("Convivenza")

        e2e.browserContext.setOffline(false)
        e2e.touch(retryButton(e2e))

        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        assertThat(page).hasTitle("Iscrizioni · Talità")
        assertThat(page.locator("nav a[aria-current=page]")).hasText("Iscrizioni")
        assertThat(page.messages()).hasCount(0)
    }

    @E2eTest
    fun `forward without network shows the message and keeps address and page together`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        e2e.touch(page.navLink("Iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        page.goBack()
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")

        e2e.browserContext.setOffline(true)
        page.goForward()

        assertThat(page.messages()).hasCount(1)
        assertThat(page.messages()).containsText("Nessuna connessione")
        assertThat(page).hasURL(e2e.url("/"))
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")
    }

    @E2eTest
    fun `a back while a page change is on its way wins, and the late answer changes nothing`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        e2e.touch(page.navLink("Iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")

        val held = AtomicReference<Route>()
        page.route("**/convivenza") { held.set(it) }
        e2e.touch(page.navLink("Convivenza"))
        page.waitUntil("document.querySelector('a.htmx-request') !== null")

        page.goBack()
        assertThat(page).hasURL(e2e.url("/"))
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")
        runCatching { held.get()?.resume() }
        page.waitForTimeout(500.0)

        assertEquals(e2e.url("/"), page.url())
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")
        assertThat(page.messages()).hasCount(0)
    }

    @E2eTest
    fun `back to a page of error and forward again, both show the right page`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/pagina-che-non-esiste")
        e2e.touch(page.locator("main a").filter(Locator.FilterOptions().setHasText("Vai alla home")))
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")

        page.goBack()
        assertThat(page).hasURL(e2e.url("/pagina-che-non-esiste"))
        assertThat(page.locator("h1")).hasText("Pagina non trovata")

        page.goForward()
        assertThat(page).hasURL(e2e.url("/"))
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")
        assertThat(page.locator("nav a[aria-current=page]")).hasText("Home")
    }

    @E2eTest
    fun `requests for fragments do not make the loading bar flash either`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.addToContent("""<div id="f">prima</div><button id="carica" type="button" hx-get="/test/frammento" hx-target="#f">Carica</button>""")

        e2e.touch(page.locator("#carica"))

        assertThat(page.locator("#f")).hasText("Frammento caricato")
        page.waitUntil("document.querySelectorAll('.htmx-request').length === 0")
        assertEquals("0", page.evaluate("getComputedStyle(document.getElementById('caricamento')).opacity"))
    }
}
