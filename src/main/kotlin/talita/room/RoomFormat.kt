package talita.room

import talita.ClockConfiguration
import talita.gathering.GatheringFormat
import talita.registration.DayKind
import talita.registration.Presence
import talita.registration.RegistrationData
import talita.registration.RegistrationFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How the rooms write numbers, people and presences (design TALITA-11, guide 6): one place, so that the page, the print and the options of a `select` say «1 posto» and
 * «5 persone» the same way, and nothing is written by hand in a template.
 */
object RoomFormat {

    private val locale: Locale = Locale.ITALIAN
    private val moment = DateTimeFormatter.ofPattern("d MMMM uuuu 'alle' H:mm", locale).withZone(ClockConfiguration.ZONE)
    private val dayOfMonth = DateTimeFormatter.ofPattern("d", locale)

    /** `1 posto`, `4 posti`. */
    fun beds(count: Int): String = if (count == 1) "1 posto" else "$count posti"

    /** `1 persona`, `5 persone`. */
    fun people(count: Int): String = if (count == 1) "1 persona" else "$count persone"

    /** `5 persone · 4 posti`, or `Vuota · 4 posti` for a room where nobody sleeps. */
    fun count(people: Int, beds: Int): String = (if (people == 0) "Vuota" else people(people)) + " · " + beds(beds)

    /** `5 persone per 4 posti.`: the notice of a room that has more people than beds (it blocks nothing). */
    fun overfull(people: Int, beds: Int): String = "${people(people)} per ${beds(beds)}."

    /** `Camera 2 · 4 su 4 posti`: the people that are in the room out of its beds, in the options of a choice. */
    fun option(name: String, people: Int, beds: Int): String = "$name · $people su ${beds(beds)}"

    /** `17 settembre 2027 alle 7:30`, the hour of Rome. */
    fun moment(at: OffsetDateTime): String = moment.format(at)

    private fun shortDay(day: LocalDate): String = RegistrationFormat.capital(GatheringFormat.weekday(day).take(3)) + " " + dayOfMonth.format(day)

    /**
     * What the child comes for, as a line of the page: `Tutti i giorni`, `Sab 18 · Dom 19`, `Ven 17 · Sab 18 · pranzo Dom 19` (design 1, 2: the days of a whole day in order,
     * then the lunches). The same words as the lists of the registrations, with the days separated by a dot.
     */
    fun presence(presence: Presence): String {
        if (presence.allDays) return "Tutti i giorni"
        val full = presence.days.filterValues { it == DayKind.FULL }.keys.sorted().map(::shortDay)
        val lunch = presence.days.filterValues { it == DayKind.LUNCH }.keys.sorted().map(::shortDay)
        return (full + listOfNotNull(("pranzo " + lunch.joinToString(", ")).takeIf { lunch.isNotEmpty() })).joinToString(" · ")
    }

    /** `10 anni · Tutti i giorni`: the age on the first day of the gathering and what the child comes for. */
    fun childLine(registration: RegistrationData, firstDay: LocalDate): String = RegistrationFormat.ageText(registration.birthDate, firstDay) + " · " + presence(registration.presence)
}
