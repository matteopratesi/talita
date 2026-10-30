package talita.gathering

import talita.ClockConfiguration
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.Normalizer
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The kinds of file that the menu of a gathering can be (TALITA-12): a PDF, or the photo of the sheet as a JPEG or a PNG. The kind is recognised from the first
 * bytes of the file ([MenuFile.typeOf]), never from its name or from what the browser says, and it is what the page says and the download sends.
 */
enum class MenuType(
    /** What `Content-Type` says and the column `media_type` keeps. */
    val mediaType: String,
    /** The extension that the saved name ends with, whatever the file was called. */
    val extension: String,
    /** How the pages say it. */
    val label: String,
) {
    PDF("application/pdf", "pdf", "PDF"),
    JPEG("image/jpeg", "jpg", "JPEG"),
    PNG("image/png", "png", "PNG"),
    ;

    companion object {
        /** The kind that the table keeps as [mediaType] (the constraint of the column allows no other). */
        fun ofMediaType(mediaType: String): MenuType = entries.single { it.mediaType == mediaType }
    }
}

/**
 * What the menu file is made of, with no database and no server: the one limit of size, the kind from the first bytes, the name that is kept, and how the size
 * and the date are said (design TALITA-12, part 1; criteria 3, 5 and 12). The application never reads the content beyond its first bytes: a parser of PDFs or
 * of images would be surface of attack for a file that nobody looks at.
 */
object MenuFile {

    private const val KB = 1024
    private const val MB = 1024 * KB

    /**
     * The limit of size: 10 MB, **written once**. The check of the upload, the sentence under the field («Al massimo 10 MB»), the error and the constraint of
     * the table (`gathering_menu_content_size`, V10, which cannot read this constant and says 10485760) all mean this number, and a test makes sure they agree.
     * 1 MB is 1.048.576 bytes, as the sizes are written.
     */
    const val MAX_BYTES: Int = 10 * MB

    /** The limit as the pages write it: `10 MB`. */
    val LIMIT_LABEL: String = "${MAX_BYTES / MB} MB"

    /** The saved name is at most this many characters, without the extension of the kind. */
    const val MAX_NAME_CHARACTERS = 100

    /** What a file is called when nothing of its name is left. */
    const val DEFAULT_NAME = "menu"

    private val pdf = byteArrayOf('%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte(), '-'.code.toByte())
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** How many bytes [typeOf] needs to look at. */
    const val HEAD_BYTES = 8

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /**
     * The kind of file whose first bytes are [head]: `%PDF-`, `FF D8 FF` or `89 50 4E 47 0D 0A 1A 0A`; null for anything else (a page with a script
     * that is called `menu.pdf`, an SVG, a HEIC, a document of Word...).
     */
    fun typeOf(head: ByteArray): MenuType? = when {
        head.startsWith(pdf) -> MenuType.PDF
        head.startsWith(jpeg) -> MenuType.JPEG
        head.startsWith(png) -> MenuType.PNG
        else -> null
    }

    /** The extensions that a file of one of the kinds is called with: one of them at the end of the name is replaced by the extension of the kind that was recognised. */
    private val knownExtensions = setOf("pdf", "jpg", "jpeg", "png")

    /**
     * The name that is kept, from the one the browser sent: only the last part (after a `/` or a `\`: some browsers send the whole path), in NFC, without
     * control characters, characters that set the direction of the text (U+202E and the like) or other invisible ones (the characters of the category
     * «format»), without spaces at the edges, at most [MAX_NAME_CHARACTERS] characters, and ending with the extension of the kind that was recognised
     * (`.pdf`, `.jpg`, `.png`): a PNG called `menu.pdf` is kept as `menu.png`. When nothing is left of the name it is `menu`. The name is only ever shown as text.
     */
    fun cleanName(sent: String?, type: MenuType): String {
        val lastPart = (sent ?: "").substringAfterLast('/').substringAfterLast('\\')
        val visible = StringBuilder()
        Normalizer.normalize(lastPart, Normalizer.Form.NFC).codePoints().filter { !isHidden(it) }.forEach { visible.appendCodePoint(it) }
        var stem = trimEdges(visible.toString())
        val dot = stem.lastIndexOf('.')
        if (dot >= 0 && stem.substring(dot + 1).lowercase(Locale.ROOT) in knownExtensions) stem = trimEdges(stem.substring(0, dot))
        stem = trimEdges(stem.codePoints().limit(MAX_NAME_CHARACTERS.toLong()).toArray().let { String(it, 0, it.size) })
        if (stem.all { it == '.' }) stem = DEFAULT_NAME
        return "$stem.${type.extension}"
    }

    /** A character that is never kept in the name: a control character, an invisible one (format, line and paragraph separators) or half of a pair that is not a pair. */
    private fun isHidden(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SURROGATE -> true
        else -> false
    }

    private fun isSpace(codePoint: Int): Boolean = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

    /** [text] without the spaces (of any kind) at its two ends. */
    private fun trimEdges(text: String): String {
        val points = text.codePoints().toArray()
        var from = 0
        var to = points.size
        while (from < to && isSpace(points[from])) from++
        while (to > from && isSpace(points[to - 1])) to--
        return String(points, from, to - from)
    }

    /**
     * The size as the pages say it: whole KB under 1 MB (at least `1 KB`), from 1 MB in MB with one decimal when it is needed (`1,5 MB`, `10 MB`). 1 KB is 1.024
     * bytes, like the limit. A size of 1000 KB or more is said in MB (`1 MB`): «1.023 KB» would read as a thousand and twenty-three, and as one and twenty-three.
     */
    fun sizeLabel(bytes: Long): String {
        val kilobytes = ((bytes + KB / 2) / KB).coerceAtLeast(1)
        if (kilobytes < 1000) return "$kilobytes KB"
        val megabytes = DecimalFormat("0.#", DecimalFormatSymbols(Locale.ITALIAN)).apply { roundingMode = java.math.RoundingMode.HALF_UP }.format(bytes.toDouble() / MB)
        return "$megabytes MB"
    }

    private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", Locale.ITALIAN).withZone(ClockConfiguration.ZONE)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ITALIAN).withZone(ClockConfiguration.ZONE)
    private val hourMinute = DateTimeFormatter.ofPattern("H:mm", Locale.ITALIAN).withZone(ClockConfiguration.ZONE)

    /** The moment of the upload as the pages say it: `3 settembre alle 21:40` (the time of the place), with the year only if it is not the year of [today]: `3 settembre 2026 alle 21:40`. */
    fun momentLabel(at: OffsetDateTime, today: LocalDate): String {
        val day = at.atZoneSameInstant(ClockConfiguration.ZONE).toLocalDate()
        return (if (day.year == today.year) dayMonth else dayMonthYear).format(at) + " alle " + hourMinute.format(at)
    }

    /**
     * `Content-Disposition` of the download: always `attachment` (the file is never opened on our address), with the name twice, as the RFC 6266 asks: a plain
     * `filename` for the clients that do not know the other one (the characters that would break it, and those that are not ASCII, become `_`) and
     * `filename*` in UTF-8, which is the one that browsers use.
     */
    fun contentDisposition(name: String): String {
        val fallback = buildString { name.forEach { append(if (it.code in 0x20..0x7E && it != '"' && it != '\\' && it != '%') it else '_') } }
        // URLEncoder writes a space as «+» and leaves «*» alone: neither is allowed in the value of `filename*`.
        val encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A")
        return "attachment; filename=\"$fallback\"; filename*=UTF-8''$encoded"
    }
}
