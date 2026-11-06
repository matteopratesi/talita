package talita.venue

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import talita.Addresses
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The QA of the two migrations of TALITA-20 (criteria 11 and 19) on cases that the tests of the developer leave out: capitals with accents, a name made unique that collides with a place written
 * by someone, many gatherings with one place, the most recent gathering of a venue with no rooms, a tie in the first day, a draft with a place and rooms, and rooms with the same names in two gatherings of
 * one venue. Each test has a fresh Postgres of its own, migrated up to the version before, with synthetic rows written in plain SQL; then the migration runs. Nothing here is a real name or a real date.
 */
class VenueQaMigrationTests {

    private fun DSLContext.id(sql: String): Long = fetchOne(sql)!!.get(0, Long::class.java)

    private fun DSLContext.count(sql: String): Int = fetchOne(sql)!!.get(0, Int::class.java)

    private fun DSLContext.gathering(code: String, start: String, place: String?, status: String = "closed"): Long {
        val where = if (place == null) "null" else "'" + place.replace("'", "''") + "'"
        return id(
            "insert into gathering (public_code, title, start_date, end_date, place, full_fee_cents, status) " +
                "values ('$code', 'Convivenza di prova', date '$start', date '$start' + 2, $where, ${if (status == "draft") "null" else "6000"}, '$status') returning id",
        )
    }

    private fun DSLContext.teacher(email: String, first: String, last: String): Long {
        val id = id("insert into account (email, created_on) values ('$email', date '2026-01-01') returning id")
        execute("insert into teacher (account_id, first_name, last_name, created_at) values ($id, '$first', '$last', now())")
        return id
    }

    private fun DSLContext.room(gathering: Long, name: String, beds: Int): Long = id("insert into room (gathering_id, name, beds) values ($gathering, '$name', $beds) returning id")

    private fun DSLContext.childInRoom(gathering: Long, teacher: Long, first: String, last: String, room: Long, status: String = "confirmed"): Long {
        val child = id("insert into child (first_name, last_name, birth_date) values ('$first', '$last', date '2016-04-04') returning id")
        val registration = id(
            "insert into registration (gathering_id, child_id, phone, attendance, status, entered_by_account_id, created_at, updated_at) " +
                "values ($gathering, $child, '3331234567', 'all_days', '$status', $teacher, now(), now()) returning id",
        )
        execute("insert into room_child (registration_id, gathering_id, room_id) values ($registration, $gathering, $room)")
        return registration
    }

    @Test
    fun `t20 c11 the places with accents and capitals are one venue, many gatherings of one place choose it, and a name made unique never collides with the place that someone wrote with that very text`() {
        // 60 letters: the first place that starts so has them as its name, the second is made «56 letters (2)», and a third place written by someone as exactly that text has to be made another one.
        val base = "L".repeat(60)
        val clash = "L".repeat(56) + " (2)"
        MigrationDatabase.use { database ->
            database.migrateTo("12")
            database.dsl { db ->
                db.gathering("qamig00000000001", "2022-09-16", "Località Esempio, Casa del bosco", status = "closed")
                db.gathering("qamig00000000002", "2023-09-15", "LOCALITÀ ESEMPIO, CASA DEL BOSCO ", status = "closed")
                db.gathering("qamig00000000003", "2024-09-13", "località esempio, casa del bosco", status = "closed")
                db.gathering("qamig00000000004", "2026-09-18", "Località Esempio, Casa del bosco", status = "open")
                db.gathering("qamig00000000005", "2027-09-17", "Località Esempio, Casa del bosco", status = "closed")
                // Two long places with the same 60 letters at the start, and then the place written by someone with the text that the migration would make for the second.
                db.gathering("qamig00000000006", "2021-09-17", base + "A")
                db.gathering("qamig00000000007", "2021-09-18", base + "B")
                db.gathering("qamig00000000008", "2021-09-19", clash)
            }

            database.migrateTo("13")

            database.dsl { db ->
                val names = db.fetch("select name from venue order by id").map { it.get(0, String::class.java) }
                // Accents and capitals: the three first gatherings, the open one and the draft have one venue.
                val woodVenues = db.fetch("select distinct venue_id from gathering where public_code in ('qamig00000000001','qamig00000000002','qamig00000000003','qamig00000000004','qamig00000000005')")
                assertEquals(1, woodVenues.size, "the same place in different capitals is one venue: $names")
                // Every venue has a name of 1 to 60 characters, all different whatever the capitals and the spaces: the place that someone wrote as exactly «56 letters (2)» got another name than the second place.
                assertTrue(names.all { it.length in 1..60 }, names.toString())
                assertEquals(names.size, names.map { it.trim().lowercase() }.toSet().size, names.toString())
                assertEquals(4, names.size, names.toString())
                // The names of the three long places: the first 60 letters, the second made unique with a number in brackets, and the third, whose place is that very text, made unique again.
                fun nameOf(code: String) = db.fetchOne("select venue.name from gathering join venue on venue.id = gathering.venue_id where public_code = '$code'")!!.get(0, String::class.java)
                assertEquals("L".repeat(60), nameOf("qamig00000000006"))
                assertEquals("L".repeat(56) + " (2)", nameOf("qamig00000000007"))
                assertEquals("L".repeat(56) + " (3)", nameOf("qamig00000000008"))
                val ofClash = db.fetchOne("select venue.name, venue.place from gathering join venue on venue.id = gathering.venue_id where public_code = 'qamig00000000008'")!!
                assertEquals(clash, ofClash.get(1, String::class.java), "the place is kept whole")
                // The three long places each have a venue of their own, with their own whole place.
                for ((code, place) in listOf("qamig00000000006" to base + "A", "qamig00000000007" to base + "B", "qamig00000000008" to clash)) {
                    assertEquals(place, db.fetchOne("select venue.place from gathering join venue on venue.id = gathering.venue_id where public_code = '$code'")!!.get(0, String::class.java), code)
                }
                // The rule «complete outside the draft» holds for every gathering that was migrated: the open one has its venue.
                assertTrue(db.count("select count(*) from gathering where status <> 'draft' and venue_id is null") == 0)
            }
        }
    }

    @Test
    fun `t20 c19 when the most recent gathering of a venue has no rooms the rooms of the one before are the rooms of the venue, a tie in the first day goes to the higher id, and a draft with a place counts`() {
        MigrationDatabase.use { database ->
            database.migrateTo("12")
            database.dsl { db ->
                db.gathering("qaroom0000000001", "2024-09-13", "Casa del bosco")
                db.gathering("qaroom0000000002", "2025-09-12", "Casa del bosco")
                db.gathering("qaroom0000000003", "2026-09-18", "Casa sul lago")
                db.gathering("qaroom0000000004", "2026-09-18", "Casa sul lago")
                db.gathering("qaroom0000000005", "2027-09-17", "Casa del lago nord", status = "draft")
            }
            database.migrateTo("13")

            val marta = database.dsl { db -> db.teacher(Addresses.unique("qa-marta"), "Marta", "Bianchi") }
            val rows = database.dsl { db ->
                val woodOld = db.id("select id from gathering where public_code = 'qaroom0000000001'")
                val woodNew = db.id("select id from gathering where public_code = 'qaroom0000000002'")
                val lakeFirst = db.id("select id from gathering where public_code = 'qaroom0000000003'")
                val lakeSecond = db.id("select id from gathering where public_code = 'qaroom0000000004'")
                val north = db.id("select id from gathering where public_code = 'qaroom0000000005'")
                // The wood: the rooms are in the older gathering; the most recent one has none.
                val woodOne = db.room(woodOld, "Camera 1", 4)
                val woodTwo = db.room(woodOld, "Camera 2", 4)
                val luca = db.childInRoom(woodOld, marta, "Luca", "Rossi", woodOne)
                // The lake: two gatherings on the same day, both with a «Camera lago»; the one with the higher id wins, and the names do not collide.
                val lakeA = db.room(lakeFirst, "Camera lago", 2)
                val sara = db.childInRoom(lakeFirst, marta, "Sara", "Neri", lakeA)
                val lakeB = db.room(lakeSecond, "Camera lago", 3)
                val lakeC = db.room(lakeSecond, "Camera 3", 5)
                // A draft that has a place and rooms, with a teacher in one: the rooms are kept, and so is the row of who was there.
                val northOne = db.room(north, "Camera nord", 6)
                db.execute("insert into room_teacher (gathering_id, teacher_account_id, room_id) values ($north, $marta, $northOne)")
                mapOf("woodOld" to woodOld, "woodNew" to woodNew, "lakeFirst" to lakeFirst, "lakeSecond" to lakeSecond, "north" to north, "woodOne" to woodOne, "woodTwo" to woodTwo, "luca" to luca,
                    "lakeA" to lakeA, "sara" to sara, "lakeB" to lakeB, "lakeC" to lakeC, "northOne" to northOne)
            }

            database.migrateTo("14")

            database.dsl { db ->
                val kept = db.fetch("select id from room order by id").map { it.get(0, Long::class.java) }
                assertEquals(listOf(rows.getValue("woodOne"), rows.getValue("woodTwo"), rows.getValue("lakeB"), rows.getValue("lakeC"), rows.getValue("northOne")).sorted(), kept)
                // The wood: the rooms of the older gathering, and Luca is where he was.
                val woodVenue = db.id("select venue_id from gathering where id = ${rows.getValue("woodNew")}")
                assertEquals(woodVenue, db.id("select venue_id from room where id = ${rows.getValue("woodOne")}"))
                assertEquals(rows.getValue("woodOne"), db.id("select room_id from room_child where registration_id = ${rows.getValue("luca")}"))
                // The lake: the rooms of the gathering with the higher id, and who slept in the other ones is gone.
                assertEquals(0, db.count("select count(*) from room_child where registration_id = ${rows.getValue("sara")}"))
                assertEquals(db.id("select venue_id from gathering where id = ${rows.getValue("lakeSecond")}"), db.id("select venue_id from room where id = ${rows.getValue("lakeB")}"))
                // The draft with a place: its room is the venue's, with the teacher in it.
                assertEquals(rows.getValue("northOne"), db.id("select room_id from room_teacher where gathering_id = ${rows.getValue("north")}"))
                // Every person is in a room of the venue of their gathering, whatever the gathering.
                assertEquals(0, db.count("select count(*) from room_child rc join gathering g on g.id = rc.gathering_id where g.venue_id is distinct from rc.venue_id"))
                assertEquals(0, db.count("select count(*) from room_teacher rt join gathering g on g.id = rt.gathering_id where g.venue_id is distinct from rt.venue_id"))
                assertEquals(0, db.count("select count(*) from room_child rc join room r on r.id = rc.room_id where r.venue_id <> rc.venue_id"))
                // Nobody is in the venue of a gathering that does not use it: the lake (two gatherings) and the wood (two) have each their rooms once.
                assertEquals(2, db.count("select count(*) from room where venue_id = $woodVenue"))
                assertNull(db.fetchOne("select 1 from room r1 join room r2 on r1.venue_id = r2.venue_id and r1.id < r2.id and lower(btrim(r1.name)) = lower(btrim(r2.name))"))
            }
        }
    }

    @Test
    fun `t20 c11 c19 a database of before with places, rooms and people goes through V13 and V14 in one go, as a deploy runs them`() {
        MigrationDatabase.use { database ->
            database.migrateTo("12")
            val rows = database.dsl { db ->
                val marta = db.teacher(Addresses.unique("qa-marta"), "Marta", "Bianchi")
                val old = db.gathering("qaonego000000001", "2025-09-12", "Casa per ritiri del bosco, Località Esempio")
                val recent = db.gathering("qaonego000000002", "2026-09-18", "  CASA per ritiri del bosco, località esempio ")
                val draft = db.gathering("qaonego000000003", "2027-09-17", null, status = "draft")
                val oldOne = db.room(old, "Camera 1", 4)
                val oldTwo = db.room(old, "Camera 2", 4)
                val luca = db.childInRoom(old, marta, "Luca", "Rossi", oldOne)
                val newOne = db.room(recent, "Camera 1", 5)
                val sara = db.childInRoom(recent, marta, "Sara", "Neri", newOne)
                db.execute("insert into room_teacher (gathering_id, teacher_account_id, room_id) values ($recent, $marta, $newOne)")
                val draftRoom = db.room(draft, "Camera della bozza", 3)
                db.execute("insert into room_teacher (gathering_id, teacher_account_id, room_id) values ($draft, $marta, $draftRoom)")
                mapOf("old" to old, "recent" to recent, "draft" to draft, "oldOne" to oldOne, "oldTwo" to oldTwo, "luca" to luca, "newOne" to newOne, "sara" to sara, "draftRoom" to draftRoom, "marta" to marta)
            }

            database.migrateTo("14")

            database.dsl { db ->
                assertEquals(1, db.count("select count(*) from venue"))
                val venue = db.id("select id from venue")
                assertEquals("CASA per ritiri del bosco, località esempio", db.fetchOne("select place from venue")!!.get(0, String::class.java), "the text of the most recent gathering")
                assertEquals(venue, db.id("select venue_id from gathering where id = ${rows.getValue("old")}"))
                assertEquals(venue, db.id("select venue_id from gathering where id = ${rows.getValue("recent")}"))
                assertNull(db.fetchOne("select venue_id from gathering where id = ${rows.getValue("draft")} and venue_id is not null"))
                assertEquals(listOf(rows.getValue("newOne")), db.fetch("select id from room").map { it.get(0, Long::class.java) })
                assertEquals(venue, db.id("select venue_id from room where id = ${rows.getValue("newOne")}"))
                assertEquals(rows.getValue("newOne"), db.id("select room_id from room_child where registration_id = ${rows.getValue("sara")}"))
                assertEquals(rows.getValue("newOne"), db.id("select room_id from room_teacher where gathering_id = ${rows.getValue("recent")}"))
                assertEquals(0, db.count("select count(*) from room_child where registration_id = ${rows.getValue("luca")}"))
                assertEquals(0, db.count("select count(*) from room_teacher where gathering_id in (${rows.getValue("old")}, ${rows.getValue("draft")})"))
                assertEquals(1, db.count("select count(*) from room_child"))
                assertEquals(1, db.count("select count(*) from room_teacher"))
                // The registrations of the child who slept in a room that went are still there: only the room goes, and who slept there.
                assertEquals(2, db.count("select count(*) from registration"))
            }
        }
    }
}
