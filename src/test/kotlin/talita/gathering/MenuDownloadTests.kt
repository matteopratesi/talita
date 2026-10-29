package talita.gathering

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.TeacherPaths
import talita.checkin.CheckinTestKit
import talita.jooq.enums.GatheringStatus
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The download of the menu, its link in Arrivi, who can open what and what the log says (TALITA-12, criteria 8-11): the headers that make the file a thing to save and
 * never a page, the link «Menù» of Arrivi, the 403 of a parent on every address (page and form), the 404 of a gathering that does not exist and of a gathering with no
 * menu, and a log that has the id of the gathering, the kind and the size, and never the name or the content of the file.
 */
@ExtendWith(OutputCaptureExtension::class)
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class MenuDownloadTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val menus = MenuTestKit(mockMvc, tokens, dsl, clock)
    private val checkins = CheckinTestKit(mockMvc, tokens, dsl, clock)

    // ---- The download (criterion 8) ----------------------------------------------------------------------------------------------

    @Test
    fun `t12 c08 the file comes back byte for byte, with its type, as an attachment with the name in UTF-8, nosniff and the policy that allows nothing`() {
        val w = menus.World()
        val pdf = MenuSamples.pdf(300)
        menus.put(w.id, name = "menu-settembre-2027.pdf", content = pdf)

        val answer = w.marta.get("/convivenza/${w.id}/menu/file")
        val response = answer.response

        assertEquals(200, response.status)
        assertTrue(pdf.contentEquals(response.contentAsByteArray), "the exact bytes")
        assertEquals("application/pdf", response.contentType)
        assertEquals(pdf.size, response.getHeader("Content-Length")?.toInt())
        val disposition = response.getHeader("Content-Disposition")!!
        assertTrue(disposition.startsWith("attachment;"), disposition)
        assertTrue(disposition.contains("filename*=UTF-8''menu-settembre-2027.pdf"), disposition)
        assertFalse(disposition.startsWith("inline"))
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"))
        assertEquals(listOf("default-src 'none'; sandbox"), response.getHeaders("Content-Security-Policy"), "one policy, the one of the file, in place of the one of every page")
        assertTrue(response.getHeader("Cache-Control")!!.contains("no-store"), "kept by nobody, as every page behind the access: ${response.getHeader("Cache-Control")}")
    }

    @Test
    fun `t12 c08 a JPEG and a PNG have their own type, and the name with quotes, spaces and accents is safe in the header`() {
        val w = menus.World()
        val jpeg = MenuSamples.jpeg(50)
        menus.put(w.id, name = "menù \"di\" 100% settembre.jpg", type = "image/jpeg", content = jpeg)

        val response = w.marta.get("/convivenza/${w.id}/menu/file").response

        assertEquals("image/jpeg", response.contentType)
        assertTrue(jpeg.contentEquals(response.contentAsByteArray))
        val disposition = response.getHeader("Content-Disposition")!!
        assertEquals("attachment; filename=\"men_ _di_ 100_ settembre.jpg\"; filename*=UTF-8''men%C3%B9%20%22di%22%20100%25%20settembre.jpg", disposition)
        assertFalse(disposition.contains('\n') || disposition.contains('\r'))

        menus.put(w.id, name = "foto.png", type = "image/png", content = MenuSamples.png(10))
        assertEquals("image/png", w.marta.get("/convivenza/${w.id}/menu/file").response.contentType)
    }

    @Test
    fun `t12 c08 the address does not carry the name of the file, and the link of the card is that address, outside htmx`() {
        val w = menus.World()
        menus.put(w.id, name = "menu-segreto-di-quagliotti.pdf")

        val card = section(w.marta.get("/convivenza/${w.id}").body(), "menu")
        val download = Regex("""<a [^>]*href="/convivenza/${w.id}/menu/file"[^>]*>""").find(card)?.value

        assertNotNull(download)
        assertTrue(download.contains("""hx-boost="false""""), "the browser saves the file and the page stays: htmx does not take the link")
        assertFalse(download.contains("quagliotti"), download)
        assertEquals("/convivenza/${w.id}/menu/file", links(card).single { it.first == "Scarica" }.second)
    }

    @Test
    fun `t12 c08 without a menu the file is 404, and so is the page of confirmation of a gathering that does not exist`() {
        val w = menus.World()

        val none = w.marta.get("/convivenza/${w.id}/menu/file")

        assertEquals(404, none.status())
        assertNull(none.response.getHeader("Content-Disposition"), "no file, nothing to save")
        assertTrue(none.response.contentType?.startsWith("application/pdf") != true && none.response.contentAsByteArray.isEmpty(), "not a file (the page of the 404 is drawn by the container: the e2e test sees it)")
        assertEquals(404, w.marta.get("/convivenza/${w.id + 100_000}/menu/file").status())
    }

    @Test
    fun `t12 c08 the file is the one of the gathering, not another one`() {
        val w = menus.World()
        val other = menus.kit.gatherings.make(title = "Un'altra convivenza", status = GatheringStatus.closed, start = LocalDate.now(clock).minusDays(400), custodians = listOf(w.martaId))
        menus.put(w.id, name = "questa.pdf", content = MenuSamples.pdf(1))
        menus.put(other, name = "quella.pdf", content = MenuSamples.pdf(2))

        assertTrue(MenuSamples.pdf(1).contentEquals(w.marta.get("/convivenza/${w.id}/menu/file").response.contentAsByteArray))
        assertTrue(MenuSamples.pdf(2).contentEquals(w.marta.get("/convivenza/$other/menu/file").response.contentAsByteArray))
    }

    // ---- Arrivi (criterion 9) ---------------------------------------------------------------------------------------------------

    @Test
    fun `t12 c09 Arrivi has the card Menu under the prints, with a row that downloads the file outside htmx`() {
        val setup = checkins.start()
        checkins.rows.register(setup.id, checkins.account(), "Luca", "Rossi")
        menus.put(setup.id, name = "menu-settembre-2027.pdf", content = MenuSamples.pdfOf(240 * 1024))

        val html = setup.marta.get("/arrivi").body()

        val card = section(html, "menu-arrivi-titolo")
        assertTrue(card.contains(">Menù</h2>"))
        val row = links(card).single()
        assertEquals("Menù Scarica il file · PDF · 240 KB", row.first)
        assertEquals("/convivenza/${setup.id}/menu/file", row.second)
        assertTrue(Regex("""<a href="/convivenza/${setup.id}/menu/file" hx-boost="false"""").containsMatchIn(card))
        assertTrue(text(card).contains("Una volta scaricato, lo apri anche senza rete."))
        assertTrue(html.indexOf("Da stampare") < html.indexOf("menu-arrivi-titolo"), "the card is under «Da stampare»")
        assertTrue(card.contains("""d="M12 15V3"""), "the arrow gives way to the icon that downloads")
        // The same address gives the file, as in 8.
        assertEquals(200, setup.marta.get(row.second).status())
    }

    @Test
    fun `t12 c09 without a menu there is no card and no link, and Arrivi is what it was`() {
        val setup = checkins.start()
        checkins.rows.register(setup.id, checkins.account(), "Luca", "Rossi")

        val html = setup.marta.get("/arrivi").body()

        assertFalse(html.contains("menu-arrivi"))
        assertFalse(html.contains("/menu/file"))
        assertFalse(text(html).contains("Scarica il file"))
        // With a menu and then without it again: the card goes away.
        menus.put(setup.id)
        assertTrue(setup.marta.get("/arrivi").body().contains("menu-arrivi"))
        setup.marta.submit("/arrivi", "/convivenza/${setup.id}/menu/togli", mapOf("from" to "detail"))
        assertFalse(setup.marta.get("/arrivi").body().contains("menu-arrivi"))
    }

    @Test
    fun `t12 c09 Arrivi with no registration yet has the card too, and a draft, which has no list, does not change`() {
        val setup = checkins.start()
        menus.put(setup.id)

        val empty = setup.marta.get("/arrivi").body()
        assertTrue(text(empty).contains("Nessun bambino iscritto"))
        assertTrue(empty.contains("menu-arrivi"), "the menu is useful whether or not somebody has registered")

        val draft = checkins.start(status = GatheringStatus.draft)
        menus.put(draft.id)
        val draftPage = draft.marta.get("/arrivi").body()
        assertFalse(draftPage.contains("menu-arrivi"), "a draft has no arrivals")
        assertFalse(draftPage.contains("/menu/file"))
    }

    // ---- Who can open what (criterion 10) -----------------------------------------------------------------------------------------

    private fun pages(id: Long) = listOf("/convivenza/$id/menu", "/convivenza/$id/menu/file", "/convivenza/$id/menu/togli")

    @Test
    fun `t12 c10 the rule of the teachers covers every address of the menu`() {
        for (path in listOf("/convivenza/1/menu", "/convivenza/1/menu/file", "/convivenza/1/menu/togli", "/convivenza/9/menu/x")) {
            assertTrue(TeacherPaths.matches(path), path)
        }
    }

    @Test
    fun `t12 c10 a parent, also one with children registered, is told 403 on every address of the menu, with a page and with a form, and nothing changes`() {
        val setup = checkins.start()
        val parentEmail = Addresses.unique("genitore-a")
        val parent = checkins.kit.parent(parentEmail)
        checkins.rows.register(setup.id, checkins.kit.accountId(parentEmail), "Luca", "Rossi")
        val before = MenuSamples.pdf(77)
        menus.put(setup.id, name = "menu.pdf", content = before)

        for (path in pages(setup.id)) {
            assertEquals(403, parent.get(path).response.status, "GET $path")
        }
        // The form with a good file, and the removal and its confirmation: the code of the form is the parent's own, and still the answer is 403.
        val code = menus.csrf(parent, "/profilo")
        assertEquals(403, menus.postFile(parent, setup.id, menus.file("nuovo.pdf", MenuSamples.pdf(5), "application/pdf"), code).response.status, "POST /convivenza/${setup.id}/menu")
        assertEquals(403, parent.post("/convivenza/${setup.id}/menu/togli", mapOf("from" to "detail"), code).response.status, "POST togli")
        assertEquals(403, mockMvc.multipart("/convivenza/${setup.id}/menu") { parent.cookie?.let { session = it }; with(csrf()); file(menus.file("nuovo.pdf", MenuSamples.pdf(5), "application/pdf")) }.andReturn().response.status)
        assertEquals(403, mockMvc.post("/convivenza/${setup.id}/menu/togli") { parent.cookie?.let { session = it }; with(csrf()); header("HX-Request", "true") }.andReturn().response.status)

        val stored = menus.stored(setup.id)!!
        assertEquals("menu.pdf", stored.name, "nothing changed")
        assertTrue(before.contentEquals(stored.content))
    }

    @Test
    fun `t12 c10 a teacher opens every address of the menu, with a page and with a form`() {
        val w = menus.World()
        menus.put(w.id)

        for (path in pages(w.id)) {
            assertEquals(200, w.marta.get(path).status(), "GET $path")
        }
        assertEquals(303, menus.upload(w.paolo, w.id, menus.file("nuovo.pdf", MenuSamples.pdf(5), "application/pdf")).status(), "POST /convivenza/${w.id}/menu")
        assertEquals(303, w.marta.submit("/convivenza/${w.id}/menu/togli", "/convivenza/${w.id}/menu/togli", mapOf("from" to "detail")).status(), "POST togli")
    }

    @Test
    fun `t12 c10 whoever has no access is sent to the access page, with a page and with a form`() {
        val w = menus.World()
        menus.put(w.id)
        val before = menus.stored(w.id)!!

        for (path in pages(w.id)) {
            val answer = mockMvc.get(path).andReturn().response
            assertEquals(302, answer.status, "GET $path")
            assertEquals("/accedi", answer.getHeader("Location"), "GET $path")
        }
        for (path in listOf("/convivenza/${w.id}/menu", "/convivenza/${w.id}/menu/togli")) {
            val answer = mockMvc.post(path).andReturn().response
            assertEquals(302, answer.status, "POST $path")
            assertEquals("/accedi", answer.getHeader("Location"), "POST $path")
            assertEquals("/accedi", mockMvc.post(path) { header("HX-Request", "true") }.andReturn().response.getHeader("HX-Redirect"), "POST (htmx) $path")
        }
        val upload = mockMvc.multipart("/convivenza/${w.id}/menu") { file(menus.file("nuovo.pdf", MenuSamples.pdf(5), "application/pdf")) }.andReturn().response
        assertEquals(302, upload.status)
        assertEquals("/accedi", upload.getHeader("Location"))
        assertTrue(before.content.contentEquals(menus.stored(w.id)!!.content), "nothing changed")
    }

    @Test
    fun `t12 c10 a teacher is told 404 for a gathering that does not exist, on every address`() {
        val w = menus.World()
        val missing = w.id + 100_000
        val code = menus.csrf(w.marta, w.path)

        for (path in pages(missing)) {
            assertEquals(404, w.marta.get(path).status(), "GET $path")
        }
        assertEquals(404, menus.postFile(w.marta, missing, menus.file("menu.pdf", MenuSamples.pdf(), "application/pdf"), code).status())
        assertEquals(404, w.marta.post("/convivenza/$missing/menu/togli", mapOf("from" to "detail"), code).status())
        assertEquals(0, menus.count())
    }

    @Test
    fun `t12 c10 the information page, its preview and the pages of the parent have no menu and no link to it`() {
        val setup = checkins.start()
        val parentEmail = Addresses.unique("genitore-b")
        val parent = checkins.kit.parent(parentEmail)
        checkins.rows.register(setup.id, checkins.kit.accountId(parentEmail), "Luca", "Rossi")
        menus.put(setup.id, name = "menu-settembre-2027.pdf")
        val code = menus.kit.codeOf(setup.id)

        val public = mockMvc.get("/info/$code").andReturn().response.contentAsString
        val preview = setup.marta.get("/convivenza/${setup.id}/anteprima").body()
        val parentPages = listOf("/", "/iscrizioni", "/profilo", "/informativa").map { it to parent.get(it).body() }

        for ((what, html) in listOf("the information page" to public, "its preview" to preview) + parentPages) {
            assertFalse(Regex("""href="[^"]*/menu\b""").containsMatchIn(html), "$what: a link to the menu")
            assertFalse(text(html).contains("Menù"), "$what: the word Menù")
            assertFalse(html.contains("menu-settembre-2027"), "$what: the name of the file")
        }
        assertTrue(public.contains("Convivenza di settembre 2027"), "it is the real page")
    }

    // ---- The log (criterion 11) ---------------------------------------------------------------------------------------------------

    @Test
    fun `t12 c11 the log has the gathering, the kind and the size of what was uploaded, refused, downloaded and taken away, and never the name or the content`(output: CapturedOutput) {
        val w = menus.World()
        val secret = "Zuppa-Quagliotti-Segreta"
        val marker = "PIATTO-SEGRETO-DI-QUAGLIOTTI"
        val pdf = (MenuSamples.pdf().toString(Charsets.ISO_8859_1) + "\n% $marker\n").toByteArray(Charsets.ISO_8859_1)

        menus.upload(w.marta, w.id, menus.file("$secret.pdf", pdf, "application/pdf"))
        menus.upload(w.marta, w.id, menus.file("$secret-finto.pdf", "<html>$marker</html>".toByteArray(), "application/pdf"))
        menus.upload(w.marta, w.id, menus.file("$secret-grande.pdf", ByteArray(MenuFile.MAX_BYTES + 5), "application/pdf"))
        menus.upload(w.marta, w.id, null)
        w.marta.get("/convivenza/${w.id}/menu/file")
        w.marta.get("/convivenza/${w.id}")
        w.marta.get(w.path)
        w.marta.submit("/convivenza/${w.id}/menu/togli", "/convivenza/${w.id}/menu/togli", mapOf("from" to "detail"))
        w.marta.submit("/convivenza/${w.id}/menu/togli", "/convivenza/${w.id}/menu/togli", mapOf("from" to "detail"))
        w.marta.get("/convivenza/${w.id}/menu/file")

        val log = output.all
        assertTrue(log.contains("Menu saved: gathering ${w.id}, application/pdf, ${pdf.size} bytes."), "saved")
        assertTrue(log.contains("Menu refused: gathering ${w.id}, not a PDF, a JPEG or a PNG, ${"<html>$marker</html>".length} bytes."), "refused: not a menu")
        assertTrue(log.contains("Menu refused: gathering ${w.id}, too big, ${MenuFile.MAX_BYTES + 5} bytes."), "refused: too big")
        assertTrue(log.contains("Menu refused: gathering ${w.id}, no file, 0 bytes."), "refused: no file")
        assertTrue(log.contains("Menu downloaded: gathering ${w.id}, application/pdf, ${pdf.size} bytes."), "downloaded")
        assertTrue(log.contains("Menu removed: gathering ${w.id}, application/pdf, ${pdf.size} bytes."), "removed")
        assertEquals(1, Regex("Menu removed: ").findAll(log).count(), "the second tap took nothing away and says nothing")
        for (secretWord in listOf(secret, "Quagliotti", marker, "PIATTO", ".pdf", "menu-segreto")) {
            assertFalse(log.contains(secretWord), "the log has '$secretWord': ${log.lines().filter { it.contains(secretWord) }.take(3)}")
        }
    }
}
