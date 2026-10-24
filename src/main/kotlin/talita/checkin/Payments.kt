package talita.checkin

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import talita.gathering.Amount
import talita.gathering.GatheringChecks
import talita.jooq.tables.references.PAYMENT
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.TEACHER
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/** A payment as it is kept: an amount that a teacher received for a registration, and when. [byAccountId] is null when the account of the teacher was deleted (TALITA-7). */
data class Payment(val id: Long, val registrationId: Long, val cents: Int, val at: OffsetDateTime, val byAccountId: Long?)

/** How much a teacher has collected in a gathering; the name is null when the person is not among the teachers any more, and so is [byAccountId] when the account was deleted. */
data class Collected(val byAccountId: Long?, val first: String?, val last: String?, val cents: Int) {
    /** Name and surname, or null for someone who is not a teacher any more. */
    val name: String? get() = if (first != null && last != null) "$first $last" else null
}

/** What came of registering a payment (criteria 11, 12). */
sealed interface PaymentResult {
    data class Registered(val id: Long) : PaymentResult

    /** The same form again (a double tap, a network that comes back): there is one payment, and it is the one of the first. */
    data class AlreadyRegistered(val id: Long) : PaymentResult

    data object NotFound : PaymentResult

    /** The request code of the form was used for another registration: a request made by hand, nothing was written. */
    data object RequestUsedElsewhere : PaymentResult
}

/** What came of taking a payment away (criterion 13). */
sealed interface RemoveResult {
    data class Removed(val cents: Int) : RemoveResult

    /** It is not there any more, because another teacher took it away a moment before: the answer is the state now, never an error. */
    data object AlreadyRemoved : RemoveResult

    /** No such registration, or the payment is of another registration. */
    data object NotFound : RemoveResult
}

/** What an amount typed in the form of a payment is: the cents, or the error with its words (criterion 11). */
data class AmountCheck(val typed: String, val cents: Int?, val error: String?)

/** The amount of a payment: from 0,01 € to 1.000 €, with a comma or a dot and at most two decimals. The same reading of the amounts as the fees of the gathering. */
object PaymentAmount {
    const val EMPTY = "Scrivi quanto hai ricevuto, per esempio 25 o 12,50."
    const val FORMAT = "Scrivi un importo in euro con al massimo due decimali, per esempio 25 o 12,50."
    const val ZERO = "L’importo deve essere più di zero."
    const val TOO_MUCH = "Un pagamento può essere al massimo 1.000 €. Se è di più, registralo in due volte."

    /** The most characters kept of what was typed: a request made by hand cannot make the page echo a book. */
    private const val MAX_TYPED = 30

    fun check(raw: String?): AmountCheck {
        // The spaces go, in the middle too (`12, 50`, `1 000`): nobody means anything else by them.
        val typed = raw.orEmpty().filterNot { it.isWhitespace() }.take(MAX_TYPED)
        if (typed.isEmpty()) return AmountCheck(typed, null, EMPTY)
        return when (val amount = GatheringChecks.amount(typed)) {
            Amount.Malformed -> AmountCheck(typed, null, FORMAT)
            Amount.TooMuch -> AmountCheck(typed, null, TOO_MUCH)
            is Amount.Cents -> if (amount.cents == 0) AmountCheck(typed, null, ZERO) else AmountCheck(typed, amount.cents, null)
        }
    }
}

/**
 * What a registration owes or is owed (criterion 10), worked out every time from what is due (`Quota`) and what was paid (the sum of the payments): it is never
 * saved, so a change of the fees or of the days (R2) changes it and nothing else.
 */
sealed interface Balance {
    /** Still to be paid. */
    data class ToPay(val cents: Int) : Balance

    data object Paid : Balance

    /** More was paid than is due (also by a registration that was withdrawn after a payment: the payments stay, and refunds are not here). */
    data class Over(val cents: Int) : Balance

    /** Nothing is due and nothing was paid. */
    data object Free : Balance

    /** A withdrawn registration owes nothing, and nothing was paid. */
    data object NothingDue : Balance

    /** What is due cannot be worked out (the days are not offered by the fees): the page says so, and nothing is guessed. */
    data object Undefined : Balance

    /** What is still to be collected: the part of the balance that is a debt. */
    val owed: Int get() = (this as? ToPay)?.cents ?: 0

    companion object {
        /** [due] is what `Quota.due` says (null when it cannot be worked out); a withdrawn registration owes nothing whatever it was. */
        fun of(due: Int?, paid: Int, withdrawn: Boolean): Balance = when {
            withdrawn -> if (paid > 0) Over(paid) else NothingDue
            due == null -> Undefined
            due == 0 -> if (paid == 0) Free else Over(paid)
            paid < due -> ToPay(due - paid)
            paid == due -> Paid
            else -> Over(paid - due)
        }
    }
}

/**
 * The payments (criteria 10-15): each one is a row with its amount, its moment and the teacher who registered it, and none overwrites another, so a family that
 * pays in instalments, or two teachers who collect at the same time, leave all of it. The amount paid is the sum; a payment that is wrong is taken away with all
 * its data. They are registered and taken away in every state of the gathering (before the start and after the end too: the paper register is copied then).
 *
 * The form carries a request code that the server wrote when it drew it, and the database holds it unique: the same form sent twice makes one payment. Nothing
 * here writes an amount, a name or a phone in the log.
 */
@Component
class Payments(private val dsl: DSLContext, private val clock: Clock) {

    /**
     * Registers [cents] for [registrationId] with the code [request] of the form. The registration is read for sharing, so it cannot go away in the middle. The
     * same code again answers [PaymentResult.AlreadyRegistered], whoever sends it first.
     */
    fun register(registrationId: Long, request: UUID, cents: Int, byAccountId: Long): PaymentResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        tx.select(REGISTRATION.ID).from(REGISTRATION).where(REGISTRATION.ID.eq(registrationId)).forShare().fetchOne() ?: return@transactionResult PaymentResult.NotFound
        val inserted = tx.insertInto(PAYMENT)
            .set(PAYMENT.REGISTRATION_ID, registrationId)
            .set(PAYMENT.AMOUNT_CENTS, cents)
            .set(PAYMENT.RECORDED_AT, OffsetDateTime.now(clock))
            .set(PAYMENT.RECORDED_BY_ACCOUNT_ID, byAccountId)
            .set(PAYMENT.REQUEST_ID, request)
            .onConflict(PAYMENT.REQUEST_ID).doNothing()
            .returningResult(PAYMENT.ID).fetchOne()
        if (inserted != null) return@transactionResult PaymentResult.Registered(inserted.value1()!!)
        // The code was there already: the first one waits for its own commit, so this finds it (and says whose it is).
        val existing = tx.select(PAYMENT.ID, PAYMENT.REGISTRATION_ID).from(PAYMENT).where(PAYMENT.REQUEST_ID.eq(request)).fetchOne()
        if (existing != null && existing[PAYMENT.REGISTRATION_ID] == registrationId) PaymentResult.AlreadyRegistered(existing[PAYMENT.ID]!!) else PaymentResult.RequestUsedElsewhere
    }

    /** Takes the payment [paymentId] of [registrationId] away. Taking away what is not there is [RemoveResult.AlreadyRemoved]. */
    fun remove(registrationId: Long, paymentId: Long): RemoveResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        val deleted = tx.deleteFrom(PAYMENT).where(PAYMENT.ID.eq(paymentId)).and(PAYMENT.REGISTRATION_ID.eq(registrationId)).returningResult(PAYMENT.AMOUNT_CENTS).fetchOne()
        when {
            deleted != null -> RemoveResult.Removed(deleted.value1()!!)
            tx.fetchExists(PAYMENT, PAYMENT.ID.eq(paymentId)) -> RemoveResult.NotFound
            else -> RemoveResult.AlreadyRemoved
        }
    }

    // ---- Reading ----------------------------------------------------------------------------------------------------------------------

    private fun row(it: org.jooq.Record): Payment =
        Payment(it[PAYMENT.ID]!!, it[PAYMENT.REGISTRATION_ID]!!, it[PAYMENT.AMOUNT_CENTS]!!, it[PAYMENT.RECORDED_AT]!!, it[PAYMENT.RECORDED_BY_ACCOUNT_ID])

    /** One payment, whichever registration it is of; null when it is not there (any more). */
    fun find(paymentId: Long): Payment? =
        dsl.select(PAYMENT.ID, PAYMENT.REGISTRATION_ID, PAYMENT.AMOUNT_CENTS, PAYMENT.RECORDED_AT, PAYMENT.RECORDED_BY_ACCOUNT_ID).from(PAYMENT).where(PAYMENT.ID.eq(paymentId)).fetchOne(::row)

    /** The payments of one registration, in the order they were registered. */
    fun ofRegistration(registrationId: Long): List<Payment> =
        dsl.select(PAYMENT.ID, PAYMENT.REGISTRATION_ID, PAYMENT.AMOUNT_CENTS, PAYMENT.RECORDED_AT, PAYMENT.RECORDED_BY_ACCOUNT_ID).from(PAYMENT)
            .where(PAYMENT.REGISTRATION_ID.eq(registrationId)).orderBy(PAYMENT.RECORDED_AT, PAYMENT.ID).fetch(::row)

    /** What each registration of the gathering has paid: the sum of its payments (the ones that paid nothing are not in the answer). */
    fun totals(gatheringId: Long): Map<Long, Int> =
        dsl.select(PAYMENT.REGISTRATION_ID, DSL.sum(PAYMENT.AMOUNT_CENTS)).from(PAYMENT).join(REGISTRATION).on(REGISTRATION.ID.eq(PAYMENT.REGISTRATION_ID))
            .where(REGISTRATION.GATHERING_ID.eq(gatheringId)).groupBy(PAYMENT.REGISTRATION_ID)
            .fetch().associate { it[PAYMENT.REGISTRATION_ID]!! to it.value2()!!.toInt() }

    /** What each teacher has collected in the gathering (the registrations that were withdrawn too), by surname and name; whoever is not a teacher any more last. */
    fun collectedBy(gatheringId: Long): List<Collected> =
        dsl.select(PAYMENT.RECORDED_BY_ACCOUNT_ID, TEACHER.FIRST_NAME, TEACHER.LAST_NAME, DSL.sum(PAYMENT.AMOUNT_CENTS))
            .from(PAYMENT).join(REGISTRATION).on(REGISTRATION.ID.eq(PAYMENT.REGISTRATION_ID))
            .leftJoin(TEACHER).on(TEACHER.ACCOUNT_ID.eq(PAYMENT.RECORDED_BY_ACCOUNT_ID))
            .where(REGISTRATION.GATHERING_ID.eq(gatheringId))
            .groupBy(PAYMENT.RECORDED_BY_ACCOUNT_ID, TEACHER.FIRST_NAME, TEACHER.LAST_NAME)
            .fetch { Collected(it[PAYMENT.RECORDED_BY_ACCOUNT_ID], it[TEACHER.FIRST_NAME], it[TEACHER.LAST_NAME], it.value4()!!.toInt()) }
            .sortedWith(compareBy<Collected>({ it.name == null }, { it.last?.lowercase() }, { it.first?.lowercase() }, { it.byAccountId }))
}
