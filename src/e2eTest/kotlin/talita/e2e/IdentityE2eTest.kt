package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What makes the shell Talità's and nobody else's, seen on the real interface (QA of TALITA-2,
 * criteria 14, 15, 18 and 19; "t2 cN" in a name is criterion N of that ticket, not of the other
 * project): the mark and the name, the favicon, the entries and their order, the words that belong
 * to another project and the labels at 320 px on every page.
 *
 * The tests inherited from the other project check the structure and the texts of the design; these
 * cover what they do not: the T of the mark and of the favicon, the words that must not appear
 * anywhere (also in the pop-up messages, the attributes and the pages of error), and the longest
 * label when it is the current one.
 */
@E2eApplication
class IdentityE2eTest {

    /**
     * Words of the other project, the institution it serves and its people: lower case, searched as
     * text. The name of the project is written in pieces: the search of the build for what is left
     * of it (NoLeftoversTests) must not find it here.
     */
    private val foreignWords = listOf("koi" + "nè", "koi" + "ne", "centro", "comunità", "comunita", "fratell")

    @E2eTest
    fun `t2 c14 the header has the T mark and the name, and the tab has the favicon of Talita`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")

        assertThat(page).hasTitle("Talità")
        val header = page.locator("header")
        assertThat(header).hasText("Talità")

        // The mark: the blue square with a white T, drawn like the favicon.
        val mark = header.locator("svg")
        assertThat(mark).hasCount(1)
        assertThat(mark).hasAttribute("aria-hidden", "true")
        assertThat(mark.locator("path")).hasAttribute("d", "M10.5 9h11M16 9v14")
        assertEquals("rgb(42, 75, 160)", mark.locator("rect").evaluate("e => getComputedStyle(e).fill"), "color of the mark")
        assertEquals("rgb(255, 255, 255)", mark.locator("path").evaluate("e => getComputedStyle(e).stroke"), "color of the T")
        val box = mark.box()
        assertTrue(box.width >= 28 && box.height >= 28, "the mark is $box")

        // The tab: one icon, an SVG that is served and draws the same T on the same blue.
        val icons = page.locator("link[rel~=icon]")
        assertThat(icons).hasCount(1)
        val favicon = page.request().get(e2e.url(icons.attr("href")))
        assertEquals(200, favicon.status(), "GET of the favicon")
        assertTrue(favicon.headers()["content-type"].orEmpty().startsWith("image/svg+xml"), "type of the favicon: ${favicon.headers()["content-type"]}")
        val svg = favicon.text()
        assertTrue(svg.contains("""d="M10.5 9h11M16 9v14""""), "the favicon does not draw the T: $svg")
        assertTrue(svg.contains("""fill="#2a4ba0""""), "the favicon is not on the blue of the brand: $svg")
    }

    @E2eTest
    fun `t2 c14 c15 the navigation has the four entries of the design in order, at the bottom on the phone and on the left on the desktop`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")

        val links = page.locator("nav a")
        assertEquals(navigationLabels, links.allInnerTexts().map { it.trim() })
        assertEquals(
            listOf("/", "/convivenza", "/iscrizioni", "/arrivi"),
            (0 until links.count()).map { links.nth(it).attr("href") },
            "addresses of the entries",
        )
        assertThat(page.locator("nav a[aria-current=page]")).hasText("Home")

        val boxes = (0 until links.count()).map { links.nth(it).box() }
        val nav = page.mainNavigation().box()
        val viewport = page.viewport()
        if (e2e.profile.isPhone) {
            assertTrue(nav.y + nav.height >= viewport.height - 1, "the navigation is not at the bottom: $nav")
            assertTrue(boxes.zipWithNext().all { (a, b) -> b.x > a.x }, "the entries are not left to right: $boxes")
        } else {
            assertTrue(nav.x <= 1 && nav.width in 250.0..260.0, "the navigation is not the column on the left: $nav")
            assertTrue(boxes.zipWithNext().all { (a, b) -> b.y > a.y }, "the entries are not top to bottom: $boxes")
        }
    }

    // ---- Nothing of the other project (criterion 18) -------------------------------------------

    /** All the text a person can read or hear on the page, and the pop-up messages kept in the markup. */
    private fun readableText(page: Page): String = page.evaluate(
        """() => {
            const parts = [document.title, document.body.innerText];
            document.querySelectorAll('[aria-label], [title], [alt], [placeholder]').forEach((e) => {
                for (const name of ['aria-label', 'title', 'alt', 'placeholder']) parts.push(e.getAttribute(name) || '');
            });
            document.querySelectorAll('meta[name=description]').forEach((m) => parts.push(m.content));
            document.querySelectorAll('template').forEach((t) => parts.push(t.content.textContent));
            return parts.join('\n');
        }""",
    ) as String

    private fun assertNoForeignWords(page: Page, where: String) {
        val text = readableText(page).lowercase()
        for (word in foreignWords) {
            val at = text.indexOf(word)
            assertTrue(at < 0, "$where: '$word' is in the text of the page: …${text.substring(maxOf(0, at - 30), minOf(text.length, at + 40)).replace('\n', ' ')}…")
        }
    }

    @E2eTest
    fun `t2 c18 no page of the shell names the other project, the Centro, the communities or the brothers`(e2e: E2e) {
        for (shellPage in shellPages) {
            e2e.open(shellPage.path)
            assertNoForeignWords(e2e.page, shellPage.name)
        }
    }

    @E2eTest
    fun `t2 c18 no pop-up message names the other project, the Centro, the communities or the brothers`(e2e: E2e) {
        val page = e2e.page
        val kinds = listOf<Pair<String, () -> Unit>>(
            "no connection" to {
                e2e.browserContext.setOffline(true)
                e2e.touch(page.navLink("Iscrizioni"))
            },
            "slow connection" to {
                page.evaluate("htmx.config.timeout = 700")
                page.route("**/iscrizioni") { /* never answered */ }
                e2e.touch(page.navLink("Iscrizioni"))
            },
            "server error" to {
                page.addToContent(
                    """<div id="bersaglio"><p>Contenuto di prima</p></div>
                       <button id="carica" type="button" hx-get="/test/errore" hx-target="#bersaglio">Carica</button>""",
                )
                e2e.touch(page.locator("#carica"))
            },
        )

        for ((name, cause) in kinds) {
            e2e.browserContext.setOffline(false)
            page.unrouteAll()
            e2e.open("/")
            cause()

            // The message is really on the screen (not only in the template) and it is the only one.
            assertThat(page.messages()).hasCount(1)
            assertThat(page.messages()).isVisible()
            assertNoForeignWords(page, "message '$name'")
        }
    }

    // ---- 320 px (criterion 19) ------------------------------------------------------------------

    private fun truncatedLabels(page: Page): List<String> {
        @Suppress("UNCHECKED_CAST")
        return page.evaluate(
            "Array.from(document.querySelectorAll('nav a span:last-child')).filter(e => e.scrollWidth > e.clientWidth).map(e => e.textContent.trim())",
        ) as List<String>
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `t2 c19 at 320 px every page reads every label in full, also the longest one when it is the current entry`(e2e: E2e) {
        for (width in listOf(320, 321)) {
            val page = e2e.newPage { setViewportSize(width, 640) }
            for (shellPage in shellPages) {
                page.navigate(e2e.url(shellPage.path))
                page.waitForSettledContent()
                val where = "${width}px ${shellPage.name}"

                assertEquals(emptyList<String>(), truncatedLabels(page), "$where: shortened labels")
                assertEquals(navigationLabels, page.locator("nav a span:last-child").allInnerTexts().map { it.trim() }, where)
                assertEquals(0, (page.evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number).toInt(), "$where: horizontal scroll")
                for (link in page.locator("nav a").all()) {
                    val box = link.box()
                    assertTrue(box.width >= 44 && box.height >= 44, "$where: ${link.innerText().trim()} is ${box.width}x${box.height}")
                }
            }

            // The case of the design: "Convivenza" is the widest label, and wider still when it is the current one (bold).
            page.navigate(e2e.url("/convivenza"))
            page.waitForSettledContent()
            val current: Locator = page.locator("nav a[aria-current=page] span:last-child")
            assertThat(current).hasText("Convivenza")
            assertEquals("600", current.evaluate("e => getComputedStyle(e).fontWeight"), "the current entry is bold")
            val text = (current.evaluate("e => { const r = document.createRange(); r.selectNodeContents(e); return r.getBoundingClientRect().width }") as Number).toDouble()
            val available = (current.evaluate("e => e.parentElement.getBoundingClientRect().width") as Number).toDouble()
            assertTrue(text <= available, "${width}px: 'Convivenza' needs $text px and the entry has $available")
        }
    }
}
