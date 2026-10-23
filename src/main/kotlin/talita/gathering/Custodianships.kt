package talita.gathering

import org.jooq.Condition
import org.jooq.DSLContext
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.GATHERING_CUSTODIAN
import java.time.LocalDate

/** The gatherings of which a teacher is a custodian, split by whether they are over. */
data class Custodianship(val unfinished: List<GatheringRef>, val finished: List<GatheringRef>) {
    val none: Boolean get() = unfinished.isEmpty() && finished.isEmpty()

    companion object {
        val NONE = Custodianship(emptyList(), emptyList())
    }
}

/**
 * Where a teacher is a custodian (criterion 40). A gathering is over when it is not a draft and its last day is before today; the
 * others (a draft, or one whose last day has not passed, whatever its state) cannot lose a custodian by «Togli»: that is why the
 * foreign key of the custodians can cascade.
 *
 * The functions take the [DSLContext] to use, so that `Teachers.remove` asks inside its own transaction, under the lock of the
 * teachers.
 */
object Custodianships {

    private fun unfinished(today: LocalDate): Condition = GATHERING.STATUS.eq(GatheringStatus.draft).or(GATHERING.END_DATE.ge(today))

    /** The gatherings that are not over in which [accountId] is a custodian. */
    fun unfinished(context: DSLContext, accountId: Long, today: LocalDate): List<GatheringRef> =
        refs(context, GATHERING_CUSTODIAN.TEACHER_ACCOUNT_ID.eq(accountId).and(unfinished(today)))

    /** For every teacher who is a custodian of some gathering, where: the data of the page «Maestri». */
    fun byTeacher(context: DSLContext, today: LocalDate): Map<Long, Custodianship> {
        val rows = context.select(GATHERING_CUSTODIAN.TEACHER_ACCOUNT_ID, GATHERING.ID, GATHERING.TITLE, GATHERING.STATUS, GATHERING.END_DATE)
            .from(GATHERING_CUSTODIAN).join(GATHERING).on(GATHERING.ID.eq(GATHERING_CUSTODIAN.GATHERING_ID))
            .orderBy(GATHERING.START_DATE.desc(), GATHERING.ID.desc()).fetch()
        return rows.groupBy({ it[GATHERING_CUSTODIAN.TEACHER_ACCOUNT_ID]!! }) { row ->
            val over = row[GATHERING.STATUS] != GatheringStatus.draft && row[GATHERING.END_DATE]!!.isBefore(today)
            over to GatheringRef(row[GATHERING.ID]!!, row[GATHERING.TITLE]!!)
        }.mapValues { (_, items) -> Custodianship(items.filter { !it.first }.map { it.second }, items.filter { it.first }.map { it.second }) }
    }

    private fun refs(context: DSLContext, condition: Condition): List<GatheringRef> =
        context.select(GATHERING.ID, GATHERING.TITLE).from(GATHERING_CUSTODIAN)
            .join(GATHERING).on(GATHERING.ID.eq(GATHERING_CUSTODIAN.GATHERING_ID))
            .where(condition).orderBy(GATHERING.START_DATE.desc(), GATHERING.ID.desc())
            .fetch { GatheringRef(it[GATHERING.ID]!!, it[GATHERING.TITLE]!!) }
}
