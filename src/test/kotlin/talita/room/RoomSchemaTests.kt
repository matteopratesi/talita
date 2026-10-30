package talita.room

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.RETENTION_LOG
import talita.jooq.tables.references.ROOM
import talita.jooq.tables.references.ROOM_CHILD
import talita.jooq.tables.references.ROOM_TEACHER
import talita.jooq.tables.references.TEACHER
import java.sql.SQLException
import java.time.Clock
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The schema of the rooms (V9): what the database refuses by itself, whatever the application does (criteria 7, 8, 10, 19). The application checks the same things first and with
 * kinder words; this is the net under it. Names and rooms are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RoomSchemaTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = RoomTestKit(mockMvc, tokens, dsl, clock)

    /** The name of the constraint or index that refused (or the text of the refusal when it has no name). */
    private fun refusedBy(block: () -> Unit): String {
        val failure = assertFailsWith<Exception> { block() }
        val sql = generateSequence<Throwable>(failure) { it.cause?.takeIf { cause -> cause !== it } }.filterIsInstance<SQLException>().first()
        return Regex("""(?:constraint|index) "([^"]+)"""").find(sql.message.orEmpty())?.groupValues?.get(1) ?: sql.message.orEmpty()
    }

    private fun columns(table: String): List<String> =
        dsl.resultQuery("select column_name from information_schema.columns where table_schema = 'public' and table_name = ? order by ordinal_position", table).fetch(0, String::class.java).map { it!! }

    @Test
    fun `t11 c19 the tables of the rooms have only the columns of the schema, so nothing says the sex, the reason of a choice, a note or a free text about a person`() {
        assertEquals(listOf("id", "gathering_id", "name", "beds"), columns("room"))
        assertEquals(listOf("registration_id", "gathering_id", "room_id"), columns("room_child"))
        assertEquals(listOf("gathering_id", "teacher_account_id", "room_id"), columns("room_teacher"))
        // The only text of the three tables is the name of the room: the house, and never a person (the rooms hold no personal data).
        val texts = dsl.resultQuery(
            "select table_name || '.' || column_name from information_schema.columns where table_schema = 'public' and table_name in ('room', 'room_child', 'room_teacher') " +
                "and data_type in ('text', 'character varying', 'character') order by 1",
        ).fetch(0, String::class.java)
        assertEquals(listOf("room.name"), texts)
        // The tables that hold people point at the registration and at the account, and copy nothing of them.
        for (word in listOf("sex", "sesso", "gender", "note", "reason", "motivo", "why")) {
            val found = dsl.resultQuery(
                "select table_name || '.' || column_name from information_schema.columns where table_schema = 'public' and table_name in ('room', 'room_child', 'room_teacher') and column_name like ?",
                "%$word%",
            ).fetch(0, String::class.java)
            assertEquals(emptyList(), found, word)
        }
    }

    @Test
    fun `t11 c02 the database refuses an empty name, beds outside 1 to 50, and the same name twice in a gathering whatever its capitals and spaces`() {
        val setup = kit.start()
        val gathering = setup.id

        assertEquals("room_name_not_empty", refusedBy { kit.room(gathering, "   ", 4) })
        assertEquals("room_beds_range", refusedBy { kit.room(gathering, "Camera 1", 0) })
        assertEquals("room_beds_range", refusedBy { kit.room(gathering, "Camera 1", 51) })
        kit.room(gathering, "Camera 1", 4)
        kit.room(gathering, "Camera 2", 50)
        assertEquals("room_name_in_gathering", refusedBy { kit.room(gathering, "camera 1", 4) })
        assertEquals("room_name_in_gathering", refusedBy { kit.room(gathering, "  CAMERA 1 ", 4) })
        // Another gathering may have the same name: the rooms are copied from one to the next.
        val other = kit.otherGathering()
        kit.room(other, "Camera 1", 4)
        assertEquals(1, kit.roomCount(other))
        assertEquals(2, kit.roomCount(gathering))
    }

    @Test
    fun `t11 c07 a child is in one room only, and the room is of the gathering of its registration`() {
        val setup = kit.start()
        val first = kit.room(setup.id, "Camera 1", 4)
        val second = kit.room(setup.id, "Camera 2", 4)
        val luca = kit.rows.register(setup.id, kit.checkin.account(), "Luca", "Rossi")
        kit.put(luca, setup.id, first)

        assertEquals("room_child_pkey", refusedBy { kit.put(luca, setup.id, second) })
        assertEquals(first, kit.roomOfChild(luca))

        // A room of another gathering, or a gathering that is not the one of the registration, is refused by the composite keys.
        val other = kit.otherGathering()
        val elsewhere = kit.room(other, "Camera 1", 4)
        val anna = kit.rows.register(setup.id, kit.checkin.account(), "Anna", "Rossi")
        assertEquals("room_child_room", refusedBy { kit.put(anna, setup.id, elsewhere) })
        assertEquals("room_child_registration", refusedBy { kit.put(anna, other, elsewhere) })
        assertNull(kit.roomOfChild(anna))
    }

    @Test
    fun `t11 c08 a teacher is in one room per gathering, the room is of that gathering and only a teacher can be in it`() {
        val setup = kit.start()
        val first = kit.room(setup.id, "Camera 1", 4)
        val second = kit.room(setup.id, "Camera 2", 4)
        kit.putTeacher(setup.id, setup.paoloId, first)

        assertEquals("room_teacher_pkey", refusedBy { kit.putTeacher(setup.id, setup.paoloId, second) })
        // Giulia is a teacher who is not a custodian: she can be in a room all the same.
        kit.putTeacher(setup.id, setup.giuliaId, second)
        assertEquals(second, kit.roomOfTeacher(setup.id, setup.giuliaId))

        val other = kit.otherGathering()
        val elsewhere = kit.room(other, "Camera 1", 4)
        assertEquals("room_teacher_room", refusedBy { kit.putTeacher(setup.id, setup.martaId, elsewhere) })
        // The same teacher is in another room of another gathering: one per gathering, not one for ever.
        kit.putTeacher(other, setup.paoloId, elsewhere)
        assertEquals(elsewhere, kit.roomOfTeacher(other, setup.paoloId))
        // A parent who is not a teacher is no one for a room.
        val parent = kit.checkin.account()
        assertEquals("room_teacher_teacher_account_id_fkey", refusedBy { kit.putTeacher(setup.id, parent, first) })
    }

    @Test
    fun `t11 c05 taking a room away, a registration or a teacher takes away who sleeps there, and nothing else`() {
        val setup = kit.start()
        val first = kit.room(setup.id, "Camera 1", 4)
        val second = kit.room(setup.id, "Camera 2", 4)
        val luca = kit.rows.register(setup.id, kit.checkin.account(), "Luca", "Rossi")
        val anna = kit.rows.register(setup.id, kit.checkin.account(), "Anna", "Rossi")
        val sara = kit.rows.register(setup.id, kit.checkin.account(), "Sara", "Neri")
        kit.put(luca, setup.id, first)
        kit.put(anna, setup.id, first)
        kit.put(sara, setup.id, second)
        kit.putTeacher(setup.id, setup.paoloId, first)
        kit.putTeacher(setup.id, setup.giuliaId, second)

        // A room that goes takes who was in it (the registrations and the teachers stay), and the others stay where they are.
        dsl.deleteFrom(ROOM).where(ROOM.ID.eq(first)).execute()
        assertNull(kit.roomOfChild(luca))
        assertNull(kit.roomOfChild(anna))
        assertNull(kit.roomOfTeacher(setup.id, setup.paoloId))
        assertEquals(second, kit.roomOfChild(sara))
        assertEquals(second, kit.roomOfTeacher(setup.id, setup.giuliaId))
        assertEquals(3, dsl.fetchCount(REGISTRATION, REGISTRATION.GATHERING_ID.eq(setup.id)))
        assertEquals(true, dsl.fetchExists(TEACHER, TEACHER.ACCOUNT_ID.eq(setup.paoloId)))

        // A registration that goes takes its room row; a teacher that is taken away from the teachers too.
        dsl.deleteFrom(REGISTRATION).where(REGISTRATION.ID.eq(sara)).execute()
        assertNull(kit.roomOfChild(sara))
        kit.checkin.removeTeacher(setup.giuliaId)
        assertNull(kit.roomOfTeacher(setup.id, setup.giuliaId))
        assertEquals(0, kit.peopleCount(setup.id))
        assertEquals(1, kit.roomCount(setup.id), "the other room stays, with its name and beds")
    }

    @Test
    fun `t11 c20 the register of the deletions accepts the rule of the rooms and still refuses any other`() {
        dsl.insertInto(RETENTION_LOG).set(RETENTION_LOG.DONE_AT, OffsetDateTime.now(clock)).set(RETENTION_LOG.RULE, "rooms").set(RETENTION_LOG.ROW_COUNT, 3).execute()
        assertEquals("retention_log_rule", refusedBy { dsl.insertInto(RETENTION_LOG).set(RETENTION_LOG.DONE_AT, OffsetDateTime.now(clock)).set(RETENTION_LOG.RULE, "camere").set(RETENTION_LOG.ROW_COUNT, 3).execute() })
        assertEquals(1, dsl.fetchCount(RETENTION_LOG, RETENTION_LOG.RULE.eq("rooms")))
        dsl.deleteFrom(RETENTION_LOG).where(RETENTION_LOG.RULE.eq("rooms")).execute()
    }

    @Test
    fun `t11 c10 two transactions that put the same child in two rooms at the same moment leave one row, with insert on conflict do update`() {
        val setup = kit.start()
        val first = kit.room(setup.id, "Camera 1", 4)
        val second = kit.room(setup.id, "Camera 2", 4)
        val luca = kit.rows.register(setup.id, kit.checkin.account(), "Luca", "Rossi")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val held = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val firstWriter = pool.submit {
                dsl.transaction { configuration ->
                    val tx = org.jooq.impl.DSL.using(configuration)
                    upsert(tx, setup.id, luca, first)
                    held.countDown()
                    release.await(20, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            held.await(20, java.util.concurrent.TimeUnit.SECONDS)
            // The second one waits for the first (the row is not committed yet) and, when it is, changes the room of the row that is there: no error, one row.
            val secondWriter = pool.submit { dsl.transaction { configuration -> upsert(org.jooq.impl.DSL.using(configuration), setup.id, luca, second) } }
            Thread.sleep(300)
            release.countDown()
            firstWriter.get(20, java.util.concurrent.TimeUnit.SECONDS)
            secondWriter.get(20, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, dsl.fetchCount(ROOM_CHILD, ROOM_CHILD.REGISTRATION_ID.eq(luca)))
        assertEquals(second, kit.roomOfChild(luca), "the last one to write is where the child is")
    }

    private fun upsert(tx: DSLContext, gathering: Long, registration: Long, room: Long) {
        tx.insertInto(ROOM_CHILD).set(ROOM_CHILD.REGISTRATION_ID, registration).set(ROOM_CHILD.GATHERING_ID, gathering).set(ROOM_CHILD.ROOM_ID, room)
            .onConflict(ROOM_CHILD.REGISTRATION_ID).doUpdate().set(ROOM_CHILD.ROOM_ID, room).execute()
    }
}
