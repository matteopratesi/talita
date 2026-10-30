package talita.room

import org.jooq.DSLContext
import org.springframework.stereotype.Component
import talita.jooq.tables.references.ROOM_CHILD
import talita.registration.WithdrawalListener

/**
 * A child whose registration is withdrawn leaves its room, in the same transaction (criterion 11). It is a [WithdrawalListener] that needs nothing but the transaction: a
 * registration that is put back later is among the children without a room.
 */
@Component
class RoomWithdrawal : WithdrawalListener {

    override fun withdrawn(tx: DSLContext, registrationId: Long) {
        tx.deleteFrom(ROOM_CHILD).where(ROOM_CHILD.REGISTRATION_ID.eq(registrationId)).execute()
    }
}
