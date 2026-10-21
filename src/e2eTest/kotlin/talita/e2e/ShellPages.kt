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

/** Adds an element to the content of the page and tells htmx about it, as a future screen would. */
internal fun Page.addToContent(html: String) {
    evaluate(
        """(html) => {
            const holder = document.createElement('div');
            holder.innerHTML = html;
            document.querySelector('main > div').append(holder);
            htmx.process(holder);
        }""",
        html,
    )
}

/** Waits until the entrance animation of the content is over, so colors and positions are final. */
internal fun Page.waitForSettledContent() {
    waitForFunction("document.querySelector('main').getAnimations().every(a => a.playState === 'finished')")
}

// The Playwright API says "maybe null"; in a test a missing value is a failure with a message.
internal fun Locator.box(): BoundingBox = requireNotNull(boundingBox()) { "the element has no box: $this" }

internal fun Locator.attr(name: String): String = requireNotNull(getAttribute(name)) { "no attribute $name on $this" }

internal fun Page.viewport(): ViewportSize = requireNotNull(viewportSize()) { "the page has no viewport" }
