package talita.registration

import org.jooq.DSLContext

/**
 * Something that has to happen in the transaction that withdraws a registration (rule of TALITA-11: the child is taken out of its room). The registration is locked
 * for update by the withdrawal, so whoever depends on it and takes it for sharing (the choice of a room) either is done before, and goes with the withdrawal, or finds it
 * withdrawn.
 *
 * It is the way for the other packages to be called without this one knowing them: `talita.room` implements it, and nothing here depends on that package. An
 * implementation takes only the [DSLContext] of the transaction, so that it can be built without `Registrations`: a listener that needed it would make a cycle.
 */
fun interface WithdrawalListener {

    /** The registration [registrationId] has just been withdrawn, inside the transaction of [tx]. */
    fun withdrawn(tx: DSLContext, registrationId: Long)
}
