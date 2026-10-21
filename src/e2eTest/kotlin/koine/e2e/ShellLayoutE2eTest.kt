package koine.e2e

import com.deque.html.axecore.playwright.AxeBuilder
import com.microsoft.playwright.Request
import com.microsoft.playwright.options.LoadState
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Structure, layout, weight and accessibility of the pages of the shell (criteria 18, 19, 20, 29, 30). */
@E2eApplication
class ShellLayoutE2eTest {

    @E2eTest
    fun `c18 every page of the shell has language, viewport, one h1, one main and a labelled navigation`(e2e: E2e) {
        val page = e2e.page
        for (shellPage in shellPages) {
            e2e.open(shellPage.path)
            val where = "${shellPage.name} (${e2e.profile.label})"

            assertEquals("it", page.locator("html").attr("lang"), where)

            val viewport = page.locator("meta[name=viewport]").attr("content")
            assertTrue("width=device-width" in viewport && "initial-scale=1" in viewport, "$where: viewport '$viewport'")
            assertTrue("maximum-scale" !in viewport && "user-scalable" !in viewport, "$where: the viewport blocks the zoom: '$viewport'")

            assertEquals(1, page.locator("h1").count(), "$where: h1")
            assertEquals(1, page.locator("main").count(), "$where: main")
            assertEquals(1, page.locator("nav").count(), "$where: nav")
            assertEquals("Navigazione principale", page.locator("nav").attr("aria-label"), where)
            assertEquals(navigationLabels, page.locator("nav a").allInnerTexts().map { it.trim() }, "$where: voci della navigazione")
        }
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `c19 on the phone the navigation is at the bottom, easy to touch, and does not cover the content`(e2e: E2e) {
        val page = e2e.page
        for (shellPage in shellPages) {
            e2e.open(shellPage.path)
            page.waitForSettledContent()
            val where = shellPage.name

            val noHorizontalScroll = page.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth") as Boolean
            assertTrue(noHorizontalScroll, "$where: horizontal scroll")

            // Visible at the bottom, without scrolling.
            val nav = page.mainNavigation().box()
            val viewportHeight = page.viewport().height
            assertTrue(nav.y > 0 && nav.y + nav.height <= viewportHeight, "$where: the navigation is not inside the screen: $nav")
            assertTrue(nav.y > viewportHeight / 2, "$where: the navigation is not at the bottom: $nav")

            // Every entry is at least 44x44 CSS pixels.
            for (link in page.locator("nav a").all()) {
                val box = link.box()
                assertTrue(box.width >= 44 && box.height >= 44, "$where: ${link.innerText()} is ${box.width}x${box.height}")
            }

            // Scrolling to the end, the last element of the content stays above the navigation.
            page.evaluate(
                """() => {
                    const content = document.querySelector('main > div');
                    const filler = document.createElement('div');
                    filler.style.height = '2000px';
                    const last = document.createElement('p');
                    last.id = 'ultimo';
                    last.textContent = 'Ultimo elemento';
                    content.append(filler, last);
                    window.scrollTo(0, document.documentElement.scrollHeight);
                }""",
            )
            val last = page.locator("#ultimo").box()
            val navAfterScroll = page.mainNavigation().box()
            assertTrue(last.y + last.height <= navAfterScroll.y, "$where: the last element ($last) is under the navigation ($navAfterScroll)")
        }
    }

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `c20 on the desktop the navigation is visible without scrolling, with the same entries`(e2e: E2e) {
        val page = e2e.page
        for (shellPage in shellPages) {
            e2e.open(shellPage.path)
            page.waitForSettledContent()
            val where = shellPage.name

            val nav = page.mainNavigation()
            assertTrue(nav.isVisible, "$where: navigation not visible")
            val box = nav.box()
            assertTrue(box.x >= 0 && box.y >= 0 && box.y + box.height <= page.viewport().height, "$where: the navigation is not inside the screen: $box")
            assertEquals(navigationLabels, page.locator("nav a").allInnerTexts().map { it.trim() }, where)

            val noHorizontalScroll = page.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth") as Boolean
            assertTrue(noHorizontalScroll, "$where: horizontal scroll")
        }
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `c29 the first visit to the home on the phone weighs at most 250 KB`(e2e: E2e) {
        val finished = mutableListOf<Request>()
        e2e.page.onRequestFinished { finished += it }

        // A new context has an empty cache: this is a first visit.
        e2e.open("/")
        e2e.page.waitForLoadState(LoadState.NETWORKIDLE)

        // No compression on the local app: what the server sends, headers and body.
        val sizes = finished.associate { it.url() to it.sizes().let { s -> s.responseBodySize + s.responseHeadersSize } }
        val total = sizes.values.sum()
        assertTrue(total <= 250_000, "the first visit transfers $total bytes: $sizes")
    }

    @E2eTest
    fun `c30 axe-core finds no serious or critical violation on the pages of the shell`(e2e: E2e) {
        for (shellPage in shellPages) {
            e2e.open(shellPage.path)
            e2e.page.waitForSettledContent()

            val results = AxeBuilder(e2e.page).analyze()
            // Make sure axe really looked at the contrast, not only skipped it as "incomplete".
            assertTrue(
                results.passes.any { it.id == "color-contrast" },
                "${shellPage.name}: color-contrast did not run, incomplete: ${results.incomplete.map { it.id }}",
            )
            val violations = results.violations
                .filter { it.impact == "serious" || it.impact == "critical" }
                .map { "${it.id} (${it.impact}): ${it.help}, ${it.nodes.size} nodes: ${it.nodes.map { n -> n.html }}" }

            assertEquals(emptyList<String>(), violations, "${shellPage.name} (${e2e.profile.label})")
        }
    }
}
