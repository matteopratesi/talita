package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.FilePayload
import com.microsoft.playwright.options.FormData
import com.microsoft.playwright.options.RequestOptions
import talita.gathering.MenuFile
import talita.gathering.MenuSamples
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.GATHERING_MENU
import java.nio.file.Files
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the QA of TALITA-12 added to the tests of the developer, always through the interface and the real server (criteria 4, 5, 6, 7, 8, 10 and 14): the headers that the
 * browser really receives with the file (and who is turned away from its address), a name with accents and markup (the card shows it as text and the browser saves it with
 * that name), the file chosen as a person chooses it (the dialog of the browser), Togli when the request fails, the file of exactly 10 MB and the one of 15 MB, the button after
 * the back of the browser, the card and the upload in the four states of a gathering, and the pages that were not looked at with 320 px (an error, the page of confirmation and
 * the dialog, with a name of 100 letters). The files are built in memory.
 */
@E2eApplication
class MenuQaE2eTest {

    private fun file(name: String, mime: String, bytes: ByteArray): FilePayload = FilePayload(name, mime, bytes)

    private fun Locator.linkNamed(name: String): Locator = getByRole(AriaRole.LINK, Locator.GetByRoleOptions().setName(name))

    private fun Locator.buttonNamed(name: String): Locator = getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName(name))

    private fun Page.card(): Locator = locator("section[aria-labelledby=menu]")

    private fun E2e.putMenu(gathering: Long, name: String = "menu-settembre-2027.pdf", type: String = "application/pdf", content: ByteArray = MenuSamples.pdf()) {
        dsl().deleteFrom(GATHERING_MENU).where(GATHERING_MENU.GATHERING_ID.eq(gathering)).execute()
        dsl().insertInto(GATHERING_MENU)
            .set(GATHERING_MENU.GATHERING_ID, gathering).set(GATHERING_MENU.FILE_NAME, name).set(GATHERING_MENU.MEDIA_TYPE, type)
            .set(GATHERING_MENU.CONTENT, content).set(GATHERING_MENU.UPLOADED_AT, OffsetDateTime.now()).execute()
    }

    private fun E2e.storedName(gathering: Long): String? =
        dsl().select(GATHERING_MENU.FILE_NAME).from(GATHERING_MENU).where(GATHERING_MENU.GATHERING_ID.eq(gathering)).fetchOne(GATHERING_MENU.FILE_NAME)

    private fun E2e.storedContent(gathering: Long): ByteArray? =
        dsl().select(GATHERING_MENU.CONTENT).from(GATHERING_MENU).where(GATHERING_MENU.GATHERING_ID.eq(gathering)).fetchOne(GATHERING_MENU.CONTENT)

    private fun E2e.saved(on: Page, target: Locator): Pair<String, ByteArray> {
        val download = on.waitForDownload { if (profile.isPhone) target.tap() else target.click() }
        return download.suggestedFilename() to Files.readAllBytes(download.path())
    }

    /** The submit button of the form of the menu, whatever it says (Carica or Sostituisci). */
    private fun Page.submit(): Locator = locator("main form button[type=submit]")

    // ---- The headers that the browser receives, and who is turned away (criteria 8 and 10) ----------------------------------------------

    @E2eTest
    fun `t12 c08 c10 the real server sends the file with the headers of the criterion, and turns away a parent and whoever is not inside`(e2e: E2e) {
        val w = e2e.arrivalsGathering()
        val pdf = MenuSamples.pdf(500)
        e2e.putMenu(w.id, name = "menù di settembre.pdf", content = pdf)
        val address = e2e.url("/convivenza/${w.id}/menu/file")
        val once = RequestOptions.create().setMaxRedirects(0)

        val answer = e2e.page.request().get(address, once)

        assertEquals(200, answer.status())
        fun values(name: String) = answer.headersArray().filter { it.name.equals(name, ignoreCase = true) }.map { it.value }
        assertEquals(listOf("application/pdf"), values("Content-Type"))
        assertEquals(listOf("attachment; filename=\"men_ di settembre.pdf\"; filename*=UTF-8''men%C3%B9%20di%20settembre.pdf"), values("Content-Disposition"))
        assertEquals(listOf("nosniff"), values("X-Content-Type-Options"))
        assertEquals(listOf("default-src 'none'; sandbox"), values("Content-Security-Policy"), "one policy: the one of the file, not the one of the pages")
        assertTrue(values("Cache-Control").single().contains("no-store"), values("Cache-Control").toString())
        assertEquals(listOf(pdf.size.toString()), values("Content-Length"))
        assertTrue(pdf.contentEquals(answer.body()), "the exact bytes")
        assertTrue(!address.contains("settembre") && !address.contains(".pdf"), "the address does not carry the name")

        // A parent (also with the address in their hand) is told 403, and no byte of the file comes with it.
        val parent = e2e.newPage(teacher = false)
        val refused = parent.request().get(address, once)
        assertEquals(403, refused.status())
        assertTrue(refused.headersArray().none { it.name.equals("Content-Disposition", ignoreCase = true) })
        assertTrue(!pdf.contentEquals(refused.body()), "not the file")

        // Whoever is not inside goes to the access page, with no file.
        val nobody = e2e.newPage(signedIn = false)
        val sent = nobody.request().get(address, once)
        assertEquals(302, sent.status())
        assertTrue(sent.headers()["location"]!!.endsWith("/accedi"), sent.headers()["location"])
        assertNull(sent.headers()["content-disposition"])
    }

    // ---- The code against forgery (criteria 2 and 6) ------------------------------------------------------------------------------------

    @E2eTest
    fun `t12 c02 c06 the upload and Togli sent without the code against forgery, or with a wrong one, change nothing`(e2e: E2e) {
        val w = e2e.arrivalsGathering()
        e2e.putMenu(w.id, name = "quello-di-prima.pdf")
        val before = e2e.storedContent(w.id)!!
        val once = RequestOptions.create().setMaxRedirects(0)
        val upload = e2e.url("/convivenza/${w.id}/menu")
        val removal = e2e.url("/convivenza/${w.id}/menu/togli")
        val attempt = file("nuovo.pdf", "application/pdf", MenuSamples.pdf(77))

        for (code in listOf<String?>(null, "", "un-codice-sbagliato")) {
            val sent = FormData.create().set("file", attempt)
            if (code != null) sent.set("_csrf", code)
            val upAnswer = e2e.page.request().post(upload, once.setMultipart(sent))
            assertTrue(upAnswer.status() in listOf(302, 303, 403) && upAnswer.headers()["location"]?.endsWith("/convivenza/${w.id}") != true, "upload with code=$code: ${upAnswer.status()}")
            val formAnswer = e2e.page.request().post(removal, RequestOptions.create().setMaxRedirects(0).setForm(FormData.create().set("from", "detail").also { if (code != null) it.set("_csrf", code) }))
            assertTrue(formAnswer.status() in listOf(302, 303, 403), "Togli with code=$code: ${formAnswer.status()}")
            assertEquals("quello-di-prima.pdf", e2e.storedName(w.id), "code=$code")
            assertTrue(before.contentEquals(e2e.storedContent(w.id)), "code=$code")
        }
    }

    // ---- A name with accents and with markup, and the dialog of the browser (criteria 2, 5, 8) --------------------------------------------

    @E2eTest
    fun `t12 c05 c08 the file is chosen with the dialog of the browser, a name with accents is saved under that name, and one with markup is shown as text`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.open("/convivenza/${w.id}/menu")

        // «Scegli file» as a person taps it: the browser opens its chooser and the person picks a file.
        val chooser = page.waitForFileChooser { if (e2e.profile.isPhone) page.getByLabel("File del menù").tap() else page.getByLabel("File del menù").click() }
        val pdf = MenuSamples.pdf(321)
        chooser.setFiles(file("Menù di più – è già pronto.pdf", "application/pdf", pdf))
        e2e.touch(page.submit())

        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}"))
        assertThat(page.card()).containsText("Menù di più – è già pronto.pdf")
        assertEquals("Menù di più – è già pronto.pdf", e2e.storedName(w.id))
        val (name, bytes) = e2e.saved(page, page.card().linkNamed("Scarica il menù"))
        assertEquals("Menù di più – è già pronto.pdf", name)
        assertTrue(pdf.contentEquals(bytes))

        // The name is the text of whoever wrote it: it is never markup, in the card and in the page of the menu.
        val markup = "<b onmouseover=alert(1)>menu & più.pdf"
        e2e.open("/convivenza/${w.id}/menu")
        page.getByLabel("File del menù").setInputFiles(file(markup, "application/pdf", MenuSamples.pdf()))
        e2e.touch(page.submit())
        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}"))
        assertEquals(markup, page.card().locator("p.font-semibold").innerText())
        assertEquals(0, page.card().locator("b").count(), "no element came out of the name")
        e2e.open("/convivenza/${w.id}/menu")
        assertEquals(markup, page.locator("section[aria-labelledby=attuale] p.font-semibold").innerText())
        assertEquals(0, page.locator("main b").count())
        assertTrue(page.noHorizontalScroll())
    }

    // ---- Togli when the request fails (criterion 6, design 4) -------------------------------------------------------------------------

    @E2eTest
    fun `t12 c06 when Togli cannot reach the server, or the server fails, the dialog closes, a message says so, the menu stays, and it goes when the request works`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.putMenu(w.id)
        e2e.open("/convivenza/${w.id}")

        fun ask() {
            e2e.touch(page.card().linkNamed("Togli il menù"))
            page.waitForSettledDialog()
            e2e.touch(page.locator("dialog[open]").buttonNamed("Togli il menù"))
        }

        // No network.
        e2e.browserContext.setOffline(true)
        ask()
        assertThat(page.messages()).containsText("Nessuna connessione")
        assertEquals(0, page.messages().locator("button:has-text('Riprova')").count(), "a removal is not repeated for the person")
        assertEquals(0, page.locator("dialog[open]").count(), "the dialog is closed")
        assertThat(page.card()).containsText("menu-settembre-2027.pdf")
        assertEquals("menu-settembre-2027.pdf", e2e.storedName(w.id))
        assertAccessible(page, "card of the menu after a Togli without network (${e2e.profile.label})")
        e2e.browserContext.setOffline(false)

        // A server that fails: what the app itself answers with when it breaks (the page of the error, as a boosted form gets it).
        val errorPage = e2e.page.request().get(e2e.url("/test/errore")).text()
        page.route("**/convivenza/${w.id}/menu/togli") { route ->
            if (route.request().method() == "POST") route.fulfill(Route.FulfillOptions().setStatus(500).setContentType("text/html; charset=utf-8").setBody(errorPage)) else route.resume()
        }
        ask()
        assertThat(page.locator("h1")).hasText("Qualcosa non ha funzionato")
        assertEquals(0, page.locator("button:has-text('Riprova')").count())
        assertEquals(0, page.locator("dialog[open]").count())
        assertEquals("menu-settembre-2027.pdf", e2e.storedName(w.id))
        page.unroute("**/convivenza/${w.id}/menu/togli")
        e2e.open("/convivenza/${w.id}")
        assertThat(page.card()).containsText("menu-settembre-2027.pdf")

        // Back to normal: the same touch takes it away.
        ask()
        assertThat(page.locator("main [data-fuoco]")).hasText("Menù tolto.")
        assertThat(page.card()).containsText("Nessun menù caricato.")
        assertNull(e2e.storedName(w.id))
    }

    // ---- The limit, with the real browser and the real server (criterion 4) ---------------------------------------------------------

    @E2eTest
    fun `t12 c04 a file of 15 MB is refused under the field and one of exactly 10 MB is saved, from the same page`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.open("/convivenza/${w.id}/menu")
        val field = page.getByLabel("File del menù")

        field.setInputFiles(file("quindici.pdf", "application/pdf", MenuSamples.pdfOf(15 * 1024 * 1024)))
        e2e.touch(page.submit())
        // 15 MB is under the 20 MB of the container: the application answers with the page (422), not with the 303 of the container.
        assertThat(page.locator("#menu-file-errore")).hasText("Il file è troppo grande: al massimo 10 MB.")
        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}/menu"))
        assertNull(e2e.storedName(w.id))

        val exact = MenuSamples.pdfOf(MenuFile.MAX_BYTES)
        field.setInputFiles(file("dieci.pdf", "application/pdf", exact))
        e2e.touch(page.submit())

        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}"))
        assertThat(page.locator("main [data-fuoco]")).hasText("Menù caricato.")
        assertThat(page.card()).containsText("dieci.pdf")
        assertThat(page.card()).containsText("PDF · 10 MB · caricato il")
        assertTrue(exact.contentEquals(e2e.storedContent(w.id)), "the 10 MB, byte for byte")
    }

    // ---- The back of the browser (criterion 14, design 2) ------------------------------------------------------------------------------

    @E2eTest
    fun `t12 c14 after an upload the back of the browser gives a form that can be used again`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.open("/convivenza/${w.id}/menu")
        page.getByLabel("File del menù").setInputFiles(file("primo.pdf", "application/pdf", MenuSamples.pdf()))
        e2e.touch(page.submit())
        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}"))

        page.goBack()
        page.waitForLoadState()
        page.waitForSettledContent()

        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}/menu"))
        assertThat(page.submit()).isEnabled()
        assertEquals(false, page.evaluate("document.querySelector('main form').classList.contains('htmx-request')"), "the form does not look as if it was still sending")
        page.getByLabel("File del menù").setInputFiles(file("secondo.png", "image/png", MenuSamples.png(10)))
        e2e.touch(page.submit())
        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}"))
        assertThat(page.card()).containsText("secondo.png")
        assertEquals("secondo.png", e2e.storedName(w.id))
    }

    // ---- Every state of a gathering, in the browser (criterion 7) -----------------------------------------------------------------------

    @E2eTest
    fun `t12 c07 the card, the upload, the download, the replacement and Togli work the same in a draft, with the registrations open or closed, and over`(e2e: E2e) {
        val page = e2e.page
        for ((label, state) in listOf(
            "a draft" to Triple(GatheringStatus.draft, 30L, 32L),
            "open" to Triple(GatheringStatus.`open`, -1L, 1L),
            "closed" to Triple(GatheringStatus.closed, -1L, 1L),
            "over" to Triple(GatheringStatus.closed, -10L, -8L),
        )) {
            val w = e2e.arrivalsGathering(first = state.second, last = state.third, status = state.first)
            e2e.open("/convivenza/${w.id}")
            assertThat(page.card().locator("h2")).hasText("Menù")
            assertThat(page.card()).containsText("Nessun menù caricato.")
            assertEquals(0, page.card().locator("[role=alert], [role=status]").count(), "$label: no notice of state in the card")

            e2e.touch(page.card().linkNamed("Carica il menù"))
            val pdf = MenuSamples.pdf(50)
            page.getByLabel("File del menù").setInputFiles(file("menu-$label.pdf", "application/pdf", pdf))
            e2e.touch(page.submit())
            assertThat(page).hasURL(e2e.url("/convivenza/${w.id}"))
            assertThat(page.locator("main [data-fuoco]")).hasText("Menù caricato.")
            assertThat(page.card()).containsText("menu-$label.pdf")

            val (name, bytes) = e2e.saved(page, page.card().linkNamed("Scarica il menù"))
            assertEquals("menu-$label.pdf", name)
            assertTrue(pdf.contentEquals(bytes), label)

            e2e.touch(page.card().linkNamed("Sostituisci"))
            val png = MenuSamples.png(20)
            page.getByLabel("File del menù").setInputFiles(file("foto-$label.png", "image/png", png))
            e2e.touch(page.submit())
            assertThat(page.card()).containsText("foto-$label.png")
            assertTrue(png.contentEquals(e2e.storedContent(w.id)), label)

            e2e.touch(page.card().linkNamed("Togli il menù"))
            page.waitForSettledDialog()
            e2e.touch(page.locator("dialog[open]").buttonNamed("Togli il menù"))
            assertThat(page.locator("main [data-fuoco]")).hasText("Menù tolto.")
            assertThat(page.card()).containsText("Nessun menù caricato.")
            assertNull(e2e.storedName(w.id), label)
            assertAccessible(page, "detail of $label gathering after Togli (${e2e.profile.label})")
        }
    }

    // ---- 320 px in the pages that were not looked at yet (criterion 14) ---------------------------------------------------------------

    @E2eTest
    fun `t12 c14 at 320 px the error, the page of confirmation and the dialog, with a name of 100 letters, do not scroll sideways and have no violation of axe`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.putMenu(w.id, name = "m".repeat(100) + ".pdf")
        page.setViewportSize(320, 800)

        // The page to replace, with the error of the type under the field.
        e2e.open("/convivenza/${w.id}/menu")
        page.getByLabel("File del menù").setInputFiles(file("menu.pdf", "application/pdf", "<html></html>".toByteArray()))
        e2e.touch(page.submit())
        assertThat(page.locator("#menu-file-errore")).hasText("Questo file non è un PDF, un JPEG o un PNG.")
        assertTrue(page.noHorizontalScroll(), "the error of the type")
        assertAccessible(page, "error of the type at 320 px (${e2e.profile.label})")

        // The error of the size, the one that comes back from the container.
        page.getByLabel("File del menù").setInputFiles(file("enorme.pdf", "application/pdf", MenuSamples.pdfOf(22 * 1024 * 1024)))
        e2e.touch(page.submit())
        assertThat(page.locator("#menu-file-errore")).hasText("Il file è troppo grande: al massimo 10 MB.")
        assertTrue(page.noHorizontalScroll(), "the error of the container")
        assertAccessible(page, "error of the container at 320 px (${e2e.profile.label})")

        // The page of confirmation (what Togli is without JavaScript).
        e2e.open("/convivenza/${w.id}/menu/togli")
        assertThat(page.locator("h1")).hasText("Togliere il menù?")
        assertTrue(page.noHorizontalScroll(), "the page of confirmation")
        assertAccessible(page, "confirmation of Togli at 320 px (${e2e.profile.label})")

        // The detail with the card, and the dialog open on it.
        e2e.open("/convivenza/${w.id}")
        assertTrue(page.noHorizontalScroll(), "the card with the long name")
        e2e.touch(page.card().linkNamed("Togli il menù"))
        page.waitForSettledDialog()
        assertTrue(page.noHorizontalScroll(), "the dialog")
        assertTrue(page.locator("dialog[open]").box().width <= 320.0, "the dialog fits the screen")
        assertAccessible(page, "dialog of Togli at 320 px (${e2e.profile.label})")
    }
}
