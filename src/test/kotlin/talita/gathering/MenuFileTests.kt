package talita.gathering

import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the menu file is made of, with no database and no server (TALITA-12, criteria 3, 5 and 12): the kind from the first bytes, the name that is kept,
 * the limit of size, how the size and the date are said. The files of the tests are built here, in memory: the build refuses a PDF or an image in the repository.
 */
class MenuFileTests {

    // ---- The kind comes from the first bytes (criterion 3) -----------------------------------------------------------------

    @Test
    fun `t12 c03 a PDF, a JPEG and a PNG are recognised by their first bytes`() {
        assertEquals(MenuType.PDF, MenuFile.typeOf(MenuSamples.pdf()))
        assertEquals(MenuType.JPEG, MenuFile.typeOf(MenuSamples.jpeg()))
        assertEquals(MenuType.PNG, MenuFile.typeOf(MenuSamples.png()))
        // Only the beginning counts: the rest of the file is not looked at.
        assertEquals(MenuType.PDF, MenuFile.typeOf("%PDF-1.4 and then anything at all".toByteArray()))
        assertEquals(MenuType.JPEG, MenuFile.typeOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals(MenuType.PNG, MenuFile.typeOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
    }

    @Test
    fun `t12 c03 a page with a script, an SVG, a HEIC and a document of Word are not one of the three, whatever they are called`() {
        val script = "<html><body><script>alert(1)</script></body></html>".toByteArray()
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".toByteArray()
        // The box `ftyp` that starts a HEIC, and the signature of a file of Word (a zip, `PK`) or of the old Word (`D0 CF 11 E0`).
        val heic = byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'h'.code.toByte(), 'e'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte())
        val docx = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 20, 0, 6, 0)
        val doc = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

        for ((what, bytes) in listOf("a page with a script" to script, "an SVG" to svg, "a HEIC" to heic, "a docx" to docx, "a doc" to doc)) {
            assertNull(MenuFile.typeOf(bytes), what)
        }
    }

    @Test
    fun `t12 c03 a file that is too short, empty, or that nearly starts like one is not recognised`() {
        assertNull(MenuFile.typeOf(ByteArray(0)))
        assertNull(MenuFile.typeOf("%PDF".toByteArray()), "the dash is part of the signature")
        assertNull(MenuFile.typeOf(" %PDF-1.4".toByteArray()), "a space before: not at the start")
        assertNull(MenuFile.typeOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertNull(MenuFile.typeOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A)), "seven bytes of the signature of a PNG are not a PNG")
        assertNull(MenuFile.typeOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0B)))
    }

    // ---- The name that is kept (criterion 5) -------------------------------------------------------------------------------

    @Test
    fun `t12 c05 only the last part of the name is kept, whatever separator the browser used`() {
        assertEquals("menu.pdf", MenuFile.cleanName("""C:\fakepath\menu.pdf""", MenuType.PDF))
        assertEquals("menu.pdf", MenuFile.cleanName("../../menu.pdf", MenuType.PDF))
        assertEquals("menu.pdf", MenuFile.cleanName("""..\..\cartella/menu.pdf""", MenuType.PDF))
        assertEquals("menu-settembre-2027.pdf", MenuFile.cleanName("menu-settembre-2027.pdf", MenuType.PDF))
        // Nothing after the last separator: nothing of the name is left.
        assertEquals("menu.pdf", MenuFile.cleanName("cartella/", MenuType.PDF))
        assertEquals("menu.pdf", MenuFile.cleanName("../..", MenuType.PDF))
    }

    @Test
    fun `t12 c05 the name ends with the extension of the kind that was recognised, not the one it was called`() {
        assertEquals("menu.png", MenuFile.cleanName("menu.pdf", MenuType.PNG), "a PNG called menu.pdf is a PNG")
        assertEquals("foto.jpg", MenuFile.cleanName("foto.JPEG", MenuType.JPEG))
        assertEquals("foto.jpg", MenuFile.cleanName("foto.jpg", MenuType.JPEG))
        assertEquals("menu.pdf", MenuFile.cleanName("menu", MenuType.PDF), "no extension: the extension of the kind is added")
        assertEquals("menu.pdf", MenuFile.cleanName("menu.PDF", MenuType.PDF))
        assertEquals("menu.v2.pdf", MenuFile.cleanName("menu.v2", MenuType.PDF), "an extension that is not one of the three is part of the name")
        assertEquals("menu.docx.pdf", MenuFile.cleanName("menu.docx", MenuType.PDF))
        assertEquals("menu.settembre.pdf", MenuFile.cleanName("menu.settembre.pdf", MenuType.PDF))
    }

    @Test
    fun `t12 c05 line breaks, quotes and the other control characters`() {
        // A line break and a tab go, the quotes stay: the name is text, and the download escapes it.
        assertEquals("""menu"settembre".pdf""", MenuFile.cleanName("menu\r\n\"settembre\"\t.pdf", MenuType.PDF))
        assertEquals("menu.pdf", MenuFile.cleanName("me\u0000n\u001Fu\u007F\u0085.pdf", MenuType.PDF))
        assertEquals("menu.pdf", MenuFile.cleanName("\n\r\t", MenuType.PDF), "only control characters: nothing is left")
    }

    @Test
    fun `t12 c05 the characters that set the direction of the text and the invisible ones are taken out`() {
        // U+202E (right-to-left override) turns «fdp.exe» into «exe.pdf» on the screen: it must not stay.
        assertEquals("menuexe.pdf", MenuFile.cleanName("menu\u202Eexe.pdf", MenuType.PDF))
        for (invisible in listOf("\u202A", "\u202B", "\u202C", "\u202D", "\u202E", "\u2066", "\u2067", "\u2068", "\u2069", "\u200E", "\u200F", "\u061C", "\u200B", "\u200D", "\u2060", "\uFEFF", "\u2028", "\u2029", "\u00AD")) {
            assertEquals("menu.pdf", MenuFile.cleanName("me${invisible}nu.pdf", MenuType.PDF), "U+%04X".format(invisible.codePointAt(0)))
        }
    }

    @Test
    fun `t12 c05 the name is in NFC and the spaces at the edges go`() {
        // «e» and a combining acute accent become the «é» that is one character.
        assertEquals("caf\u00E9.pdf", MenuFile.cleanName("cafe\u0301.pdf", MenuType.PDF))
        assertEquals("menu di settembre.pdf", MenuFile.cleanName("   menu di settembre   .pdf", MenuType.PDF))
        assertEquals("menu.pdf", MenuFile.cleanName("\u00A0 \u2003menu\u3000.pdf", MenuType.PDF), "spaces of every kind, the non-breaking one too")
        assertEquals("menu.pdf", MenuFile.cleanName("   .pdf", MenuType.PDF))
    }

    @Test
    fun `t12 c05 a name of 300 characters is cut at 100, before the extension, and a name with nothing left is menu`() {
        val long = MenuFile.cleanName("a".repeat(300) + ".pdf", MenuType.PDF)

        assertEquals("a".repeat(100) + ".pdf", long)
        assertEquals(104, long.length)
        assertEquals("a".repeat(100) + ".jpg", MenuFile.cleanName("a".repeat(300), MenuType.JPEG))
        // A pair of characters is not cut in half: 100 characters, not 100 units of text.
        val emoji = "\uD83C\uDF72".repeat(150)
        val cut = MenuFile.cleanName("$emoji.png", MenuType.PNG)
        assertEquals(100, cut.removeSuffix(".png").codePointCount(0, cut.length - 4))
        assertTrue(cut.removeSuffix(".png").all { it.isSurrogate() }, "whole pairs only")
        // The cut leaves no space at the end.
        assertEquals("a".repeat(99) + ".pdf", MenuFile.cleanName("a".repeat(99) + " " + "b".repeat(10) + ".pdf", MenuType.PDF))
        for (nothing in listOf(null, "", " ", ".", "..", "...pdf", ".pdf", "\u202E")) {
            assertEquals("menu.pdf", MenuFile.cleanName(nothing, MenuType.PDF), "'$nothing'")
        }
    }

    @Test
    fun `t12 c05 a name that looks like markup stays as text, and a name that fits the column always does`() {
        assertEquals("<img src=x onerror=alert(1)>.pdf", MenuFile.cleanName("<img src=x onerror=alert(1)>.pdf", MenuType.PDF))
        // The table allows 104 characters at most, with no control characters: whatever is sent fits.
        for (sent in listOf("a".repeat(5000), "\uD83C\uDF72".repeat(500), "\u202E".repeat(400) + "x", "x/".repeat(300) + "y".repeat(400))) {
            val name = MenuFile.cleanName(sent, MenuType.JPEG)
            assertTrue(name.codePointCount(0, name.length) in 1..104, "${name.length}")
            assertFalse(name.any { Character.isISOControl(it) })
        }
    }

    // ---- The limit (criteria 4 and 12) ------------------------------------------------------------------------------------

    @Test
    fun `t12 c12 the limit is 10 MB, written once, and the sentences are made from it`() {
        assertEquals(10 * 1024 * 1024, MenuFile.MAX_BYTES)
        assertEquals(10_485_760, MenuFile.MAX_BYTES)
        assertEquals("10 MB", MenuFile.LIMIT_LABEL)
        assertEquals("10 MB", MenuFile.sizeLabel(MenuFile.MAX_BYTES.toLong()), "the limit, said as a size, is what the pages call it")
    }

    // ---- How the size and the date are said (design, part 1) ---------------------------------------------------------------

    @Test
    fun `t12 the size is whole KB under 1 MB and MB with one decimal from 1 MB`() {
        assertEquals("1 KB", MenuFile.sizeLabel(1))
        assertEquals("1 KB", MenuFile.sizeLabel(511))
        assertEquals("1 KB", MenuFile.sizeLabel(1024))
        assertEquals("2 KB", MenuFile.sizeLabel(1536), "half a KB rounds up")
        assertEquals("240 KB", MenuFile.sizeLabel(240L * 1024))
        assertEquals("239 KB", MenuFile.sizeLabel(245_000))
        assertEquals("999 KB", MenuFile.sizeLabel(999L * 1024))
        assertEquals("1 MB", MenuFile.sizeLabel(1000L * 1024), "from 1000 KB it is said in MB: «1.023 KB» would be hard to read")
        assertEquals("1 MB", MenuFile.sizeLabel(1023L * 1024))
        assertEquals("1 MB", MenuFile.sizeLabel(1024L * 1024 - 1), "it would be 1024 KB: it is 1 MB")
        assertEquals("1 MB", MenuFile.sizeLabel(1024L * 1024))
        assertEquals("1,5 MB", MenuFile.sizeLabel(1_572_864))
        assertEquals("1 MB", MenuFile.sizeLabel(1_100_000), "one decimal, and a zero decimal is not written")
        assertEquals("1,1 MB", MenuFile.sizeLabel(1_200_000))
        assertEquals("9,9 MB", MenuFile.sizeLabel(10_400_000))
        assertEquals("10 MB", MenuFile.sizeLabel(10L * 1024 * 1024))
    }

    @Test
    fun `t12 the moment of the upload has no year in the current year and has it in another`() {
        val at = OffsetDateTime.parse("2027-09-03T21:40:00+02:00")

        assertEquals("3 settembre alle 21:40", MenuFile.momentLabel(at, LocalDate.of(2027, 9, 20)))
        assertEquals("3 settembre 2027 alle 21:40", MenuFile.momentLabel(at, LocalDate.of(2028, 1, 10)))
        assertEquals("3 settembre 2027 alle 21:40", MenuFile.momentLabel(at, LocalDate.of(2026, 12, 31)))
        // The time of the place: 21:40 in Rome is 19:40 UTC, and it is said in Rome.
        assertEquals("3 settembre alle 21:40", MenuFile.momentLabel(OffsetDateTime.parse("2027-09-03T19:40:00Z"), LocalDate.of(2027, 9, 20)))
        // The day is the one of Rome: a quarter to midnight in Rome in the last evening of the year is still that year.
        assertEquals("31 dicembre alle 23:45", MenuFile.momentLabel(OffsetDateTime.parse("2027-12-31T22:45:00Z"), LocalDate.of(2027, 12, 31)))
        assertEquals("1 gennaio 2028 alle 0:15", MenuFile.momentLabel(OffsetDateTime.parse("2027-12-31T23:15:00Z"), LocalDate.of(2027, 12, 31)))
    }

    // ---- The download (criterion 8) ----------------------------------------------------------------------------------------

    @Test
    fun `t12 c08 the disposition is an attachment with the name in UTF-8 and a fallback that cannot break the header`() {
        assertEquals("attachment; filename=\"menu-settembre-2027.pdf\"; filename*=UTF-8''menu-settembre-2027.pdf", MenuFile.contentDisposition("menu-settembre-2027.pdf"))

        val awkward = MenuFile.contentDisposition("men\u00F9 \"di\" 100% \\ settembre.pdf")
        assertEquals("attachment; filename=\"men_ _di_ 100_ _ settembre.pdf\"; filename*=UTF-8''men%C3%B9%20%22di%22%20100%25%20%5C%20settembre.pdf", awkward)
        assertFalse(awkward.contains('\n') || awkward.contains('\r'))
        // The name of the header never ends the quoted string early: one quote at the start and one at the end.
        assertEquals(2, awkward.substringBefore("; filename*").count { it == '"' })
        assertTrue(MenuFile.contentDisposition("a*b'c(d).pdf").contains("filename*=UTF-8''a%2Ab%27c%28d%29.pdf"), "the characters that are not allowed in the value are encoded")
    }
}

/** Files built in memory for the tests: the build refuses a PDF or an image in the repository, and none of these is a real document. */
internal object MenuSamples {
    /** A PDF of a few hundred bytes: the header of a PDF 1.4, a page that says what it is, and the end. Not meant to be opened. */
    fun pdf(extra: Int = 0): ByteArray =
        ("%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n" +
            "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n").toByteArray() + ByteArray(extra) { ' '.code.toByte() }

    /** The start of a JPEG (SOI, an APP0 JFIF segment) and its end (EOI), with [extra] bytes of nothing between. */
    fun jpeg(extra: Int = 0): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1, 0, 0, 1, 0, 1, 0, 0) +
            ByteArray(extra) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** The signature of a PNG and an `IHDR` chunk of one pixel, then [extra] bytes of nothing. */
    fun png(extra: Int = 0): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(), 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0) +
            ByteArray(extra)

    /** [prefix] and then bytes up to [size] in all. */
    fun padded(prefix: ByteArray, size: Int): ByteArray = prefix + ByteArray((size - prefix.size).coerceAtLeast(0))

    /** A PDF of exactly [size] bytes. */
    fun pdfOf(size: Int): ByteArray = padded(pdf(), size).copyOf(size)
}
