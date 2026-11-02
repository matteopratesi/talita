package talita.registration

import org.jooq.DSLContext

/**
 * Something that has to happen in the transaction that corrects a registration (TALITA-19: the presences of the days after today that its new days do not carry any
 * more). The correction has locked the registration for update and has written the days already: the listener reads what is there now.
 *
 * It is the same way as [WithdrawalListener] for the other packages to be called without this one knowing them, and it follows the same rule: an implementation takes only
 * the [DSLContext] of the transaction, so that it can be built without `Registrations`.
 */
fun interface CorrectionListener {

    /** The registration [registrationId] has just been corrected (its days, its state, the child), inside the transaction of [tx]. */
    fun corrected(tx: DSLContext, registrationId: Long)
}
