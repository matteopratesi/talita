package talita.room

import org.jooq.DSLContext
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.ROOM_CHILD
import talita.jooq.tables.references.ROOM_TEACHER
import talita.retention.Deleted
import talita.retention.RetentionContext
import talita.retention.RetentionPolicy
import talita.retention.RetentionRule

/**
 * **Who slept where, at the end of the gathering** (criterion 20, decision of Matteo on question 4: the rooms are tied to the house, so they are emptied when the
 * gathering ends): from the day after the last day, the people of every room, children and teachers, are deleted. The rooms stay, with their name and beds and nobody in
 * them, for the teachers to copy the year after. The notes on health go a month after the end and the registrations a year after: this is the first of the three.
 *
 * A gathering that is a draft is not touched (it has no end that counts, as for every other rule: a draft has no registrations); its teachers in a room stay with it.
 * The job runs it first thing on the day (`@Order(15)`, between the notes and the registrations), and the register has one row, `rooms`, with the number of the rows.
 */
@Component
@Order(15)
class RoomsRule(private val policy: RetentionPolicy) : RetentionRule {

    override val name: String = "rooms"

    override fun apply(tx: DSLContext, context: RetentionContext): List<Deleted> {
        val expired = tx.select(GATHERING.ID, GATHERING.END_DATE).from(GATHERING).where(GATHERING.STATUS.ne(GatheringStatus.draft)).fetch()
            .filter { policy.roomsExpired(it[GATHERING.END_DATE]!!, context.today) }.map { it[GATHERING.ID]!! }
        if (expired.isEmpty()) return listOf(Deleted("rooms", 0))
        val children = tx.deleteFrom(ROOM_CHILD).where(ROOM_CHILD.GATHERING_ID.`in`(expired)).execute()
        val teachers = tx.deleteFrom(ROOM_TEACHER).where(ROOM_TEACHER.GATHERING_ID.`in`(expired)).execute()
        return listOf(Deleted("rooms", children + teachers))
    }
}
