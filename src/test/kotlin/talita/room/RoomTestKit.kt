package talita.room

import org.jooq.DSLContext
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.checkin.CheckinSetup
import talita.checkin.CheckinTestKit
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.ROOM
import talita.jooq.tables.references.ROOM_CHILD
import talita.jooq.tables.references.ROOM_TEACHER
import java.time.Clock

/**
 * What the tests of the rooms have in common (TALITA-11): the gathering and its teachers of the tests of the arrivals (Marta Bianchi and Paolo Verdi are the custodians, Giulia Serra
 * is a teacher who is not), the registrations made straight in the tables, and the rooms and who is in them written as the database holds them. Names, rooms and numbers are synthetic.
 */
internal class RoomTestKit(mockMvc: MockMvc, tokens: OneTimeTokenService, private val dsl: DSLContext, clock: Clock) {

    val checkin = CheckinTestKit(mockMvc, tokens, dsl, clock)
    val rows get() = checkin.rows

    /** A gathering of three days (yesterday, today, tomorrow), open, with the three teachers: the one of the tests of the arrivals. */
    fun start(first: Long = -1, last: Long = 1, status: GatheringStatus = GatheringStatus.`open`): CheckinSetup = checkin.start(first, last, status)

    /** The gathering ended [days] days ago (and started two days before that), whatever its state was. */
    fun endedDaysAgo(gathering: Long, days: Long) {
        val today = checkin.today()
        dsl.update(GATHERING).set(GATHERING.START_DATE, today.minusDays(days + 2)).set(GATHERING.END_DATE, today.minusDays(days)).where(GATHERING.ID.eq(gathering)).execute()
    }

    /** Another gathering, closed (there is at most one that is not): the next one or the one before, with the same custodians as nobody. Its id. */
    fun otherGathering(title: String = "La convivenza dopo", daysFromToday: Long = 60): Long =
        checkin.kit.gatherings.make(title = title, start = checkin.today().plusDays(daysFromToday), status = GatheringStatus.closed)

    // ---- The tables -------------------------------------------------------------------------------------------------------------------

    /** A room made straight in the table: its id. */
    fun room(gathering: Long, name: String, beds: Int): Long =
        dsl.insertInto(ROOM).set(ROOM.GATHERING_ID, gathering).set(ROOM.NAME, name).set(ROOM.BEDS, beds.toShort()).returningResult(ROOM.ID).fetchSingle().value1()!!

    /** The rooms of a gathering as name and beds, in the order they were written. */
    fun roomsOf(gathering: Long): List<Pair<String, Int>> =
        dsl.select(ROOM.NAME, ROOM.BEDS).from(ROOM).where(ROOM.GATHERING_ID.eq(gathering)).orderBy(ROOM.ID).fetch { it[ROOM.NAME]!! to it[ROOM.BEDS]!!.toInt() }

    /** The id of the room with this name in the gathering. */
    fun roomId(gathering: Long, name: String): Long = dsl.select(ROOM.ID).from(ROOM).where(ROOM.GATHERING_ID.eq(gathering)).and(ROOM.NAME.eq(name)).fetchSingle(ROOM.ID)!!

    /** A child in a room, as the table keeps it. */
    fun put(registration: Long, gathering: Long, room: Long) {
        dsl.insertInto(ROOM_CHILD).set(ROOM_CHILD.REGISTRATION_ID, registration).set(ROOM_CHILD.GATHERING_ID, gathering).set(ROOM_CHILD.ROOM_ID, room).execute()
    }

    /** A teacher in a room, as the table keeps it. */
    fun putTeacher(gathering: Long, account: Long, room: Long) {
        dsl.insertInto(ROOM_TEACHER).set(ROOM_TEACHER.GATHERING_ID, gathering).set(ROOM_TEACHER.TEACHER_ACCOUNT_ID, account).set(ROOM_TEACHER.ROOM_ID, room).execute()
    }

    fun roomOfChild(registration: Long): Long? = dsl.select(ROOM_CHILD.ROOM_ID).from(ROOM_CHILD).where(ROOM_CHILD.REGISTRATION_ID.eq(registration)).fetchOne(ROOM_CHILD.ROOM_ID)

    fun roomOfTeacher(gathering: Long, account: Long): Long? =
        dsl.select(ROOM_TEACHER.ROOM_ID).from(ROOM_TEACHER).where(ROOM_TEACHER.GATHERING_ID.eq(gathering)).and(ROOM_TEACHER.TEACHER_ACCOUNT_ID.eq(account)).fetchOne(ROOM_TEACHER.ROOM_ID)

    fun roomCount(gathering: Long): Int = dsl.fetchCount(ROOM, ROOM.GATHERING_ID.eq(gathering))

    fun peopleCount(gathering: Long): Int = dsl.fetchCount(ROOM_CHILD, ROOM_CHILD.GATHERING_ID.eq(gathering)) + dsl.fetchCount(ROOM_TEACHER, ROOM_TEACHER.GATHERING_ID.eq(gathering))

    /** What the tables of the rooms hold, in one value: if nothing was changed it is the same after the attempts. */
    fun state(): List<Any?> = listOf(
        dsl.select(ROOM.ID, ROOM.GATHERING_ID, ROOM.NAME, ROOM.BEDS).from(ROOM).orderBy(ROOM.ID).fetch().map { it.intoList() },
        dsl.select(ROOM_CHILD.REGISTRATION_ID, ROOM_CHILD.ROOM_ID).from(ROOM_CHILD).orderBy(ROOM_CHILD.REGISTRATION_ID).fetch().map { it.intoList() },
        dsl.select(ROOM_TEACHER.TEACHER_ACCOUNT_ID, ROOM_TEACHER.ROOM_ID).from(ROOM_TEACHER).orderBy(ROOM_TEACHER.TEACHER_ACCOUNT_ID).fetch().map { it.intoList() },
    )
}
