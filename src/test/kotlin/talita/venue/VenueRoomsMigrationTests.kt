package talita.venue

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The migration of the rooms to the venues on a database that has data (TALITA-20, part 2, criterion 19): the rooms of the most recent gathering of each venue, among those that have rooms, become the rooms of
 * the venue with the same ids, and who sleeps in them stays where they are; the other rooms are deleted, with who sleeps in them, and so are those of a draft with no venue. The database is a fresh Postgres, migrated up
 * to V13 with synthetic rows (the schema of the rooms of TALITA-11), and then V14 runs. Nothing here is a real name or a real date.
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
                val marta = db.teacher("marta.migr@example.org", "Marta", "Bianchi")
                val paolo = db.teacher("paolo.migr@example.org", "Paolo", "Verdi")
                // The lake: one gathering, two rooms, kept.
                val lakeOne = db.room(lake, "Camera lago 1", 4)
                val lakeTwo = db.room(lake, "Camera lago 2", 2)
                // The wood: the gathering before has its rooms, with people in them; the most recent has other rooms, with people in them too.
                val oldOne = db.room(before, "Camera 1", 4)
                val oldTwo = db.room(before, "Camera vecchia", 4)
                val luca = db.registration(before, marta, "Luca", "Rossi")
                db.childInRoom(luca, before, oldOne)
                db.teacherInRoom(before, paolo, oldTwo)
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
                assertEquals(0, db.count("select count(*) from room_child where registration_id = ${rows.luca}"))
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
