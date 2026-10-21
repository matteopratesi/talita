package talita.e2e

import com.deque.html.axecore.playwright.AxeBuilder
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pop-up messages of the design (section 4.7): texts, place, size of the buttons, how long they
 * stay, accessibility. QA of KOINE-11, beyond criteria 27 and 28.
 */
@E2eApplication
class MessagesE2eTest {

    private data class Message(val title: String, val text: String)

    private val network = Message("Nessuna connessione", "Controlla di avere campo o il Wi-Fi e riprova.")
    private val slow = Message("La connessione è troppo lenta", "Talità non ha risposto in tempo. Riprova quando hai più campo.")
    private val server = Message("Qualcosa non ha funzionato", "Riprova tra qualche minuto. Se il problema continua, avvisa chi organizza la convivenza.")

    /** The network is gone and the person touches an entry. */
    private fun lostConnection(e2e: E2e) {
        e2e.browserContext.setOffline(true)
        e2e.touch(e2e.page.navLink("Iscrizioni"))
        assertThat(e2e.page.messages().filter(textFilter(network.title))).hasCount(1)
    }

    /** The server answers 500 to a request that does not change the page. */
    private fun serverError(e2e: E2e) {
        e2e.page.addToContent(
            """<div id="bersaglio"><p>Contenuto di prima</p></div>
               <button id="carica" type="button" hx-get="/test/errore" hx-target="#bersaglio">Carica</button>""",
        )
        e2e.touch(e2e.page.locator("#carica"))
        assertThat(e2e.page.messages().filter(textFilter(server.title))).hasCount(1)
    }

    /** The server does not answer in time: the timeout of htmx (20 s in the app) is shortened by the test. */
    private fun slowConnection(e2e: E2e) {
        e2e.page.evaluate("htmx.config.timeout = 700")
        e2e.page.route("**/iscrizioni") { /* never answered */ }
        e2e.touch(e2e.page.navLink("Iscrizioni"))
        assertThat(e2e.page.messages().filter(textFilter(slow.title))).hasCount(1)
    }

    private fun textFilter(text: String) = com.microsoft.playwright.Locator.FilterOptions().setHasText(text)

    @E2eTest
    fun `the three messages have the texts, the buttons and the roles of the design`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        assertEquals(20000, (page.evaluate("htmx.config.timeout") as Number).toInt(), "timeout of the requests")

        for ((kind, expected) in listOf<Pair<(E2e) -> Unit, Message>>({ it: E2e -> lostConnection(it) } to network, { it: E2e -> serverError(it) } to server, { it: E2e -> slowConnection(it) } to slow)) {
            e2e.browserContext.setOffline(false)
            page.unrouteAll()
            e2e.open("/")
            kind(e2e)

            val message = page.messages().filter(textFilter(expected.title))
            assertEquals("alert", message.getAttribute("role"))
            assertThat(message.locator("p").nth(0)).hasText(expected.title)
            assertThat(message.locator("p").nth(1)).hasText(expected.text)
            assertThat(message.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Riprova"))).hasCount(1)
            assertThat(message.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Chiudi"))).hasCount(1)
            // The icons are decoration: hidden from the screen readers.
            assertEquals(0, message.locator("svg:not([aria-hidden=true])").count(), "icons of '${expected.title}' read by a screen reader")
            // The region that holds the messages is announced politely.
            assertThat(page.locator("#messaggi")).hasAttribute("aria-live", "polite")
        }
    }

    @E2eTest
    fun `the message sits above the navigation on the phone, at the bottom right on the desktop, and does not move the content`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.waitForSettledContent()
        val heading = page.locator("h1").box()
        val content = page.locator("main > div").box()

        lostConnection(e2e)
        page.waitForFunction("document.querySelector('#messaggi [role=alert]').getAnimations().length === 0")

        val message = page.messages().box()
        val nav = page.mainNavigation().box()
        val viewport = page.viewport()
        assertTrue(message.width <= 448.0 + 1, "the message is wider than 448 px: $message")
        if (e2e.profile.isPhone) {
            assertTrue(message.y + message.height <= nav.y, "the message ($message) covers the navigation ($nav)")
            assertTrue(message.x >= 0 && message.x + message.width <= viewport.width, "the message leaves the screen: $message")
            assertTrue(nav.y - (message.y + message.height) < 24, "the message is far from the navigation: $message $nav")
        } else {
            assertTrue(message.x >= nav.x + nav.width, "the message covers the navigation: $message $nav")
            assertTrue(viewport.width - (message.x + message.width) in 16.0..32.0, "not at the right: $message")
            assertTrue(viewport.height - (message.y + message.height) in 16.0..32.0, "not at the bottom: $message")
        }
        // The content below does not move.
        assertEquals(heading.y, page.locator("h1").box().y, "the heading moved")
        assertEquals(content.height, page.locator("main > div").box().height, "the content changed height")
    }

    @E2eTest
    fun `the buttons of the message are at least 44 px`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        lostConnection(e2e)
        page.waitForFunction("document.querySelector('#messaggi [role=alert]').getAnimations().length === 0")

        for (button in page.messages().locator("button").all()) {
            val box = button.box()
            assertTrue(box.width >= 44 && box.height >= 44, "'${button.innerText().trim()}${button.getAttribute("aria-label").orEmpty()}' is ${box.width}x${box.height}")
        }
    }

    @E2eTest
    fun `an error message stays until it is closed or a request goes well, never by itself`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        lostConnection(e2e)

        // Confirmations (not used yet) go after 6 seconds: an error must outlast them.
        page.waitForTimeout(6500.0)
        assertThat(page.messages()).hasCount(1)

        e2e.browserContext.setOffline(false)
        e2e.touch(page.navLink("Iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        assertThat(page.messages()).hasCount(0)
    }

    @E2eTest
    fun `there is one message per kind and a different kind is shown next to it`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")

        lostConnection(e2e)
        e2e.touch(page.navLink("Convivenza"))
        e2e.touch(page.navLink("Iscrizioni"))
        assertThat(page.messages().filter(textFilter(network.title))).hasCount(1)
        assertThat(page.messages()).hasCount(1)

        e2e.browserContext.setOffline(false)
        serverError(e2e)
        assertThat(page.messages()).hasCount(2)
        // The two do not overlap.
        val boxes = page.messages().all().map { it.box() }
        val (upper, lower) = boxes.sortedBy { it.y }
        assertTrue(upper.y + upper.height <= lower.y + 1, "messages overlap: $upper $lower")
    }

    @E2eTest
    fun `trying again after a form was sent offers no Riprova`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        // A form of the app is boosted by htmx; the address answers 405 to a POST, which is a failure.
        page.addToContent("""<form method="post" action="/test/errore"><button id="invia" type="submit">Invia</button></form>""")

        e2e.touch(page.locator("#invia"))

        assertThat(page.messages().filter(textFilter(server.title))).hasCount(1)
        assertEquals(0, page.messages().locator("[data-azione=riprova]").count(), "a form must not be sent again with a tap on Riprova")
        assertEquals(1, page.messages().locator("[data-azione=chiudi]").count())
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")
        assertEquals(e2e.url("/"), page.url())
    }

    @E2eTest
    fun `an error 4xx of a request that does not change the page is left to htmx and shows no message`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.evaluate("document.addEventListener('htmx:responseError', () => { window.__responseErrors = (window.__responseErrors || 0) + 1; })")
        page.addToContent(
            """<div id="bersaglio"><p>Contenuto di prima</p></div>
               <button id="carica" type="button" hx-get="/frammento-che-non-esiste" hx-target="#bersaglio">Carica</button>""",
        )

        e2e.touch(page.locator("#carica"))

        page.waitForFunction("window.__responseErrors === 1")
        assertThat(page.messages()).hasCount(0)
        assertThat(page.locator("#bersaglio")).hasText("Contenuto di prima")
    }

    @E2eTest
    fun `axe-core finds no serious or critical violation with each message open`(e2e: E2e) {
        val page = e2e.page
        for (kind in listOf<(E2e) -> Unit>({ lostConnection(it) }, { serverError(it) }, { slowConnection(it) })) {
            e2e.browserContext.setOffline(false)
            page.unrouteAll()
            e2e.open("/")
            kind(e2e)
            page.waitForFunction("document.querySelector('#messaggi [role=alert]').getAnimations().length === 0")

            val results = AxeBuilder(page).analyze()
            val violations = results.violations
                .filter { it.impact == "serious" || it.impact == "critical" }
                .map { "${it.id} (${it.impact}): ${it.help}, nodes: ${it.nodes.map { n -> n.html }}" }
            assertEquals(emptyList<String>(), violations, "message: ${page.messages().first().innerText().lines().first()}")
            assertTrue(results.passes.any { it.id == "color-contrast" }, "color-contrast did not run, incomplete: ${results.incomplete.map { it.id }}")
        }
    }
}
