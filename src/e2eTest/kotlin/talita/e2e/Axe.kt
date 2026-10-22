package talita.e2e

import com.deque.html.axecore.playwright.AxeBuilder
import com.microsoft.playwright.Page
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * axe-core looks at the page as it is now and finds nothing «serious» or «critical» (criterion 49).
 * It also makes sure that the contrast was really checked, not left among the «incomplete» results.
 * Waits for the entrance animation first, so that colors are the final ones.
 */
internal fun assertAccessible(page: Page, what: String) {
    page.waitForSettledContent()
    val results = AxeBuilder(page).analyze()
    assertTrue(
        results.passes.any { it.id == "color-contrast" },
        "$what: color-contrast did not run, incomplete: ${results.incomplete.map { it.id }}",
    )
    val violations = results.violations
        .filter { it.impact == "serious" || it.impact == "critical" }
        .map { "${it.id} (${it.impact}): ${it.help}, ${it.nodes.size} nodes: ${it.nodes.map { n -> n.html }}" }
    assertEquals(emptyList<String>(), violations, what)
}
