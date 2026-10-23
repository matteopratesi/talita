package talita.gathering

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
