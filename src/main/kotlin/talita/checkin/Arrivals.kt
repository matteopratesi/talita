package talita.checkin

import org.springframework.stereotype.Component
import talita.gathering.GatheringInfo
import talita.gathering.GatheringReader
import talita.gathering.GatheringState
import talita.registration.Offer
import talita.registration.Quota
import talita.registration.RegistrationData
import talita.registration.Registrations
import talita.registration.Status
import talita.retention.RetentionPolicy
import java.time.LocalDate

/**
 * Which gathering Arrivi is about, and which registrations belong to it (criteria 1, 28): the one that is not a draft and starts last, as the list of the
 * registrations of the teachers (the year after it moves to the new one when that leaves the draft; the old one stays in the database and has no pages). The
 * id of a registration of another gathering is an id that does not exist.
 */
@Component
class ArrivalsScope(private val gatherings: GatheringReader, private val registrations: Registrations, private val policy: RetentionPolicy) {

    fun gathering(): GatheringInfo? = gatherings.latestNotDraft()

    /** A draft, when there is nothing else: the page says that the arrivals begin when the registrations open. */
    fun draft(): GatheringInfo? = gatherings.current()?.takeIf { it.state == GatheringState.DRAFT }

    /** The registration with this id and the gathering of Arrivi, if the registration is one of its own. */
    fun registration(id: Long): Pair<RegistrationData, GatheringInfo>? {
        val gathering = gathering() ?: return null
        val registration = registrations.find(id) ?: return null
        return if (registration.gatheringId == gathering.id) registration to gathering else null
    }

    fun today(): LocalDate = gatherings.today()

    /** The registrations of the gathering are gone (a year after its end, from the day of the date): Arrivi and the prints say so, and lead to the gathering. */
    fun expired(gathering: GatheringInfo): Boolean = policy.registrationsExpired(gathering.end, today())
}

/** Where today is among the days of the gathering. */
enum class Phase { BEFORE, DURING, AFTER }

/**
 * The days of the gathering and the one that is being looked at (criterion 1): today during the gathering, the first day before it and the last after it, unless
 * the address names another of its days. The presences of a day can be marked while the day is not after today.
 */
data class DayChoice(val days: List<LocalDate>, val selected: LocalDate, val today: LocalDate) {

    val phase: Phase
        get() = when {
            today.isBefore(days.first()) -> Phase.BEFORE
            today.isAfter(days.last()) -> Phase.AFTER
            else -> Phase.DURING
        }

    /** The presences of the chosen day can be marked and taken away. */
    val markable: Boolean get() = !selected.isAfter(today)

    companion object {
        fun defaultDay(days: List<LocalDate>, today: LocalDate): LocalDate = when {
            today.isBefore(days.first()) -> days.first()
            today.isAfter(days.last()) -> days.last()
            else -> today
        }

        /** [requested] is what the address says (`2027-09-18`): a day of the gathering is taken, anything else is ignored without a word. */
        fun of(days: List<LocalDate>, requested: String?, today: LocalDate): DayChoice {
            val asked = requested?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            return DayChoice(days, asked?.takeIf { it in days } ?: defaultDay(days, today), today)
        }
    }
}

/**
 * What the lists and the prints of one gathering read, taken together: the registrations by surname and name, who is present on which day, what each has paid,
 * the possible duplicates and what each teacher has collected. Only reads: the pages decide what to show of it, and none of it is saved here. [today] is the day of
 * Rome at the moment of reading, the one that «present» is worked out against.
 */
class ArrivalsData(
    val gathering: GatheringInfo,
    val today: LocalDate,
    val registrations: List<RegistrationData>,
    private val presences: Map<Long, Map<LocalDate, Checkin>>,
    private val paid: Map<Long, Int>,
    val duplicates: Map<Long, List<Long>>,
    val collected: List<Collected>,
) {
    private val offer = Offer(gathering.fees, gathering.days)

    fun withdrawn(registration: RegistrationData): Boolean = registration.status == Status.WITHDRAWN

    /**
     * The row of [registration] on [day], as it is kept, if there is one (for the card of the child, which also shows the rows of the days that have not come: they are
     * the arrival that was written in advance). It does not say who is present: that is [present].
     */
    fun presence(registration: RegistrationData, day: LocalDate): Checkin? = presences[registration.id]?.get(day)

    /**
     * Whether [registration] is **present** on [day] (TALITA-19, criterion 7): the registration is not withdrawn, the row of that day is there, and the day is not after
     * today. It is the only place that says who is there: the list, the summary, the register, the list for the venue and the modules of the venue read it, and no one
     * else counts the rows (the card shows the rows as they are kept, and tells the ones of the days that have not come apart: «Sarà presente»). A row of a day that has not
     * come is not a presence yet: it becomes one on its day, if nobody takes it away.
     */
    fun present(registration: RegistrationData, day: LocalDate): Boolean = !withdrawn(registration) && !day.isAfter(today) && presence(registration, day) != null

    /**
     * The days after [day], in a row, that have a row: what taking the presence of [day] away takes away with it (TALITA-19, criterion 9). Of the rows as they are kept, so it
     * counts the days after today too.
     */
    fun takenWith(registration: RegistrationData, day: LocalDate): List<LocalDate> =
        generateSequence(day.plusDays(1)) { it.plusDays(1) }.takeWhile { presence(registration, it) != null }.toList()

    /** All the presences of [registration], by day. */
    fun presencesOf(registration: RegistrationData): Map<LocalDate, Checkin> = presences[registration.id].orEmpty()

    /** What [registration] has paid: the sum of its payments. */
    fun paid(registration: RegistrationData): Int = paid[registration.id] ?: 0

    /** What is due (`Quota`), null for a registration that was withdrawn (it owes nothing) or whose days the fees do not offer. */
    fun due(registration: RegistrationData): Int? = if (withdrawn(registration)) null else Quota.due(offer, registration.presence)

    fun balance(registration: RegistrationData): Balance = Balance.of(due(registration), paid(registration), withdrawn(registration))

    /** All the money that was registered in the gathering (the registrations that were withdrawn since too). */
    val totalCollected: Int get() = collected.sumOf { it.cents }

    /** What is still to be collected from the registrations that are confirmed. */
    val stillToCollect: Int get() = registrations.filter { it.status == Status.CONFIRMED }.sumOf { balance(it).owed }

    /** The signed form of a paper registration has not arrived (the label «Manleva da ricevere»): never for a withdrawn registration or one made online. */
    fun receiptDue(registration: RegistrationData): Boolean = !withdrawn(registration) && registration.onPaper && !registration.paperFormReceived

    /** Whether the registration is expected on [day] (not withdrawn, and it includes the day). */
    fun expected(registration: RegistrationData, day: LocalDate): Boolean = !withdrawn(registration) && registration.includes(day)
}

/** Reads what the pages of Arrivi need, in one place. */
@Component
class Arrivals(private val registrations: Registrations, private val checkins: Checkins, private val payments: Payments, private val gatherings: GatheringReader) {

    fun load(gathering: GatheringInfo): ArrivalsData {
        val presences = checkins.ofGathering(gathering.id).groupBy({ it.registrationId }) { it }.mapValues { (_, rows) -> rows.associateBy { it.day } }
        return ArrivalsData(
            gathering = gathering,
            today = gatherings.today(),
            registrations = registrations.ofGathering(gathering.id),
            presences = presences,
            paid = payments.totals(gathering.id),
            duplicates = registrations.duplicates(gathering.id),
            collected = payments.collectedBy(gathering.id),
        )
    }
}
