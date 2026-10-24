package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.Media
import talita.checkin.CheckinFormat
import talita.jooq.tables.references.CHECKIN
import talita.jooq.tables.references.REGISTRATION
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three prints on a real browser (criteria 23-27): the button that prints, the page that does not scroll sideways at 320 px, axe, the sheet as it comes out of the
 * printer (hiding, black on white, nothing cut on the right) and the number of sheets that Chromium makes, checked against the «pagina N di M» that the server counts.
 * The PDF files are written in a folder that is thrown away: never in the repository. Names and numbers are synthetic.
 */
@E2eApplication
class PrintE2eTest {

    private val prints = listOf(
        "/arrivi/registro" to "Registro arrivi",
        "/arrivi/elenco" to "Elenco per la struttura",
        "/arrivi/foglio" to "Foglio per i maestri",
    )

    /** [count] children confirmed for every day, with a name and a phone that are made up. With [long] the names take two lines of the narrow column of the register. */
    private fun E2e.crowd(world: ArrivalsWorld, count: Int, long: Boolean = false) {
        val rows = rows()
        for (number in 1..count) {
            rows.register(
                world.id, someParent(),
                if (long) "Maria Concetta" else "Bambino",
                if (long) "Montefiore %02d".format(number) else "Prova%02d".format(number),
                LocalDate.of(2016, 1, 1).plusDays(number.toLong()),
                phone = "333 123 %04d".format(number),
            )
        }
    }

    /** The «M» of the «pagina N di M» of the last caption that the eyes see. */
    private fun Page.sheetsAnnounced(): Int {
        val last = locator("caption:not(.sr-only)").last().innerText()
        return checkNotNull(Regex("""pagina (\d+) di (\d+)""").find(last)) { "no page count in '$last'" }.groupValues[2].toInt()
    }

    /** The number of pages of a PDF that Chromium wrote (the page objects are in the clear: Skia writes no object streams). */
    private fun pagesOf(pdf: Path): Int = Regex("/Type\\s*/Page[^s]").findAll(String(Files.readAllBytes(pdf), Charsets.ISO_8859_1)).count()

    private fun Page.print() = emulateMedia(Page.EmulateMediaOptions().setMedia(Media.PRINT))

    // ---- On the screen (criteria 24, 27) ----------------------------------------------------------------------------------------

    @E2eTest
    fun `t6 c24 c27 each print has the button that prints, and the page has no sideways scrolling, also at 320 px, and no violation of axe`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.crowd(w, 12)

        for ((path, title) in prints) {
            e2e.open(path)

            assertThat(page).hasTitle("$title · Talità")
            assertThat(page.locator("h1")).hasText(title)
            // The button is in the page hidden, and app.js shows it: it prints with window.print() and asks the server nothing.
            val button = page.locator("main button[data-azione=stampa]")
            assertThat(button).isVisible()
            assertThat(button).hasText("Stampa")
            assertTrue(button.box().height >= 47.9, "the button is ${button.box()}")
            page.evaluate("() => { window.__printed = 0; window.print = () => { window.__printed += 1; }; }")
            e2e.press(button)
            assertEquals(1, (page.evaluate("window.__printed") as Number).toInt(), "$path: one tap on Stampa prints once")
            assertTrue(page.noHorizontalScroll(), "$path: sideways scrolling")
            page.setViewportSize(320, 800)
            assertTrue(page.noHorizontalScroll(), "$path: sideways scrolling at 320 px")
            page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
            assertAccessible(page, "$title (${e2e.profile.label})")
        }
    }

    @E2eTest
    fun `t6 c24 c27 the table of the register scrolls in its own box on a phone, with the line that says so, and the page does not`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering(first = -3, last = 3)
        e2e.crowd(w, 6)
        e2e.open("/arrivi/registro")

        val box = page.locator("main div.overflow-x-auto").first()
        val hint = page.locator("main p:has-text('Scorri la tabella di lato per vedere tutte le colonne.')")
        if (e2e.profile.isPhone) {
            assertThat(hint).isVisible()
            val overflows = box.evaluate("e => e.scrollWidth > e.clientWidth") as Boolean
            assertTrue(overflows, "seven days do not fit in 360 px: the table scrolls in its box")
        } else {
            assertThat(hint).isHidden()
        }
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "the page does not scroll at 320 px, the box does")
        assertAccessible(page, "register of seven days at 320 px (${e2e.profile.label})")
    }

    @E2eTest
    fun `t6 c25 the list for the venue changes with the day that is chosen, with a normal GET and nothing personal in the address`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.crowd(w, 4)
        val ids = e2e.dsl().select(REGISTRATION.ID).from(REGISTRATION).fetch(REGISTRATION.ID).map { it!! }
        e2e.dsl().insertInto(CHECKIN)
            .set(CHECKIN.REGISTRATION_ID, ids.first())
            .set(CHECKIN.DAY, w.today)
            .set(CHECKIN.RECORDED_AT, OffsetDateTime.now())
            .set(CHECKIN.RECORDED_BY_ACCOUNT_ID, w.martaId)
            .execute()
        e2e.open("/arrivi/elenco")
        assertThat(page.locator("main h2").first()).hasText("Bambini con l’iscrizione confermata · 4")

        page.getByLabel("Chi mettere fra i bambini").selectOption(w.today.toString())
        e2e.press(page.locator("main").buttonNamed("Aggiorna l’elenco"))

        assertThat(page).hasURL(e2e.url("/arrivi/elenco?giorno=${w.today}"))
        assertThat(page.locator("main h2").first()).containsText("Bambini presenti")
        assertThat(page.locator("main h2").first()).containsText("· 1")
        assertThat(page.getByLabel("Chi mettere fra i bambini")).hasValue(w.today.toString())
        assertAccessible(page, "list for the venue with a day (${e2e.profile.label})")
        // A day with nobody: the empty state, and the way back to the confirmed ones.
        e2e.open("/arrivi/elenco?giorno=${w.tomorrow}")
        assertThat(page.locator("main h2")).hasText("Nessun bambino presente ${CheckinFormat.longDay(w.tomorrow)}")
        assertAccessible(page, "list for the venue with no one (${e2e.profile.label})")
        e2e.touch(page.locator("main").linkNamed("Vedi le iscrizioni confermate"))
        assertThat(page).hasURL(e2e.url("/arrivi/elenco"))
    }

    // ---- As it comes out of the printer (criterion 24) ----------------------------------------------------------------------------

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t6 c24 on paper the shell, the bar and the messages are gone, it is black on white, and nothing goes out of the sheet on the right`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.crowd(w, 30)

        for ((path, title) in prints) {
            e2e.open(path)
            page.print()
            // The width of a sheet of A4 inside the margins of the design: 210 mm less 2 x 18 mm, 658 px.
            page.setViewportSize(658, 1001)

            assertEquals("none", page.locator("header").evaluate("e => getComputedStyle(e).display"), "$title: the header")
            assertEquals("none", page.locator("body > nav").evaluate("e => getComputedStyle(e).display"), "$title: the navigation")
            assertEquals("none", page.locator("#messaggi").evaluate("e => getComputedStyle(e).display"), "$title: the messages")
            for (hidden in page.locator("main .print\\:hidden").all()) assertEquals("none", hidden.evaluate("e => getComputedStyle(e).display"), "$title: the bar and the help are not printed")
            assertEquals("rgb(0, 0, 0)", page.locator("h1").evaluate("e => getComputedStyle(e).color"), "$title: the title is black")
            assertEquals("rgb(255, 255, 255)", page.locator("body").evaluate("e => getComputedStyle(e).backgroundColor"))
            for (cell in page.locator("main td, main th").all().take(40)) {
                assertEquals("rgb(0, 0, 0)", cell.evaluate("e => getComputedStyle(e).borderTopColor"), "$title: a border is not black")
                assertTrue(cell.evaluate("e => getComputedStyle(e).backgroundColor") in listOf("rgb(255, 255, 255)", "rgba(0, 0, 0, 0)"), "$title: a cell has a colour")
            }
            assertTrue(page.noHorizontalScroll(), "$title: the sheet is cut on the right")
            for (table in page.locator("main table").all()) {
                val box = table.box()
                assertTrue(box.x >= 0 && box.x + box.width <= 658.5, "$title: a table goes out of the sheet: $box")
            }
            // The rows do not break between two sheets, and the next sheet starts on a new page.
            assertEquals("avoid", page.locator("main tbody tr").first().evaluate("e => getComputedStyle(e).breakInside"), "$title: a row can be cut")
            if (page.locator(".blocco-stampa").count() > 1) {
                assertEquals("page", page.locator(".blocco-stampa + .blocco-stampa").first().evaluate("e => getComputedStyle(e).breakBefore"), "$title: the second sheet does not start a page")
            }
        }
        e2e.open("/arrivi/foglio")
        page.print()
        assertEquals("rgb(0, 0, 0)", page.locator("main .riquadro-riservato").evaluate("e => getComputedStyle(e).borderTopColor"))
        assertEquals("rgb(255, 255, 255)", page.locator("main .riquadro-riservato").evaluate("e => getComputedStyle(e).backgroundColor"), "the sentence has a border, not a colour")
        e2e.open("/arrivi/registro")
        page.print()
        assertEquals("rgb(0, 0, 0)", page.locator("main .casella").first().evaluate("e => getComputedStyle(e).borderTopColor"), "the box is black")
    }

    /** Opens [path], makes the PDF as Chromium prints it (A4 and the margins of the design), and compares its pages with the «di M» that the server wrote. */
    private fun E2e.sheetsMatch(path: String, folder: Path, name: String, expected: Int?) {
        open(path)
        page.waitForSettledContent()
        val announced = page.sheetsAnnounced()
        // The PDF first: after emulating the media the page would be printed with the screen CSS, on a Letter sheet.
        val pdf = folder.resolve("$name.pdf")
        page.pdf(Page.PdfOptions().setPath(pdf).setPreferCSSPageSize(true).setPrintBackground(false))
        val pages = pagesOf(pdf)

        if (expected != null) assertEquals(expected, announced, "$name: the server counts $announced sheets and the test expects $expected")
        assertEquals(announced, pages, "$name: Chromium makes $pages sheets and the page says «di $announced»")
    }

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t6 c24 the sheets that Chromium makes are the ones that the server counts, for the register, the list for the venue and the sheet for the teachers`(e2e: E2e) {
        val folder = Files.createTempDirectory("talita-stampe")
        try {
            // The register of three days: 26 children and the five blank rows are 31 rows, 20 and 11; 40 children are 45 rows, 20, 24 and 1.
            for ((children, sheets) in listOf(5 to 1, 26 to 2, 40 to 3, 39 to 3)) {
                val w = e2e.arrivalsGathering()
                e2e.crowd(w, children)
                e2e.sheetsMatch("/arrivi/registro", folder, "registro-3-giorni-$children", expected = sheets)
            }
            // Seven days: 16 and 15 rows, and with one child more the totals have a sheet of their own.
            for ((children, sheets) in listOf(26 to 2, 27 to 3)) {
                val w = e2e.arrivalsGathering(first = -3, last = 3)
                e2e.crowd(w, children)
                e2e.sheetsMatch("/arrivi/registro", folder, "registro-7-giorni-$children", expected = sheets)
            }
            // The list for the venue: one sheet, two (the teachers follow the 7 children of the second), three (they have a sheet of their own).
            for ((children, sheets) in listOf(8 to 1, 27 to 2, 45 to 3)) {
                val w = e2e.arrivalsGathering()
                e2e.crowd(w, children)
                e2e.sheetsMatch("/arrivi/elenco", folder, "elenco-$children", expected = sheets)
            }
            // The sheet for the teachers: one, two, and three when the last sentence has no room.
            for ((children, sheets) in listOf(10 to 1, 31 to 2, 41 to 3)) {
                val w = e2e.arrivalsGathering()
                e2e.crowd(w, children)
                e2e.sheetsMatch("/arrivi/foglio", folder, "foglio-$children", expected = sheets)
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t6 c24 with names on two lines the rows do not grow, and the sheets are still the ones that the server counts`(e2e: E2e) {
        val folder = Files.createTempDirectory("talita-stampe")
        try {
            // Three days and seven: the names take two lines in the column of the register, a row stays 9,5 mm high, and the sheets are the ones the server counts.
            for ((first, last) in listOf(-1L to 1L, -3L to 3L)) {
                val w = e2e.arrivalsGathering(first = first, last = last)
                e2e.crowd(w, 26, long = true)
                e2e.sheetsMatch("/arrivi/registro", folder, "registro-lunghi-$last", expected = null)
                e2e.sheetsMatch("/arrivi/foglio", folder, "foglio-lunghi-$last", expected = null)
                e2e.sheetsMatch("/arrivi/elenco", folder, "elenco-lunghi-$last", expected = null)
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    // ---- From the list to the prints --------------------------------------------------------------------------------------------

    @E2eTest
    fun `t6 c24 the three prints are reached from the card Da stampare of the list, and Arrivi is the current entry`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.crowd(w, 3)

        for ((path, title) in prints) {
            e2e.open("/arrivi")
            e2e.press(page.locator("#stampe").linkWith(title))
            assertThat(page).hasURL(e2e.url(path))
            assertThat(page.locator("h1")).hasText(title)
            assertThat(page.mainNavigation().locator("a[aria-current=page]")).hasText("Arrivi")
        }
    }

    /** The link of a row of «Da stampare»: its name is the title and the line under it, so it is found by the title. */
    private fun Locator.linkWith(title: String): Locator = locator("a").filter(Locator.FilterOptions().setHasText(title))
}
