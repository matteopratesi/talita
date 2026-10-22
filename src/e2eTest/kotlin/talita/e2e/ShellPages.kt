package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.BoundingBox
import com.microsoft.playwright.options.ViewportSize

/** The labels of the main navigation, in order: the same on the phone and on the desktop. */
internal val navigationLabels = listOf("Home", "Convivenza", "Iscrizioni", "Arrivi")

/** A page of the shell: the home, the placeholders of the sections and the two error pages. */
internal data class ShellPage(val name: String, val path: String)

internal val shellPages = listOf(
    ShellPage("home", "/"),
    ShellPage("convivenza", "/convivenza"),
    ShellPage("iscrizioni", "/iscrizioni"),
    ShellPage("arrivi", "/arrivi"),
    ShellPage("404", "/pagina-che-non-esiste"),
    // Exists only in the e2e tests (TestOnlyController).
    ShellPage("500", "/test/errore"),
)

internal fun Page.mainNavigation(): Locator = locator("nav[aria-label='Navigazione principale']")

/** The link of the main navigation with this label. */
internal fun Page.navLink(label: String): Locator =
    locator("nav a").filter(Locator.FilterOptions().setHasText(label))

/** The pop-up messages of the app (errors that do not change the page). */
internal fun Page.messages(): Locator = locator("#messaggi [role=alert]")

/**
 * Adds an element to the content of the page and tells htmx about it, as a future screen would. At the end of the content, or, with
 * [atTop], before it: a button that a test touches while a message is open must not sit where the message is (just above the
 * navigation, where the end of a short page is), because the message would catch the touch.
 */
internal fun Page.addToContent(html: String, atTop: Boolean = false) {
    evaluate(
        """({ html, atTop }) => {
            const holder = document.createElement('div');
            holder.innerHTML = html;
            const content = document.querySelector('main > div');
            if (atTop) content.prepend(holder); else content.append(holder);
            htmx.process(holder);
        }""",
        mapOf("html" to html, "atTop" to atTop),
    )
}

/**
 * Waits until [expression] is true in the page, looking again every 30 ms. Not `Page.waitForFunction`:
 * Playwright runs the expression of that one with `eval` inside the page, and the Content-Security-Policy
 * of the app (no `unsafe-eval`) forbids it. `evaluate` goes through the debugging protocol of the browser,
 * which the policy does not touch. A page that changes while it is asked is asked again.
 */
internal fun Page.waitUntil(expression: String, timeoutMs: Long = 10_000) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    var failure: String? = null
    while (System.nanoTime() < deadline) {
        try {
            if (evaluate("!!($expression)") == true) return
            failure = null
        } catch (error: com.microsoft.playwright.PlaywrightException) {
            // The page was being replaced (the context of the script is gone): try again on the new one.
            failure = error.message?.lineSequence()?.firstOrNull()
        }
        Thread.sleep(30)
    }
    throw AssertionError("Timeout of $timeoutMs ms waiting for: $expression" + (failure?.let { " (last error: $it)" } ?: ""))
}

/** Waits until the entrance animation of the content is over, so colors and positions are final. */
internal fun Page.waitForSettledContent() {
    waitUntil("document.querySelector('main').getAnimations().every(a => a.playState === 'finished')")
}

/**
 * Waits until the entrance animation of the open messages is over (they fade in, and the colors that axe reads while it runs are
 * those of a half transparent message): with no message open there is nothing to wait for.
 */
internal fun Page.waitForSettledMessages() {
    waitUntil("Array.from(document.querySelectorAll('#messaggi [role=alert]')).every(m => m.getAnimations().every(a => a.playState === 'finished'))")
}

// The Playwright API says "maybe null"; in a test a missing value is a failure with a message.
internal fun Locator.box(): BoundingBox = requireNotNull(boundingBox()) { "the element has no box: $this" }

internal fun Locator.attr(name: String): String = requireNotNull(getAttribute(name)) { "no attribute $name on $this" }

internal fun Page.viewport(): ViewportSize = requireNotNull(viewportSize()) { "the page has no viewport" }
