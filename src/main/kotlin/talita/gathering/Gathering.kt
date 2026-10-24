package talita.gathering

import org.jooq.DSLContext
import talita.jooq.enums.GatheringStatus
import java.time.LocalDate

/**
 * Where a gathering is: a draft (the parents do not see the page), with the registrations open, or closed. «Over» is not a
 * state: it is a gathering that is not a draft and whose last day has passed (see [Gathering.isOver]).
 */
enum class GatheringState(val column: GatheringStatus) {
    DRAFT(GatheringStatus.draft),
    OPEN(GatheringStatus.`open`),
    CLOSED(GatheringStatus.closed),
    ;

    companion object {
        fun of(column: GatheringStatus): GatheringState = entries.single { it.column == column }
    }
}

/** The three fees, in cents. Null: not provided («Non prevista»). Zero: free of charge. */
data class Fees(val fullCents: Int?, val dailyCents: Int?, val lunchOnlyCents: Int?) {
    val none: Boolean get() = fullCents == null && dailyCents == null && lunchOnlyCents == null
}

/** A teacher who takes the children in custody, with the name that the teacher wrote (TALITA-3). */
data class Custodian(val accountId: Long, val first: String, val last: String) {
    val name: String get() = "$first $last"
}

/**
 * A gathering. It holds no personal data: it is an event and its place. The public page shows only some of it (see
 * [PublicInfo]), never the custodians.
 *
 * @property publicCode the 16 characters of the address of the public page; never changes
 */
data class Gathering(
    val id: Long,
    val publicCode: String,
    val title: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val place: String?,
    val fees: Fees,
    val infoText: String?,
    val whatToBring: String?,
    val state: GatheringState,
) {
    /** The days of presence: every day from the first to the last, both included. */
    val days: List<LocalDate> get() = generateSequence(startDate) { it.plusDays(1) }.takeWhile { !it.isAfter(endDate) }.toList()

    /** Not a draft and the last day is before [today]. After the end the dates never change. */
    fun isOver(today: LocalDate): Boolean = state != GatheringState.DRAFT && endDate.isBefore(today)

    /** A draft, or a gathering whose last day has not passed: the custodians of such a gathering cannot be taken away. */
    fun isNotOver(today: LocalDate): Boolean = !isOver(today)

    /** The registrations are open and the gathering is not over: the parents can register today. */
    fun registrationsPossible(today: LocalDate): Boolean = state == GatheringState.OPEN && !endDate.isBefore(today)
}

/** What a draft still needs before the registrations open (criterion 10), in the order of the forms. */
enum class Missing(val text: String, val path: (Long) -> String) {
    PLACE("Scrivi il luogo", { "/convivenza/$it/date#luogo" }),
    FULL_FEE("Scrivi la quota intera", { "/convivenza/$it/quote#quota-intera" }),
    CUSTODIAN("Scegli almeno un maestro affidatario", { "/convivenza/$it/affidatari" }),
    DATES("Cambia le date: l’ultimo giorno è già passato", { "/convivenza/$it/date#ultimo-giorno" }),
}

/** Where a gathering was asked about by the services of the other tickets (criterion 36): what they need, and nothing more. */
data class GatheringInfo(
    val id: Long,
    val code: String,
    val title: String,
    val start: LocalDate,
    val end: LocalDate,
    /** Every day of presence, in order. */
    val days: List<LocalDate>,
    val place: String?,
    /** In cents; null means not provided. */
    val fees: Fees,
    /** The teachers in custody, by surname and name. */
    val custodians: List<Custodian>,
    val state: GatheringState,
    /** Not a draft and the last day has passed (day of Rome). */
    val over: Boolean,
    /** Open and not over: a parent can register today. */
    val registrationsPossibleToday: Boolean,
)

/** How many registrations of each state a gathering has: only counts, never names (the card «Iscrizioni» of its detail, the list of the teachers). */
data class RegistrationCounts(val confirmed: Int, val maybe: Int, val withdrawn: Int) {
    /** The registrations that are not withdrawn. */
    val active: Int get() = confirmed + maybe

    val none: Boolean get() = confirmed + maybe + withdrawn == 0
}

/** How many registrations that are not withdrawn use the daily fee (they have a whole day among their days) and the fee of the lunch only. */
data class FeeUsers(val daily: Int, val lunchOnly: Int)

/**
 * What the registrations tell the gathering (rules R2 and R3 of TALITA-4, criteria 30 and 31): how many there are, and how many use a fee or a day. The
 * gathering asks, and `talita.registration` answers: this package never reads the tables of the registrations, and does not depend on that package. The
 * functions that count for a change take the [DSLContext] of the transaction that makes the change, under the lock of the row of the gathering, so that a
 * registration that is being made is either counted or waits.
 */
interface RegistrationUsage {

    /** The registrations of [gatheringId] by state. */
    fun counts(gatheringId: Long): RegistrationCounts

    /** How many registrations of [gatheringId] that are not withdrawn use each of the two fees that can be left empty. */
    fun feeUsers(context: DSLContext, gatheringId: Long): FeeUsers

    /**
     * For each of [days], how many registrations that are not withdrawn have it: the ones of every day have all of them, the ones of some days have the ones
     * they chose. A day nobody has is not in the answer.
     */
    fun dayUsers(context: DSLContext, gatheringId: Long, days: Collection<LocalDate>): Map<LocalDate, Int>
}
