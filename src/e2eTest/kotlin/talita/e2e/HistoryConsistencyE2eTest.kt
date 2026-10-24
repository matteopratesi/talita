package talita.e2e

import com.deque.html.axecore.playwright.AxeBuilder
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Round two of the QA of KOINE-11: the back and forward buttons with and without network, seen as a
 * whole trip. The property that must always hold is that the address and the page on screen belong
 * together (same page, same title, same current entry), whatever happens in between.
 */
@E2eApplication
class HistoryConsistencyE2eTest {

    private data class Known(val heading: String, val title: String, val current: String)

    private val pages = mapOf(
        "/" to Known("Ti diamo il benvenuto", "Talità", "Home"),
        "/convivenza" to Known("Convivenza", "Convivenza · Talità", "Convivenza"),
        "/iscrizioni" to Known("Iscrizioni", "Iscrizioni · Talità", "Iscrizioni"),
        "/arrivi" to Known("Arrivi", "Arrivi · Talità", "Arrivi"),
    )

    /** What the page shows, as a short line for the messages of a failure. */
    private fun shown(page: Page): String =
        "address ${URI(page.url()).path}, title '${page.title()}', heading '${page.locator("h1").first().innerText().trim()}', " +
            "current ${page.mainNavigation().locator("a[aria-current=page]").allInnerTexts().map { it.trim() }}"

    /**
     * Waits (a back is answered a moment later) until address and page agree, and fails with what it saw
     * if they never do.
     */
    private fun assertTogether(page: Page, step: String) {
        var last = ""
        repeat(60) {
            val path = URI(page.url()).path
            val known = pages[path] ?: fail("$step: unexpected address $path")
            val busy = page.locator(".htmx-request, .htmx-settling, .htmx-swapping, .htmx-added").count() > 0
            last = shown(page)
            val together = !busy &&
                page.title() == known.title &&
                page.locator("h1").first().innerText().trim() == known.heading &&
                page.mainNavigation().locator("a[aria-current=page]").allInnerTexts().map { it.trim() } == listOf(known.current)
            if (together) return
            page.waitForTimeout(100.0)
        }
        fail("$step: address and page are apart: $last")
    }

    private fun retry(e2e: E2e): Locator =
        e2e.page.messages().getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Riprova"))

    @E2eTest
    fun `a trip with failed backs and forwards never leaves address and page apart`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        for (label in listOf("Iscrizioni", "Convivenza", "Arrivi")) {
            e2e.touch(page.navLink(label))
            assertThat(page.locator("h1")).hasText(label)
        }
        assertTogether(page, "after three changes of page")

        e2e.browserContext.setOffline(true)
        page.goBack()
        assertThat(page.messages()).hasCount(1)
        assertTogether(page, "first failed back")
        page.goBack()
        assertThat(page.messages()).hasCount(1)
        assertTogether(page, "second failed back")
        page.goForward()
        assertThat(page.messages()).hasCount(1)
        assertTogether(page, "failed forward")

        // The network is back: the page that the last gesture was going to is one touch away.
        e2e.browserContext.setOffline(false)
        e2e.touch(retry(e2e))
        assertThat(page.messages()).hasCount(0)
        assertTogether(page, "Riprova after the failures")

        // And from there on, back and forward are the ones of a normal visit.
        page.goBack()
        assertTogether(page, "back on line")
        page.goBack()
        assertTogether(page, "second back on line")
        page.goForward()
        page.goForward()
        assertTogether(page, "forward on line")
    }

    @E2eTest
    fun `a failed back does not hide a page change that works afterwards`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        e2e.touch(page.navLink("Iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        e2e.touch(page.navLink("Convivenza"))
        assertThat(page.locator("h1")).hasText("Convivenza")

        e2e.browserContext.setOffline(true)
        page.goBack()
        assertThat(page.messages()).hasCount(1)
        e2e.browserContext.setOffline(false)

        // Not the Riprova: an ordinary touch on the navigation.
        e2e.touch(page.navLink("Arrivi"))

        assertThat(page).hasURL(e2e.url("/arrivi"))
        assertTogether(page, "after a touch on the navigation")
        assertThat(page.messages()).hasCount(0)
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `a back that gets no answer for 20 seconds shows the message of the slow connection`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        e2e.touch(page.navLink("Convivenza"))
        assertThat(page.locator("h1")).hasText("Convivenza")
        e2e.touch(page.navLink("Iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")

        // The server never answers the page that the back button asks for.
        val held = AtomicReference<Route>()
        page.route("**/convivenza") { held.set(it) }
        page.goBack()
        assertThat(page.messages()).hasCount(0)

        assertThat(page.messages()).containsText(
            "La connessione è troppo lenta",
            LocatorAssertions.ContainsTextOptions().setTimeout(25_000.0),
        )
        assertThat(page.messages()).containsText("Talità non ha risposto in tempo. Riprova quando hai più campo.")
        assertTogether(page, "after the slow back")
    }

    private fun assertNothingKept(page: Page, step: String) {
        page.waitUntil("document.querySelector('.htmx-request, .htmx-settling, .htmx-swapping, .htmx-added') === null")
        assertEquals(0, (page.evaluate("localStorage.length") as Number).toInt(), "$step: localStorage is not empty")
        assertEquals(emptyList<String>(), page.evaluate("Object.keys(sessionStorage)") as List<*>, "$step: sessionStorage is not empty")
    }

    @E2eTest
    fun `nothing is kept in the browser after a back to a page of error or a failed back`(e2e: E2e) {
        val page = e2e.page
        // A back that reloads the page (the 404 opened directly) ...
        e2e.open("/pagina-che-non-esiste")
        e2e.touch(page.locator("main a").filter(Locator.FilterOptions().setHasText("Vai alla home")))
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")
        page.goBack()
        assertThat(page.locator("h1")).hasText("Pagina non trovata")
        assertNothingKept(page, "after the back that reloads the page")

        // ... and a back that fails for lack of network.
        page.navigate(e2e.url("/"))
        e2e.touch(page.navLink("Iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        e2e.touch(page.navLink("Convivenza"))
        assertThat(page.locator("h1")).hasText("Convivenza")
        e2e.browserContext.setOffline(true)
        page.goBack()
        assertThat(page.messages()).hasCount(1)
        assertTogether(page, "failed back")
        assertNothingKept(page, "after the failed back")
    }

    @E2eTest
    fun `axe-core finds no serious or critical violation after a failed back`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        e2e.touch(page.navLink("Iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        e2e.touch(page.navLink("Convivenza"))
        assertThat(page.locator("h1")).hasText("Convivenza")
        e2e.browserContext.setOffline(true)
        page.goBack()
        assertThat(page.messages()).hasCount(1)
        page.waitUntil("document.querySelector('#messaggi [role=alert]').getAnimations().length === 0")

        val violations = AxeBuilder(page).analyze().violations
            .filter { it.impact == "serious" || it.impact == "critical" }
            .map { "${it.id} (${it.impact}): ${it.help}, nodes: ${it.nodes.map { n -> n.html }}" }

        assertEquals(emptyList<String>(), violations)
    }

    // ---- Defect found in the second round of the QA (D6), now fixed: these tests guard against a regression. ----

    @E2eTest
    fun `a back while another back is still waiting for its answer, the last one wins`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        for (label in listOf("Iscrizioni", "Convivenza", "Arrivi")) {
            e2e.touch(page.navLink(label))
            assertThat(page.locator("h1")).hasText(label)
        }
        // The page that the first back asks for is slow; the second one is answered at once.
        val held = AtomicReference<Route>()
        page.route("**/convivenza") { held.set(it) }
        page.goBack()
        page.waitForTimeout(300.0)
        page.goBack()
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")

        runCatching { held.get()?.resume() }
        page.waitForTimeout(600.0)

        assertTogether(page, "after the late answer of the first back")
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
    }

    @E2eTest
    fun `a touch on an entry while a back is waiting for its answer wins over the late answer`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        e2e.touch(page.navLink("Iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        e2e.touch(page.navLink("Convivenza"))
        assertThat(page.locator("h1")).hasText("Convivenza")

        val held = AtomicReference<Route>()
        page.route("**/iscrizioni") { held.set(it) }
        page.goBack()
        page.waitForTimeout(300.0)
        e2e.touch(page.navLink("Arrivi"))
        assertThat(page).hasURL(e2e.url("/arrivi"))
        assertThat(page.locator("h1")).hasText("Arrivi")

        runCatching { held.get()?.resume() }
        page.waitForTimeout(600.0)

        assertTogether(page, "after the late answer of the back")
        assertThat(page).hasURL(e2e.url("/arrivi"))
    }

    @E2eTest
    fun `a forward while another forward is still waiting for its answer, the last one wins`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        for (label in listOf("Iscrizioni", "Convivenza")) {
            e2e.touch(page.navLink(label))
            assertThat(page.locator("h1")).hasText(label)
        }
        page.goBack()
        page.goBack()
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")

        // The page that the first forward asks for is slow; the second one is answered at once.
        val held = AtomicReference<Route>()
        page.route("**/iscrizioni") { held.set(it) }
        page.goForward()
        page.waitForTimeout(300.0)
        page.goForward()
        assertThat(page).hasURL(e2e.url("/convivenza"))
        assertThat(page.locator("h1")).hasText("Convivenza")

        runCatching { held.get()?.resume() }
        page.waitForTimeout(600.0)

        assertTogether(page, "after the late answer of the first forward")
        assertThat(page).hasURL(e2e.url("/convivenza"))
    }

    @E2eTest
    fun `a forward after a back that is still waiting for its answer, the forward wins`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        for (label in listOf("Iscrizioni", "Convivenza", "Arrivi")) {
            e2e.touch(page.navLink(label))
            assertThat(page.locator("h1")).hasText(label)
        }
        val held = AtomicReference<Route>()
        page.route("**/convivenza") { held.set(it) }
        page.goBack()
        page.waitForTimeout(300.0)
        page.goForward()
        assertThat(page).hasURL(e2e.url("/arrivi"))

        runCatching { held.get()?.resume() }
        page.waitForTimeout(600.0)

        assertTogether(page, "after the late answer of the back")
        assertThat(page).hasURL(e2e.url("/arrivi"))
    }

    @org.junit.jupiter.api.Disabled("Osservazione KOINE-11 (D7, bassa, da portare in un ticket a parte): un indietro che aspetta la risposta viene annullato da un tocco su una voce, e se anche quel tocco fallisce per mancanza di rete l'indirizzo resta quello dell'indietro mentre la pagina è l'ultima visibile. Succede solo con indietro lento, tocco e rete che cade nello stesso momento.")
    @E2eTest
    fun `a touch that fails while a back was waiting does not leave address and page apart`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        for (label in listOf("Iscrizioni", "Convivenza", "Arrivi")) {
            e2e.touch(page.navLink(label))
            assertThat(page.locator("h1")).hasText(label)
        }
        val held = AtomicReference<Route>()
        page.route("**/convivenza") { held.set(it) }
        page.goBack()
        page.waitForTimeout(300.0)

        e2e.browserContext.setOffline(true)
        e2e.touch(page.navLink("Iscrizioni"))
        assertThat(page.messages()).hasCount(1)
        e2e.browserContext.setOffline(false)
        runCatching { held.get()?.resume() }
        page.waitForTimeout(600.0)

        // The page on screen is still Arrivi (nothing succeeded), so that is the address too.
        assertEquals("/arrivi", URI(page.url()).path, "the address is ${page.url()} but the page is ${page.locator("h1").first().innerText()}")
    }
}
