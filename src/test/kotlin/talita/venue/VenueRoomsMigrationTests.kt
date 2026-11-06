package talita.venue

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import talita.Addresses
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The migration of the rooms to the venues on a database that has data (TALITA-20, part 2, criterion 19): the rooms of one gathering of each venue, among those that have rooms, become the rooms of the venue with
 * the same ids, and who sleeps in them stays where they are; the other rooms are deleted, with who sleeps in them, and so are those of a draft with no venue. The gathering is chosen first by having people in its
 * rooms (a gathering that is closed but not over is not lost for a draft that has copied rooms), then by not being a draft, then by the most recent first day, then by the id. The database is a fresh Postgres, migrated
 * up to V13 with synthetic rows (the schema of the rooms of TALITA-11), and then V14 runs. Nothing here is a real name or a real date.
 */
class VenueRoomsMigrationTests {

    private fun DSLContext.id(sql: String): Long = fetchOne(sql)!!.get(0, Long::class.java)

    private fun DSLContext.count(sql: String): Int = fetchOne(sql)!!.get(0, Int::class.java)

    private fun DSLContext.teacher(email: String, first: String, last: String): Long {
        val id = id("insert into account (email, created_on) values ('$email', date '2026-01-01') returning id")
        execute("insert into teacher (account_id, first_name, last_name, created_at) values ($id, '$first', '$last', now())")
        return id
    }

    /** A gathering as the schema of V12 holds it (the place is a text of the gathering): a closed one, or the draft. */
    private fun DSLContext.gathering(code: String, title: String, start: String, end: String, place: String?, draft: Boolean = false): Long {
        val where = if (place == null) "null" else "'$place'"
        return id(
            "insert into gathering (public_code, title, start_date, end_date, place, full_fee_cents, status) " +
                "values ('$code', '$title', date '$start', date '$end', $where, ${if (draft) "null" else "6000"}, '${if (draft) "draft" else "closed"}') returning id",
        )
    }

    private fun DSLContext.room(gathering: Long, name: String, beds: Int): Long = id("insert into room (gathering_id, name, beds) values ($gathering, '$name', $beds) returning id")

    /** A child registered on paper by [teacher], the id of the registration. */
    private fun DSLContext.registration(gathering: Long, teacher: Long, first: String, last: String): Long {
        val child = id("insert into child (first_name, last_name, birth_date) values ('$first', '$last', date '2016-04-04') returning id")
        return id(
            "insert into registration (gathering_id, child_id, phone, attendance, status, entered_by_account_id, created_at, updated_at) " +
                "values ($gathering, $child, '3331234567', 'all_days', 'confirmed', $teacher, now(), now()) returning id",
        )
    }

    private fun DSLContext.childInRoom(registration: Long, gathering: Long, room: Long) {
        execute("insert into room_child (registration_id, gathering_id, room_id) values ($registration, $gathering, $room)")
    }

    private fun DSLContext.teacherInRoom(gathering: Long, teacher: Long, room: Long) {
        execute("insert into room_teacher (gathering_id, teacher_account_id, room_id) values ($gathering, $teacher, $room)")
    }

    /** The rows that the test writes before V14: the gatherings, the rooms and the people in them. */
    private data class Rows(
        val lake: Long, val before: Long, val recent: Long, val draft: Long,
        val lakeOne: Long, val lakeTwo: Long, val oldOne: Long, val oldTwo: Long, val newOne: Long, val newTwo: Long, val draftRoom: Long,
        val paolo: Long, val sara: Long, val luca: Long,
    )

    @Test
    fun `t20 c19 the rooms of the most recent gathering of each venue become the rooms of the venue with the same ids, who sleeps there stays, and the others go with who slept in them`() {
        MigrationDatabase.use { database ->
            database.migrateTo("12")
            database.dsl { db ->
                db.gathering("rooms00000000001", "Convivenza di settembre 2024", "2024-09-13", "2024-09-15", "Casa sul lago")
                db.gathering("rooms00000000002", "Convivenza di settembre 2025", "2025-09-12", "2025-09-14", "Casa del bosco")
                db.gathering("rooms00000000003", "Convivenza di settembre 2026", "2026-09-18", "2026-09-20", "Casa del bosco")
                // A draft with no place, so with no venue, and a room that was written for it.
                db.gathering("rooms00000000004", "Convivenza di settembre 2027", "2027-09-17", "2027-09-19", null, draft = true)
            }
            database.migrateTo("13")

            val rows = database.dsl { db ->
                val lake = db.id("select id from gathering where public_code = 'rooms00000000001'")
                val before = db.id("select id from gathering where public_code = 'rooms00000000002'")
                val recent = db.id("select id from gathering where public_code = 'rooms00000000003'")
                val draft = db.id("select id from gathering where public_code = 'rooms00000000004'")
                val marta = db.teacher(Addresses.unique("marta-migr"), "Marta", "Bianchi")
                val paolo = db.teacher(Addresses.unique("paolo-migr"), "Paolo", "Verdi")
                // The lake: one gathering, two rooms, kept.
                val lakeOne = db.room(lake, "Camera lago 1", 4)
                val lakeTwo = db.room(lake, "Camera lago 2", 2)
                // The wood: the gathering before has its rooms, with nobody in them (a registration with no room); the most recent has other rooms, with people in them.
                val oldOne = db.room(before, "Camera 1", 4)
                val oldTwo = db.room(before, "Camera vecchia", 4)
                val luca = db.registration(before, marta, "Luca", "Rossi")
                val newOne = db.room(recent, "Camera 1", 5)
                val newTwo = db.room(recent, "Stanza azzurra", 2)
                val sara = db.registration(recent, marta, "Sara", "Neri")
                db.childInRoom(sara, recent, newOne)
                db.teacherInRoom(recent, paolo, newTwo)
                // The draft with no venue: a room and a teacher in it.
                val draftRoom = db.room(draft, "Camera della bozza", 3)
                db.teacherInRoom(draft, marta, draftRoom)
                Rows(lake, before, recent, draft, lakeOne, lakeTwo, oldOne, oldTwo, newOne, newTwo, draftRoom, paolo, sara, luca)
            }

            database.migrateTo("14")

            database.dsl { db ->
                // The gathering is not a column of the room any more: the venue is.
                assertEquals(0, db.count("select count(*) from information_schema.columns where table_name = 'room' and column_name = 'gathering_id'"))
                val venueOfLake = db.id("select venue_id from gathering where id = ${rows.lake}")
                val venueOfWood = db.id("select venue_id from gathering where id = ${rows.recent}")
                assertEquals(venueOfWood, db.id("select venue_id from gathering where id = ${rows.before}"), "the two gatherings of the wood have the same venue")
                // The rooms that are kept have the same ids, and the venue of their gathering.
                assertEquals(listOf(rows.lakeOne, rows.lakeTwo, rows.newOne, rows.newTwo).sorted(), db.fetch("select id from room order by id").map { it.get(0, Long::class.java) })
                assertEquals(venueOfLake, db.id("select venue_id from room where id = ${rows.lakeOne}"))
                assertEquals(venueOfLake, db.id("select venue_id from room where id = ${rows.lakeTwo}"))
                assertEquals(venueOfWood, db.id("select venue_id from room where id = ${rows.newOne}"))
                assertEquals(venueOfWood, db.id("select venue_id from room where id = ${rows.newTwo}"))
                // The rooms of the gathering before and those of the draft are gone, and the people that slept in them too.
                for (gone in listOf(rows.oldOne, rows.oldTwo, rows.draftRoom)) assertEquals(0, db.count("select count(*) from room where id = $gone"), "room $gone")
                assertEquals(1, db.count("select count(*) from registration where id = ${rows.luca}"), "the registration of the gathering that lost its rooms is still there")
                assertEquals(0, db.count("select count(*) from room_teacher where gathering_id in (${rows.before}, ${rows.draft})"))
                // Who sleeps in the rooms that were kept stays where they are, with the venue.
                assertEquals(rows.newOne, db.id("select room_id from room_child where registration_id = ${rows.sara}"))
                assertEquals(venueOfWood, db.id("select venue_id from room_child where registration_id = ${rows.sara}"))
                assertEquals(rows.newTwo, db.id("select room_id from room_teacher where gathering_id = ${rows.recent} and teacher_account_id = ${rows.paolo}"))
                assertEquals(venueOfWood, db.id("select venue_id from room_teacher where gathering_id = ${rows.recent} and teacher_account_id = ${rows.paolo}"))
                // The names are unique in the venue, whatever the capitals and the spaces.
                val sameName = runCatching { db.execute("insert into room (venue_id, name, beds) values ($venueOfWood, '  CAMERA 1 ', 2)") }.exceptionOrNull()
                assertTrue(sameName?.message.orEmpty().contains("room_name_in_venue"), sameName?.message)
                // Another venue may have the same name.
                db.execute("insert into room (venue_id, name, beds) values ($venueOfLake, 'Camera 1', 2)")
                // The database refuses a person in a room of another venue than the one of the gathering, and a change of the venue of a gathering that has people in its rooms.
                val elsewhere = runCatching { db.execute("update room_child set room_id = ${rows.lakeOne} where registration_id = ${rows.sara}") }.exceptionOrNull()
                assertTrue(elsewhere != null, "a child in a room of another venue")
                val moved = runCatching { db.execute("update gathering set venue_id = $venueOfLake where id = ${rows.recent}") }.exceptionOrNull()
                assertTrue(moved != null, "the venue of a gathering that has people in its rooms")
                assertEquals(venueOfWood, db.id("select venue_id from gathering where id = ${rows.recent}"))
            }
        }
    }

    /** The rows of the test of the rooms with people: the gatherings, the rooms that stay, the people, and the rooms that go. */
    private data class Picked(
        val running: Long, val draft: Long, val lakeEarlier: Long, val runningOne: Long, val runningTwo: Long, val lakeOne: Long, val luca: Long, val sara: Long, val paolo: Long, val goneRooms: List<Long>,
    )

    @Test
    fun `t20 c19 the rooms with people in them are kept before those of a later gathering, even a draft with copied rooms, and the people stay where they are`() {
        MigrationDatabase.use { database ->
            database.migrateTo("12")
            database.dsl { db ->
                // The wood: the gathering that is closed but not over, with people in its rooms, and a draft of the year after in the same house that has the rooms copied (and one more).
                db.gathering("rooms00000000021", "Convivenza di settembre 2026", "2026-09-18", "2026-09-20", "Casa del bosco")
                db.gathering("rooms00000000022", "Convivenza di settembre 2027", "2027-09-17", "2027-09-19", "Casa del bosco", draft = true)
                // The lake: two gatherings that are not drafts; the earlier has a child in a room, the later has rooms with nobody in them.
                db.gathering("rooms00000000023", "Convivenza di settembre 2025", "2025-09-12", "2025-09-14", "Casa sul lago")
                db.gathering("rooms00000000024", "Convivenza di settembre 2026", "2026-09-11", "2026-09-13", "Casa sul lago")
            }
            database.migrateTo("13")

            val rows = database.dsl { db ->
                val running = db.id("select id from gathering where public_code = 'rooms00000000021'")
                val draft = db.id("select id from gathering where public_code = 'rooms00000000022'")
                val lakeEarlier = db.id("select id from gathering where public_code = 'rooms00000000023'")
                val lakeLater = db.id("select id from gathering where public_code = 'rooms00000000024'")
                val marta = db.teacher(Addresses.unique("marta-pick"), "Marta", "Bianchi")
                val paolo = db.teacher(Addresses.unique("paolo-pick"), "Paolo", "Verdi")
                val runningOne = db.room(running, "Camera 1", 4)
                val runningTwo = db.room(running, "Stanza azzurra", 2)
                val luca = db.registration(running, marta, "Luca", "Rossi")
                db.childInRoom(luca, running, runningOne)
                db.teacherInRoom(running, paolo, runningTwo)
                val draftRooms = listOf(db.room(draft, "Camera 1", 4), db.room(draft, "Stanza azzurra", 2), db.room(draft, "Camera nuova", 3))
                val lakeOne = db.room(lakeEarlier, "Camera lago 1", 4)
                val sara = db.registration(lakeEarlier, marta, "Sara", "Neri")
                db.childInRoom(sara, lakeEarlier, lakeOne)
                val lakeLaterRooms = listOf(db.room(lakeLater, "Camera lago 1", 4), db.room(lakeLater, "Camera lago 2", 2))
                Picked(running, draft, lakeEarlier, runningOne, runningTwo, lakeOne, luca, sara, paolo, draftRooms + lakeLaterRooms)
            }

            database.migrateTo("14")

            database.dsl { db ->
                val woodVenue = db.id("select venue_id from gathering where id = ${rows.running}")
                val lakeVenue = db.id("select venue_id from gathering where id = ${rows.lakeEarlier}")
                assertEquals(woodVenue, db.id("select venue_id from gathering where id = ${rows.draft}"), "the draft is in the house of the gathering that is going on")
                // The rooms with people are the rooms of the venue, with the same ids; those of the draft and of the later gathering are gone.
                assertEquals(listOf(rows.runningOne, rows.runningTwo, rows.lakeOne).sorted(), db.fetch("select id from room order by id").map { it.get(0, Long::class.java) })
                assertEquals(woodVenue, db.id("select venue_id from room where id = ${rows.runningOne}"))
                assertEquals(lakeVenue, db.id("select venue_id from room where id = ${rows.lakeOne}"))
                for (gone in rows.goneRooms) assertEquals(0, db.count("select count(*) from room where id = $gone"), "room $gone")
                // Nobody was lost: the child, the teacher and the other child are where they were, with the venue.
                assertEquals(rows.runningOne, db.id("select room_id from room_child where registration_id = ${rows.luca}"))
                assertEquals(woodVenue, db.id("select venue_id from room_child where registration_id = ${rows.luca}"))
                assertEquals(rows.runningTwo, db.id("select room_id from room_teacher where gathering_id = ${rows.running} and teacher_account_id = ${rows.paolo}"))
                assertEquals(rows.lakeOne, db.id("select room_id from room_child where registration_id = ${rows.sara}"))
                assertEquals(2, db.count("select count(*) from room_child"))
                assertEquals(1, db.count("select count(*) from room_teacher"))
                assertEquals(2, db.count("select count(*) from registration"))
            }
        }
    }

    @Test
    fun `t20 c19 when two gatherings that are not over both have people in their rooms the oldest one keeps its rooms and its people, and the open one further on loses them`() {
        MigrationDatabase.use { database ->
            database.migrateTo("12")
            database.dsl { db ->
                // The one that is happening now: registrations closed, in the house, with people in the rooms.
                db.gathering("rooms00000000041", "Convivenza di settembre 2026", "2026-09-18", "2026-09-20", "Casa del bosco")
                // The one that is open further on, in the same house, with teachers already in its rooms (the same names, copied).
                db.gathering("rooms00000000042", "Convivenza di settembre 2027", "2027-09-17", "2027-09-19", "Casa del bosco")
                db.execute("update gathering set status = 'open' where public_code = 'rooms00000000042'")
            }
            database.migrateTo("13")

            val rows = database.dsl { db ->
                val running = db.id("select id from gathering where public_code = 'rooms00000000041'")
                val open = db.id("select id from gathering where public_code = 'rooms00000000042'")
                val marta = db.teacher(Addresses.unique("marta-two"), "Marta", "Bianchi")
                val paolo = db.teacher(Addresses.unique("paolo-two"), "Paolo", "Verdi")
                val runningOne = db.room(running, "Camera 1", 4)
                val runningTwo = db.room(running, "Stanza azzurra", 2)
                val luca = db.registration(running, marta, "Luca", "Rossi")
                db.childInRoom(luca, running, runningOne)
                db.teacherInRoom(running, paolo, runningTwo)
                val openOne = db.room(open, "Camera 1", 4)
                val openTwo = db.room(open, "Stanza azzurra", 2)
                val sara = db.registration(open, marta, "Sara", "Neri")
                db.childInRoom(sara, open, openOne)
                db.teacherInRoom(open, marta, openTwo)
                OldestKept(running, open, runningOne, runningTwo, listOf(openOne, openTwo), luca, sara, paolo)
            }

            database.migrateTo("14")

            database.dsl { db ->
                val venue = db.id("select venue_id from gathering where id = ${rows.running}")
                assertEquals(venue, db.id("select venue_id from gathering where id = ${rows.open}"))
                // The rooms of the one that is happening now are the rooms of the venue, with the same ids; those of the open one are gone.
                assertEquals(listOf(rows.runningOne, rows.runningTwo).sorted(), db.fetch("select id from room order by id").map { it.get(0, Long::class.java) })
                for (gone in rows.openRooms) assertEquals(0, db.count("select count(*) from room where id = $gone"), "room $gone")
                // The people of the one that is happening now are where they were, with the venue.
                assertEquals(rows.runningOne, db.id("select room_id from room_child where registration_id = ${rows.luca}"))
                assertEquals(venue, db.id("select venue_id from room_child where registration_id = ${rows.luca}"))
                assertEquals(rows.runningTwo, db.id("select room_id from room_teacher where gathering_id = ${rows.running} and teacher_account_id = ${rows.paolo}"))
                // The people of the open one went with its rooms, and nobody else: the registration of the child is still there.
                assertEquals(0, db.count("select count(*) from room_child where registration_id = ${rows.sara}"))
                assertEquals(0, db.count("select count(*) from room_teacher where gathering_id = ${rows.open}"))
                assertEquals(1, db.count("select count(*) from room_child"))
                assertEquals(1, db.count("select count(*) from room_teacher"))
                assertEquals(2, db.count("select count(*) from registration"))
            }
        }
    }

    /** The rows of the test of two gatherings with people: the one that is happening, the open one, their rooms and the people. */
    private data class OldestKept(
        val running: Long, val open: Long, val runningOne: Long, val runningTwo: Long, val openRooms: List<Long>, val luca: Long, val sara: Long, val paolo: Long,
    )

    @Test
    fun `t20 c19 with nobody in the rooms a gathering that is not a draft is kept before a draft that is later, and the draft's rooms go`() {
        MigrationDatabase.use { database ->
            database.migrateTo("12")
            database.dsl { db ->
                db.gathering("rooms00000000031", "Convivenza di settembre 2025", "2025-09-12", "2025-09-14", "Casa del bosco")
                db.gathering("rooms00000000032", "Convivenza di settembre 2027", "2027-09-17", "2027-09-19", "Casa del bosco", draft = true)
            }
            database.migrateTo("13")
            val rows = database.dsl { db ->
                val closed = db.id("select id from gathering where public_code = 'rooms00000000031'")
                val draft = db.id("select id from gathering where public_code = 'rooms00000000032'")
                db.room(closed, "Camera 1", 4) to listOf(db.room(draft, "Camera 1", 4), db.room(draft, "Camera 2", 2))
            }

            database.migrateTo("14")

            database.dsl { db ->
                assertEquals(listOf(rows.first), db.fetch("select id from room").map { it.get(0, Long::class.java) })
                for (gone in rows.second) assertEquals(0, db.count("select count(*) from room where id = $gone"), "room $gone")
                assertEquals(1, db.count("select count(*) from venue"))
            }
        }
    }

    @Test
    fun `t20 c19 a database with no room migrates to no room, and the rooms of a venue that has gatherings and no room stay none`() {
        MigrationDatabase.use { database ->
            database.migrateTo("12")
            database.dsl { db -> db.gathering("rooms00000000011", "Convivenza di settembre 2025", "2025-09-12", "2025-09-14", "Casa sul lago") }
            database.migrateTo("13")
            database.migrateTo("14")
            database.dsl { db ->
                assertEquals(0, db.count("select count(*) from room"))
                assertEquals(0, db.count("select count(*) from room_child"))
                assertEquals(0, db.count("select count(*) from room_teacher"))
                assertEquals(1, db.count("select count(*) from venue"))
            }
        }
    }
}
