package talita.e2e

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the design asks of the pages of the shell, checked on the real interface: exact texts,
 * sizes, colors of the tokens, structure and keyboard (QA of KOINE-11, beyond criteria 18-21).
 */
@E2eApplication
class ShellDesignE2eTest {

    private val sections = listOf(
        // path, name, summary on the home. Every section has its pages (the gathering in GatheringE2eTest, the registrations in EnrollmentE2eTest and MineE2eTest,
        // the arrivals in ArrivalsE2eTest): the row of the home is the only thing that stays here.
        Triple("/convivenza", "Convivenza", "Le date, i maestri e le informazioni per i genitori."),
        Triple("/iscrizioni", "Iscrizioni", "I bambini iscritti, anche quelli dal modulo cartaceo."),
        Triple("/arrivi", "Arrivi", "Chi è arrivato e le liste da stampare."),
    )

    @E2eTest
    fun `the home shows the texts of the design`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")

        assertThat(page).hasTitle("Talità")
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")
        assertThat(page.locator("main")).containsText("Talità raccoglie le iscrizioni alla convivenza dei bambini e aiuta i maestri a organizzarla.")
        assertThat(page.locator("main")).containsText("Talità è in costruzione. Le sezioni arriveranno una alla volta.")
        // Only the first words of the notice are in bold.
        assertThat(page.locator("main strong")).hasText("Talità è in costruzione.")
        assertThat(page.locator("main h2")).hasText("Sezioni")

        val rows = page.locator("main section ul a")
        assertEquals(sections.size, rows.count(), "rows of the list of the sections")
        for ((index, section) in sections.withIndex()) {
            assertThat(rows.nth(index)).hasAttribute("href", section.first)
            assertThat(rows.nth(index)).containsText(section.second)
            assertThat(rows.nth(index)).containsText(section.third)
        }
    }

    @E2eTest
    fun `the page not found has its button and the page of the error has them one under the other`(e2e: E2e) {
        val page = e2e.page

        e2e.open("/pagina-che-non-esiste")
        assertEquals(1, page.locator("main a").count(), "404: links in the content")
        assertThat(page.locator("main a")).hasText("Vai alla home")

        e2e.open("/test/errore")
        // The content slides in for 160 ms: while it moves, the browser reports the 48 px of a button as
        // 47.99997 (about one load in five), and the check of the height below fails by chance.
        page.waitForSettledContent()
        val buttons = page.locator("main a")
        assertEquals(listOf("Riprova", "Vai alla home"), buttons.allInnerTexts().map { it.trim() }, "500: buttons, in order")
        val retry = buttons.nth(0).box()
        val home = buttons.nth(1).box()
        assertTrue(home.y >= retry.y + retry.height, "500: the buttons are not one under the other: $retry $home")
        assertTrue(kotlin.math.abs(home.x - retry.x) < 1, "500: the buttons are not aligned: $retry $home")
        // "Riprova" is the primary button (filled), "Vai alla home" the secondary one (white, with border).
        val retryBg = buttons.nth(0).evaluate("e => getComputedStyle(e).backgroundColor") as String
        val homeBg = buttons.nth(1).evaluate("e => getComputedStyle(e).backgroundColor") as String
        assertEquals("rgb(42, 75, 160)", retryBg)
        assertEquals("rgb(255, 255, 255)", homeBg)
        // Buttons are at least 48 px high, as the design says.
        assertTrue(retry.height >= 48 && home.height >= 48, "500: buttons are $retry and $home")
    }

    @E2eTest
    fun `typography and colors are those of the tokens of the design`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.waitForSettledContent()

        @Suppress("UNCHECKED_CAST")
        val styles = page.evaluate(
            """() => {
                const s = (el, p) => getComputedStyle(el)[p];
                const current = document.querySelector('body > nav a[aria-current=page]');
                const other = document.querySelector('body > nav a:not([aria-current])');
                const h1 = document.querySelector('h1');
                return {
                    bodySize: s(document.body, 'fontSize'), bodyBg: s(document.body, 'backgroundColor'),
                    bodyColor: s(document.body, 'color'), feature: s(document.body, 'fontFeatureSettings'),
                    h1Size: s(h1, 'fontSize'), h1Weight: s(h1, 'fontWeight'),
                    h2Size: s(document.querySelector('main h2'), 'fontSize'),
                    currentColor: s(current, 'color'), otherColor: s(other, 'color'),
                    currentBg: s(current, 'backgroundColor'), pillBg: s(current.firstElementChild, 'backgroundColor'),
                };
            }""",
        ) as Map<String, String>

        assertEquals("17px", styles["bodySize"])
        assertEquals("rgb(246, 245, 242)", styles["bodyBg"], "canvas")
        assertEquals("rgb(28, 27, 25)", styles["bodyColor"], "ink")
        assertTrue(styles.getValue("feature").contains("cv08"), "Inter variant cv08: ${styles["feature"]}")
        assertEquals(if (e2e.profile.isPhone) "26px" else "32px", styles["h1Size"], "h1")
        assertEquals("600", styles["h1Weight"])
        assertEquals("22px", styles["h2Size"])
        assertEquals("rgb(42, 75, 160)", styles["currentColor"], "current entry: brand")
        assertEquals("rgb(87, 83, 76)", styles["otherColor"], "other entries: ink-muted")
        if (e2e.profile.isPhone) {
            // Phone: the pill around the icon is light blue, the entry itself is transparent.
            assertEquals("rgb(231, 236, 248)", styles["pillBg"], "pill of the current entry")
            assertEquals("rgba(0, 0, 0, 0)", styles["currentBg"])
        } else {
            // Desktop: the whole row of the current entry is light blue.
            assertEquals("rgb(231, 236, 248)", styles["currentBg"], "row of the current entry")
        }
    }

    @E2eTest
    fun `no text of the pages of the shell is smaller than 13 px`(e2e: E2e) {
        for (shellPage in shellPages) {
            e2e.open(shellPage.path)
            e2e.page.waitForSettledContent()
            @Suppress("UNCHECKED_CAST")
            val small = e2e.page.evaluate(
                """() => {
                    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                    const found = [];
                    while (walker.nextNode()) {
                        const node = walker.currentNode;
                        const el = node.parentElement;
                        if (!node.textContent.trim() || el.closest('template')) continue;
                        const box = el.getBoundingClientRect();
                        if (box.width === 0 || box.height === 0) continue;
                        const size = parseFloat(getComputedStyle(el).fontSize);
                        if (size < 13) found.push(size + 'px: ' + node.textContent.trim().slice(0, 30));
                    }
                    return found;
                }""",
            ) as List<String>
            assertEquals(emptyList<String>(), small, "${shellPage.name} (${e2e.profile.label})")
        }
    }

    @E2eTest(profiles = [Profile.PHONE])
    fun `on the phone the header is 56 px and stays on top, the navigation is 64 px`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.waitForSettledContent()

        val header = page.locator("header").box()
        assertEquals(0.0, header.y)
        assertTrue(header.height in 56.0..57.0, "header: $header")
        assertEquals(360.0, header.width)
        val nav = page.mainNavigation().box()
        assertTrue(nav.height in 64.0..66.0, "navigation: $nav")

        // The four entries share the width equally.
        val widths = page.mainNavigation().locator("a").all().map { it.box().width }
        assertTrue(widths.max() - widths.min() < 1, "entries of different width: $widths")

        // Scrolling down, the header stays at the top.
        page.evaluate(
            """() => {
                const filler = document.createElement('div');
                filler.style.height = '2000px';
                document.querySelector('main > div').append(filler);
                window.scrollTo(0, 600);
            }""",
        )
        page.waitUntil("window.scrollY >= 600")
        assertEquals(0.0, page.locator("header").box().y, "the header scrolled away")
    }

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `on the desktop the navigation is a column of 256 px, the content a centered column of 768 px`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.waitForSettledContent()

        val nav = page.mainNavigation().box()
        assertEquals(0.0, nav.x)
        assertEquals(256.0, nav.width)
        val header = page.locator("header").box()
        assertEquals(256.0, header.width, "the brand sits in the column of the navigation")
        assertEquals(0.0, header.y)

        val main = page.locator("main").box()
        assertEquals(256.0, main.x, "the content starts after the navigation")
        val column = page.locator("main > div").box()
        assertEquals(768.0, column.width, "width of the column of the content")
        val left = column.x - main.x
        val right = main.x + main.width - (column.x + column.width)
        assertTrue(kotlin.math.abs(left - right) < 1, "the column is not centered: left $left, right $right")
        assertTrue(left >= 40, "margin of the page: $left")

        // The entries are one under the other.
        val ys = page.mainNavigation().locator("a").all().map { it.box().y }
        assertEquals(ys.sorted(), ys)
        assertEquals(ys.size, ys.toSet().size)
    }

    @E2eTest
    fun `the name Talita in the header leads to the home and is never the current entry`(e2e: E2e) {
        val page = e2e.page
        for (shellPage in shellPages) {
            e2e.open(shellPage.path)
            val brand = page.locator("header a[href='/']")
            assertThat(brand).hasAttribute("href", "/")
            assertThat(brand).containsText("Talità")
            assertEquals(0, page.locator("header [aria-current]").count(), "${shellPage.name}: the brand is marked as current")
        }
    }

    @E2eTest
    fun `the keyboard reaches the skip link first, then the content, and the focus ring is visible`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/convivenza")

        page.keyboard().press("Tab")
        val skip = page.locator("a[href='#contenuto']")
        assertThat(skip).isFocused()
        assertThat(skip).hasText("Vai al contenuto")
        val box = skip.box()
        val viewport = page.viewport()
        assertTrue(box.width > 40 && box.height > 40 && box.x >= 0 && box.y >= 0 && box.x + box.width <= viewport.width, "the skip link is not visible when focused: $box")

        page.keyboard().press("Enter")
        assertEquals("contenuto", page.evaluate("document.activeElement.id"), "the focus did not move to the content")

        // Brand, the profile, then the entries of the navigation, in order, with a visible ring.
        page.navigate(e2e.url("/convivenza"))
        page.keyboard().press("Tab") // skip link
        page.keyboard().press("Tab") // brand
        assertEquals("Talità", (page.evaluate("document.activeElement.textContent") as String).trim())
        page.keyboard().press("Tab") // the pill of the profile
        assertEquals("Profilo", (page.evaluate("document.activeElement.getAttribute('href') === '/profilo' ? 'Profilo' : ''") as String))
        for (label in navigationLabels) {
            page.keyboard().press("Tab")
            assertEquals(label, (page.evaluate("document.activeElement.textContent") as String).trim())
            // The ring fades from the color of the text to the blue of the focus in 150 ms (transition-colors).
            page.waitUntil(
                "(() => { const s = getComputedStyle(document.activeElement); return s.outlineStyle + ' ' + s.outlineWidth + ' ' + s.outlineColor })() === 'solid 3px rgb(42, 75, 160)'",
            )
        }
    }

    @E2eTest
    fun `the keyboard can follow an entry with Enter and the focus moves to the heading`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.evaluate("window.__marker = 'still-here'")

        page.navLink("Iscrizioni").focus()
        page.keyboard().press("Enter")

        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        assertEquals("still-here", page.evaluate("window.__marker"), "the page was reloaded")
        // A screen reader announces the new title: the focus goes to the h1, without a ring.
        page.waitUntil("document.activeElement.tagName === 'H1'")
        assertEquals("none", page.evaluate("getComputedStyle(document.activeElement).outlineStyle"))
    }

    @E2eTest
    fun `no inline style or script is left by the navigation, the messages or the error pages`(e2e: E2e) {
        // A future Content-Security-Policy (TALITA-3) blocks them: the DOM built by htmx and app.js must be free too.
        val page = e2e.page
        e2e.open("/")
        e2e.touch(page.navLink("Convivenza"))
        assertThat(page.locator("h1")).hasText("Convivenza")
        e2e.browserContext.setOffline(true)
        e2e.touch(page.navLink("Iscrizioni"))
        assertThat(page.messages()).hasCount(1)
        e2e.browserContext.setOffline(false)

        val offenders = page.evaluate(
            """() => ({
                styleAttributes: document.querySelectorAll('[style]').length,
                styleElements: document.querySelectorAll('style').length,
                inlineScripts: Array.from(document.querySelectorAll('script')).filter(s => !s.src).length,
                handlers: Array.from(document.querySelectorAll('*')).filter(e => Array.from(e.attributes).some(a => a.name.startsWith('on') || a.name.startsWith('hx-on') || a.name.startsWith('data-hx-on'))).length,
            })""",
        )
        assertEquals(mapOf("styleAttributes" to 0, "styleElements" to 0, "inlineScripts" to 0, "handlers" to 0), offenders)
    }
}
