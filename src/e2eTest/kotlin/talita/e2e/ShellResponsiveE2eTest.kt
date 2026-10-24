package talita.e2e

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hard screens of the design (section 2.7): phone on its side, enlarged text, narrow screens,
 * zoom at 200%, iPhone with notch and home bar. QA of KOINE-11, edge cases of the analysis.
 */
@E2eApplication
class ShellResponsiveE2eTest {

    /** The pages checked on every screen: the home (the longest), a section and the error page. */
    private val paths = listOf("/", "/arrivi", "/test/errore")

    private fun Page.horizontalOverflow(): Int =
        (evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number).toInt()

    /** Adds a tall filler and a last element to the content, scrolls to the end. */
    private fun Page.scrollToTheEndOfAnLongPage() {
        evaluate(
            """() => {
                const content = document.querySelector('main > div');
                const filler = document.createElement('div');
                filler.style.height = '1500px';
                const last = document.createElement('p');
                last.id = 'ultimo';
                last.textContent = 'Ultimo elemento';
                content.append(filler, last);
                window.scrollTo(0, document.documentElement.scrollHeight);
            }""",
        )
    }

    /** No horizontal scroll, the navigation is on the screen and the last element is not under it. */
    private fun assertUsable(page: Page, where: String, minTouchHeight: Double = 44.0) {
        assertEquals(0, page.horizontalOverflow(), "$where: horizontal scroll")
        val viewport = page.viewport()
        val nav = page.mainNavigation().box()
        assertTrue(nav.x >= 0 && nav.x + nav.width <= viewport.width && nav.y + nav.height <= viewport.height + 1, "$where: the navigation is outside the screen: $nav")
        for (link in page.mainNavigation().locator("a").all()) {
            val box = link.box()
            assertTrue(box.height >= minTouchHeight && box.width >= 44, "$where: ${link.innerText().trim()} is ${box.width}x${box.height}")
        }
        page.scrollToTheEndOfAnLongPage()
        val last = page.locator("#ultimo").box()
        val navAfter = page.mainNavigation().box()
        assertTrue(last.y + last.height <= navAfter.y, "$where: the last element ($last) is under the navigation ($navAfter)")
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `on the phone on its side the navigation is compact, the header scrolls away and nothing is covered`(e2e: E2e) {
        for ((width, height) in listOf(800 to 360, 667 to 375)) {
            val page = e2e.newPage { setViewportSize(width, height) }
            for (path in paths) {
                page.navigate(e2e.url(path))
                page.waitForSettledContent()
                val where = "${width}x$height $path"

                // 48 px high (plus the border), icon and label side by side.
                val nav = page.mainNavigation().box()
                assertTrue(nav.height in 48.0..50.0, "$where: navigation $nav")
                val link = page.mainNavigation().locator("a").first()
                assertTrue(link.box().height <= 48.0, "$where: entry ${link.box()}")
                val icon = link.locator("svg").box()
                val label = link.locator("span").last().box()
                assertTrue(label.x >= icon.x + icon.width, "$where: icon and label are not side by side: $icon $label")
                assertUsable(page, where)
            }
            // The header is not sticky in landscape: it scrolls away with the page.
            page.navigate(e2e.url("/"))
            page.evaluate("document.querySelector('main > div').append(Object.assign(document.createElement('div'), {style: 'height: 1500px'})); window.scrollTo(0, 400)")
            page.waitUntil("window.scrollY >= 400")
            assertTrue(page.locator("header").box().y < 0, "${width}x$height: the header stays on top in landscape")
        }
    }

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `the desktop at 200 percent zoom takes the layout of the phone`(e2e: E2e) {
        // 1280x800 zoomed at 200% is a 640x400 CSS pixel window.
        val page = e2e.newPage { setViewportSize(640, 400); setDeviceScaleFactor(2.0) }
        for (path in paths) {
            page.navigate(e2e.url(path))
            page.waitForSettledContent()
            val where = "zoom 200% $path"

            val nav = page.mainNavigation().box()
            assertEquals(0.0, nav.x, where)
            assertEquals(640.0, nav.width, "$where: the navigation is not a bar at the bottom")
            assertTrue(nav.y > 200, "$where: the navigation is not at the bottom: $nav")
            assertEquals(0.0, page.locator("main").box().x, "$where: the content still leaves room for a side navigation")
            assertUsable(page, where)
        }
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `narrow screens never scroll sideways and keep every label`(e2e: E2e) {
        for (width in listOf(300, 256)) {
            val page = e2e.newPage { setViewportSize(width, 640) }
            for (path in paths) {
                page.navigate(e2e.url(path))
                page.waitForSettledContent()
                val where = "${width}px $path"
                assertUsable(page, where)
                // The labels never disappear: all four are there and visible (below 256 px they are shortened with dots).
                val labels = page.mainNavigation().locator("a > span:last-child")
                assertEquals(navigationLabels, labels.allInnerTexts().map { it.trim() }, where)
                for (label in labels.all()) assertTrue(label.isVisible, "$where: label '${label.innerText()}' not visible")
            }
        }
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `at 300 px the labels are read in full`(e2e: E2e) {
        val page = e2e.newPage { setViewportSize(300, 640) }
        page.navigate(e2e.url("/"))
        page.waitForSettledContent()

        assertEquals(emptyList<String>(), truncatedLabels(page))
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `at 320 px the longest label is read in full`(e2e: E2e) {
        val page = e2e.newPage { setViewportSize(320, 640) }
        page.navigate(e2e.url("/"))
        page.waitForSettledContent()

        assertEquals(emptyList<String>(), truncatedLabels(page))
    }

    private fun truncatedLabels(page: Page): List<String> {
        @Suppress("UNCHECKED_CAST")
        return page.evaluate(
            "Array.from(document.querySelectorAll('body > nav a span:last-child')).filter(e => e.scrollWidth > e.clientWidth).map(e => e.textContent.trim())",
        ) as List<String>
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `with the text of the system enlarged the layout holds, the navigation does not cover the content`(e2e: E2e) {
        // The text is enlarged as the browser setting does: the root font size grows, and all sizes (in rem) with it.
        for ((width, percent) in listOf(360 to 130, 320 to 130, 360 to 150)) {
            val page = e2e.newPage { setViewportSize(width, 800) }
            for (path in paths) {
                page.navigate(e2e.url(path))
                // Through the CSSOM: the Content-Security-Policy does not let a <style> element be added.
                page.evaluate("document.documentElement.style.fontSize = '$percent%'")
                page.waitForSettledContent()
                assertUsable(page, "${width}px, text at $percent% $path")
            }
        }
    }

    // The iPhone has no `env(safe-area-inset-*)` in headless Chromium: the CSS of the app is rewritten with the
    // insets of a real iPhone 14, so what the layout does with them can be measured.
    private fun Page.withSafeArea(top: Int, bottom: Int, left: Int, right: Int) {
        context().route("**/css/app-*.css") { route: Route ->
            val response = route.fetch()
            val css = response.text()
                .replace("env(safe-area-inset-top)", "${top}px")
                .replace("env(safe-area-inset-bottom)", "${bottom}px")
                .replace("env(safe-area-inset-left)", "${left}px")
                .replace("env(safe-area-inset-right)", "${right}px")
            route.fulfill(Route.FulfillOptions().setResponse(response).setBody(css))
        }
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `on an iPhone with notch and home bar the header, the navigation and the messages stay in the safe area`(e2e: E2e) {
        val page = e2e.newPage { setViewportSize(390, 844); setDeviceScaleFactor(3.0) }
        page.withSafeArea(top = 47, bottom = 34, left = 0, right = 0)
        for (path in paths) {
            page.navigate(e2e.url(path))
            page.waitForSettledContent()
            val where = "iPhone portrait $path"

            // The header starts below the notch.
            assertEquals("47px", page.evaluate("getComputedStyle(document.querySelector('header')).paddingTop"), where)
            assertTrue(page.locator("header a[href='/']").box().y >= 47, "$where: the brand is inside the notch")
            // The navigation ends at the bottom of the screen, with the home bar below its entries.
            val nav = page.mainNavigation().box()
            assertEquals(844.0, nav.y + nav.height, "$where: the navigation does not reach the bottom")
            assertEquals("34px", page.evaluate("getComputedStyle(document.querySelector('nav')).paddingBottom"), where)
            val lastEntry = page.mainNavigation().locator("a").first().box()
            assertTrue(lastEntry.y + lastEntry.height <= 844 - 34, "$where: an entry is inside the home bar: $lastEntry")
            assertUsable(page, where)
        }

        // A message sits above the navigation, home bar included.
        page.navigate(e2e.url("/"))
        page.context().setOffline(true)
        page.navLink("Iscrizioni").tap()
        page.waitForSelector("#messaggi [role=alert]")
        page.waitUntil("document.querySelector('#messaggi [role=alert]').getAnimations().length === 0")
        val message = page.messages().box()
        val nav = page.mainNavigation().box()
        assertTrue(message.y + message.height <= nav.y, "the message ($message) is on the navigation ($nav)")
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `on an iPhone on its side the content keeps clear of the notch`(e2e: E2e) {
        val page = e2e.newPage { setViewportSize(844, 390); setDeviceScaleFactor(3.0) }
        page.withSafeArea(top = 0, bottom = 21, left = 47, right = 47)
        for (path in paths) {
            page.navigate(e2e.url(path))
            page.waitForSettledContent()
            val where = "iPhone landscape $path"

            assertEquals("47px", page.evaluate("getComputedStyle(document.querySelector('main')).paddingLeft"), where)
            assertEquals("47px", page.evaluate("getComputedStyle(document.querySelector('main')).paddingRight"), where)
            assertTrue(page.locator("header a[href='/']").box().x >= 47, "$where: the brand is inside the notch")
            val nav = page.mainNavigation().box()
            assertEquals(390.0, nav.y + nav.height, "$where: the navigation does not reach the bottom")
            assertEquals("21px", page.evaluate("getComputedStyle(document.querySelector('nav')).paddingBottom"), where)
            val entry = page.mainNavigation().locator("a").first().box()
            assertTrue(entry.x >= 47 && entry.y + entry.height <= 390 - 21, "$where: an entry is in the safe area: $entry")
            assertUsable(page, where)
        }
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `from 320 px up no label is shortened and nothing scrolls sideways`(e2e: E2e) {
        for (width in listOf(320, 321, 330, 340, 360, 375, 390, 412, 480)) {
            val page = e2e.newPage { setViewportSize(width, 640) }
            page.navigate(e2e.url("/"))
            page.waitForSettledContent()

            assertEquals(emptyList<String>(), truncatedLabels(page), "${width}px: shortened labels")
            assertEquals(0, page.horizontalOverflow(), "${width}px: horizontal scroll")
        }
    }
}
