package talita.registration

import talita.ClockConfiguration
import talita.gathering.GatheringFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How the registrations say what they say, the same in every page of the parent and of the teachers (design guide, 6): the amounts, the moments,
 * the days of presence, the ages. Dates and amounts come from [GatheringFormat]; nothing is written by hand in a template.
 */
object RegistrationFormat {

    private val locale: Locale = Locale.ITALIAN
    private val time = DateTimeFormatter.ofPattern("d MMMM uuuu 'alle' HH:mm", locale).withZone(ClockConfiguration.ZONE)
    private val dayOfMonth = DateTimeFormatter.ofPattern("d", locale)
    private val dayAndMonth = DateTimeFormatter.ofPattern("d MMMM", locale)

    /** `60 €`, with a space that does not break before the euro sign, so that an amount is never split between two lines; `Gratuita` for zero. */
    fun money(cents: Int): String = GatheringFormat.fee(cents).replace(' ', ' ')

    /** `12 giugno 2027 alle 21:30`, the hour of Rome. */
    fun moment(at: OffsetDateTime): String = time.format(at)

    private val dayOnly = DateTimeFormatter.ofPattern("d MMMM uuuu", locale).withZone(ClockConfiguration.ZONE)

    /** `12 giugno 2027`: the day of a moment, in Rome. */
    fun day(at: OffsetDateTime): String = dayOnly.format(at)

    /** The first letter in capitals: `venerdì` becomes `Venerdì`. */
    fun capital(text: String): String = text.replaceFirstChar { it.uppercase() }

    /** `Venerdì 17`: how a day is named in the choice of the days of a form. */
    fun dayLabel(day: LocalDate): String = capital(GatheringFormat.weekday(day)) + " " + dayOfMonth.format(day)

    /** `4 aprile 2016`. */
    fun birth(date: LocalDate): String = GatheringFormat.date(date)

    /** The age in whole years on [on] (the first day of the gathering: the age that the child will have when they arrive). */
    fun age(birth: LocalDate, on: LocalDate): Int = Period.between(birth, on).years

    fun ageText(birth: LocalDate, on: LocalDate): String = age(birth, on).let { if (it == 1) "1 anno" else "$it anni" }

    /** `sabato 18` for days of one month, `sabato 18 settembre` when they are not all in the same one. */
    private fun short(days: List<LocalDate>): List<String> {
        val sameMonth = days.all { it.month == days.first().month && it.year == days.first().year }
        return days.map { GatheringFormat.weekday(it) + " " + (if (sameMonth) dayOfMonth else dayAndMonth).format(it) }
    }

    private fun list(items: List<String>): String = if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString(", ") + " e " + items.last()

    /**
     * When the child comes, as the pages write it: `Tutti i giorni (17–19 settembre 2027)` (`Tutto il giorno (18 settembre 2027)` for a gathering of
     * one day), or `Giornata: sabato 18. Solo pranzo: domenica 19.` with the days of each kind in order.
     */
    fun presence(presence: Presence, gatheringDays: List<LocalDate>): String {
        if (presence.allDays) {
            val range = GatheringFormat.range(gatheringDays.first(), gatheringDays.last())
            return (if (gatheringDays.size == 1) "Tutto il giorno" else "Tutti i giorni") + " ($range)"
        }
        return DayKind.entries.mapNotNull { kind ->
            val days = presence.days.filterValues { it == kind }.keys.sorted()
            if (days.isEmpty()) null else (if (kind == DayKind.FULL) "Giornata" else "Solo pranzo") + ": " + list(short(days)) + "."
        }.joinToString(" ")
    }

    /** The same for a row of a list: `Tutti i giorni`, `Sab 18, Dom 19`, `Sab 18 · pranzo Dom 19`. */
    fun presenceShort(presence: Presence): String {
        if (presence.allDays) return "Tutti i giorni"
        fun abbreviated(days: Collection<LocalDate>) = days.sorted().joinToString(", ") { capital(GatheringFormat.weekday(it).take(3)) + " " + dayOfMonth.format(it) }
        val full = presence.days.filterValues { it == DayKind.FULL }.keys
        val lunch = presence.days.filterValues { it == DayKind.LUNCH }.keys
        return listOfNotNull(abbreviated(full).takeIf { full.isNotEmpty() }, ("pranzo " + abbreviated(lunch)).takeIf { lunch.isNotEmpty() }).joinToString(" · ")
    }

    /** The two initials in the circle of a row. */
    fun initials(first: String, last: String): String = (first.take(1) + last.take(1)).uppercase()

    /** The digits of a phone with the sign, for a `tel:` link. */
    fun telephone(phone: String): String = phone.filter { it.isDigit() || it == '+' }
}
