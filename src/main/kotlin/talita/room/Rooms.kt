package talita.room

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import talita.gathering.GatheringInfo
import talita.gathering.GatheringReader
import talita.gathering.GatheringState
import talita.gathering.RoomAssignments
import talita.gathering.RoomSummaries
import talita.gathering.RoomSummary
import talita.gathering.isForeignKeyViolation
import talita.gathering.isUniqueViolation
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationAttendance
import talita.jooq.enums.RegistrationDayKind
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.REGISTRATION_DAY
import talita.jooq.tables.references.ROOM
import talita.jooq.tables.references.ROOM_CHILD
import talita.jooq.tables.references.ROOM_TEACHER
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.VENUE
import talita.registration.DayKind
import talita.registration.RegistrationData
import talita.registration.Registrations
import talita.registration.Status
import talita.teacher.TeacherRow
import talita.teacher.Teachers

/** A room of the house: its name and its beds. It is not a person and holds no personal data. */
data class Room(val id: Long, val name: String, val beds: Int)

/** A person that can be in a room: a child, by the number of its registration, or a teacher, by the number of their account. The forms send `b12` and `m7`. */
sealed interface PersonRef {
    val key: String

    data class Child(val registrationId: Long) : PersonRef {
        override val key: String get() = "b$registrationId"
    }

    data class Teacher(val accountId: Long) : PersonRef {
        override val key: String get() = "m$accountId"
    }

    companion object {
        private val format = Regex("""([bm])(\d{1,18})""")

        /** What a form says (`b12`, `m7`), or null for anything else. */
        fun parse(typed: String?): PersonRef? {
            val match = format.matchEntire(typed.orEmpty().trim()) ?: return null
            val id = match.groupValues[2].toLong()
            return if (match.groupValues[1] == "b") Child(id) else Teacher(id)
        }
    }
}

/**
 * Everything the page of the rooms says, read together: the rooms, the people who are in them and those who are not. Only reads; the page decides what to show of it.
 * The children are the registrations of the gathering by surname and name; the teachers all of them, by surname and name.
 */
class RoomBoard(
    val gathering: GatheringInfo,
    val rooms: List<Room>,
    val registrations: List<RegistrationData>,
    val teachers: List<TeacherRow>,
    private val childRoom: Map<Long, Long>,
    private val teacherRoom: Map<Long, Long>,
) {
    val draft: Boolean get() = gathering.state == GatheringState.DRAFT

    val over: Boolean get() = gathering.over

    fun roomOfChild(registrationId: Long): Room? = childRoom[registrationId]?.let { id -> rooms.firstOrNull { it.id == id } }

    fun roomOfTeacher(accountId: Long): Room? = teacherRoom[accountId]?.let { id -> rooms.firstOrNull { it.id == id } }

    /** The children in [room]: surname and name. */
    fun childrenIn(room: Room): List<RegistrationData> = registrations.filter { childRoom[it.id] == room.id && it.status != Status.WITHDRAWN }

    /** The teachers in [room]: surname and name. */
    fun teachersIn(room: Room): List<TeacherRow> = teachers.filter { teacherRoom[it.accountId] == room.id }

    fun people(room: Room): Int = childrenIn(room).size + teachersIn(room).size

    /** More people than beds: the room and the summary say so, and nothing is blocked. The children who are in doubt count. */
    fun overfull(room: Room): Boolean = people(room) > room.beds

    /**
     * The children that are not in a room: the registrations that are not withdrawn and that have at least one whole day, or every day. Whoever comes for lunch only does not
     * sleep there, and is not in the list.
     */
    val freeChildren: List<RegistrationData>
        get() = registrations.filter { it.status != Status.WITHDRAWN && comesAtNight(it) && it.id !in childRoom }

    /** The custodians that are not in a room, by surname and name. */
    val freeCustodians: List<TeacherRow>
        get() = gathering.custodians.mapNotNull { custodian -> teachers.firstOrNull { it.accountId == custodian.accountId } }.filter { it.accountId !in teacherRoom }

    /** Every teacher that is not in a room: the ones that «Aggiungi un maestro» offers. */
    val teachersWithoutRoom: List<TeacherRow> get() = teachers.filter { it.accountId !in teacherRoom }

    val roomCount: Int get() = rooms.size

    val beds: Int get() = rooms.sumOf { it.beds }

    val peopleInRooms: Int get() = rooms.sumOf { people(it) }

    /** Nobody is in any room: what was there was deleted at the end of the gathering, or nobody was put in. */
    val nobodyInRooms: Boolean get() = childRoom.isEmpty() && teacherRoom.isEmpty()

    /**
     * After the end, who slept where was deleted: the gathering is over, it has rooms and nobody is in any of them. With **no rooms** there was nothing to delete, and the pages
     * say that the gathering is over (criterion 13), not that «chi dormiva dove è stato cancellato».
     */
    val peopleDeleted: Boolean get() = over && rooms.isNotEmpty() && nobodyInRooms

    companion object {
        /** The child sleeps there: it has every day, or at least one whole day (and not only lunch). */
        fun comesAtNight(registration: RegistrationData): Boolean =
            registration.presence.allDays || registration.presence.days.values.any { it == DayKind.FULL }
    }
}

/**
 * What came of writing a room of a venue (TALITA-20). The rooms of a venue change at any moment, also for a gathering that is over: who sleeps where is what the end of a gathering closes, and it is
 * deleted by then.
 */
sealed interface SaveResult {
    data class Saved(val room: Room) : SaveResult

    /** The name is the one of another room of the venue (whatever the capitals and the spaces at the sides). */
    data object NameTaken : SaveResult

    /** No such venue, or no such room in it. */
    data object NotFound : SaveResult
}

/** What came of taking a room away. */
sealed interface RemoveResult {
    /** [people] were in it, in [gatherings] gatherings that are not over, and are now without a room. */
    data class Removed(val name: String, val people: Int, val gatherings: Int) : RemoveResult

    /** It was gone already (another teacher took it away a moment before): the same answer, never an error. */
    data object AlreadyGone : RemoveResult

    data object NotFound : RemoveResult
}

/** Who is in a room of a venue, in the gatherings that are not over: how many people, and in how many gatherings (the dialog of «Togli» says it). */
data class RoomPeople(val people: Int, val gatherings: Int)

/** What came of putting a person in a room, or taking them out. [name] is the one of the person. */
sealed interface PlaceResult {
    /** The person is in [room] now. [before] is where they were (null if nowhere), and it may be [room] itself. */
    data class Placed(val name: String, val room: Room, val before: Room?) : PlaceResult

    /** The person is in no room now. [before] is where they were, null when they were in none already. */
    data class Cleared(val name: String, val before: Room?) : PlaceResult

    /** The registration was withdrawn meanwhile: it cannot have a room (the withdrawal took it out of one). */
    data class Withdrawn(val name: String) : PlaceResult

    data object Over : PlaceResult

    /** The room is a room of another venue than the one of the gathering (the page was open when the venue of the gathering changed): nothing was done, and the answer is the page as it is now. */
    data object OtherVenue : PlaceResult

    /** No such gathering, registration, teacher or room in it. */
    data object NotFound : PlaceResult
}

/**
 * The rooms of the house and who sleeps where (TALITA-11, and TALITA-20: the rooms are the venue's).
 *
 * - **The rooms are written by venue** (`create`, `update`, `remove`), at any moment: a room is the house, and every gathering that is held there finds it. A room that is taken away is
 *   locked for update first, and leaves its people without a room (the keys of the tables cascade): the people are counted in the same transaction.
 * - **Who sleeps where is written by gathering, and every such change is in a transaction that first locks the gathering for sharing** (`select … for share`, as the registrations do), and its
 *   venue after it: the change of the dates, of the venue and the closing wait for it, and it finds them. «Over» is checked there, with the clock of the application: from the day after the last
 *   day nothing changes (409). A room is chosen among the rooms of the venue **of the gathering at that moment**: a room of another venue is refused (`PlaceResult.OtherVenue`), and the database refuses it too.
 * - **A child is put in a room under the lock of its registration** (`for share`): the withdrawal takes the same row `for update`, in the same transaction that takes the
 *   child out of its room ([talita.registration.WithdrawalListener]), so the choice either happens before the withdrawal and goes with it, or finds it and is refused.
 * - **A person is in one room only** (the key of the table), and a choice is `insert … on conflict do update`: two teachers who put the same child in two rooms at the same
 *   moment leave one row, and nobody gets an error. The answer says where the person is, and where they were.
 * - **Nothing here writes a name in the log**: the log is for numbers and ids, and this class does not log at all.
 */
@Component
class Rooms(
    private val dsl: DSLContext,
    private val gatherings: GatheringReader,
    private val registrations: Registrations,
    private val teachers: Teachers,
) : RoomSummaries, RoomAssignments {

    // ---- Reading -------------------------------------------------------------------------------------------------------------------

    /** The rooms of the venue [venueId] in the order they were written. */
    fun rooms(venueId: Long): List<Room> =
        dsl.select(ROOM.ID, ROOM.NAME, ROOM.BEDS).from(ROOM).where(ROOM.VENUE_ID.eq(venueId)).orderBy(ROOM.ID).fetch { Room(it[ROOM.ID]!!, it[ROOM.NAME]!!, it[ROOM.BEDS]!!.toInt()) }

    /** Whether a room with this id is there, of whatever venue (a page that was open when the venue of a gathering changed asks for a room of the venue before: `409`, not `404`). */
    fun exists(roomId: Long): Boolean = dsl.fetchExists(ROOM, ROOM.ID.eq(roomId))

    /** The room [roomId] of the venue [venueId], or null: a room of another venue is not found. */
    fun find(venueId: Long, roomId: Long): Room? =
        dsl.select(ROOM.ID, ROOM.NAME, ROOM.BEDS).from(ROOM).where(ROOM.ID.eq(roomId)).and(ROOM.VENUE_ID.eq(venueId))
            .fetchOne { Room(it[ROOM.ID]!!, it[ROOM.NAME]!!, it[ROOM.BEDS]!!.toInt()) }

    /** The board of the gathering, or null if there is none: the rooms are those of its venue (none, if it has none). Draft gatherings have no registrations (R1 of TALITA-4), so no children. */
    fun board(gatheringId: Long): RoomBoard? {
        val gathering = gatherings.byId(gatheringId) ?: return null
        val childRoom = dsl.select(ROOM_CHILD.REGISTRATION_ID, ROOM_CHILD.ROOM_ID).from(ROOM_CHILD).where(ROOM_CHILD.GATHERING_ID.eq(gatheringId))
            .fetch().associate { it[ROOM_CHILD.REGISTRATION_ID]!! to it[ROOM_CHILD.ROOM_ID]!! }
        val teacherRoom = dsl.select(ROOM_TEACHER.TEACHER_ACCOUNT_ID, ROOM_TEACHER.ROOM_ID).from(ROOM_TEACHER).where(ROOM_TEACHER.GATHERING_ID.eq(gatheringId))
            .fetch().associate { it[ROOM_TEACHER.TEACHER_ACCOUNT_ID]!! to it[ROOM_TEACHER.ROOM_ID]!! }
        val rooms = gathering.venueId?.let(::rooms).orEmpty()
        return RoomBoard(gathering, rooms, registrations.ofGathering(gatheringId), teachers.all(), childRoom, teacherRoom)
    }

    /**
     * Who is in each room of the venue [venueId], in the gatherings that are not over (a draft, or whose last day has not passed): the people that «Togli» of a room would leave without a room, and in how many
     * gatherings. A gathering that is over has no one left in its rooms but for the hours between midnight and the job that deletes them: those are not counted, and go with the room all the same.
     */
    fun people(venueId: Long): Map<Long, RoomPeople> {
        val notOver = GATHERING.STATUS.eq(GatheringStatus.draft).or(GATHERING.END_DATE.ge(gatherings.today()))
        val children = dsl.select(ROOM_CHILD.ROOM_ID, ROOM_CHILD.GATHERING_ID).from(ROOM_CHILD).join(GATHERING).on(GATHERING.ID.eq(ROOM_CHILD.GATHERING_ID))
            .join(REGISTRATION).on(REGISTRATION.ID.eq(ROOM_CHILD.REGISTRATION_ID))
            .where(ROOM_CHILD.VENUE_ID.eq(venueId)).and(REGISTRATION.STATUS.ne(RegistrationStatus.withdrawn)).and(notOver).fetch().map { it[ROOM_CHILD.ROOM_ID]!! to it[ROOM_CHILD.GATHERING_ID]!! }
        val teachers = dsl.select(ROOM_TEACHER.ROOM_ID, ROOM_TEACHER.GATHERING_ID).from(ROOM_TEACHER).join(GATHERING).on(GATHERING.ID.eq(ROOM_TEACHER.GATHERING_ID))
            .where(ROOM_TEACHER.VENUE_ID.eq(venueId)).and(notOver).fetch().map { it[ROOM_TEACHER.ROOM_ID]!! to it[ROOM_TEACHER.GATHERING_ID]!! }
        return (children + teachers).groupBy({ it.first }, { it.second }).mapValues { RoomPeople(it.value.size, it.value.toSet().size) }
    }

    override fun summary(gatheringId: Long): RoomSummary {
        val gathering = gatherings.byId(gatheringId)
        val rooms = gathering?.venueId?.let(::rooms).orEmpty()
        val beds = rooms.sumOf { it.beds }
        val venue = gathering?.venueId
        val name = gathering?.venueName
        // After the end nobody is counted: who was in a room is deleted by then (or is about to be), and the card says only the house.
        if (gathering == null || gathering.over || gathering.state == GatheringState.DRAFT) return RoomSummary(rooms.size, beds, null, venue, name)
        val children = nightChildren(gatheringId)
        // Nobody to place (no registration yet): the card does not say that «everybody has a room».
        val unassigned = if (children.first == 0) null else children.second
        return RoomSummary(rooms.size, beds, unassigned, venue, name)
    }

    /**
     * How many children sleep there, and how many of them are not in a room: the same rule as [RoomBoard.freeChildren] (not withdrawn, every day or at least a whole day), counted by the
     * database because the card of the gathering reads no names.
     */
    private fun nightChildren(gatheringId: Long): Pair<Int, Int> {
        val sleeping = REGISTRATION.GATHERING_ID.eq(gatheringId).and(REGISTRATION.STATUS.ne(RegistrationStatus.withdrawn)).and(
            REGISTRATION.ATTENDANCE.eq(RegistrationAttendance.all_days)
                .or(DSL.exists(DSL.selectOne().from(REGISTRATION_DAY).where(REGISTRATION_DAY.REGISTRATION_ID.eq(REGISTRATION.ID)).and(REGISTRATION_DAY.KIND.eq(RegistrationDayKind.full)))),
        )
        val all = dsl.selectCount().from(REGISTRATION).where(sleeping).fetchSingle().value1()
        val free = dsl.selectCount().from(REGISTRATION).where(sleeping).andNotExists(DSL.selectOne().from(ROOM_CHILD).where(ROOM_CHILD.REGISTRATION_ID.eq(REGISTRATION.ID))).fetchSingle().value1()
        return all to free
    }

    // ---- Writing: who sleeps where of a gathering, when its venue changes ------------------------------------------------------------

    override fun peopleIn(context: DSLContext, gatheringId: Long): Int =
        context.fetchCount(ROOM_CHILD, ROOM_CHILD.GATHERING_ID.eq(gatheringId)) + context.fetchCount(ROOM_TEACHER, ROOM_TEACHER.GATHERING_ID.eq(gatheringId))

    override fun peopleIn(gatheringId: Long): Int = peopleIn(dsl, gatheringId)

    override fun clearAll(context: DSLContext, gatheringId: Long): Int =
        context.deleteFrom(ROOM_CHILD).where(ROOM_CHILD.GATHERING_ID.eq(gatheringId)).execute() + context.deleteFrom(ROOM_TEACHER).where(ROOM_TEACHER.GATHERING_ID.eq(gatheringId)).execute()

    // ---- Writing: the rooms of a venue ---------------------------------------------------------------------------------------------

    /** The gathering locked for sharing inside [tx], with its venue after it: null if there is none. A change made after the last day is refused by the caller (see [over]). */
    private fun shared(tx: DSLContext, gatheringId: Long): GatheringInfo? = gatherings.sharedIn(tx, gatheringId)

    /** The venue locked for sharing inside [tx]: a venue that is being taken away is not found, and one that is found is not taken away until this ends. */
    private fun venueShared(tx: DSLContext, venueId: Long): Boolean = tx.select(VENUE.ID).from(VENUE).where(VENUE.ID.eq(venueId)).forShare().fetchOne() != null

    /** A new room [name] of [beds] beds in the venue (already checked by [RoomChecks]). */
    fun create(venueId: Long, name: String, beds: Int): SaveResult = guarded {
        dsl.transactionResult { configuration ->
            val tx = DSL.using(configuration)
            if (!venueShared(tx, venueId)) return@transactionResult SaveResult.NotFound
            val id = tx.insertInto(ROOM).set(ROOM.VENUE_ID, venueId).set(ROOM.NAME, name).set(ROOM.BEDS, beds.toShort())
                .onConflictDoNothing().returningResult(ROOM.ID).fetchOne()?.value1()
                ?: return@transactionResult SaveResult.NameTaken
            SaveResult.Saved(Room(id, name, beds))
        }
    }

    /** The room [roomId] has a new name and new beds. Lowering the beds under the people is allowed: the page of the gathering says so, nothing is refused. */
    fun update(venueId: Long, roomId: Long, name: String, beds: Int): SaveResult = guarded {
        dsl.transactionResult { configuration ->
            val tx = DSL.using(configuration)
            tx.select(ROOM.ID).from(ROOM).where(ROOM.ID.eq(roomId)).and(ROOM.VENUE_ID.eq(venueId)).forUpdate().fetchOne() ?: return@transactionResult SaveResult.NotFound
            val taken = tx.fetchExists(ROOM, ROOM.VENUE_ID.eq(venueId).and(ROOM.ID.ne(roomId)).and(DSL.lower(DSL.trim(ROOM.NAME)).eq(name.trim().lowercase())))
            if (taken) return@transactionResult SaveResult.NameTaken
            tx.update(ROOM).set(ROOM.NAME, name).set(ROOM.BEDS, beds.toShort()).where(ROOM.ID.eq(roomId)).execute()
            SaveResult.Saved(Room(roomId, name, beds))
        }
    }

    /** Two teachers who write the same name at the same moment: the unique key of the database says no to the second, and that is the same as the name being taken. */
    private fun guarded(save: () -> SaveResult): SaveResult = try {
        save()
    } catch (e: RuntimeException) {
        when {
            e.isUniqueViolation() -> SaveResult.NameTaken
            // The venue was taken away between the lock and the insert: it cannot happen with the lock, and a key that says so is a venue that is not there.
            e.isForeignKeyViolation() -> SaveResult.NotFound
            else -> throw e
        }
    }

    /**
     * Takes the room away; the people in it are without a room (the keys of the tables cascade), in every gathering. [RemoveResult.Removed] says how many they were in the gatherings that are not over (the
     * number that the dialog said, or more, if somebody was put in meanwhile: the room is locked for update first, and a choice of a room locks it for sharing).
     */
    fun remove(venueId: Long, roomId: Long): RemoveResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        val row = tx.select(ROOM.NAME).from(ROOM).where(ROOM.ID.eq(roomId)).and(ROOM.VENUE_ID.eq(venueId)).forUpdate().fetchOne()
            ?: return@transactionResult if (wasHere(tx, roomId)) RemoveResult.AlreadyGone else RemoveResult.NotFound
        val notOver = GATHERING.STATUS.eq(GatheringStatus.draft).or(GATHERING.END_DATE.ge(gatherings.today()))
        val children = tx.select(ROOM_CHILD.GATHERING_ID).from(ROOM_CHILD).join(GATHERING).on(GATHERING.ID.eq(ROOM_CHILD.GATHERING_ID)).join(REGISTRATION).on(REGISTRATION.ID.eq(ROOM_CHILD.REGISTRATION_ID))
            .where(ROOM_CHILD.ROOM_ID.eq(roomId)).and(REGISTRATION.STATUS.ne(RegistrationStatus.withdrawn)).and(notOver).fetch(ROOM_CHILD.GATHERING_ID)
        val teachers = tx.select(ROOM_TEACHER.GATHERING_ID).from(ROOM_TEACHER).join(GATHERING).on(GATHERING.ID.eq(ROOM_TEACHER.GATHERING_ID)).where(ROOM_TEACHER.ROOM_ID.eq(roomId)).and(notOver).fetch(ROOM_TEACHER.GATHERING_ID)
        tx.deleteFrom(ROOM).where(ROOM.ID.eq(roomId)).execute()
        RemoveResult.Removed(row[ROOM.NAME]!!, children.size + teachers.size, (children + teachers).toSet().size)
    }

    /**
     * A room that is not in the venue was taken away a moment ago (by another teacher: the answer is the state as it is), and not an id that was never there or that is of another
     * venue (404). The ids are those of an identity column and are never used again, so a room that existed and is nowhere has a number that the column has already given.
     */
    private fun wasHere(tx: DSLContext, roomId: Long): Boolean {
        if (tx.fetchExists(ROOM, ROOM.ID.eq(roomId))) return false
        val issued = tx.fetchOne("select case when is_called then last_value else 0 end from room_id_seq")?.get(0, Long::class.java) ?: 0L
        return roomId <= issued
    }

    // ---- Writing: who sleeps where -------------------------------------------------------------------------------------------------

    /** Puts [person] in the room [roomId] of the gathering: wherever they were, they are here now. */
    fun place(gatheringId: Long, person: PersonRef, roomId: Long): PlaceResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        when (person) {
            is PersonRef.Child -> placeChild(tx, gatheringId, person.registrationId, roomId)
            is PersonRef.Teacher -> placeTeacher(tx, gatheringId, person.accountId, roomId)
        }
    }

    /** The room [roomId] among the rooms of the venue [venueId], locked for sharing; null if there is none. */
    private fun roomIn(tx: DSLContext, venueId: Long?, roomId: Long): Room? =
        if (venueId == null) null else tx.select(ROOM.ID, ROOM.NAME, ROOM.BEDS).from(ROOM).where(ROOM.ID.eq(roomId)).and(ROOM.VENUE_ID.eq(venueId)).forShare()
            .fetchOne { Room(it[ROOM.ID]!!, it[ROOM.NAME]!!, it[ROOM.BEDS]!!.toInt()) }

    /** The answer for a room that is not among the rooms of the venue of the gathering: a room of another venue (the page is old) or a room that is not there. */
    private fun notInVenue(tx: DSLContext, roomId: Long): PlaceResult = if (tx.fetchExists(ROOM, ROOM.ID.eq(roomId))) PlaceResult.OtherVenue else PlaceResult.NotFound

    private fun nameOfRoom(tx: DSLContext, roomId: Long?): Room? =
        roomId?.let { id -> tx.select(ROOM.ID, ROOM.NAME, ROOM.BEDS).from(ROOM).where(ROOM.ID.eq(id)).fetchOne { Room(it[ROOM.ID]!!, it[ROOM.NAME]!!, it[ROOM.BEDS]!!.toInt()) } }

    private fun placeChild(tx: DSLContext, gatheringId: Long, registrationId: Long, roomId: Long): PlaceResult {
        // The registration first, locked for sharing: a withdrawal takes it for update (and the child out of the room in the same transaction), so it waits for this
        // choice to be written, or it has been made already and is found here. Then the gathering, in the order of every other change of a registration.
        val registration = tx.select(REGISTRATION.STATUS, CHILD.FIRST_NAME, CHILD.LAST_NAME).from(REGISTRATION).join(CHILD).on(CHILD.ID.eq(REGISTRATION.CHILD_ID))
            .where(REGISTRATION.ID.eq(registrationId)).and(REGISTRATION.GATHERING_ID.eq(gatheringId)).forShare().of(REGISTRATION).fetchOne() ?: return PlaceResult.NotFound
        val name = "${registration[CHILD.FIRST_NAME]} ${registration[CHILD.LAST_NAME]}"
        val gathering = shared(tx, gatheringId) ?: return PlaceResult.NotFound
        if (gathering.over) return PlaceResult.Over
        if (registration[REGISTRATION.STATUS] == RegistrationStatus.withdrawn) return PlaceResult.Withdrawn(name)
        val room = roomIn(tx, gathering.venueId, roomId) ?: return notInVenue(tx, roomId)
        val before = tx.select(ROOM_CHILD.ROOM_ID).from(ROOM_CHILD).where(ROOM_CHILD.REGISTRATION_ID.eq(registrationId)).forUpdate().fetchOne(ROOM_CHILD.ROOM_ID)
        tx.insertInto(ROOM_CHILD).set(ROOM_CHILD.REGISTRATION_ID, registrationId).set(ROOM_CHILD.GATHERING_ID, gatheringId).set(ROOM_CHILD.VENUE_ID, gathering.venueId!!).set(ROOM_CHILD.ROOM_ID, room.id)
            .onConflict(ROOM_CHILD.REGISTRATION_ID).doUpdate().set(ROOM_CHILD.ROOM_ID, room.id).execute()
        return PlaceResult.Placed(name, room, nameOfRoom(tx, before))
    }

    private fun teacherName(tx: DSLContext, accountId: Long): String? =
        tx.select(TEACHER.FIRST_NAME, TEACHER.LAST_NAME).from(TEACHER).where(TEACHER.ACCOUNT_ID.eq(accountId)).forShare().fetchOne { "${it[TEACHER.FIRST_NAME]} ${it[TEACHER.LAST_NAME]}" }

    private fun placeTeacher(tx: DSLContext, gatheringId: Long, accountId: Long, roomId: Long): PlaceResult {
        val gathering = shared(tx, gatheringId) ?: return PlaceResult.NotFound
        if (gathering.over) return PlaceResult.Over
        // The teacher is locked for sharing: «Togli» takes the row to delete it, so a teacher who is taken away is either not found here or goes with the row.
        val name = teacherName(tx, accountId) ?: return PlaceResult.NotFound
        val room = roomIn(tx, gathering.venueId, roomId) ?: return notInVenue(tx, roomId)
        val before = tx.select(ROOM_TEACHER.ROOM_ID).from(ROOM_TEACHER).where(ROOM_TEACHER.GATHERING_ID.eq(gatheringId)).and(ROOM_TEACHER.TEACHER_ACCOUNT_ID.eq(accountId)).forUpdate()
            .fetchOne(ROOM_TEACHER.ROOM_ID)
        tx.insertInto(ROOM_TEACHER).set(ROOM_TEACHER.GATHERING_ID, gatheringId).set(ROOM_TEACHER.VENUE_ID, gathering.venueId!!).set(ROOM_TEACHER.TEACHER_ACCOUNT_ID, accountId).set(ROOM_TEACHER.ROOM_ID, room.id)
            .onConflict(ROOM_TEACHER.GATHERING_ID, ROOM_TEACHER.TEACHER_ACCOUNT_ID).doUpdate().set(ROOM_TEACHER.ROOM_ID, room.id).execute()
        return PlaceResult.Placed(name, room, nameOfRoom(tx, before))
    }

    /** Takes [person] out of their room. Taking out who is out already is the same answer, with nothing to say about the room they were in. */
    fun clear(gatheringId: Long, person: PersonRef): PlaceResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        val gathering = shared(tx, gatheringId) ?: return@transactionResult PlaceResult.NotFound
        if (gathering.over) return@transactionResult PlaceResult.Over
        when (person) {
            is PersonRef.Child -> {
                val registration = tx.select(CHILD.FIRST_NAME, CHILD.LAST_NAME).from(REGISTRATION).join(CHILD).on(CHILD.ID.eq(REGISTRATION.CHILD_ID))
                    .where(REGISTRATION.ID.eq(person.registrationId)).and(REGISTRATION.GATHERING_ID.eq(gatheringId)).fetchOne() ?: return@transactionResult PlaceResult.NotFound
                val was = tx.select(ROOM_CHILD.ROOM_ID).from(ROOM_CHILD).where(ROOM_CHILD.REGISTRATION_ID.eq(person.registrationId)).forUpdate().fetchOne(ROOM_CHILD.ROOM_ID)
                val before = nameOfRoom(tx, was)
                tx.deleteFrom(ROOM_CHILD).where(ROOM_CHILD.REGISTRATION_ID.eq(person.registrationId)).execute()
                PlaceResult.Cleared("${registration[CHILD.FIRST_NAME]} ${registration[CHILD.LAST_NAME]}", before)
            }

            is PersonRef.Teacher -> {
                val name = teacherName(tx, person.accountId) ?: return@transactionResult PlaceResult.NotFound
                val was = tx.select(ROOM_TEACHER.ROOM_ID).from(ROOM_TEACHER).where(ROOM_TEACHER.GATHERING_ID.eq(gatheringId)).and(ROOM_TEACHER.TEACHER_ACCOUNT_ID.eq(person.accountId))
                    .forUpdate().fetchOne(ROOM_TEACHER.ROOM_ID)
                val before = nameOfRoom(tx, was)
                tx.deleteFrom(ROOM_TEACHER).where(ROOM_TEACHER.GATHERING_ID.eq(gatheringId)).and(ROOM_TEACHER.TEACHER_ACCOUNT_ID.eq(person.accountId)).execute()
                PlaceResult.Cleared(name, before)
            }
        }
    }
}
