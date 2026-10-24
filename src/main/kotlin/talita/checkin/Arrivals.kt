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
import java.time.LocalDate

/**
 * Which gathering Arrivi is about, and which registrations belong to it (criteria 1, 28): the one that is not a draft and starts last, as the list of the
 * registrations of the teachers (the year after it moves to the new one when that leaves the draft; the old one stays in the database and has no pages). The
 * id of a registration of another gathering is an id that does not exist.
 */
@Component
class ArrivalsScope(private val gatherings: GatheringReader, private val registrations: Registrations) {

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
 * the possible duplicates and what each teacher has collected. Only reads: the pages decide what to show of it, and none of it is saved here.
 */
class ArrivalsData(
    val gathering: GatheringInfo,
    val registrations: List<RegistrationData>,
    private val presences: Map<Long, Map<LocalDate, Checkin>>,
    private val paid: Map<Long, Int>,
    val duplicates: Map<Long, List<Long>>,
    val collected: List<Collected>,
) {
    private val offer = Offer(gathering.fees, gathering.days)

    fun withdrawn(registration: RegistrationData): Boolean = registration.status == Status.WITHDRAWN

    /** The presence of [registration] on [day], if it was marked. */
    fun presence(registration: RegistrationData, day: LocalDate): Checkin? = presences[registration.id]?.get(day)

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
class Arrivals(private val registrations: Registrations, private val checkins: Checkins, private val payments: Payments) {

    fun load(gathering: GatheringInfo): ArrivalsData {
        val presences = checkins.ofGathering(gathering.id).groupBy({ it.registrationId }) { it }.mapValues { (_, rows) -> rows.associateBy { it.day } }
        return ArrivalsData(
            gathering = gathering,
            registrations = registrations.ofGathering(gathering.id),
            presences = presences,
            paid = payments.totals(gathering.id),
            duplicates = registrations.duplicates(gathering.id),
            collected = payments.collectedBy(gathering.id),
        )
    }
}
