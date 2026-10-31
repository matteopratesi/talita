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
 * Waits until [probe] says that the condition holds, looking again every 30 ms for [timeoutMs] at most (TALITA-16). The probe returns `null` when the condition holds, and otherwise
 * a text with the values that it read (a scroll position, a box): the failure says [what] was waited for and the last text, so a slow runner leaves something to read and not only
 * "false". This is the only wait of the e2e tests that sleeps in a loop; everything that depends on a condition of the page goes through it, because a read of the page
 * right after an action (`page.evaluate`, `box()`) does not wait, while the `assertThat` of Playwright does.
 * A page that is being replaced while it is asked (the context of the script is gone, an element has no box) is asked again.
 */
internal fun awaitCondition(what: String, timeoutMs: Long = 10_000, probe: () -> String?) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    var last = "never asked"
    while (true) {
        try {
            last = probe() ?: return
        } catch (error: com.microsoft.playwright.PlaywrightException) {
            last = "error: " + (error.message?.lineSequence()?.firstOrNull() ?: error.javaClass.simpleName)
        } catch (error: IllegalArgumentException) {
            // `box()` of an element that is not there at this moment.
            last = "error: " + error.message
        }
        if (System.nanoTime() >= deadline) throw AssertionError("Timeout of $timeoutMs ms waiting for $what; the last values: $last")
        Thread.sleep(30)
    }
}

/**
 * Waits until [expression] is true in the page, looking again every 30 ms. Not `Page.waitForFunction`:
 * Playwright runs the expression of that one with `eval` inside the page, and the Content-Security-Policy
 * of the app (no `unsafe-eval`) forbids it. `evaluate` goes through the debugging protocol of the browser,
 * which the policy does not touch. A page that changes while it is asked is asked again.
 */
internal fun Page.waitUntil(expression: String, timeoutMs: Long = 10_000) =
    awaitCondition("`$expression`", timeoutMs) { if (evaluate("!!($expression)") == true) null else "false" }

/**
 * Waits until the focus is on the element that [expression] describes (`document.activeElement` is the element, and the expression is true of it), and does not read it once:
 * after a touch, Enter, Tab or the answer of htmx the focus arrives a moment later, on a slow runner a long moment. Every e2e test that checks where the focus is uses this one.
 */
internal fun Page.focusIs(expression: String) = waitUntil("document.activeElement && ($expression)")

/** What htmx adds to an element while it works: a request that is going, the old content that is going away, the new one that is settling. */
private const val HTMX_BUSY = ".htmx-request, .htmx-swapping, .htmx-settling, .htmx-added"

/**
 * Waits until htmx has finished with the page: no request in flight and nothing swapping or settling. After a form that was sent (`waitForResponse`) the answer has arrived and
 * htmx has not yet put the new page in place: the next step would write in the old form, or find the text of the answer before, which is the same.
 */
internal fun Page.waitForHtmxIdle() = waitUntil("document.querySelector('$HTMX_BUSY') === null")

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
