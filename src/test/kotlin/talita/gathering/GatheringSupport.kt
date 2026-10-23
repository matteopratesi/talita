package talita.gathering

import org.jooq.DSLContext
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.GATHERING_CUSTODIAN
import java.time.Clock
import java.time.LocalDate

/**
 * What the tests of the gathering have in common. The database is shared by every test of the application, and there can be one gathering at
 * most that is not closed: every test starts by clearing them (`reset`) and makes the ones it needs, straight in the table, like a
 * gathering that was made yesterday. Dates are made from the day of the test clock (a fixed date of 2027 would stop being in the future
 * one day): the sentences that need exact dates are tried by the tests of the formatter, with fixed dates.
 *
 * Every title, place and name here is synthetic.
 */
class GatheringSupport(private val dsl: DSLContext, private val clock: Clock) {

    fun today(): LocalDate = LocalDate.now(clock)

    /** No gathering at all: the section is empty and a new one can be made. */
    fun reset() {
        dsl.deleteFrom(GATHERING_CUSTODIAN).execute()
        dsl.deleteFrom(GATHERING).execute()
    }

    /**
     * A gathering made straight in the table. By default a complete draft of three days that starts in a month. Returns its id; the code
     * is the one given or a fixed one that is not used by any other.
     */
    fun make(
        title: String = "Convivenza di settembre 2027",
        start: LocalDate = today().plusDays(30),
        end: LocalDate = start.plusDays(2),
        status: GatheringStatus = GatheringStatus.draft,
        place: String? = "Casa per ritiri di esempio",
        full: Int? = 6000,
        daily: Int? = 2500,
        lunchOnly: Int? = 1000,
        info: String? = null,
        whatToBring: String? = null,
        code: String = nextCode(),
        custodians: List<Long> = emptyList(),
    ): Long {
        val id = dsl.insertInto(GATHERING)
            .set(GATHERING.PUBLIC_CODE, code)
            .set(GATHERING.TITLE, title)
            .set(GATHERING.START_DATE, start)
            .set(GATHERING.END_DATE, end)
            .set(GATHERING.PLACE, place)
            .set(GATHERING.FULL_FEE_CENTS, full)
            .set(GATHERING.DAILY_FEE_CENTS, daily)
            .set(GATHERING.LUNCH_ONLY_FEE_CENTS, lunchOnly)
            .set(GATHERING.INFO_TEXT, info)
            .set(GATHERING.WHAT_TO_BRING, whatToBring)
            .set(GATHERING.STATUS, status)
            .returningResult(GATHERING.ID).fetchSingle().value1()!!
        for (teacher in custodians) {
            dsl.insertInto(GATHERING_CUSTODIAN).set(GATHERING_CUSTODIAN.GATHERING_ID, id).set(GATHERING_CUSTODIAN.TEACHER_ACCOUNT_ID, teacher).execute()
        }
        return id
    }

    fun statusOf(id: Long): GatheringStatus = dsl.select(GATHERING.STATUS).from(GATHERING).where(GATHERING.ID.eq(id)).fetchSingle(GATHERING.STATUS)!!

    fun custodiansOf(id: Long): List<Long> =
        dsl.select(GATHERING_CUSTODIAN.TEACHER_ACCOUNT_ID).from(GATHERING_CUSTODIAN).where(GATHERING_CUSTODIAN.GATHERING_ID.eq(id))
            .orderBy(GATHERING_CUSTODIAN.TEACHER_ACCOUNT_ID).fetch(GATHERING_CUSTODIAN.TEACHER_ACCOUNT_ID).map { it!! }

    companion object {
        private var counter = 0L

        /** A code of 16 characters of the alphabet of the codes, different every time. */
        @Synchronized
        fun nextCode(): String = "test" + "%012d".format(++counter)
    }
}
