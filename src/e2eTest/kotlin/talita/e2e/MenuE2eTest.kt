package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.FilePayload
import talita.gathering.MenuFile
import talita.gathering.MenuSamples
import talita.jooq.tables.references.GATHERING_MENU
import java.nio.file.Files
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The menu of the gathering in a real browser, on a phone and on a desktop, with axe (TALITA-12, criteria 1, 2, 4, 6, 8, 9, 10, 14): the card of the detail without a file and with
 * one, the upload with a file chosen as a person chooses it (`FilePayload`, files made in memory), the kinds and the errors under the field, the replacement, the dialog
 * of «Togli» and its page without JavaScript, the download (the name and the bytes of what the browser saves, and the page that stays as it is), the file that is too
 * big for the application and the one that is too big for the container, the button while the file leaves, the card of Arrivi and what a parent is told.
 */
@E2eApplication
class MenuE2eTest {

    private fun file(name: String, mime: String, bytes: ByteArray): FilePayload = FilePayload(name, mime, bytes)

    private fun Locator.linkNamed(name: String): Locator = getByRole(AriaRole.LINK, Locator.GetByRoleOptions().setName(name))

    private fun Locator.buttonNamed(name: String): Locator = getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName(name))

    private fun Page.waitForSettledDialog() =
        waitUntil("document.querySelector('dialog[open]') && document.querySelector('dialog[open]').getAnimations().every(a => a.playState === 'finished')")

    private fun Page.card(): Locator = locator("section[aria-labelledby=menu]")

    /** The menu written straight in the table, as one that was uploaded some time ago. */
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

    /** Taps [target] and waits for what the browser saves: the name it proposes and the bytes of the file. */
    private fun E2e.saved(on: Page, target: Locator): Pair<String, ByteArray> {
        val download = on.waitForDownload { if (profile.isPhone) target.tap() else target.click() }
        return download.suggestedFilename() to Files.readAllBytes(download.path())
    }

    // ---- The card and the upload (criteria 1, 2, 14) ---------------------------------------------------------------------------

    @E2eTest
    fun `t12 c01 c02 c14 the card says there is no menu, the page uploads a PDF, and the card shows it with its name, kind and size`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.open("/convivenza/${w.id}")

        assertThat(page.card().locator("h2")).hasText("Menù")
        assertThat(page.card()).containsText("Nessun menù caricato.")
        assertThat(page.card()).containsText("Carica il file del menù, per averlo sempre a portata di mano. Lo vedono solo i maestri.")
        assertTrue(page.noHorizontalScroll())
        if (e2e.profile.isPhone) assertTrue(page.card().linkNamed("Carica il menù").box().width >= page.card().box().width - 50, "the button is as wide as the card on the phone")
        assertAccessible(page, "card of the menu, empty (${e2e.profile.label})")

        e2e.touch(page.card().linkNamed("Carica il menù"))

        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}/menu"))
        assertThat(page).hasTitle("Menù · Talità")
        assertThat(page.locator("h1")).hasText("Carica il menù")
        assertThat(page.locator("nav a[aria-current=page]")).hasText("Convivenza")
        assertThat(page.locator("main")).containsText("Convivenza di settembre 2027")
        assertThat(page.locator("main")).containsText("Nel file niente nomi, allergie o altri dati dei bambini: le allergie stanno nelle segnalazioni.")
        assertThat(page.getByLabel("File del menù")).hasAttribute("accept", "application/pdf,image/jpeg,image/png")
        assertThat(page.locator("main")).containsText("Un PDF, oppure una foto JPEG o PNG del foglio. Al massimo 10 MB. Lo vedono solo i maestri.")
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px")
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "page of the menu, upload (${e2e.profile.label})")

        val pdf = MenuSamples.pdf()
        page.getByLabel("File del menù").setInputFiles(file("menu-settembre-2027.pdf", "application/pdf", pdf))
        e2e.touch(page.buttonNamed("Carica il menù"))

        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}"))
        assertThat(page.locator("main [data-fuoco]")).hasText("Menù caricato.")
        assertThat(page.locator("main [data-fuoco]")).hasAttribute("role", "status")
        assertEquals(true, page.evaluate("document.querySelector('main h1').parentElement.nextElementSibling.querySelector('[data-fuoco]') !== null"), "the notice is the first thing after the title")
        assertThat(page.card()).containsText("menu-settembre-2027.pdf")
        assertThat(page.card()).containsText("PDF · 1 KB · caricato il")
        assertThat(page.card().linkNamed("Scarica il menù")).isVisible()
        assertThat(page.card().linkNamed("Sostituisci")).isVisible()
        assertThat(page.card().linkNamed("Togli il menù")).isVisible()
        assertEquals("menu-settembre-2027.pdf", e2e.storedName(w.id))
        assertTrue(pdf.contentEquals(e2e.storedContent(w.id)), "the bytes that the browser chose")
        assertTrue(page.noHorizontalScroll())
        // Every target of the card is at least 44 px tall («Scarica» is 48), and the three are reachable on the phone.
        for (name in listOf("Scarica il menù", "Sostituisci", "Togli il menù")) assertTrue(page.card().linkNamed(name).box().height >= 43.9, "$name is at least 44 px tall")
        assertAccessible(page, "card of the menu, with a file (${e2e.profile.label})")
    }

    @E2eTest
    fun `t12 c02 a JPEG and a PNG are uploaded the same way, and the card says their kind`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()

        for ((index, upload) in listOf(
            Quadruple("foto-del-foglio.jpg", "image/jpeg", MenuSamples.jpeg(100), "JPEG"),
            Quadruple("foto-del-foglio.png", "image/png", MenuSamples.png(100), "PNG"),
        ).withIndex()) {
            e2e.open("/convivenza/${w.id}/menu")
            page.getByLabel("File del menù").setInputFiles(file(upload.a, upload.b, upload.c))
            e2e.touch(page.buttonNamed(if (index == 0) "Carica il menù" else "Sostituisci il menù"))

            assertThat(page).hasURL(e2e.url("/convivenza/${w.id}"))
            assertThat(page.card()).containsText(upload.a)
            assertThat(page.card()).containsText("${upload.d} · 1 KB")
            assertTrue(upload.c.contentEquals(e2e.storedContent(w.id)))
        }
    }

    /** A file to upload: its name, the type that the browser says, its bytes and the kind that the card must say. */
    private class Quadruple(val a: String, val b: String, val c: ByteArray, val d: String)

    // ---- The kind and the limit, with the error under the field (criteria 3, 4) -------------------------------------------------------

    @E2eTest
    fun `t12 c03 c04 a page with a script called menu_pdf, a file that is too big and no file are refused under the field, with the focus there, and the menu that was there stays`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.putMenu(w.id, name = "quello-di-prima.pdf")
        val field = page.getByLabel("File del menù")

        // Not one of the three, whatever the name says.
        e2e.open("/convivenza/${w.id}/menu")
        field.setInputFiles(file("menu.pdf", "application/pdf", "<html><script>alert(1)</script></html>".toByteArray()))
        e2e.touch(page.buttonNamed("Sostituisci il menù"))
        assertThat(page.locator("#menu-file-errore")).hasText("Questo file non è un PDF, un JPEG o un PNG.")
        assertThat(field).hasAttribute("aria-invalid", "true")
        page.focusIs("document.activeElement.id === 'menu-file'")
        assertThat(page.locator("h1")).hasText("Sostituisci il menù")
        assertThat(page.locator("main")).containsText("quello-di-prima.pdf")
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "menu page, wrong kind (${e2e.profile.label})")
        assertEquals("quello-di-prima.pdf", e2e.storedName(w.id))

        // Too big for the application: 10 MB and a byte.
        field.setInputFiles(file("grande.pdf", "application/pdf", MenuSamples.pdfOf(MenuFile.MAX_BYTES + 1)))
        e2e.touch(page.buttonNamed("Sostituisci il menù"))
        assertThat(page.locator("#menu-file-errore")).hasText("Il file è troppo grande: al massimo 10 MB.")
        page.focusIs("document.activeElement.id === 'menu-file'")
        assertAccessible(page, "menu page, too big (${e2e.profile.label})")
        assertEquals("quello-di-prima.pdf", e2e.storedName(w.id))

        // Nothing chosen.
        e2e.touch(page.buttonNamed("Sostituisci il menù"))
        assertThat(page.locator("#menu-file-errore")).hasText("Scegli il file del menù.")
        page.focusIs("document.activeElement.id === 'menu-file'")
        assertEquals("quello-di-prima.pdf", e2e.storedName(w.id))

        // And a good file after all of this goes in: the person did not have to start again from the detail.
        field.setInputFiles(file("giusto.pdf", "application/pdf", MenuSamples.pdf()))
        e2e.touch(page.buttonNamed("Sostituisci il menù"))
        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}"))
        assertThat(page.card()).containsText("giusto.pdf")
    }

    @E2eTest
    fun `t12 c04 a file past the limit of the container, 25 MB, comes back to the page of the menu with the same error under the field, never the home`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.putMenu(w.id, name = "quello-di-prima.pdf")
        e2e.open("/convivenza/${w.id}/menu")

        page.getByLabel("File del menù").setInputFiles(file("enorme.pdf", "application/pdf", MenuSamples.pdfOf(25 * 1024 * 1024)))
        e2e.touch(page.buttonNamed("Sostituisci il menù"))

        // The server answered (a 303), the browser is on the page of the menu (not the home, not the 403, not its own error), and the error is the one of every file that is too big.
        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}/menu"))
        assertThat(page.locator("h1")).hasText("Sostituisci il menù")
        assertThat(page.locator("#menu-file-errore")).hasText("Il file è troppo grande: al massimo 10 MB.")
        assertThat(page.getByLabel("File del menù")).hasAttribute("aria-invalid", "true")
        page.focusIs("document.activeElement.id === 'menu-file'")
        assertThat(page.locator("main")).containsText("quello-di-prima.pdf")
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "menu page, past the limit of the container (${e2e.profile.label})")
        assertEquals("quello-di-prima.pdf", e2e.storedName(w.id))
        // It is shown once: opening the page again gives the page.
        e2e.open("/convivenza/${w.id}/menu")
        assertEquals(0, page.locator("#menu-file-errore").count())
    }

    // ---- Replace and take away (criterion 6) -------------------------------------------------------------------------------------

    @E2eTest
    fun `t12 c06 Sostituisci shows the menu that is there and puts the new file in its place`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.putMenu(w.id, name = "menu-settembre-2027.pdf")
        e2e.open("/convivenza/${w.id}")

        e2e.touch(page.card().linkNamed("Sostituisci"))

        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}/menu"))
        assertThat(page.locator("h1")).hasText("Sostituisci il menù")
        assertThat(page.locator("section[aria-labelledby=attuale] h2")).hasText("Menù attuale")
        assertThat(page.locator("section[aria-labelledby=attuale]")).containsText("menu-settembre-2027.pdf")
        assertThat(page.locator("section[aria-labelledby=attuale]")).containsText("Il nuovo file prende il suo posto: il menù resta uno solo.")
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "menu page, replace (${e2e.profile.label})")
        // «Annulla» goes back to the detail and changes nothing.
        e2e.touch(page.locator("main").linkNamed("Annulla"))
        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}"))
        e2e.touch(page.card().linkNamed("Sostituisci"))

        // A PNG called menu.pdf is a PNG.
        val png = MenuSamples.png(50)
        page.getByLabel("File del menù").setInputFiles(file("menu.pdf", "application/pdf", png))
        e2e.touch(page.buttonNamed("Sostituisci il menù"))

        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}"))
        assertThat(page.locator("main [data-fuoco]")).hasText("Menù caricato.")
        assertThat(page.card()).containsText("menu.png")
        assertThat(page.card()).containsText("PNG · 1 KB")
        assertThat(page.card()).not().containsText("menu-settembre-2027.pdf")
        assertTrue(png.contentEquals(e2e.storedContent(w.id)))
    }

    @E2eTest
    fun `t12 c06 Togli asks in a dialog, Annulla leaves the menu, and the button takes it away for good`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.putMenu(w.id)
        e2e.open("/convivenza/${w.id}")

        e2e.touch(page.card().linkNamed("Togli il menù"))
        page.waitForSettledDialog()

        val dialog = page.locator("dialog[open]")
        assertThat(dialog.locator("h2")).hasText("Togliere il menù?")
        assertThat(dialog).containsText("Il file del menù viene cancellato da Talità e i maestri non potranno più scaricarlo. Chi l’ha già scaricato ne ha una copia sul telefono. Potrai caricarne un altro quando vuoi.")
        page.focusIs("document.activeElement.textContent.trim() === 'Annulla'")
        assertTrue(dialog.buttonNamed("Togli il menù").box().height >= 47.9, "the button is 48 px tall")
        assertAccessible(page, "dialog Togli il menù (${e2e.profile.label})")

        e2e.touch(dialog.buttonNamed("Annulla"))
        assertEquals(0, page.locator("dialog[open]").count())
        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}"))
        assertThat(page.card()).containsText("menu-settembre-2027.pdf")
        assertEquals("menu-settembre-2027.pdf", e2e.storedName(w.id), "Annulla takes nothing away")

        e2e.touch(page.card().linkNamed("Togli il menù"))
        page.waitForSettledDialog()
        e2e.touch(page.locator("dialog[open]").buttonNamed("Togli il menù"))

        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}"))
        assertThat(page.locator("main [data-fuoco]")).hasText("Menù tolto.")
        assertThat(page.card()).containsText("Nessun menù caricato.")
        assertNull(e2e.storedName(w.id), "deleted from the database")
        assertAccessible(page, "card of the menu, after Togli (${e2e.profile.label})")
    }

    @E2eTest
    fun `t12 c06 without JavaScript Togli is a page of confirmation with the same words, and the whole flow works`(e2e: E2e) {
        val w = e2e.arrivalsGathering()
        e2e.putMenu(w.id)
        val plain = e2e.newPage { setJavaScriptEnabled(false) }
        plain.navigate(e2e.url("/convivenza/${w.id}"))
        plain.waitForSettledContent()

        plain.card().linkNamed("Togli il menù").click()
        plain.waitForSettledContent()

        assertThat(plain).hasURL(e2e.url("/convivenza/${w.id}/menu/togli"))
        assertThat(plain.locator("h1")).hasText("Togliere il menù?")
        assertThat(plain.locator("main")).containsText("Il file del menù viene cancellato da Talità e i maestri non potranno più scaricarlo.")
        assertEquals("menu-settembre-2027.pdf", e2e.storedName(w.id), "asking takes nothing away")
        plain.locator("main").linkNamed("Annulla").click()
        plain.waitForSettledContent()
        assertThat(plain).hasURL(e2e.url("/convivenza/${w.id}"))

        // Upload without JavaScript: the form is a form, and the file leaves.
        plain.card().linkNamed("Sostituisci").click()
        plain.waitForSettledContent()
        plain.getByLabel("File del menù").setInputFiles(file("senza-script.pdf", "application/pdf", MenuSamples.pdf()))
        plain.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Sostituisci il menù")).click()
        plain.waitForSettledContent()
        assertThat(plain).hasURL(e2e.url("/convivenza/${w.id}"))
        assertThat(plain.locator("main [data-fuoco]")).hasText("Menù caricato.")
        assertEquals("senza-script.pdf", e2e.storedName(w.id))

        plain.card().linkNamed("Togli il menù").click()
        plain.waitForSettledContent()
        plain.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Togli il menù")).click()
        plain.waitForSettledContent()
        assertThat(plain).hasURL(e2e.url("/convivenza/${w.id}"))
        assertThat(plain.locator("main [data-fuoco]")).hasText("Menù tolto.")
        assertNull(e2e.storedName(w.id))
    }

    // ---- The download (criterion 8) ---------------------------------------------------------------------------------------------

    @E2eTest
    fun `t12 c08 c14 Scarica saves the file with its name and its exact bytes, and the page stays as it is`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val pdf = MenuSamples.pdf(777)
        e2e.putMenu(w.id, name = "menu-settembre-2027.pdf", content = pdf)
        e2e.open("/convivenza/${w.id}")
        val title = page.locator("h1").innerText()

        val (name, bytes) = e2e.saved(page, page.card().linkNamed("Scarica il menù"))

        assertEquals("menu-settembre-2027.pdf", name)
        assertTrue(pdf.contentEquals(bytes), "the bytes of the file")
        // Nothing of the page changed: the same address, the same title, the card still there, no message of error.
        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}"))
        assertEquals(title, page.locator("h1").innerText())
        assertThat(page.card()).containsText("menu-settembre-2027.pdf")
        assertEquals(0, page.locator("#messaggi [role=alert]").count())

        // An image is saved as the image it is, with its own name.
        val png = MenuSamples.png(300)
        e2e.putMenu(w.id, name = "foto del foglio.png", type = "image/png", content = png)
        e2e.open("/convivenza/${w.id}")
        val (imageName, imageBytes) = e2e.saved(page, page.card().linkNamed("Scarica il menù"))
        assertEquals("foto del foglio.png", imageName)
        assertTrue(png.contentEquals(imageBytes))
    }

    @E2eTest
    fun `t12 c08 without JavaScript the link saves the file too, and the page stays`(e2e: E2e) {
        val w = e2e.arrivalsGathering()
        val pdf = MenuSamples.pdf(40)
        e2e.putMenu(w.id, name = "menu-settembre-2027.pdf", content = pdf)
        val plain = e2e.newPage { setJavaScriptEnabled(false) }
        plain.navigate(e2e.url("/convivenza/${w.id}"))
        plain.waitForSettledContent()

        val (name, bytes) = e2e.saved(plain, plain.card().linkNamed("Scarica il menù"))

        assertEquals("menu-settembre-2027.pdf", name)
        assertTrue(pdf.contentEquals(bytes))
        assertThat(plain).hasURL(e2e.url("/convivenza/${w.id}"))
        assertThat(plain.card()).containsText("menu-settembre-2027.pdf")
    }

    @E2eTest
    fun `t12 c08 with no menu the address of the file is the page not found, and nothing is saved`(e2e: E2e) {
        val w = e2e.arrivalsGathering()

        val response = e2e.open("/convivenza/${w.id}/menu/file")

        assertEquals(404, response.status())
        assertThat(e2e.page.locator("h1")).hasText("Pagina non trovata")
    }

    // ---- Arrivi (criterion 9) ---------------------------------------------------------------------------------------------------

    @E2eTest
    fun `t12 c09 Arrivi has the row Menu that saves the file, and nothing without a menu`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.rows().register(w.id, e2e.someParent(), "Anna", "Rossi", java.time.LocalDate.of(2019, 1, 9))
        e2e.open("/arrivi")
        assertEquals(0, page.locator("#menu-arrivi").count(), "no menu: no card")

        val pdf = MenuSamples.pdf(123)
        e2e.putMenu(w.id, name = "menu-settembre-2027.pdf", content = pdf)
        e2e.open("/arrivi")
        val card = page.locator("#menu-arrivi")

        assertThat(card.locator("h2")).hasText("Menù")
        assertThat(card).containsText("Scarica il file · PDF · 1 KB")
        assertThat(card).containsText("Una volta scaricato, lo apri anche senza rete.")
        assertTrue(card.linkNamed("Menù Scarica il file · PDF · 1 KB").box().height >= 63.9, "the row is at least 64 px tall")
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px")
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "Arrivi with the card of the menu (${e2e.profile.label})")

        val (name, bytes) = e2e.saved(page, card.locator("a"))

        assertEquals("menu-settembre-2027.pdf", name)
        assertTrue(pdf.contentEquals(bytes))
        assertThat(page).hasURL(e2e.url("/arrivi"))
        assertThat(page.locator("h1")).hasText("Arrivi")
        assertThat(card).isVisible()
    }

    // ---- The long name, and the button while the file leaves ---------------------------------------------------------------------

    @E2eTest
    fun `t12 c05 c14 a name of 100 letters with no spaces wraps in the card, in the page of the menu and in Arrivi, at 320 px`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.rows().register(w.id, e2e.someParent(), "Anna", "Rossi", java.time.LocalDate.of(2019, 1, 9))
        e2e.putMenu(w.id, name = "m".repeat(100) + ".pdf")
        page.setViewportSize(320, 800)

        for (path in listOf("/convivenza/${w.id}", "/convivenza/${w.id}/menu", "/arrivi")) {
            e2e.open(path)
            assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px in $path")
        }
    }

    // ---- Who can open it (criterion 10) ------------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `t12 c10 a parent is told this is not a page for them on the page of the menu and on the file, and nothing is saved`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering(meTeacher = false)
        e2e.putMenu(w.id)

        for (path in listOf("/convivenza/${w.id}/menu", "/convivenza/${w.id}/menu/file", "/convivenza/${w.id}/menu/togli")) {
            val response = e2e.open(path)

            assertEquals(403, response.status(), path)
            assertThat(page.locator("h1")).hasText("Non puoi vedere questa pagina")
            assertEquals(0, page.getByText("menu-settembre-2027").count(), "nothing of the file in $path")
        }
        assertEquals("menu-settembre-2027.pdf", e2e.storedName(w.id))
    }
}
