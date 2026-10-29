package talita.room

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.TestcontainersConfiguration
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.ROOM_CHILD
import talita.registration.ChangeResult
import talita.registration.RestoreResult
import talita.registration.Registrations
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules of the rooms on a Postgres that is real (criteria 2, 4, 5, 7-13): what is written and what is refused, who is in the list of those who have no room, the two teachers at
 * the same moment (two transactions) and the withdrawal that takes the child out of its room. Names, rooms and numbers are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RoomServiceTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val rooms: Rooms,
    @Autowired private val registrations: Registrations,
) {

    private val kit = RoomTestKit(mockMvc, tokens, dsl, clock)

    private fun child(gathering: Long, first: String, last: String, days: Map<java.time.LocalDate, String>? = null, status: RegistrationStatus = RegistrationStatus.confirmed): Long =
        kit.rows.register(gathering, kit.checkin.account(), first, last, days = days, status = status)

    // ---- A room: written, changed, taken away, copied (criteria 2, 4, 5) ------------------------------------------------------------------

    @Test
    fun `t11 c02 a room is written with its name and beds, and a name already used, whatever its capitals and spaces, is refused and nothing is written`() {
        val setup = kit.start()

        val saved = assertIs<SaveResult.Saved>(rooms.create(setup.id, "Camera 1", 4))
        assertEquals(Room(saved.room.id, "Camera 1", 4), rooms.find(setup.id, saved.room.id))
        assertEquals(SaveResult.NameTaken, rooms.create(setup.id, "camera 1", 2))
        assertEquals(SaveResult.NameTaken, rooms.create(setup.id, "  CAMERA 1  ", 2))
        assertEquals(1, kit.roomCount(setup.id))

        val second = assertIs<SaveResult.Saved>(rooms.create(setup.id, "Stanza azzurra", 2))
        // A change keeps its own name (also with other capitals) and takes none of another room.
        assertIs<SaveResult.Saved>(rooms.update(setup.id, saved.room.id, "CAMERA 1", 6))
        assertEquals(Room(saved.room.id, "CAMERA 1", 6), rooms.find(setup.id, saved.room.id))
        assertEquals(SaveResult.NameTaken, rooms.update(setup.id, second.room.id, "camera 1", 2))
        assertEquals(Room(second.room.id, "Stanza azzurra", 2), rooms.find(setup.id, second.room.id))
        // A room of another gathering, or one that is not there, or a gathering that is not there: not found.
        val other = kit.otherGathering()
        assertEquals(SaveResult.NotFound, rooms.update(other, saved.room.id, "Camera 9", 2))
        assertEquals(SaveResult.NotFound, rooms.update(setup.id, saved.room.id + 100_000, "Camera 9", 2))
        assertEquals(SaveResult.NotFound, rooms.create(setup.id + 100_000, "Camera 9", 2))
        assertNull(rooms.find(other, saved.room.id), "a room is of one gathering")
    }

    @Test
    fun `t11 c02 two teachers who write the same name at the same moment get one room, and the second is told that the name is taken`() {
        val setup = kit.start()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val results = (1..2).map { pool.submit<SaveResult> { start.await(10, TimeUnit.SECONDS); rooms.create(setup.id, "Camera 7", 3) } }
            start.countDown()
            val answers = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, answers.count { it is SaveResult.Saved }, "$answers")
            assertEquals(1, answers.count { it == SaveResult.NameTaken }, "$answers")
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, kit.roomCount(setup.id))
    }

    @Test
    fun `t11 c04 the rooms of the gathering before are copied empty into one that has none, and a second copy changes nothing`() {
        val setup = kit.start()
        val before = kit.otherGathering("Convivenza di settembre 2026", daysFromToday = -400)
        val one = kit.room(before, "Camera 1", 4)
        kit.room(before, "Camera 2", 4)
        kit.room(before, "Stanza azzurra", 2)
        kit.putTeacher(before, setup.paoloId, one)

        val previous = rooms.previous(setup.id)
        assertEquals(PreviousRooms(before, "Convivenza di settembre 2026", 3, 10), previous)

        assertEquals(CopyResult.Copied(3), rooms.copyPrevious(setup.id))
        assertEquals(listOf(Room(0, "Camera 1", 4), Room(0, "Camera 2", 4), Room(0, "Stanza azzurra", 2)), rooms.rooms(setup.id).map { it.copy(id = 0) })
        assertEquals(0, kit.peopleCount(setup.id), "copied without the people")
        assertEquals(1, kit.peopleCount(before), "the rooms of before are as they were")
        // With rooms already there nothing is copied, and a request by hand changes nothing.
        val state = kit.state()
        assertEquals(CopyResult.NotEmpty, rooms.copyPrevious(setup.id))
        assertEquals(state, kit.state())
        assertNull(rooms.previous(before), "the gathering before has nothing before it")
        assertEquals(CopyResult.NotFound, rooms.copyPrevious(setup.id + 100_000))
    }

    @Test
    fun `t11 c04 without a gathering before that has rooms there is nothing to copy, and two copies at the same moment leave one set of rooms`() {
        val setup = kit.start()
        assertNull(rooms.previous(setup.id))
        assertEquals(CopyResult.NothingToCopy, rooms.copyPrevious(setup.id))
        assertEquals(0, kit.roomCount(setup.id))

        val before = kit.otherGathering("Convivenza di settembre 2026", daysFromToday = -400)
        kit.room(before, "Camera 1", 4)
        kit.room(before, "Camera 2", 4)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val results = (1..2).map { pool.submit<CopyResult> { start.await(10, TimeUnit.SECONDS); rooms.copyPrevious(setup.id) } }
            start.countDown()
            results.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(2, kit.roomCount(setup.id), "the names are unique: the second copy has nothing to add")
    }

    @Test
    fun `t11 c05 taking away a room puts who was in it among those without a room, and taking away what is gone is the state as it is`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        val two = kit.room(setup.id, "Camera 2", 4)
        val luca = child(setup.id, "Luca", "Rossi")
        val anna = child(setup.id, "Anna", "Rossi")
        kit.put(luca, setup.id, one)
        kit.put(anna, setup.id, two)
        kit.putTeacher(setup.id, setup.paoloId, one)

        assertEquals(RemoveResult.Removed("Camera 1", 2), rooms.remove(setup.id, one))
        assertNull(kit.roomOfChild(luca))
        assertNull(kit.roomOfTeacher(setup.id, setup.paoloId))
        assertEquals(two, kit.roomOfChild(anna), "the other room is as it was")
        val board = rooms.board(setup.id)!!
        assertTrue(board.freeChildren.any { it.id == luca })
        assertTrue(board.freeCustodians.any { it.accountId == setup.paoloId })
        // Taken away by another teacher a moment ago: the same answer, and nothing changes.
        val elsewhere = kit.room(kit.otherGathering(), "Camera 1", 4)
        val state = kit.state()
        assertEquals(RemoveResult.AlreadyGone, rooms.remove(setup.id, one))
        assertEquals(state, kit.state())
        // A room that was never there, or that is of another gathering, is not found.
        assertEquals(RemoveResult.NotFound, rooms.remove(setup.id, one + 100_000))
        assertEquals(RemoveResult.NotFound, rooms.remove(setup.id, elsewhere))
        assertEquals(RemoveResult.NotFound, rooms.remove(setup.id + 100_000, two))
        assertEquals(state, kit.state())
    }

    // ---- Who sleeps where (criteria 6-10) ---------------------------------------------------------------------------------------------------

    @Test
    fun `t11 c06 who has no room is every child that is not withdrawn and comes for a whole day or for every day, and lunch only is not among them`() {
        val setup = kit.start()
        val luca = child(setup.id, "Luca", "Rossi")
        val sara = child(setup.id, "Sara", "Neri", status = RegistrationStatus.maybe)
        val davide = child(setup.id, "Davide", "Costa", days = mapOf(setup.today to "full", setup.tomorrow to "full"))
        val nicolo = child(setup.id, "Nicolò", "Ferri", days = mapOf(setup.tomorrow to "lunch"))
        val mixed = child(setup.id, "Samuele", "Moretti", days = mapOf(setup.yesterday to "full", setup.tomorrow to "lunch"))
        val chiara = child(setup.id, "Chiara", "Fontana", status = RegistrationStatus.withdrawn)
        val board = rooms.board(setup.id)!!

        assertEquals(listOf("Costa", "Moretti", "Neri", "Rossi"), board.freeChildren.map { it.lastName }, "by surname and name, with the one in doubt")
        assertTrue(board.freeChildren.none { it.id == nicolo || it.id == chiara }, "lunch only and withdrawn are not in the list")
        assertEquals(setOf(luca, sara, davide, mixed), board.freeChildren.map { it.id }.toSet())
        // The custodians have no room either: they are after the children, in their own list.
        assertEquals(listOf("Bianchi", "Verdi"), board.freeCustodians.map { it.name.last })
        assertEquals(listOf("Bianchi", "Serra", "Verdi"), board.teachersWithoutRoom.map { it.name.last }, "any teacher can be added from a room")
    }

    @Test
    fun `t11 c07 a child is put in a room, moved to another or taken out, and only that child changes, who is out is among those without a room`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        val two = kit.room(setup.id, "Camera 2", 4)
        val luca = child(setup.id, "Luca", "Rossi")
        val anna = child(setup.id, "Anna", "Rossi")
        kit.put(anna, setup.id, one)

        val placed = assertIs<PlaceResult.Placed>(rooms.place(setup.id, PersonRef.Child(luca), one))
        assertEquals(Room(one, "Camera 1", 4), placed.room)
        assertNull(placed.before)
        assertEquals(one, kit.roomOfChild(luca))
        val moved = assertIs<PlaceResult.Placed>(rooms.place(setup.id, PersonRef.Child(luca), two))
        assertEquals(Room(one, "Camera 1", 4), moved.before)
        assertEquals(two, kit.roomOfChild(luca))
        assertEquals(one, kit.roomOfChild(anna), "only Luca changed")
        // The same place again is the state as it is, not an error.
        val again = assertIs<PlaceResult.Placed>(rooms.place(setup.id, PersonRef.Child(luca), two))
        assertEquals(again.room, again.before)

        val out = assertIs<PlaceResult.Cleared>(rooms.clear(setup.id, PersonRef.Child(luca)))
        assertEquals("Luca Rossi", out.name)
        assertEquals(Room(two, "Camera 2", 4), out.before)
        assertNull(kit.roomOfChild(luca))
        assertTrue(rooms.board(setup.id)!!.freeChildren.any { it.id == luca })
        // Taken out again: the state as it is, with nobody to say it was in.
        val twice = assertIs<PlaceResult.Cleared>(rooms.clear(setup.id, PersonRef.Child(luca)))
        assertNull(twice.before)
        assertEquals(one, kit.roomOfChild(anna))
    }

    @Test
    fun `t11 c07 a registration or a room of another gathering, or one that is not there, is not found and nothing changes`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        val luca = child(setup.id, "Luca", "Rossi")
        val other = kit.otherGathering()
        val elsewhere = kit.room(other, "Camera 1", 4)
        val stranger = child(other, "Elena", "Marino")
        val state = kit.state()

        assertEquals(PlaceResult.NotFound, rooms.place(setup.id, PersonRef.Child(luca), elsewhere), "a room of another gathering")
        assertEquals(PlaceResult.NotFound, rooms.place(setup.id, PersonRef.Child(luca), one + 100_000))
        assertEquals(PlaceResult.NotFound, rooms.place(setup.id, PersonRef.Child(stranger), one), "a registration of another gathering")
        assertEquals(PlaceResult.NotFound, rooms.place(setup.id, PersonRef.Child(luca + 100_000), one))
        assertEquals(PlaceResult.NotFound, rooms.clear(setup.id, PersonRef.Child(stranger)))
        assertEquals(PlaceResult.NotFound, rooms.place(setup.id + 100_000, PersonRef.Child(luca), one))
        assertEquals(state, kit.state())
    }

    @Test
    fun `t11 c08 a teacher, a custodian or not, is put in a room and taken out, in one room only, and a teacher taken away from the teachers is not in any room`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        val two = kit.room(setup.id, "Stanza azzurra", 2)

        assertIs<PlaceResult.Placed>(rooms.place(setup.id, PersonRef.Teacher(setup.giuliaId), two))
        assertIs<PlaceResult.Placed>(rooms.place(setup.id, PersonRef.Teacher(setup.paoloId), one))
        val moved = assertIs<PlaceResult.Placed>(rooms.place(setup.id, PersonRef.Teacher(setup.paoloId), two))
        assertEquals("Paolo Verdi", moved.name)
        assertEquals(one, moved.before?.id)
        assertEquals(two, kit.roomOfTeacher(setup.id, setup.paoloId))
        val board = rooms.board(setup.id)!!
        assertEquals(listOf("Serra", "Verdi"), board.teachersIn(board.rooms.first { it.id == two }).map { it.name.last }, "by surname")
        assertEquals(listOf("Bianchi"), board.freeCustodians.map { it.name.last }, "the custodian who has no room is after the children")

        assertIs<PlaceResult.Cleared>(rooms.clear(setup.id, PersonRef.Teacher(setup.giuliaId)))
        assertNull(kit.roomOfTeacher(setup.id, setup.giuliaId))
        // A person who is not a teacher is not found; neither is one that was taken away from the teachers.
        val parent = kit.checkin.account()
        assertEquals(PlaceResult.NotFound, rooms.place(setup.id, PersonRef.Teacher(parent), one))
        kit.checkin.removeTeacher(setup.paoloId)
        assertEquals(PlaceResult.NotFound, rooms.place(setup.id, PersonRef.Teacher(setup.paoloId), one))
        assertNull(kit.roomOfTeacher(setup.id, setup.paoloId), "taken away from the teachers, out of the rooms")
    }

    @Test
    fun `t11 c09 more people than beds is allowed and counted, children in doubt included, and lowering the beds under the people is saved`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        for ((first, last) in listOf("Luca" to "Rossi", "Anna" to "Rossi", "Federico" to "Longo")) kit.put(child(setup.id, first, last), setup.id, one)
        kit.put(child(setup.id, "Sara", "Neri", status = RegistrationStatus.maybe), setup.id, one)
        assertEquals(false, rooms.board(setup.id)!!.overfull(rooms.board(setup.id)!!.rooms.single()))

        assertIs<PlaceResult.Placed>(rooms.place(setup.id, PersonRef.Teacher(setup.paoloId), one))
        val board = rooms.board(setup.id)!!
        assertEquals(5, board.people(board.rooms.single()))
        assertTrue(board.overfull(board.rooms.single()), "5 people for 4 beds, the one in doubt counts")
        assertEquals(5, board.peopleInRooms)

        // Lowering the beds of a full room is saved, and the room says so: nothing is refused.
        val full = kit.room(setup.id, "Camera 2", 5)
        kit.put(child(setup.id, "Elena", "Marino"), setup.id, full)
        kit.put(child(setup.id, "Matilde", "Gallo"), setup.id, full)
        assertIs<SaveResult.Saved>(rooms.update(setup.id, full, "Camera 2", 1))
        val after = rooms.board(setup.id)!!
        assertTrue(after.overfull(after.rooms.first { it.id == full }))
    }

    @Test
    fun `t11 c10 two teachers who put the same child in two rooms at the same moment leave it in one, and nobody gets an error`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        val two = kit.room(setup.id, "Camera 2", 4)
        val luca = child(setup.id, "Luca", "Rossi")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            // The first teacher has put Luca in Camera 1 and has not finished: the row is not committed yet.
            val first = pool.submit {
                dsl.transaction { configuration ->
                    val tx = DSL.using(configuration)
                    tx.insertInto(ROOM_CHILD).set(ROOM_CHILD.REGISTRATION_ID, luca).set(ROOM_CHILD.GATHERING_ID, setup.id).set(ROOM_CHILD.ROOM_ID, one).execute()
                    held.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
            }
            held.await(20, TimeUnit.SECONDS)
            // The second chooses Camera 2 for the same child: it waits for the first, and then changes the row that is there.
            val second = pool.submit<PlaceResult> { rooms.place(setup.id, PersonRef.Child(luca), two) }
            Thread.sleep(300)
            assertEquals(false, second.isDone, "the second choice waits for the first")
            release.countDown()
            first.get(20, TimeUnit.SECONDS)
            val result = assertIs<PlaceResult.Placed>(second.get(20, TimeUnit.SECONDS))
            assertEquals(two, result.room.id)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, dsl.fetchCount(ROOM_CHILD, ROOM_CHILD.REGISTRATION_ID.eq(luca)))
        assertEquals(two, kit.roomOfChild(luca))
        // What the answers of both say is where the child is now: the board is read after the write.
        assertEquals("Camera 2", rooms.board(setup.id)!!.roomOfChild(luca)?.name)
    }

    @Test
    fun `t11 c10 two teachers who put the same teacher in two rooms at the same moment leave one row`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        val two = kit.room(setup.id, "Camera 2", 4)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val results = listOf(one, two).map { room -> pool.submit<PlaceResult> { start.await(10, TimeUnit.SECONDS); rooms.place(setup.id, PersonRef.Teacher(setup.giuliaId), room) } }
            start.countDown()
            results.forEach { assertIs<PlaceResult.Placed>(it.get(30, TimeUnit.SECONDS)) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, kit.peopleCount(setup.id))
        assertTrue(kit.roomOfTeacher(setup.id, setup.giuliaId) in listOf(one, two))
    }

    // ---- The withdrawal and the new registrations (criteria 11, 12) -------------------------------------------------------------------------------

    @Test
    fun `t11 c11 withdrawing a registration takes the child out of its room in the same transaction, and a registration that is put back is among those without a room`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        val two = kit.room(setup.id, "Camera 2", 4)
        val sara = child(setup.id, "Sara", "Neri", status = RegistrationStatus.maybe)
        val anna = child(setup.id, "Anna", "Rossi")
        kit.put(sara, setup.id, two)
        kit.put(anna, setup.id, one)

        assertEquals(ChangeResult.Done, registrations.withdraw(sara, null))
        assertNull(kit.roomOfChild(sara))
        assertEquals(one, kit.roomOfChild(anna), "only the child withdrawn leaves its room")
        // Withdrawn, it is in no list; a choice for it is refused with its name and nothing is written.
        val board = rooms.board(setup.id)!!
        assertTrue(board.freeChildren.none { it.id == sara })
        assertEquals(PlaceResult.Withdrawn("Sara Neri"), rooms.place(setup.id, PersonRef.Child(sara), two))
        assertNull(kit.roomOfChild(sara))
        // Put back, it is among those without a room: the withdrawal took the room away for good.
        assertEquals(RestoreResult.Done, registrations.restoreConfirmed(sara))
        assertTrue(rooms.board(setup.id)!!.freeChildren.any { it.id == sara })
        assertNull(kit.roomOfChild(sara))
        // Withdrawing what is withdrawn already is the same answer and changes nothing.
        assertEquals(ChangeResult.Done, registrations.withdraw(sara, null))
        assertEquals(ChangeResult.AlreadyDone, registrations.withdraw(sara, null))
    }

    @Test
    fun `t11 c11 a choice and a withdrawal at the same moment leave the child in no room, whoever is first`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        val sara = child(setup.id, "Sara", "Neri")
        val pool = Executors.newFixedThreadPool(2)
        try {
            // The choice is first: it holds the registration for sharing (and has written the room), and the withdrawal, which takes it for update, waits for it.
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val choosing = pool.submit {
                dsl.transaction { configuration ->
                    val tx = DSL.using(configuration)
                    tx.select(REGISTRATION.ID).from(REGISTRATION).where(REGISTRATION.ID.eq(sara)).forShare().fetch()
                    tx.insertInto(ROOM_CHILD).set(ROOM_CHILD.REGISTRATION_ID, sara).set(ROOM_CHILD.GATHERING_ID, setup.id).set(ROOM_CHILD.ROOM_ID, one).execute()
                    held.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
            }
            held.await(20, TimeUnit.SECONDS)
            val withdrawing = pool.submit<ChangeResult> { registrations.withdraw(sara, null) }
            Thread.sleep(300)
            assertEquals(false, withdrawing.isDone, "the withdrawal waits for the choice, which holds the registration")
            release.countDown()
            choosing.get(20, TimeUnit.SECONDS)
            assertEquals(ChangeResult.Done, withdrawing.get(20, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertNull(kit.roomOfChild(sara), "the room was taken away by the withdrawal")

        // The withdrawal is first and has not finished: the choice waits for the row, then finds the registration withdrawn and writes nothing.
        val anna = child(setup.id, "Anna", "Rossi")
        val pool2 = Executors.newFixedThreadPool(2)
        try {
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val withdrawing = pool2.submit {
                dsl.transaction { configuration ->
                    val tx = DSL.using(configuration)
                    tx.select(REGISTRATION.ID).from(REGISTRATION).where(REGISTRATION.ID.eq(anna)).forUpdate().fetch()
                    tx.update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.withdrawn).where(REGISTRATION.ID.eq(anna)).execute()
                    held.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
            }
            held.await(20, TimeUnit.SECONDS)
            val choosing = pool2.submit<PlaceResult> { rooms.place(setup.id, PersonRef.Child(anna), one) }
            Thread.sleep(300)
            assertEquals(false, choosing.isDone, "the choice waits for the withdrawal")
            release.countDown()
            withdrawing.get(20, TimeUnit.SECONDS)
            assertEquals(PlaceResult.Withdrawn("Anna Rossi"), choosing.get(20, TimeUnit.SECONDS))
        } finally {
            pool2.shutdownNow()
        }
        assertNull(kit.roomOfChild(anna), "the room did not come to be")
    }

    @Test
    fun `t11 c11 a new registration, also from the paper form during the gathering, is among those without a room`() {
        val setup = kit.start()
        kit.room(setup.id, "Camera 1", 4)
        assertEquals(0, rooms.board(setup.id)!!.freeChildren.size)

        child(setup.id, "Luca", "Rossi")
        kit.rows.register(setup.id, null, "Nicolò", "Ferri", enteredBy = setup.martaId)
        val board = rooms.board(setup.id)!!
        assertEquals(listOf("Ferri", "Rossi"), board.freeChildren.map { it.lastName })
        assertEquals(2, rooms.summary(setup.id).unassigned)
    }

    @Test
    fun `t11 c12 a child in a room whose registration becomes lunch only stays in the room, and only the withdrawal takes it out`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        val luca = child(setup.id, "Luca", "Rossi")
        kit.put(luca, setup.id, one)

        // The registration changes: lunch only on one day (the days of a registration of some days).
        dsl.update(REGISTRATION).set(REGISTRATION.ATTENDANCE, talita.jooq.enums.RegistrationAttendance.days).where(REGISTRATION.ID.eq(luca)).execute()
        dsl.insertInto(talita.jooq.tables.references.REGISTRATION_DAY).set(talita.jooq.tables.references.REGISTRATION_DAY.REGISTRATION_ID, luca)
            .set(talita.jooq.tables.references.REGISTRATION_DAY.DAY, setup.today).set(talita.jooq.tables.references.REGISTRATION_DAY.KIND, talita.jooq.enums.RegistrationDayKind.lunch).execute()

        val board = rooms.board(setup.id)!!
        assertEquals(one, kit.roomOfChild(luca))
        assertEquals(listOf(luca), board.childrenIn(board.rooms.single()).map { it.id }, "he is in the room, and the page shows what he comes for")
        assertTrue(board.freeChildren.isEmpty())
        assertEquals(ChangeResult.Done, registrations.withdraw(luca, null))
        assertNull(kit.roomOfChild(luca))
    }

    // ---- The states (criterion 13) -----------------------------------------------------------------------------------------------------------

    @Test
    fun `t11 c13 in a draft the rooms are written and copied and the teachers are put in them, but there are no children`() {
        val setup = kit.start(first = 30, last = 32, status = GatheringStatus.draft)
        assertEquals(CopyResult.NothingToCopy, rooms.copyPrevious(setup.id))
        val saved = assertIs<SaveResult.Saved>(rooms.create(setup.id, "Camera 1", 4))
        assertIs<SaveResult.Saved>(rooms.update(setup.id, saved.room.id, "Camera 1", 5))
        assertIs<PlaceResult.Placed>(rooms.place(setup.id, PersonRef.Teacher(setup.paoloId), saved.room.id))
        assertEquals(PlaceResult.NotFound, rooms.place(setup.id, PersonRef.Child(1), saved.room.id), "a draft has no registration")
        val board = rooms.board(setup.id)!!
        assertTrue(board.draft && !board.over)
        assertTrue(board.registrations.isEmpty() && board.freeChildren.isEmpty())
        assertNull(rooms.summary(setup.id).unassigned, "a draft has no children to count")
        assertEquals(CopyResult.NotEmpty, rooms.copyPrevious(setup.id))
        assertEquals(RemoveResult.Removed("Camera 1", 1), rooms.remove(setup.id, saved.room.id))
    }

    @Test
    fun `t11 c13 with the registrations closed everything still changes until the last day, as with them open`() {
        val setup = kit.start(status = GatheringStatus.closed)
        val luca = child(setup.id, "Luca", "Rossi")

        val saved = assertIs<SaveResult.Saved>(rooms.create(setup.id, "Camera 1", 4))
        assertIs<PlaceResult.Placed>(rooms.place(setup.id, PersonRef.Child(luca), saved.room.id))
        assertIs<PlaceResult.Placed>(rooms.place(setup.id, PersonRef.Teacher(setup.giuliaId), saved.room.id))
        assertIs<SaveResult.Saved>(rooms.update(setup.id, saved.room.id, "Camera 1 bis", 3))
        assertIs<PlaceResult.Cleared>(rooms.clear(setup.id, PersonRef.Child(luca)))
        assertEquals(RemoveResult.Removed("Camera 1 bis", 1), rooms.remove(setup.id, saved.room.id))
    }

    @Test
    fun `t11 c13 until the last day everything changes, and from the day after the end nothing does and every write is refused with nothing changed`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        val luca = child(setup.id, "Luca", "Rossi")
        // The last day is today: still everything changes.
        dsl.update(talita.jooq.tables.references.GATHERING).set(talita.jooq.tables.references.GATHERING.END_DATE, setup.today).where(talita.jooq.tables.references.GATHERING.ID.eq(setup.id)).execute()
        assertEquals(false, rooms.board(setup.id)!!.over)
        assertIs<PlaceResult.Placed>(rooms.place(setup.id, PersonRef.Child(luca), one))
        assertIs<SaveResult.Saved>(rooms.create(setup.id, "Camera 2", 2))

        // The day after the last day: the board says it is over and every write says so, whoever asks.
        kit.endedDaysAgo(setup.id, 1)
        val board = rooms.board(setup.id)!!
        assertTrue(board.over, "the day after the end")
        val state = kit.state()
        assertEquals(SaveResult.Over, rooms.create(setup.id, "Camera 3", 2))
        assertEquals(SaveResult.Over, rooms.update(setup.id, one, "Camera 1 bis", 2))
        assertEquals(RemoveResult.Over, rooms.remove(setup.id, one))
        assertEquals(CopyResult.Over, rooms.copyPrevious(setup.id))
        assertEquals(PlaceResult.Over, rooms.place(setup.id, PersonRef.Child(luca), one))
        assertEquals(PlaceResult.Over, rooms.place(setup.id, PersonRef.Teacher(setup.paoloId), one))
        assertEquals(PlaceResult.Over, rooms.clear(setup.id, PersonRef.Child(luca)))
        assertEquals(PlaceResult.Over, rooms.clear(setup.id, PersonRef.Teacher(setup.paoloId)))
        assertEquals(state, kit.state(), "nothing was changed")
        assertEquals(one, kit.roomOfChild(luca), "who was in a room is there until the job deletes them")
    }

    @Test
    fun `t11 c16 the counts of the gathering are rooms, beds and children without a room, and none of them is a name`() {
        val setup = kit.start()
        assertEquals(talita.gathering.RoomSummary(0, 0, null), rooms.summary(setup.id), "no rooms and no children")
        val one = kit.room(setup.id, "Camera 1", 4)
        kit.room(setup.id, "Camera 2", 4)
        kit.room(setup.id, "Stanza azzurra", 2)
        assertEquals(talita.gathering.RoomSummary(3, 10, null), rooms.summary(setup.id), "no children registered yet: nothing is said of them")
        val luca = child(setup.id, "Luca", "Rossi")
        child(setup.id, "Anna", "Rossi")
        child(setup.id, "Nicolò", "Ferri", days = mapOf(setup.today to "lunch"))
        child(setup.id, "Chiara", "Fontana", status = RegistrationStatus.withdrawn)
        assertEquals(talita.gathering.RoomSummary(3, 10, 2), rooms.summary(setup.id))
        kit.put(luca, setup.id, one)
        assertEquals(1, rooms.summary(setup.id).unassigned)
        // After the end only the house is counted.
        kit.endedDaysAgo(setup.id, 1)
        assertEquals(talita.gathering.RoomSummary(3, 10, null), rooms.summary(setup.id))
    }
}
