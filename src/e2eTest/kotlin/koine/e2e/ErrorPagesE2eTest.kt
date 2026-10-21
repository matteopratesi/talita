package koine.e2e

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 404, 500 and the errors of requests that do not change the page (criteria 25, 26, 28). */
@E2eApplication
class ErrorPagesE2eTest {

    /** What the person must never see: codes, exception names, stack traces, technical messages. */
    private val technicalDetails = listOf("IllegalStateException", "Exception", "segreto-tecnico", "at koine.", "stack", "trace", "Whitelabel")

    @E2eTest
    fun `c25 an address that does not exist shows the page not found inside the shell, opened directly`(e2e: E2e) {
        val response = e2e.open("/pagina-che-non-esiste")

        assertEquals(404, response.status())
        assertNotFoundPage(e2e)
    }

    @E2eTest
    fun `c25 an address that does not exist shows the page not found inside the shell, from a link of the app`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.addToContent("""<a id="link-rotto" href="/altra-pagina-che-non-esiste">Un link</a>""")

        val response = page.waitForResponse({ it.url().endsWith("/altra-pagina-che-non-esiste") }) {
            e2e.touch(page.locator("#link-rotto"))
        }

        assertEquals(404, response.status())
        assertNotFoundPage(e2e)
        // It is a page like the others: it has its own address.
        assertThat(page).hasURL(e2e.url("/altra-pagina-che-non-esiste"))
    }

    private fun assertNotFoundPage(e2e: E2e) {
        val page = e2e.page
        assertThat(page).hasTitle("Pagina non trovata · Koinè")
        assertThat(page.locator("h1")).hasText("Pagina non trovata")
        assertThat(page.locator("main")).containsText("L’indirizzo potrebbe essere sbagliato, oppure la pagina non esiste più.")
        // Inside the shell: the navigation is there and none of its entries is the current one.
        assertThat(page.mainNavigation()).isVisible()
        assertThat(page.locator("nav [aria-current]")).hasCount(0)
        // A way back to the home.
        val home = page.locator("main a").filter(com.microsoft.playwright.Locator.FilterOptions().setHasText("Vai alla home"))
        assertThat(home).hasAttribute("href", "/")
    }

    @E2eTest
    fun `c26 an unexpected server error shows the error page without technical details, opened directly`(e2e: E2e) {
        val response = e2e.open("/test/errore")

        assertEquals(500, response.status())
        assertErrorPage(e2e)
    }

    @E2eTest
    fun `c26 an unexpected server error shows the error page without technical details, from a link of the app`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.addToContent("""<a id="link-guasto" href="/test/errore">Un link</a>""")

        val response = page.waitForResponse({ it.url().endsWith("/test/errore") }) {
            e2e.touch(page.locator("#link-guasto"))
        }

        assertEquals(500, response.status())
        assertErrorPage(e2e)
        assertThat(page).hasURL(e2e.url("/test/errore"))
    }

    private fun assertErrorPage(e2e: E2e) {
        val page = e2e.page
        assertThat(page).hasTitle("Qualcosa non ha funzionato · Koinè")
        assertThat(page.locator("h1")).hasText("Qualcosa non ha funzionato")
        assertThat(page.locator("main")).containsText("Non è colpa tua: c’è stato un problema in Koinè.")
        assertThat(page.mainNavigation()).isVisible()
        assertThat(page.locator("nav [aria-current]")).hasCount(0)

        // Try again (the same address, it was a read) and go home.
        val retry = page.locator("main a").filter(com.microsoft.playwright.Locator.FilterOptions().setHasText("Riprova"))
        assertThat(retry).hasAttribute("href", "/test/errore")
        val home = page.locator("main a").filter(com.microsoft.playwright.Locator.FilterOptions().setHasText("Vai alla home"))
        assertThat(home).hasAttribute("href", "/")

        // Nothing technical, neither in the text nor in the markup.
        val visible = page.locator("body").innerText()
        val markup = page.content()
        for (detail in technicalDetails) {
            assertFalse(detail in visible, "'$detail' is visible on the error page")
            assertFalse(detail in markup, "'$detail' is in the markup of the error page")
        }
    }

    @E2eTest
    fun `c28 a request that does not change the page and gets a server error shows the message and replaces nothing`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.addToContent(
            """<div id="bersaglio"><p>Contenuto di prima</p></div>
               <button id="carica" type="button" hx-get="/test/errore" hx-target="#bersaglio">Carica</button>""",
        )

        e2e.touch(page.locator("#carica"))

        assertThat(page.messages()).hasCount(1)
        assertThat(page.messages()).containsText("Qualcosa non ha funzionato")
        assertThat(page.messages()).containsText("Riprova tra qualche minuto. Se il problema continua, avvisa il Centro.")
        assertThat(page.locator("#bersaglio")).hasText("Contenuto di prima")
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")
        assertEquals(e2e.url("/"), page.url())
    }

    @E2eTest
    fun `the message of a server error closes by itself when a request goes well`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.addToContent(
            """<div id="bersaglio"><p>Contenuto di prima</p></div>
               <button id="guasto" type="button" hx-get="/test/errore" hx-target="#bersaglio">Guasto</button>
               <button id="bene" type="button" hx-get="/test/frammento" hx-target="#bersaglio">Bene</button>""",
        )
        e2e.touch(page.locator("#guasto"))
        assertThat(page.messages()).hasCount(1)

        e2e.touch(page.locator("#bene"))

        assertThat(page.locator("#bersaglio")).hasText("Frammento caricato")
        assertThat(page.messages()).hasCount(0)
    }
}
