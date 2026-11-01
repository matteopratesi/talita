package talita.e2e

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals

/**
 * Moving around the app beyond criteria 21-23: nothing is kept in the browser at any step, the back
 * button asks the server, double touches, no JavaScript, and the cases of the history that went wrong
 * once (the pages of error, no network on a back, requests that must not cancel each other: defects
 * found by the QA of KOINE-11).
 */
@E2eApplication
class HistoryE2eTest {

    /** The names of the pages, to tell that address and content go together. */
    private val headings = mapOf("/" to "Ti diamo il benvenuto", "/convivenza" to "Convivenza", "/iscrizioni" to "Iscrizioni", "/arrivi" to "Arrivi")

    /** What criterion 22 asks, and a little more: no copy of the pages in any browser memory. */
    private fun assertNothingKept(page: Page, step: String) {
        page.waitForHtmxIdle()
        assertEquals(0, (page.evaluate("localStorage.length") as Number).toInt(), "$step: localStorage is not empty")
        assertEquals(emptyList<String>(), page.evaluate("Object.keys(sessionStorage)") as List<*>, "$step: sessionStorage is not empty")
        assertEquals(emptyList<String>(), page.evaluate("caches.keys()") as List<*>, "$step: the Cache API is not empty")
    }

    @E2eTest
    fun `nothing is kept in the browser at any step of the navigation`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        assertNothingKept(page, "first visit")

        e2e.touch(page.navLink("Convivenza"))
        assertThat(page.locator("h1")).hasText("Convivenza")
        assertNothingKept(page, "after a change of page")

        e2e.touch(page.navLink("Iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        assertNothingKept(page, "after a second change of page")

        page.goBack()
        assertThat(page).hasURL(e2e.url("/convivenza"))
        assertNothingKept(page, "after back")

        page.goForward()
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertNothingKept(page, "after forward")

        page.addToContent("""<a id="link-rotto" href="/altra-pagina-che-non-esiste">x</a>""")
        e2e.touch(page.locator("#link-rotto"))
        assertThat(page.locator("h1")).hasText("Pagina non trovata")
        assertNothingKept(page, "on the page not found")

        e2e.touch(page.navLink("Home"))
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")
        page.addToContent("""<a id="link-guasto" href="/test/errore">y</a>""")
        e2e.touch(page.locator("#link-guasto"))
        assertThat(page.locator("h1")).hasText("Qualcosa non ha funzionato")
        assertNothingKept(page, "on the page of the error")

        e2e.browserContext.setOffline(true)
        e2e.touch(page.navLink("Arrivi"))
        assertThat(page.messages()).hasCount(1)
        assertNothingKept(page, "with the message of the lost connection")
        e2e.browserContext.setOffline(false)

        page.reload()
        assertNothingKept(page, "after a full reload")
    }

    @E2eTest
    fun `the back button asks the server for the page, it is never served from a copy`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        e2e.touch(page.navLink("Convivenza"))
        assertThat(page.locator("h1")).hasText("Convivenza")
        e2e.touch(page.navLink("Iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")

        val request = page.waitForRequest({ it.url().endsWith("/convivenza") }) { page.goBack() }

        assertEquals("true", request.allHeaders()["hx-history-restore-request"], "the page was not asked for as a restoration of the history")
        assertThat(page.locator("h1")).hasText("Convivenza")
        assertThat(page).hasTitle("Convivenza · Talità")
        assertThat(page.mainNavigation().locator("a[aria-current=page]")).hasText("Convivenza")
    }

    @E2eTest
    fun `touching the same entry twice while the first answer is late, the last touch wins and nothing is reported as an error`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        val requests = AtomicInteger()
        val first = AtomicReference<Route>()
        page.route("**/convivenza") { route -> if (requests.incrementAndGet() == 1) first.set(route) else route.resume() }

        e2e.touch(page.navLink("Convivenza"))
        page.waitUntil("document.querySelector('a.htmx-request') !== null")
        e2e.touch(page.navLink("Convivenza"))

        assertThat(page).hasURL(e2e.url("/convivenza"))
        assertThat(page.locator("h1")).hasText("Convivenza")
        // The page gave up on the first request: its late answer changes nothing.
        runCatching { first.get()?.resume() }
        page.waitForTimeout(500.0)
        assertEquals(2, requests.get(), "both touches should have sent a request")
        assertEquals(e2e.url("/convivenza"), page.url())
        assertThat(page.locator("h1")).hasText("Convivenza")
        assertThat(page.messages()).hasCount(0)
        page.waitForHtmxIdle()
        assertEquals("Convivenza", page.mainNavigation().locator("a[aria-current=page]").innerText().trim())
    }

    @E2eTest
    fun `a quick double touch on an entry leads to its page with no error`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")

        if (e2e.profile.isPhone) {
            page.navLink("Iscrizioni").tap()
            page.navLink("Iscrizioni").tap()
        } else {
            page.navLink("Iscrizioni").dblclick()
        }

        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        page.waitForTimeout(500.0)
        assertThat(page.messages()).hasCount(0)
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        assertEquals(1, page.mainNavigation().locator("a[aria-current=page]").count())
        page.waitForHtmxIdle()
    }

    @E2eTest
    fun `without JavaScript the pages of error come from the server with their texts and their way out`(e2e: E2e) {
        val page = e2e.newPage { setJavaScriptEnabled(false) }

        val notFound = requireNotNull(page.navigate(e2e.url("/pagina-che-non-esiste")))
        assertEquals(404, notFound.status())
        assertThat(page).hasTitle("Pagina non trovata · Talità")
        assertThat(page.locator("h1")).hasText("Pagina non trovata")
        assertThat(page.locator("main")).containsText("L’indirizzo potrebbe essere sbagliato, oppure la pagina non esiste più.")
        assertEquals(navigationLabels, page.mainNavigation().locator("a").allInnerTexts().map { it.trim() })
        assertEquals(0, page.mainNavigation().locator("[aria-current]").count())
        // (Playwright cannot wait for the entrance animation to be "stable" with the scripts off: force the click.)
        page.locator("main a").filter(com.microsoft.playwright.Locator.FilterOptions().setHasText("Vai alla home")).click(com.microsoft.playwright.Locator.ClickOptions().setForce(true))
        assertThat(page).hasURL(e2e.url("/"))
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")

        val failure = requireNotNull(page.navigate(e2e.url("/test/errore")))
        assertEquals(500, failure.status())
        assertThat(page).hasTitle("Qualcosa non ha funzionato · Talità")
        assertThat(page.locator("h1")).hasText("Qualcosa non ha funzionato")
        assertThat(page.locator("main")).containsText("Non è colpa tua: c’è stato un problema in Talità.")
        val retry = page.locator("main a").filter(com.microsoft.playwright.Locator.FilterOptions().setHasText("Riprova"))
        assertThat(retry).hasAttribute("href", "/test/errore")
        retry.click(com.microsoft.playwright.Locator.ClickOptions().setForce(true))
        assertThat(page.locator("h1")).hasText("Qualcosa non ha funzionato")
        assertThat(page).hasURL(e2e.url("/test/errore"))
    }

    // ---- Defects found by the QA (KOINE-11) ----

    private fun openBrokenLinkThenHomeThenBack(e2e: E2e, brokenPath: String) {
        val page = e2e.page
        e2e.open("/")
        page.addToContent("""<a id="link-rotto" href="$brokenPath">x</a>""")
        e2e.touch(page.locator("#link-rotto"))
        assertThat(page).hasURL(e2e.url(brokenPath))
        e2e.touch(page.navLink("Home"))
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")

        page.goBack()
    }

    @E2eTest
    fun `back to a page not found shows that page again`(e2e: E2e) {
        val page = e2e.page
        openBrokenLinkThenHomeThenBack(e2e, "/altra-pagina-che-non-esiste")

        assertThat(page).hasURL(e2e.url("/altra-pagina-che-non-esiste"))
        assertThat(page).hasTitle("Pagina non trovata · Talità")
        assertThat(page.locator("h1")).hasText("Pagina non trovata")
        assertEquals(0, page.mainNavigation().locator("[aria-current]").count())
    }

    @E2eTest
    fun `back to a page not found opened directly shows that page again`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/indirizzo-che-non-esiste")
        e2e.touch(page.locator("main a").filter(com.microsoft.playwright.Locator.FilterOptions().setHasText("Vai alla home")))
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")

        page.goBack()

        assertThat(page).hasURL(e2e.url("/indirizzo-che-non-esiste"))
        assertThat(page).hasTitle("Pagina non trovata · Talità")
        assertThat(page.locator("h1")).hasText("Pagina non trovata")
        assertEquals(0, page.mainNavigation().locator("[aria-current]").count())
    }

    @E2eTest
    fun `back to a page of error shows that page again`(e2e: E2e) {
        val page = e2e.page
        openBrokenLinkThenHomeThenBack(e2e, "/test/errore")

        assertThat(page).hasURL(e2e.url("/test/errore"))
        assertThat(page).hasTitle("Qualcosa non ha funzionato · Talità")
        assertThat(page.locator("h1")).hasText("Qualcosa non ha funzionato")
        assertEquals(0, page.mainNavigation().locator("[aria-current]").count())
    }

    @E2eTest
    fun `back without network shows the message and never leaves address and page apart`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        e2e.touch(page.navLink("Iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        e2e.touch(page.navLink("Convivenza"))
        assertThat(page.locator("h1")).hasText("Convivenza")

        e2e.browserContext.setOffline(true)
        page.goBack()

        assertThat(page.messages()).hasCount(1)
        assertThat(page.messages()).containsText("Nessuna connessione")
        val path = java.net.URI(page.url()).path
        assertEquals(headings.getValue(path), page.locator("h1").innerText().trim(), "the address is $path but the page is another one")
    }

    @E2eTest
    fun `two independent requests started together both complete`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")

        // A page with two parts that load by themselves, as the screens of M1 will have.
        page.addToContent(
            """<div id="a" hx-get="/test/frammento" hx-trigger="load" hx-swap="innerHTML">a-prima</div>
               <div id="b" hx-get="/test/frammento" hx-trigger="load" hx-swap="innerHTML">b-prima</div>""",
        )

        assertThat(page.locator("#a")).hasText("Frammento caricato")
        assertThat(page.locator("#b")).hasText("Frammento caricato")
    }

    @E2eTest
    fun `a request that does not change the page does not cancel a page change in progress`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.addToContent("""<div id="f">f-prima</div><button id="carica" type="button" hx-get="/test/frammento" hx-target="#f">Carica</button>""")
        val held = AtomicReference<Route>()
        page.route("**/convivenza") { held.set(it) }

        e2e.touch(page.navLink("Convivenza"))
        page.waitUntil("document.querySelector('a.htmx-request') !== null")
        e2e.touch(page.locator("#carica"))
        assertThat(page.locator("#f")).hasText("Frammento caricato")
        runCatching { held.get()?.resume() }

        assertThat(page).hasURL(e2e.url("/convivenza"))
        assertThat(page.locator("h1")).hasText("Convivenza")
    }

    @E2eTest
    fun `three quick touches on three entries, the last one wins and nothing is reported as an error`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        val held = java.util.concurrent.CopyOnWriteArrayList<Route>()
        page.route("**/convivenza") { held += it }
        page.route("**/iscrizioni") { held += it }

        e2e.touch(page.navLink("Convivenza"))
        page.waitUntil("document.querySelector('a.htmx-request') !== null")
        e2e.touch(page.navLink("Iscrizioni"))
        page.waitUntil("document.querySelector('a.htmx-request')?.getAttribute('href') === '/iscrizioni'")
        e2e.touch(page.navLink("Arrivi"))

        assertThat(page).hasURL(e2e.url("/arrivi"))
        assertThat(page.locator("h1")).hasText("Arrivi")
        // The answers that were still on their way change nothing.
        held.forEach { runCatching { it.resume() } }
        page.waitForTimeout(500.0)
        assertEquals(e2e.url("/arrivi"), page.url())
        assertThat(page.locator("h1")).hasText("Arrivi")
        assertThat(page.messages()).hasCount(0)
        page.waitForHtmxIdle()
        assertEquals("Arrivi", page.mainNavigation().locator("a[aria-current=page]").innerText().trim())
    }
}
