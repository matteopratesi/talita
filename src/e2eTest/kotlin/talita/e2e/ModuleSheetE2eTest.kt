package talita.e2e

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.Media
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.springframework.boot.test.system.CapturedOutput
import talita.CapturesOutput
import talita.jooq.tables.references.CHECKIN
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.VENUE_FORM
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sheet of a form of the venue on a real browser, on a phone and on a desktop (TALITA-20, part 3, criteria 21, 22, 25, 29 and 31): «Da stampare» with the forms, the sheet that does not scroll sideways at 320 px and
 * has no violation of axe, the sheets of paper as Chromium prints them, and above all **what a teacher writes in the fields of the teachers**: in no request, in no storage, on the PDF that the browser saves, and gone when the
 * page is left and comes back or is loaded again. The PDF files are written in a folder that is thrown away. Names, venues and the values of the fields are synthetic.
 */
@E2eApplication
class ModuleSheetE2eTest {

    private fun E2e.venueOf(gathering: Long): Long = dsl().select(GATHERING.VENUE_ID).from(GATHERING).where(GATHERING.ID.eq(gathering)).fetchSingle(GATHERING.VENUE_ID)!!

    private fun E2e.form(gathering: Long, name: String, age: Boolean = false, days: Boolean = false, document: Boolean = false, taxCode: Boolean = false): Long =
        dsl().insertInto(VENUE_FORM).set(VENUE_FORM.VENUE_ID, venueOf(gathering)).set(VENUE_FORM.NAME, name).set(VENUE_FORM.WITH_AGE, age).set(VENUE_FORM.WITH_DAYS, days)
            .set(VENUE_FORM.WITH_TEACHER_DOCUMENT, document).set(VENUE_FORM.WITH_TEACHER_TAX_CODE, taxCode).returningResult(VENUE_FORM.ID).fetchSingle().value1()!!

    /** [count] children who are present on [day]: registered for every day, with the presence marked by a custodian. */
    private fun E2e.present(world: ArrivalsWorld, count: Int, day: LocalDate = world.yesterday) {
        val rows = rows()
        for (number in 1..count) {
            val id = rows.register(world.id, someParent(), "Bambino", "Prova%02d".format(number), LocalDate.of(2016, 1, 1).plusDays(number.toLong()))
            dsl().insertInto(CHECKIN).set(CHECKIN.REGISTRATION_ID, id).set(CHECKIN.DAY, day).set(CHECKIN.RECORDED_AT, OffsetDateTime.now()).set(CHECKIN.RECORDED_BY_ACCOUNT_ID, world.martaId).execute()
        }
    }

    private fun Page.print() = emulateMedia(Page.EmulateMediaOptions().setMedia(Media.PRINT))

    private fun pagesOf(pdf: Path): Int = Regex("/Type\\s*/Page[^s]").findAll(String(Files.readAllBytes(pdf), Charsets.ISO_8859_1)).count()

    /** The text of each sheet of a PDF, with `pdftotext` (null when the program is not there). */
    private fun sheetsOf(pdf: Path): List<String>? = try {
        val process = ProcessBuilder("pdftotext", "-layout", pdf.toString(), "-").redirectErrorStream(true).start()
        val text = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (process.waitFor() == 0) text.split('\u000c').filter { it.isNotBlank() } else null
    } catch (e: java.io.IOException) {
        null
    }

    private fun Page.sideways(e2e: E2e, what: String) {
        assertTrue(noHorizontalScroll(), "$what: no sideways scrolling")
        setViewportSize(320, 800)
        assertTrue(noHorizontalScroll(), "$what: no sideways scrolling at 320 px")
        setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
    }

    // ---- «Da stampare» and the sheet (criteria 21, 22, 29) -----------------------------------------------------------------------

    @E2eTest
    fun `t20 c21 c22 c29 Da stampare has a row for each form, the sheet is read from there, does not scroll sideways at 320 px, has no violation of axe and has the fields of the teachers`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.present(w, 10)
        val register = e2e.form(w.id, "Registro per la tassa di soggiorno", age = true, days = true, document = true, taxCode = true)
        e2e.form(w.id, "Solo nomi")
        val onlyTaxCode = e2e.form(w.id, "Solo il codice fiscale", taxCode = true)

        e2e.open("/arrivi")
        val section = page.locator("#stampe")
        assertThat(section).containsText("Registro per la tassa di soggiorno")
        assertThat(section).containsText("nome e cognome, età al primo giorno e giorni di presenza. Maestri: documento e codice fiscale.")
        assertThat(section).containsText("I moduli della struttura hanno i bambini già segnati presenti: stampali dopo gli arrivi.")
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "Arrivi with the forms of the venue (${e2e.profile.label})")
        e2e.press(section.locator("a[href='/arrivi/modulo/$register']"))

        assertThat(page).hasURL(e2e.url("/arrivi/modulo/$register"))
        assertThat(page.locator("h1")).hasText("Registro per la tassa di soggiorno")
        assertThat(page).hasTitle("Registro per la tassa di soggiorno · Talità")
        assertThat(page.locator("main")).containsText("Bambini presenti · 10")
        assertThat(page.locator("main")).containsText("Quello che scrivi qui non si salva in Talità.")
        assertEquals(13, page.locator("input[data-solo-carta]").count(), "two custodians with two fields and three blank rows with three")
        assertEquals(0, page.locator("main form").count(), "no form in the page of the sheet: nothing can send a field")
        page.sideways(e2e, "sheet of a form")
        assertAccessible(page, "sheet of a form with the fields of the teachers (${e2e.profile.label})")
        // The tables scroll sideways inside their regions, which have a name and are reached with the keyboard.
        assertThat(page.locator("div[role=region][tabindex='0']").first()).hasAttribute("aria-label", Pattern.compile("Registro per la tassa di soggiorno, bambini.*"))
        // A phone is told so: the line that says to scroll the table.
        val hint = page.locator("main p:has-text('Scorri la tabella di lato per vedere tutte le colonne.')")
        assertEquals(1, hint.count(), "the sheet with days has the line that says to scroll")
        if (e2e.profile.isPhone) assertThat(hint).isVisible() else assertThat(hint).isHidden()

        // The sheet with only the names: the narrow table, no field, no notice.
        e2e.open("/arrivi/modulo/${e2e.dsl().select(VENUE_FORM.ID).from(VENUE_FORM).where(VENUE_FORM.NAME.eq("Solo nomi")).fetchSingle(VENUE_FORM.ID)}")
        assertEquals(0, page.locator("input").count())
        assertThat(page.locator("main")).not().containsText("Quello che scrivi qui non si salva")
        page.sideways(e2e, "sheet with only the names")
        assertAccessible(page, "sheet of a form with only the names (${e2e.profile.label})")
        // Nothing scrolls in a sheet with no days: no line that says to scroll (D4 of the QA), on the sheet with only the names and on the one with only the tax code of the teachers.
        assertEquals(0, page.locator("main p:has-text('Scorri la tabella di lato')").count(), "no line to scroll on the sheet with only the names")
        e2e.open("/arrivi/modulo/$onlyTaxCode")
        assertThat(page.locator("h1")).hasText("Solo il codice fiscale")
        assertEquals(0, page.locator("main p:has-text('Scorri la tabella di lato')").count(), "no line to scroll on the sheet with only the tax code")
        assertEquals(8, page.locator("input[data-solo-carta]").count(), "the tax code of the five rows of the teachers, and the name of the three blank rows")
        page.sideways(e2e, "sheet with only the tax code")
        assertAccessible(page, "sheet of a form with only the tax code (${e2e.profile.label})")
    }

    @E2eTest
    fun `t20 c22 c24 the sheet with nobody present has the state that leads to the list for the venue, and a form that is not there is the page that does not exist`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.rows().register(w.id, e2e.someParent(), "Sara", "Neri", LocalDate.of(2017, 3, 3))
        val id = e2e.form(w.id, "Registro per la tassa di soggiorno", age = true, document = true)

        e2e.open("/arrivi/modulo/$id")
        assertThat(page.locator("main h2:visible").first()).hasText("Nessun bambino presente")
        assertThat(page.locator("main")).containsText("Segna gli arrivi, oppure stampa l’elenco di chi è iscritto.")
        assertEquals(0, page.locator("input, table").count())
        page.sideways(e2e, "sheet with nobody present")
        assertAccessible(page, "sheet with nobody present (${e2e.profile.label})")
        e2e.press(page.locator("main").linkNamed("Apri l’elenco per la struttura"))
        assertThat(page).hasURL(e2e.url("/arrivi/elenco"))
        assertEquals(404, e2e.open("/arrivi/modulo/${id + 100_000}").status())
    }

    // ---- The sheets of paper (criterion 25) -------------------------------------------------------------------------------------------

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t20 c25 35 children, 7 days, the age and the fields of the teachers are two A4 sheets in portrait as Chromium prints them, with the header on each sheet, no row cut, and the days left out of the table of the teachers`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering(first = -1, last = 5)
        e2e.present(w, 35)
        val id = e2e.form(w.id, "Registro per la tassa di soggiorno", age = true, days = true, document = true, taxCode = true)
        val folder = Files.createTempDirectory("talita-stampa-modulo")
        try {
            e2e.open("/arrivi/modulo/$id")
            assertThat(page.locator("caption:not(.sr-only)").first()).hasText("Registro per la tassa di soggiorno · pagina 1 di 2")
            assertEquals(3, page.locator("div.blocco-stampa").count(), "two sheets of children and the table of the teachers")
            assertEquals(0, page.locator("table.tabella-stampa--maestri th.col-giorno").count(), "with seven days and two fields the table of the teachers has no day")

            val pdf = folder.resolve("modulo.pdf")
            page.pdf(Page.PdfOptions().setPath(pdf).setPreferCSSPageSize(true).setPrintBackground(false))
            assertEquals(2, pagesOf(pdf), "Chromium makes the two sheets that the server announced")
            val box = Regex("/MediaBox\\s*\\[\\s*0\\s+0\\s+([0-9.]+)\\s+([0-9.]+)").find(String(Files.readAllBytes(pdf), Charsets.ISO_8859_1))!!.groupValues
            assertTrue(box[1].toDouble() in 594.0..596.0 && box[2].toDouble() in 841.0..843.0, "A4 upright is 595 by 842 points: $box")
            val sheets = sheetsOf(pdf)
            assumeTrue(sheets != null, "pdftotext is not installed: the sheets of the PDF are not read")
            assertEquals(2, sheets!!.size)
            assertTrue(sheets.all { it.contains("Cognome e nome") }, "every sheet has the header of its table")
            val text = sheets.joinToString("\n")
            for (number in 1..35) assertEquals(1, Regex("Prova%02d".format(number)).findAll(text).count(), "Prova%02d is once on the sheets".format(number))
            assertTrue(sheets.last().contains("Maestri") && sheets.last().contains("Documento d’identità") && sheets.last().contains("Codice fiscale"), "the teachers are under the last sheet")
            assertTrue(sheets.first().contains("Prova01") && !sheets.first().contains("Prova35"), "the rows are divided in order")
            assertFalse(text.contains("Quello che scrivi qui non si salva"), "the notice is not printed")
            assertFalse(text.contains("Stampa su A4") || text.contains("Torna agli arrivi"), "the bar is not printed")

            // Printed: black on white, the notice and the bar are gone, and the fields have no border (the value is the text of the cell).
            page.print()
            assertThat(page.locator("main button[data-azione=stampa]")).not().isVisible()
            assertThat(page.locator("main .campo-stampa").first()).isVisible()
            assertEquals("0px", page.locator("main .campo-stampa").first().evaluate("e => getComputedStyle(e).borderTopWidth"), "no border on the paper")
            assertEquals("rgb(0, 0, 0)", page.locator("main h1").evaluate("e => getComputedStyle(e).color"))
            for (block in page.locator("div.blocco-stampa").all()) assertEquals("auto", block.evaluate("e => getComputedStyle(e).breakInside"))
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    // ---- What a teacher writes in the fields (criterion 31) --------------------------------------------------------------------------

    @E2eTest
    @CapturesOutput
    fun `t20 c31 what is written in the fields of the teachers is in no request and no storage, goes into the PDF, is gone when the page is left and comes back and when it is loaded again, and is not in the log`(e2e: E2e, output: CapturedOutput) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.present(w, 4)
        val id = e2e.form(w.id, "Registro per la tassa di soggiorno", age = true, days = true, document = true, taxCode = true)
        val document = "DOCPROVA123"
        val taxCode = "CFPROVA000000001"
        val name = "NOMEPROVA"
        val values = listOf(document, taxCode, name)
        val requests = mutableListOf<String>()
        val folder = Files.createTempDirectory("talita-stampa-campi")
        try {
            page.onRequest { requests += "${it.method()} ${it.url()} ${it.headers()} ${it.postData()}" }
            e2e.open("/arrivi/modulo/$id")
            val fields = page.locator("input[data-solo-carta]")
            val count = fields.count()
            assertEquals(13, count)
            val before = requests.size

            // Every field: the value that goes there, and Enter (a field that is not in a form sends nothing).
            val written = mutableListOf<String>()
            for (index in 0 until count) {
                val field = fields.nth(index)
                val value = when (field.getAttribute("maxlength")) {
                    "20" -> document
                    "16" -> taxCode
                    else -> name
                }
                field.scrollIntoViewIfNeeded()
                field.fill(value)
                field.press("Enter")
                written += value
            }
            page.waitForTimeout(600.0)
            assertEquals(count, written.size)
            assertEquals(before, requests.size, "writing in the fields and pressing Enter sends no request at all: ${requests.drop(before)}")
            assertEquals(emptyList(), requests.filter { it.startsWith("POST") || it.startsWith("PUT") || it.startsWith("PATCH") || it.startsWith("DELETE") }, "no request that changes something")
            assertTrue(page.url().endsWith("/arrivi/modulo/$id"), "the page is where it was: ${page.url()}")
            for (value in values) assertTrue(requests.none { it.contains(value) }, "«$value» is in a request: ${requests.filter { it.contains(value) }}")
            assertEquals(written, fields.evaluateAll("els => els.map(e => e.value)") as List<*>, "the page keeps what was written until it is left")

            // Nothing in the storage of the browser: no local storage, no session storage, no database, no cookie with the values.
            val storage = page.evaluate("async () => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage }, databases: (indexedDB.databases ? await indexedDB.databases() : []), cookie: document.cookie })") as String
            for (value in values) assertFalse(storage.contains(value), "«$value» is in the storage of the page: $storage")
            for (cookie in page.context().cookies()) for (value in values) assertFalse(cookie.value.contains(value) || cookie.name.contains(value), "a cookie has «$value»")
            assertEquals("[]", page.evaluate("async () => JSON.stringify(indexedDB.databases ? await indexedDB.databases() : [])"), "the application opens no database in the browser")

            // The paper: the browser prints the value as the text of the cell, and the PDF has it (the browser of the teacher saves it; Talità never sees it).
            if (e2e.profile == Profile.DESKTOP) {
                val pdf = folder.resolve("campi.pdf")
                page.pdf(Page.PdfOptions().setPath(pdf).setPreferCSSPageSize(true).setPrintBackground(false))
                val sheets = sheetsOf(pdf)
                assumeTrue(sheets != null, "pdftotext is not installed: the PDF is not read")
                val text = sheets!!.joinToString("\n")
                // Two custodians with the two fields and three blank rows with the two fields: five of each; the name of the blank rows is there three times.
                assertEquals(5, Regex(document).findAll(text).count(), "the document of every row is on the paper")
                assertEquals(5, Regex(taxCode).findAll(text).count(), "the tax code of every row is on the paper")
                assertEquals(3, Regex(name).findAll(text).count(), "the name of the blank rows is on the paper")
            }

            // The way out and the way back, by a link and with the button of the browser, and a new load: the fields are empty.
            e2e.press(page.locator("main").linkNamed("Torna agli arrivi"))
            assertThat(page).hasURL(Pattern.compile(".*/arrivi(#stampe)?$"))
            page.goBack()
            assertThat(page.locator("h1")).hasText("Registro per la tassa di soggiorno")
            assertEquals(count, fields.count())
            assertEquals(List(count) { "" }, fields.evaluateAll("els => els.map(e => e.value)") as List<*>, "after the way back the fields are empty")
            fields.first().fill(document)
            page.reload()
            assertEquals(List(count) { "" }, page.locator("input[data-solo-carta]").evaluateAll("els => els.map(e => e.value)") as List<*>, "after a new load the fields are empty")
            // A page that is left by a normal navigation and comes back (the cache of the browser, if it keeps the page) is empty too.
            page.locator("input[data-solo-carta]").first().fill(document)
            page.navigate(e2e.url("/informativa"))
            page.goBack()
            assertThat(page.locator("h1")).hasText("Registro per la tassa di soggiorno")
            assertEquals(List(count) { "" }, page.locator("input[data-solo-carta]").evaluateAll("els => els.map(e => e.value)") as List<*>, "after a normal way back the fields are empty")

            // The server did not see them: not in the log.
            val log = output.all
            for (value in values) assertFalse(log.contains(value), "the log has «$value»")
        } finally {
            folder.toFile().deleteRecursively()
        }
    }
}
