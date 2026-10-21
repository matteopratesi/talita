package koine.e2e

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Response
import com.microsoft.playwright.options.LoadState
import koine.TestcontainersConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * Every e2e test class carries this: the whole app on a random port, with Postgres from
 * Testcontainers. All the classes share one application context.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
annotation class E2eApplication

/** What a test method receives: a fresh page of a fresh browser context, in one [Profile]. */
class E2e(
    val page: Page,
    val browserContext: BrowserContext,
    val profile: Profile,
    private val baseUrl: String,
) {
    private val extraContexts = mutableListOf<BrowserContext>()

    fun url(path: String): String = baseUrl + path

    /** Opens a path of the app and waits for the page to load, fonts and scripts included. */
    fun open(path: String = "/"): Response = open(page, path)

    private fun open(on: Page, path: String): Response {
        val response = requireNotNull(on.navigate(url(path))) { "no response for $path" }
        on.waitForLoadState(LoadState.LOAD)
        return response
    }

    /** A tap on the phone, a click on the desktop. */
    fun touch(locator: Locator) {
        if (profile.isPhone) locator.tap() else locator.click()
    }

    /**
     * A second page in its own browser context, in the same profile, for what the main one cannot
     * do (for example JavaScript switched off). It is closed with the test.
     */
    fun newPage(customize: Browser.NewContextOptions.() -> Unit = {}): Page {
        val context = Browsers.chromium.newContext(profile.contextOptions().apply(customize))
        extraContexts += context
        return context.newPage().also { it.setDefaultTimeout(10_000.0) }
    }

    internal fun close() {
        extraContexts.forEach { runCatching { it.close() } }
        browserContext.close()
    }
}
