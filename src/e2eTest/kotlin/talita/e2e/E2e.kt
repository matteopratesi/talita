package talita.e2e

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Response
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.LoadState
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest
import org.springframework.security.authentication.ott.OneTimeTokenService
import talita.Addresses
import talita.TestcontainersConfiguration
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Every e2e test class carries this: the whole app on a random port, with Postgres from
 * Testcontainers and a Mailpit for the mail. All the classes share one application context.
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
    /** The application that the browser talks to (the same JVM): for what a person cannot do, like making a link without the mail. */
    val application: ApplicationContext,
) {
    private val extraContexts = mutableListOf<BrowserContext>()

    /** Messages of the console of every page of the test that complain of the Content-Security-Policy. */
    internal val cspViolations = CopyOnWriteArrayList<String>()

    /** The address with which the main page of the test is inside (when the test is not about someone without access). */
    var signedInAs: String? = null
        private set

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
     * Enters as a person does, with the real flow and no shortcut in the application: a link made by
     * the service of the one-time tokens (what the application does, without the mail), the page of the
     * link, the tap on "Entra". The account is born here, as at the first login of anyone.
     */
    fun signIn(on: Page = page, email: String = Addresses.unique("persona")): String {
        val link = application.getBean(OneTimeTokenService::class.java)
            .generate(GenerateOneTimeTokenRequest(email, Duration.ofMinutes(15))).tokenValue
        on.navigate(url("/accedi/conferma?token=$link"))
        on.waitForLoadState(LoadState.LOAD)
        // Playwright waits for an element to be still before it touches it, and with JavaScript off it cannot tell
        // while the page is still entering (160 ms): so it is let to finish.
        on.waitForSettledContent()
        val enter = on.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Entra"))
        if (profile.isPhone) enter.tap() else enter.click()
        on.waitForURL(url("/"))
        on.waitForLoadState(LoadState.LOAD)
        if (on === page) signedInAs = email
        return email
    }

    /**
     * A second page in its own browser context, in the same profile, for what the main one cannot
     * do (for example JavaScript switched off, or another size). It is inside like the main one, unless
     * the test says [signedIn] false. It is closed with the test.
     */
    fun newPage(signedIn: Boolean = true, customize: Browser.NewContextOptions.() -> Unit = {}): Page {
        val context = Browsers.chromium.newContext(profile.contextOptions().apply(customize))
        extraContexts += context
        val extra = context.newPage().also { it.setDefaultTimeout(10_000.0) }
        watchCsp(extra)
        if (signedIn) signIn(extra)
        return extra
    }

    /** Remembers what the console of [on] says about the Content-Security-Policy: no test may have a violation. */
    internal fun watchCsp(on: Page) {
        on.onConsoleMessage { if (it.text().contains("Content Security Policy", ignoreCase = true)) cspViolations += it.text() }
    }

    internal fun close() {
        extraContexts.forEach { runCatching { it.close() } }
        browserContext.close()
    }
}
