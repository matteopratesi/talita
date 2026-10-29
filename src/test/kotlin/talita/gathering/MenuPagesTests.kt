package talita.gathering

import org.jooq.DSLContext
import org.jooq.ExecuteContext
import org.jooq.impl.DefaultExecuteListener
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.TestcontainersConfiguration
import talita.jooq.enums.GatheringStatus
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The menu of the gathering as the server renders it (TALITA-12, criteria 1-7): the card of the detail, the page to upload or replace it with its errors, the removal
 * with its confirmation, and every state of the gathering. The teachers have entered with the link, like anyone; the files are built in memory ([MenuSamples]). What
 * needs a real server (a file past the limit of the container) is in `MenuOversizeTests`.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class MenuPagesTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val menus = MenuTestKit(mockMvc, tokens, dsl, clock)

    private val thisYear: Int get() = LocalDate.now(clock).year

    // ---- The card «Menù» of the detail (criterion 1) ---------------------------------------------------------------------------

    @Test
    fun `t12 c01 the detail has the card Menu, without a file it says so and leads to the page to upload it`() {
        val w = menus.World()

        val html = w.marta.get("/convivenza/${w.id}").body()
        val card = section(html, "menu")

        assertTrue(card.contains(">Menù</h2>"), "an h2 «Menù»")
        assertTrue(text(card).contains("Nessun menù caricato."))
        assertTrue(text(card).contains("Carica il file del menù, per averlo sempre a portata di mano. Lo vedono solo i maestri."))
        assertEquals(listOf("Carica il menù" to "/convivenza/${w.id}/menu"), links(card))
        assertFalse(text(card).contains("Scarica"), "nothing to download")
        assertFalse(text(card).contains("Togli"))
        assertFalse(html.contains("dialogo-togli-menu"), "no dialog to take away what is not there")
    }

    @Test
    fun `t12 c01 with a file the card says its name, kind, size and day, with Scarica, Sostituisci and Togli`() {
        val w = menus.World()
        val longAgo = OffsetDateTime.parse("$thisYear-09-03T21:40:00+02:00")
        menus.put(w.id, name = "menu-settembre-2027.pdf", content = MenuSamples.pdfOf(2048), at = longAgo)

        val html = w.marta.get("/convivenza/${w.id}").body()
        val card = section(html, "menu")

        assertTrue(text(card).contains("menu-settembre-2027.pdf"))
        assertTrue(text(card).contains("PDF · 2 KB · caricato il 3 settembre alle 21:40"), text(card))
        assertEquals(
            listOf("Scarica" to "/convivenza/${w.id}/menu/file", "Sostituisci" to "/convivenza/${w.id}/menu", "Togli" to "/convivenza/${w.id}/menu/togli"),
            links(card),
        )
        assertTrue(card.contains("""aria-label="Scarica il menù""""))
        assertTrue(card.contains("""aria-label="Togli il menù""""))
        assertTrue(card.contains("""data-apri="dialogo-togli-menu""""), "«Togli» opens its dialog")
        assertTrue(text(card).contains("Lo vedono solo i maestri. Scaricato sul telefono, si apre anche senza rete."))
        assertFalse(text(card).contains("Nessun menù caricato."))
        assertTrue(html.contains("""<dialog id="dialogo-togli-menu""""), "the dialog is in the page")
    }

    @Test
    fun `t12 c01 the year is in the day only when it is not this year, and an image has its own kind`() {
        val w = menus.World()

        menus.put(w.id, name = "foto.jpg", type = "image/jpeg", content = MenuSamples.jpeg(1_572_864 - 22), at = OffsetDateTime.parse("${thisYear - 1}-09-03T21:40:00+02:00"))
        assertTrue(text(section(w.marta.get("/convivenza/${w.id}").body(), "menu")).contains("JPEG · 1,5 MB · caricato il 3 settembre ${thisYear - 1} alle 21:40"))

        menus.put(w.id, name = "foto.png", type = "image/png", content = MenuSamples.png(100), at = OffsetDateTime.parse("$thisYear-01-05T08:05:00+01:00"))
        assertTrue(text(section(w.marta.get("/convivenza/${w.id}").body(), "menu")).contains("PNG · 1 KB · caricato il 5 gennaio alle 8:05"))
    }

    @Test
    fun `t12 c01 the card reads the summary of the menu and never its content`() {
        val w = menus.World()
        menus.put(w.id, content = MenuSamples.pdfOf(3 * 1024 * 1024))
        val statements = mutableListOf<String>()
        val recording = dsl.configuration().derive(object : DefaultExecuteListener() {
            override fun executeStart(ctx: ExecuteContext) {
                statements += ctx.sql().orEmpty()
            }
        })

        val summary = GatheringMenus(DSL.using(recording), clock).summary(w.id)

        assertNotNull(summary)
        assertEquals(3 * 1024 * 1024, summary.sizeBytes, "the size is worked out by the database")
        assertEquals(1, statements.size)
        val sql = statements.single()
        assertTrue(sql.contains("octet_length"), sql)
        assertFalse(sql.replace(Regex("""octet_length\([^)]*\)"""), "").contains("\"content\""), "the content is not among the columns that are read: $sql")
    }

    // ---- The page to upload or replace (criterion 2) -----------------------------------------------------------------------------

    @Test
    fun `t12 c02 the page of the menu without a file is a form with a file field, its help and the notice on the data`() {
        val w = menus.World()

        val page = w.marta.get(w.path)
        val html = page.body()

        assertEquals(200, page.status())
        assertEquals("Carica il menù", heading(html))
        assertEquals("Menù · Talità", title(html))
        assertTrue(Regex("""<a href="/convivenza" aria-current="page"""").containsMatchIn(html), "Convivenza is the current entry")
        assertTrue(text(html).contains("Convivenza di settembre 2027"), "the title of the gathering under the heading")
        assertFalse(html.contains("Menù attuale"), "there is no menu to show")
        val form = Regex("""<form[^>]*enctype="multipart/form-data"[^>]*>([\s\S]*?)</form>""").find(html)
        assertNotNull(form, "a form of multipart/form-data")
        val open = Regex("""<form[^>]*enctype="multipart/form-data"[^>]*>""").find(html)!!.value
        assertTrue(open.contains("""method="post"""") && open.contains("""action="/convivenza/${w.id}/menu"""") && open.contains("""hx-boost="false"""") && open.contains("novalidate"), open)
        assertTrue(Regex("""<input type="hidden" name="_csrf" value="[^"]+">""").containsMatchIn(form.groupValues[1]), "the code against forgery is in the body of the form")
        assertTrue(Regex("""<label for="menu-file"[^>]*>File del menù</label>""").containsMatchIn(html))
        val field = Regex("""<input id="menu-file"[^>]*>""").find(html)!!.value
        assertTrue(field.contains("""name="file"""") && field.contains("""type="file"""") && field.contains("""accept="application/pdf,image/jpeg,image/png""""), field)
        assertFalse(field.contains("required"), "the errors are ours")
        assertFalse(field.contains("aria-invalid") || field.contains("autofocus"), "no error: no flag and no focus")
        assertTrue(text(html).contains("Un PDF, oppure una foto JPEG o PNG del foglio. Al massimo 10 MB. Lo vedono solo i maestri."))
        assertTrue(text(html).contains("Nel file niente nomi, allergie o altri dati dei bambini: le allergie stanno nelle segnalazioni."))
        assertTrue(text(html).contains("Con poco campo può volerci un po’. Se il caricamento si interrompe non si salva niente: scegli di nuovo il file e riprova."))
        assertTrue(Regex("""<button type="submit"[^>]*>[\s\S]*?Carica il menù[\s\S]*?Caricamento…[\s\S]*?</button>""").containsMatchIn(html), "the button and its words while it waits")
        assertEquals("/convivenza/${w.id}", links(form.groupValues[1]).single { it.first == "Annulla" }.second)
        assertTrue(Regex("""<form[^>]*data-carica""").containsMatchIn(html), "app.js shows that the file is leaving")
        assertFalse(Regex("""\bstyle=|<script|\bon[a-z]+=""").containsMatchIn(html.substringBefore("<script src=")), "no style, no inline script, no handler")
    }

    @Test
    fun `t12 c02 a PDF is saved, with its name and its bytes, and the person goes back to the detail with the confirmation`() {
        val w = menus.World()
        val pdf = MenuSamples.pdf()

        val answer = menus.upload(w.marta, w.id, menus.file("menu-settembre-2027.pdf", pdf, "application/pdf"))

        assertEquals(303, answer.status())
        assertEquals("/convivenza/${w.id}", answer.location())
        val stored = assertNotNull(menus.stored(w.id))
        assertEquals("menu-settembre-2027.pdf", stored.name)
        assertEquals("application/pdf", stored.mediaType)
        assertTrue(pdf.contentEquals(stored.content), "the bytes that were sent")
        val detail = w.marta.get("/convivenza/${w.id}")
        assertTrue(text(detail.body()).contains("Menù caricato."))
        assertTrue(detail.body().contains("""role="status""""), "announced to a screen reader")
        assertTrue(text(section(detail.body(), "menu")).contains("menu-settembre-2027.pdf"))
        assertFalse(text(w.marta.get("/convivenza/${w.id}").body()).contains("Menù caricato."), "the notice is shown once")
    }

    @Test
    fun `t12 c02 a JPEG and a PNG are saved the same way`() {
        val w = menus.World()
        val jpeg = MenuSamples.jpeg(500)
        val png = MenuSamples.png(500)

        assertEquals(303, menus.upload(w.marta, w.id, menus.file("foto-del-foglio.jpg", jpeg, "image/jpeg")).status())
        assertEquals("image/jpeg", menus.stored(w.id)!!.mediaType)
        assertTrue(jpeg.contentEquals(menus.stored(w.id)!!.content))
        assertTrue(text(section(w.marta.get("/convivenza/${w.id}").body(), "menu")).contains("JPEG ·"))

        assertEquals(303, menus.upload(w.marta, w.id, menus.file("foto-del-foglio.png", png, "image/png")).status())
        assertEquals("image/png", menus.stored(w.id)!!.mediaType)
        assertTrue(png.contentEquals(menus.stored(w.id)!!.content))
        assertEquals("foto-del-foglio.png", menus.stored(w.id)!!.name)
    }

    @Test
    fun `t12 c02 with a menu the page says replace, shows the menu that is there and says that the new file takes its place`() {
        val w = menus.World()
        menus.put(w.id, name = "menu-settembre-2027.pdf", content = MenuSamples.pdfOf(240 * 1024), at = OffsetDateTime.parse("$thisYear-09-03T21:40:00+02:00"))

        val html = w.marta.get(w.path).body()

        assertEquals("Sostituisci il menù", heading(html))
        val current = section(html, "attuale")
        assertTrue(current.contains(">Menù attuale</h2>"))
        assertTrue(text(current).contains("menu-settembre-2027.pdf") && text(current).contains("PDF · 240 KB · caricato il 3 settembre alle 21:40"))
        assertTrue(text(current).contains("Il nuovo file prende il suo posto: il menù resta uno solo."))
        assertTrue(Regex("""<button type="submit"[^>]*>[\s\S]*?Sostituisci il menù[\s\S]*?Sostituzione…[\s\S]*?</button>""").containsMatchIn(html))
        assertFalse(html.contains("Carica il menù"), "the words of the first upload are not here")
    }

    // ---- The kind comes from the first bytes (criterion 3) -----------------------------------------------------------------------

    @Test
    fun `t12 c03 a page with a script called menu_pdf, an SVG, a HEIC and a Word document are refused with 422 under the field, and the menu stays`() {
        val w = menus.World()
        val before = MenuSamples.pdfOf(5000)
        menus.put(w.id, name = "quello-di-prima.pdf", content = before)
        val script = "<html><body><script>alert(1)</script></body></html>".toByteArray()
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".toByteArray()
        val heic = byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'h'.code.toByte(), 'e'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte())
        val docx = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 20, 0, 6, 0, 8, 0)

        for ((what, file) in listOf(
            "a page with a script" to menus.file("menu.pdf", script, "application/pdf"),
            "an SVG that says it is a PNG" to menus.file("menu.png", svg, "image/png"),
            "a HEIC" to menus.file("foto.heic", heic, "image/heic"),
            "a docx that says it is a PDF" to menus.file("menu.docx", docx, "application/pdf"),
        )) {
            val answer = menus.upload(w.marta, w.id, file)

            assertEquals(422, answer.status(), what)
            assertEquals("Questo file non è un PDF, un JPEG o un PNG.", errorOf(answer.body(), "menu-file"), what)
            val stored = menus.stored(w.id)!!
            assertEquals("quello-di-prima.pdf", stored.name, "$what: the menu that was there stays")
            assertTrue(before.contentEquals(stored.content), what)
        }
    }

    @Test
    fun `t12 c03 the error is under the field, the field is invalid and has the focus, and the page is the one of replacing`() {
        val w = menus.World()
        menus.put(w.id, name = "quello-di-prima.pdf")

        val html = menus.upload(w.marta, w.id, menus.file("menu.pdf", "non un pdf".toByteArray(), "application/pdf")).body()

        val field = Regex("""<input id="menu-file"[^>]*>""").find(html)!!.value
        assertTrue(field.contains("""aria-invalid="true"""") && field.contains("autofocus"), field)
        assertTrue(field.contains("""aria-describedby="menu-file-aiuto menu-file-avviso menu-file-errore""""), field)
        assertEquals("Sostituisci il menù", heading(html))
        assertTrue(text(html).contains("quello-di-prima.pdf"), "the menu that is there is still shown")
        assertEquals(1, Regex("""menu-file-errore"[^>]*>""").findAll(html.replace("aria-describedby=\"menu-file-aiuto menu-file-avviso menu-file-errore\"", "")).count(), "one error")
        assertFalse(html.contains("Controlla i"), "with one field there is no notice above the form")
        assertTrue(html.contains("Nel file niente nomi"), "the notice on the data is still there")
    }

    @Test
    fun `t12 c03 the kind is not taken from the name or from the type that the browser says`() {
        val w = menus.World()

        // A PNG called menu.pdf, said to be a PDF, is a PNG and is kept as menu.png.
        assertEquals(303, menus.upload(w.marta, w.id, menus.file("menu.pdf", MenuSamples.png(300), "application/pdf")).status())
        assertEquals("menu.png", menus.stored(w.id)!!.name)
        assertEquals("image/png", menus.stored(w.id)!!.mediaType)

        // And the other way: a PDF called foto.png, said to be an image, is a PDF.
        assertEquals(303, menus.upload(w.marta, w.id, menus.file("foto.png", MenuSamples.pdf(), "image/png")).status())
        assertEquals("foto.pdf", menus.stored(w.id)!!.name)
        assertEquals("application/pdf", menus.stored(w.id)!!.mediaType)
        assertEquals(1, menus.count())
    }

    // ---- The limit and the empty form (criterion 4) -----------------------------------------------------------------------------

    @Test
    fun `t12 c04 a file of exactly 10 MB is saved`() {
        val w = menus.World()
        val exact = MenuSamples.pdfOf(MenuFile.MAX_BYTES)

        val answer = menus.upload(w.marta, w.id, menus.file("menu.pdf", exact, "application/pdf"))

        assertEquals(303, answer.status())
        assertEquals(MenuFile.MAX_BYTES, menus.stored(w.id)!!.content.size)
        assertTrue(exact.contentEquals(menus.stored(w.id)!!.content))
    }

    @Test
    fun `t12 c04 a file of 10 MB and a byte, or of 15 MB, is refused with 422 and the menu stays`() {
        val w = menus.World()
        val before = MenuSamples.pdfOf(1000)
        menus.put(w.id, name = "quello-di-prima.pdf", content = before)

        for (size in listOf(MenuFile.MAX_BYTES + 1, 15 * 1024 * 1024)) {
            val answer = menus.upload(w.marta, w.id, menus.file("menu.pdf", MenuSamples.pdfOf(size), "application/pdf"))

            assertEquals(422, answer.status(), "$size bytes")
            assertEquals("Il file è troppo grande: al massimo 10 MB.", errorOf(answer.body(), "menu-file"))
            assertEquals("quello-di-prima.pdf", menus.stored(w.id)!!.name, "the menu that was there stays")
            assertTrue(before.contentEquals(menus.stored(w.id)!!.content))
        }
    }

    @Test
    fun `t12 c04 a file that is too big is told so also when it is not a menu, the size comes first`() {
        val w = menus.World()

        val answer = menus.upload(w.marta, w.id, menus.file("foto.heic", ByteArray(MenuFile.MAX_BYTES + 10), "image/heic"))

        assertEquals(422, answer.status())
        assertEquals("Il file è troppo grande: al massimo 10 MB.", errorOf(answer.body(), "menu-file"))
    }

    @Test
    fun `t12 c04 the form sent with no file, or with an empty one, says to choose the file, and the menu stays`() {
        val w = menus.World()
        menus.put(w.id, name = "quello-di-prima.pdf")

        for ((what, file) in listOf("nothing chosen" to null, "an empty file" to menus.file("menu.pdf", ByteArray(0), "application/pdf"))) {
            val answer = menus.upload(w.marta, w.id, file)

            assertEquals(422, answer.status(), what)
            assertEquals("Scegli il file del menù.", errorOf(answer.body(), "menu-file"), what)
            assertEquals("quello-di-prima.pdf", menus.stored(w.id)!!.name, what)
        }
        // A form that is not a file at all (a request made by hand) is the same.
        val csrf = menus.csrf(w.marta, w.path)
        val plain = w.marta.post(w.path, mapOf("file" to "menu.pdf"), csrf)
        assertEquals(422, plain.status())
        assertEquals("Scegli il file del menù.", errorOf(plain.body(), "menu-file"))
    }

    // ---- The name (criterion 5) ------------------------------------------------------------------------------------------------

    @Test
    fun `t12 c05 the name that is kept is the last part, in NFC, with no control or direction characters, cut at 100 characters`() {
        val w = menus.World()
        val cases = listOf(
            """C:\fakepath\menu.pdf""" to "menu.pdf",
            "../../menu.pdf" to "menu.pdf",
            "me\r\nnu \"di\" settembre.pdf" to "menu \"di\" settembre.pdf",
            "menu\u202Eexe.pdf" to "menuexe.pdf",
            "a".repeat(300) + ".pdf" to "a".repeat(100) + ".pdf",
            "cafe\u0301.pdf" to "caf\u00E9.pdf",
            "   .pdf" to "menu.pdf",
        )

        for ((sent, kept) in cases) {
            assertEquals(303, menus.upload(w.marta, w.id, menus.file(sent, MenuSamples.pdf(), "application/pdf")).status(), sent)
            assertEquals(kept, menus.stored(w.id)!!.name, sent)
        }
    }

    @Test
    fun `t12 c05 the name is always shown as text, in the card and in the page`() {
        val w = menus.World()
        menus.put(w.id, name = "<img src=x onerror=alert(1)>.pdf")

        for (html in listOf(w.marta.get("/convivenza/${w.id}").body(), w.marta.get(w.path).body())) {
            assertFalse(html.contains("<img src=x"), "markup that was written stays text")
            assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;.pdf") || html.contains("&lt;img src&#61;x onerror&#61;alert(1)&gt;.pdf"), "it is escaped")
        }
    }

    // ---- Replace, take away, two at once (criterion 6) ---------------------------------------------------------------------------

    @Test
    fun `t12 c06 another file takes the place of the old one, and the menu is still one`() {
        val w = menus.World()
        val first = MenuSamples.pdf(100)
        val second = MenuSamples.jpeg(200)
        assertEquals(303, menus.upload(w.marta, w.id, menus.file("primo.pdf", first, "application/pdf")).status())
        val firstDay = menus.stored(w.id)!!.uploadedAt

        val answer = menus.upload(w.paolo, w.id, menus.file("secondo.jpg", second, "image/jpeg"))

        assertEquals(303, answer.status())
        assertEquals("/convivenza/${w.id}", answer.location())
        assertEquals(1, menus.count())
        val stored = menus.stored(w.id)!!
        assertEquals("secondo.jpg", stored.name)
        assertEquals("image/jpeg", stored.mediaType)
        assertTrue(second.contentEquals(stored.content))
        assertFalse(stored.uploadedAt.isBefore(firstDay), "the day is the one of the new file")
        assertTrue(text(w.marta.get("/convivenza/${w.id}").body()).contains("Menù caricato."), "the same words for the replacement")
    }

    @Test
    fun `t12 c06 Togli asks first, with the page that stands in for the dialog, and then the file is deleted from the database`() {
        val w = menus.World()
        menus.put(w.id, name = "menu-settembre-2027.pdf")

        val ask = w.marta.get("/convivenza/${w.id}/menu/togli")
        val page = ask.body()

        assertEquals(200, ask.status())
        assertEquals("Togliere il menù?", heading(page))
        assertTrue(text(page).contains("Il file del menù viene cancellato da Talità e i maestri non potranno più scaricarlo. Chi l’ha già scaricato ne ha una copia sul telefono. Potrai caricarne un altro quando vuoi."))
        assertFalse(page.contains("menu-settembre-2027.pdf"), "the page of confirmation reads nothing of the file")
        assertTrue(Regex("""<form method="post" action="/convivenza/${w.id}/menu/togli"""").containsMatchIn(page))
        assertTrue(Regex("""<button[^>]*>[\s\S]*?Togli il menù[\s\S]*?</button>""").containsMatchIn(page))
        assertEquals("/convivenza/${w.id}", links(page).single { it.first == "Annulla" }.second)
        assertEquals(1, menus.count(), "asking takes nothing away")

        val done = w.marta.submit("/convivenza/${w.id}/menu/togli", "/convivenza/${w.id}/menu/togli", mapOf("from" to "detail"))

        assertEquals(303, done.status())
        assertEquals("/convivenza/${w.id}", done.location())
        assertNull(menus.stored(w.id), "deleted from the database")
        val detail = w.marta.get("/convivenza/${w.id}").body()
        assertTrue(text(detail).contains("Menù tolto."))
        assertTrue(text(section(detail, "menu")).contains("Nessun menù caricato."), "the card is empty")
    }

    @Test
    fun `t12 c06 the dialog of Togli says what the guide asks and has the button of danger`() {
        val w = menus.World()
        menus.put(w.id)

        val html = w.marta.get("/convivenza/${w.id}").body()

        val dialog = Regex("""<dialog id="dialogo-togli-menu"[\s\S]*?</dialog>""").find(html)!!.value
        assertTrue(dialog.contains(">Togliere il menù?</h2>"))
        assertTrue(text(dialog).contains("Il file del menù viene cancellato da Talità e i maestri non potranno più scaricarlo. Chi l’ha già scaricato ne ha una copia sul telefono. Potrai caricarne un altro quando vuoi."))
        assertTrue(dialog.contains("""action="/convivenza/${w.id}/menu/togli""""))
        assertTrue(dialog.contains("""name="from" value="detail""""))
        assertTrue(dialog.contains("bg-danger"), "the file does not come back: the button is the one of danger")
        assertTrue(Regex(""">\s*Togli il menù\s*<""").containsMatchIn(dialog))
        assertTrue(dialog.contains("Rimozione…"))
        assertTrue(dialog.contains("autofocus"), "the focus is on «Annulla»")
    }

    @Test
    fun `t12 c06 Togli on a menu that another teacher took away a moment ago is the same confirmation and no error`() {
        val w = menus.World()
        menus.put(w.id)
        val ask = w.marta.get("/convivenza/${w.id}/menu/togli")
        assertEquals(200, ask.status())

        assertEquals(303, w.paolo.submit("/convivenza/${w.id}/menu/togli", "/convivenza/${w.id}/menu/togli", mapOf("from" to "detail")).status())
        assertNull(menus.stored(w.id))
        val again = w.marta.submit("/convivenza/${w.id}/menu/togli", "/convivenza/${w.id}/menu/togli", mapOf("from" to "detail"))

        assertEquals(303, again.status())
        assertEquals("/convivenza/${w.id}", again.location())
        assertTrue(text(w.marta.get("/convivenza/${w.id}").body()).contains("Menù tolto."))
        assertEquals(0, menus.count())
    }

    @Test
    fun `t12 c06 two teachers who upload at the same moment leave one row, with one of the two files whole, and no error`() {
        val w = menus.World()
        val a = MenuSamples.pdfOf(3 * 1024 * 1024).also { it[100] = 1 }
        val b = MenuSamples.jpeg(3 * 1024 * 1024).also { it[100] = 2 }
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val answers = listOf(w.marta to menus.file("di-marta.pdf", a, "application/pdf"), w.paolo to menus.file("di-paolo.jpg", b, "image/jpeg")).map { (browser, file) ->
                pool.submit<Int> {
                    // The page and its code first (each browser has its own), then both send together.
                    val csrf = menus.csrf(browser, w.path)
                    ready.countDown()
                    go.await()
                    menus.postFile(browser, w.id, file, csrf).status()
                }
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS))
            go.countDown()

            assertEquals(listOf(303, 303), answers.map { it.get(60, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, menus.count())
        val stored = menus.stored(w.id)!!
        assertTrue((stored.name == "di-marta.pdf" && stored.content.contentEquals(a)) || (stored.name == "di-paolo.jpg" && stored.content.contentEquals(b)), "one of the two, whole: ${stored.name}")
    }

    // ---- Every state of the gathering (criterion 7) -----------------------------------------------------------------------------

    @Test
    fun `t12 c07 the menu is uploaded, replaced, taken away and downloaded in the same way in a draft, with the registrations open or closed, and over`() {
        val today = LocalDate.now(clock)
        val states = listOf(
            "a draft" to (GatheringStatus.draft to today.plusDays(30)),
            "open" to (GatheringStatus.`open` to today.plusDays(30)),
            "closed" to (GatheringStatus.closed to today.plusDays(30)),
            "open and over" to (GatheringStatus.`open` to today.minusDays(40)),
            "closed and over" to (GatheringStatus.closed to today.minusDays(400)),
        )

        for ((what, state) in states) {
            val w = menus.World(status = state.first, start = state.second)
            val detail = w.marta.get("/convivenza/${w.id}").body()
            assertTrue(text(section(detail, "menu")).contains("Nessun menù caricato."), "$what: the card is there")
            assertFalse(text(section(detail, "menu")).contains("bozza") || text(section(detail, "menu")).contains("finita"), "$what: no notice about the state")

            assertEquals(303, menus.upload(w.marta, w.id, menus.file("menu.pdf", MenuSamples.pdf(), "application/pdf")).status(), "$what: upload")
            assertEquals(303, menus.upload(w.paolo, w.id, menus.file("nuovo.png", MenuSamples.png(), "image/png")).status(), "$what: replace")
            assertEquals("nuovo.png", menus.stored(w.id)!!.name, what)
            assertEquals(200, w.marta.get("/convivenza/${w.id}/menu/file").status(), "$what: download")
            assertEquals(303, w.marta.submit("/convivenza/${w.id}/menu/togli", "/convivenza/${w.id}/menu/togli", mapOf("from" to "detail")).status(), "$what: take away")
            assertNull(menus.stored(w.id), what)
        }
    }

    @Test
    fun `t12 c07 a new gathering is born without a menu, and the one of the year before keeps its own`() {
        val w = menus.World(status = GatheringStatus.closed, start = LocalDate.now(clock).minusDays(400), title = "Convivenza di settembre 2026")
        menus.put(w.id, name = "menu-settembre-2026.pdf")

        val next = menus.kit.gatherings.make(title = "Convivenza di settembre 2027", status = GatheringStatus.draft, custodians = listOf(w.martaId))

        assertNull(menus.stored(next), "nothing is copied from the year before")
        assertTrue(text(section(w.marta.get("/convivenza/$next").body(), "menu")).contains("Nessun menù caricato."))
        assertEquals("menu-settembre-2026.pdf", menus.stored(w.id)!!.name)
        assertTrue(text(section(w.marta.get("/convivenza/${w.id}").body(), "menu")).contains("menu-settembre-2026.pdf"))
        // The page of the new one has no «copy the menu of the year before»: the old one is downloaded and uploaded again.
        assertFalse(text(w.marta.get("/convivenza/$next/menu").body()).contains("anno prima", ignoreCase = true))
        assertFalse(text(w.marta.get("/convivenza/$next").body()).contains("Copia il menù"))
    }

    @Test
    fun `t12 c07 a gathering that does not exist has no menu page, no file and no way to take it away`() {
        val w = menus.World()
        val missing = w.id + 100_000

        assertEquals(404, w.marta.get("/convivenza/$missing/menu").status())
        assertEquals(404, w.marta.get("/convivenza/$missing/menu/file").status())
        assertEquals(404, w.marta.get("/convivenza/$missing/menu/togli").status())
        val csrf = menus.csrf(w.marta, w.path)
        assertEquals(404, menus.postFile(w.marta, missing, menus.file("menu.pdf", MenuSamples.pdf(), "application/pdf"), csrf).status())
        assertEquals(404, w.marta.post("/convivenza/$missing/menu/togli", mapOf("from" to "detail"), csrf).status())
        assertEquals(0, menus.count(), "nothing was saved")
    }

    // ---- The files that these tests are made of -------------------------------------------------------------------------------

    @Test
    fun `t12 the samples of these tests are what they say they are`() {
        assertEquals(MenuType.PDF, MenuFile.typeOf(MenuSamples.pdf()))
        assertEquals(MenuType.JPEG, MenuFile.typeOf(MenuSamples.jpeg()))
        assertEquals(MenuType.PNG, MenuFile.typeOf(MenuSamples.png()))
        assertTrue(MenuSamples.pdf().size < 400, "a PDF of a few hundred bytes")
        assertEquals(MenuFile.MAX_BYTES + 1, MenuSamples.pdfOf(MenuFile.MAX_BYTES + 1).size)
    }
}
