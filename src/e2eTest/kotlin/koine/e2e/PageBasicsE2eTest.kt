package koine.e2e

import com.microsoft.playwright.options.LoadState
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Model of an e2e test (criteria 15 and 17): it runs in both profiles, opens a real page and checks
 * what only a browser can tell. The QA copies this shape for the tests of the other tickets.
 */
@E2eApplication
class PageBasicsE2eTest {

    @E2eTest
    fun `c15 the test runs in the profile it declares`(e2e: E2e) {
        e2e.open()

        val width = e2e.page.evaluate("window.innerWidth") as Number
        val touch = e2e.page.evaluate("navigator.maxTouchPoints") as Number
        val coarse = e2e.page.evaluate("matchMedia('(pointer: coarse)').matches") as Boolean

        if (e2e.profile.isPhone) {
            assertEquals(360, width.toInt())
            assertTrue(touch.toInt() > 0, "the phone profile has no touch")
            assertTrue(coarse, "the phone profile has no coarse pointer")
        } else {
            assertEquals(1280, width.toInt())
            assertEquals(0, touch.toInt())
        }
    }

    @E2eTest
    fun `c17 a page loads only from the app, with the design font and htmx 2 and no console errors`(e2e: E2e) {
        val requests = mutableListOf<String>()
        val errors = mutableListOf<String>()
        e2e.page.onRequest { requests += it.url() }
        e2e.page.onConsoleMessage { if (it.type() == "error") errors += it.text() }
        e2e.page.onPageError { errors += it }

        e2e.open()
        e2e.page.waitForLoadState(LoadState.NETWORKIDLE)
        e2e.page.evaluate("document.fonts.ready.then(() => true)")

        // Every request goes to the origin of the app.
        assertTrue(requests.isNotEmpty())
        val outside = requests.filterNot { it.startsWith(e2e.url("/")) || it.startsWith("data:") }
        assertEquals(emptyList<String>(), outside, "requests to other hosts")

        assertEquals(emptyList<String>(), errors, "errors in the console")

        // The font of the body is the one of the design system, and it was really loaded.
        val family = e2e.page.evaluate("getComputedStyle(document.body).fontFamily") as String
        assertTrue(family.startsWith("Inter"), "font-family of the body: $family")
        val loaded = e2e.page.evaluate(
            "Array.from(document.fonts).some(f => f.family.replaceAll('\"', '') === 'Inter' && f.status === 'loaded')",
        ) as Boolean
        assertTrue(loaded, "the Inter font is not loaded")

        val version = e2e.page.evaluate("htmx.version") as String
        assertTrue(version.startsWith("2."), "htmx.version: $version")
    }
}
