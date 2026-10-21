package talita.e2e

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.ReducedMotion
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The loading state of the design (sections 4.10 and 2.9) beyond criterion 24: what the bar looks
 * like, when it appears, what happens to the content and to the touched entry, "reduce motion".
 */
@E2eApplication
class LoadingStateE2eTest {

    private val bar = "document.getElementById('caricamento')"

    /** Writes into `window.__samples`, every frame, the opacity of the bar and of the content. */
    private val sampler = """() => {
        window.__samples = [];
        const start = performance.now();
        const tick = () => {
            window.__samples.push({
                t: performance.now() - start,
                bar: parseFloat(getComputedStyle(document.getElementById('caricamento')).opacity),
                main: parseFloat(getComputedStyle(document.querySelector('main')).opacity),
                entry: getComputedStyle(document.querySelector('nav a[href="/convivenza"]')).backgroundColor,
            });
            window.__frame = requestAnimationFrame(tick);
        };
        tick();
    }"""

    @Suppress("UNCHECKED_CAST")
    private fun samples(page: Page): List<Map<String, Any>> = page.evaluate("window.__samples") as List<Map<String, Any>>

    private fun Map<String, Any>.number(key: String) = (getValue(key) as Number).toDouble()

    @E2eTest
    fun `while the answer is late the bar is 4 px at the top, the content is dimmed and the touched entry is darker`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.waitForSettledContent()
        val held = AtomicReference<Route>()
        page.route("**/convivenza") { held.set(it) }

        e2e.touch(page.navLink("Convivenza"))
        page.waitForFunction("parseFloat(getComputedStyle($bar).opacity) === 1")

        // The bar: 4 px, on the top edge, as wide as the screen, in the blue of the actions.
        val barBox = page.locator("#caricamento").box()
        assertEquals(0.0, barBox.y)
        assertEquals(4.0, barBox.height)
        assertEquals(page.viewport().width.toDouble(), barBox.width)
        assertEquals("rgb(42, 75, 160)", page.evaluate("getComputedStyle(document.querySelector('#caricamento > div')).backgroundColor"))
        assertEquals("true", page.locator("#caricamento").getAttribute("aria-hidden"), "the bar is not hidden from screen readers")
        // The content before the change is dimmed to 60% (it is still the old page), the touched entry is darker.
        page.waitForFunction("parseFloat(getComputedStyle(document.querySelector('main')).opacity) === 0.6")
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")
        assertEquals("rgb(239, 237, 232)", page.evaluate("getComputedStyle(document.querySelector('nav a[href=\"/convivenza\"]')).backgroundColor"))
        // It never blocks the page: no layer over the content catches the touches.
        assertEquals("none", page.evaluate("getComputedStyle($bar).pointerEvents"))

        held.get().resume()
        assertThat(page.locator("h1")).hasText("Convivenza")
        page.waitForFunction("parseFloat(getComputedStyle($bar).opacity) === 0")
        page.waitForSettledContent()
        assertEquals(1.0, (page.evaluate("parseFloat(getComputedStyle(document.querySelector('main')).opacity)") as Number).toDouble())
        assertEquals(0, page.locator(".htmx-request").count())
    }

    // Phone only: on the desktop the entry is darkened by the mouse over it before any request.
    @E2eTest(profiles = [Profile.PHONE])
    fun `the touched entry darkens before the bar appears`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.waitForSettledContent()
        page.evaluate(sampler)
        val held = AtomicReference<Route>()
        page.route("**/convivenza") { held.set(it) }

        e2e.touch(page.navLink("Convivenza"))
        page.waitForTimeout(900.0)
        held.get().resume()
        assertThat(page.locator("h1")).hasText("Convivenza")

        val all = samples(page)
        val darker = all.firstOrNull { it["entry"] == "rgb(239, 237, 232)" }?.number("t")
        val barVisible = all.firstOrNull { it.number("bar") > 0.05 }?.number("t")
        val dimmed = all.firstOrNull { it.number("main") < 0.99 }?.number("t")
        assertTrue(darker != null && barVisible != null && dimmed != null, "samples: darker=$darker bar=$barVisible dimmed=$dimmed")
        assertTrue(darker < barVisible - 100, "the entry darkens at $darker ms, the bar appears at $barVisible ms")
        // The bar and the dimmed content wait the same time: 300 ms after the touch, not before.
        assertTrue(barVisible - darker >= 100 && dimmed >= darker, "bar at $barVisible ms, content dimmed at $dimmed ms, entry darker at $darker ms")
    }

    @E2eTest
    fun `a fast answer does not make the bar flash`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.waitForSettledContent()
        page.evaluate(sampler)
        // The server answers in about 100 ms: well under the 300 ms of the design.
        page.route("**/iscrizioni") { route -> Thread.sleep(100); route.resume() }

        e2e.touch(page.navLink("Iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        page.waitForTimeout(600.0)

        val brightest = samples(page).maxOf { it.number("bar") }
        assertTrue(brightest < 0.05, "the bar reached opacity $brightest on a fast answer")
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `with reduce motion the bar is still and full`(e2e: E2e) {
        val page = e2e.newPage { setReducedMotion(ReducedMotion.REDUCE) }
        page.navigate(e2e.url("/"))
        page.waitForSettledContent()
        val held = AtomicReference<Route>()
        page.route("**/convivenza") { held.set(it) }

        page.navLink("Convivenza").tap()
        page.waitForFunction("parseFloat(getComputedStyle($bar).opacity) === 1")

        assertEquals(360.0, page.locator("#caricamento > div").box().width, "the bar is not full width")
        assertEquals(0, (page.evaluate("document.querySelector('#caricamento > div').getAnimations().length") as Number).toInt(), "the bar is moving")
        // And the content does not slide in.
        held.get().resume()
        assertThat(page.locator("h1")).hasText("Convivenza")
        assertEquals("none", page.evaluate("getComputedStyle(document.querySelector('main')).animationName"), "the content is animated")
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `without reduce motion the bar moves`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        val held = AtomicReference<Route>()
        page.route("**/convivenza") { held.set(it) }

        e2e.touch(page.navLink("Convivenza"))
        page.waitForFunction("parseFloat(getComputedStyle($bar).opacity) === 1")

        assertEquals("progress", page.evaluate("getComputedStyle(document.querySelector('#caricamento > div')).animationName"))
        held.get().resume()
        assertThat(page.locator("h1")).hasText("Convivenza")
    }

    @E2eTest
    fun `the bar goes away after a request that fails, with or without network`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.addToContent(
            """<div id="bersaglio"><p>Contenuto di prima</p></div>
               <button id="carica" type="button" hx-get="/test/errore" hx-target="#bersaglio">Carica</button>""",
        )

        e2e.touch(page.locator("#carica"))
        assertThat(page.messages()).hasCount(1)
        page.waitForFunction("parseFloat(getComputedStyle($bar).opacity) === 0")
        assertEquals(0, page.locator(".htmx-request").count())

        e2e.browserContext.setOffline(true)
        e2e.touch(page.navLink("Iscrizioni"))
        page.waitForFunction("document.querySelectorAll('#messaggi [role=alert]').length === 2")
        page.waitForFunction("parseFloat(getComputedStyle($bar).opacity) === 0")
        assertEquals(0, page.locator(".htmx-request").count())
    }

    @E2eTest
    fun `changing to the page not found or to the page of error does not make the bar flash`(e2e: E2e) {
        val page = e2e.page
        for (broken in listOf("/altra-pagina-che-non-esiste", "/test/errore")) {
            e2e.open("/")
            page.waitForSettledContent()
            page.addToContent("""<a id="link-rotto" href="$broken">x</a>""")
            page.evaluate(sampler)

            e2e.touch(page.locator("#link-rotto"))
            assertThat(page).hasURL(e2e.url(broken))
            page.waitForTimeout(600.0)

            val brightest = samples(page).maxOf { it.number("bar") }
            assertTrue(brightest < 0.05, "$broken: the bar reached opacity $brightest on a fast answer")
        }
    }

    @E2eTest
    fun `a fast request for a fragment does not make the bar appear`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.waitForSettledContent()
        page.addToContent("""<div id="f">prima</div><button id="carica" type="button" hx-get="/test/frammento" hx-target="#f">Carica</button>""")
        page.evaluate(sampler)

        e2e.touch(page.locator("#carica"))
        assertThat(page.locator("#f")).hasText("Frammento caricato")
        page.waitForTimeout(600.0)

        val brightest = samples(page).maxOf { it.number("bar") }
        assertTrue(brightest < 0.05, "the bar reached opacity $brightest on a fast fragment")
    }

    @E2eTest
    fun `a slow request for a fragment shows the bar but does not dim the page`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.waitForSettledContent()
        page.addToContent("""<div id="f">prima</div><button id="carica" type="button" hx-get="/test/frammento" hx-target="#f">Carica</button>""")
        val held = AtomicReference<Route>()
        page.route("**/test/frammento") { held.set(it) }

        e2e.touch(page.locator("#carica"))
        page.waitForFunction("parseFloat(getComputedStyle($bar).opacity) === 1")
        // The content is dimmed only for a change of page or a form that is sent, not for a part of the page.
        assertEquals("1", page.evaluate("getComputedStyle(document.querySelector('main')).opacity"))
        assertThat(page.locator("#f")).hasText("prima")

        held.get().resume()
        assertThat(page.locator("#f")).hasText("Frammento caricato")
        page.waitForFunction("parseFloat(getComputedStyle($bar).opacity) === 0")
    }
}
