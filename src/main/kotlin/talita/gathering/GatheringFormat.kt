package talita.gathering

import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * How dates and amounts are written, the same in the section, the detail, the preview and the public page (design guide, 6):
 * one place, `Locale.ITALIAN`, nothing written by hand in a template.
 */
object GatheringFormat {

    private val locale: Locale = Locale.ITALIAN

    private val day = DateTimeFormatter.ofPattern("d", locale)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", locale)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMMM uuuu", locale)

    /** `18 settembre 2027`. */
    fun date(date: LocalDate): String = dayMonthYear.format(date)

    /**
     * The first and last day: `17–19 settembre 2027` (same month, an en dash with no spaces), `30 aprile – 2 maggio 2027`
     * (different months, spaces), `30 dicembre 2027 – 1 gennaio 2028` (different years), `18 settembre 2027` (one day).
     */
    fun range(start: LocalDate, end: LocalDate): String = when {
        start == end -> date(start)
        start.year != end.year -> "${date(start)} – ${date(end)}"
        start.month != end.month -> "${dayMonth.format(start)} – ${date(end)}"
        else -> "${day.format(start)}–${date(end)}"
    }

    /** `venerdì` (lowercase): the name of the day of the week. */
    fun weekday(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)

    /**
     * The days of presence with their day of the week, as people say them: `Venerdì 17, sabato 18 e domenica 19 settembre 2027`;
     * two days `Venerdì 17 e sabato 18 settembre 2027`; one `Sabato 18 settembre 2027`. When the days are not all in one
     * month of one year each has its own date: `Giovedì 30 dicembre 2027, venerdì 31 dicembre 2027 e sabato 1 gennaio 2028`.
     */
    fun days(days: List<LocalDate>): String {
        if (days.isEmpty()) return ""
        val sameMonth = days.all { it.year == days.first().year && it.month == days.first().month }
        val labels = days.map { if (sameMonth) "${weekday(it)} ${day.format(it)}" else "${weekday(it)} ${date(it)}" }
        val joined = when (labels.size) {
            1 -> labels.single()
            else -> labels.dropLast(1).joinToString(", ") + " e " + labels.last()
        }
        val text = if (sameMonth) "$joined ${dayMonthYear.format(days.first()).substringAfter(' ')}" else joined
        return text.replaceFirstChar { it.uppercase() }
    }

    /** The word for a free fee. */
    const val FREE = "Gratuita"

    /** `60 €`, `12,50 €`, `1.000 €`: decimals only when they are not zero; zero is `Gratuita`, never `0 €`. */
    fun fee(cents: Int): String = if (cents == 0) FREE else amount(cents) + " €"

    /** `60`, `12,50`: how an amount is written in a field of a form (no thousands separator, comma for decimals). */
    fun amountField(cents: Int): String = if (cents % 100 == 0) (cents / 100).toString() else "${cents / 100},${"%02d".format(cents % 100)}"

    private fun amount(cents: Int): String {
        val whole = NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = true }
        return if (cents % 100 == 0) whole.format(cents / 100) else whole.format(cents / 100) + ",${"%02d".format(cents % 100)}"
    }
}
