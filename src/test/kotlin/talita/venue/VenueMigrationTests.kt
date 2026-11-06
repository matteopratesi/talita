package talita.venue

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The migration of the venues on a database that has data (TALITA-20, part 1, criterion 11): every different place of the gatherings of before (without the capitals and the spaces at the sides) becomes a
 * venue, with that place and, as its name, the place cut to 60 characters and made unique if it has to be; the gatherings with that place choose it, and a draft with no place stays with no venue. The
 * pages and the texts say the place of before. The database is a fresh Postgres at version 12 with synthetic rows, and then V13 runs. Nothing here is a real name or a real date.
 */
class VenueMigrationTests {

    private fun DSLContext.gathering(code: String, title: String, start: String, end: String, place: String?, status: String = "closed", fee: Int? = 6000) {
        val where = if (place == null) "null" else "'" + place.replace("'", "''") + "'"
        execute(
            "insert into gathering (public_code, title, start_date, end_date, place, full_fee_cents, status) " +
                "values ('$code', '$title', date '$start', date '$end', $where, ${fee ?: "null"}, '$status')",
        )
    }

    private fun DSLContext.venueOf(code: String): Long? = fetchOne("select venue_id from gathering where public_code = ?", code)?.get(0, Long::class.javaObjectType)

    @Test
    fun `t20 c11 every different place becomes a venue with that place and the place cut to 60 characters as its name, and the gatherings with it choose it`() {
        // 60 characters, the last one a space: cut to 60 and trimmed it is 59, and two places that start the same have the same name.
        val sixty = "Casa per ritiri di esempio con un nome davvero molto lungo, "
        assertEquals(60, sixty.length)
        val north = sixty + "Località Nord"
        val south = sixty + "Località Sud"

        MigrationDatabase.use { database ->
            database.migrateTo("12")
            val places = database.dsl { db ->
                db.gathering("migr000000000001", "Convivenza di settembre 2024", "2024-09-13", "2024-09-15", "Casa sul lago")
                db.gathering("migr000000000002", "Convivenza di settembre 2025", "2025-09-12", "2025-09-14", "Casa per ritiri del bosco, Località Esempio")
                // The same place written with other capitals and spaces at the sides: the same venue, with the text of the most recent gathering.
                db.gathering("migr000000000003", "Convivenza di settembre 2026", "2026-09-18", "2026-09-20", "  CASA per ritiri del bosco, località esempio ")
                db.gathering("migr000000000004", "Convivenza di giugno 2026", "2026-06-12", "2026-06-14", north)
                db.gathering("migr000000000005", "Convivenza di luglio 2026", "2026-07-10", "2026-07-12", south)
                // A draft with no place: it stays with no venue.
                db.gathering("migr000000000006", "Convivenza di settembre 2027", "2027-09-17", "2027-09-19", null, status = "draft", fee = null)
                db.fetch("select public_code, place from gathering").associate { it.get(0, String::class.java) to it.get(1, String::class.java) }
            }

            database.migrateTo("13")

            database.dsl { db ->
                // The place is not a column of the gathering any more.
                assertEquals(0, db.fetchOne("select count(*) from information_schema.columns where table_name = 'gathering' and column_name = 'place'")!!.get(0, Int::class.java))
                val venues = db.fetch("select name, place from venue order by id").map { it.get(0, String::class.java) to it.get(1, String::class.java) }
                // One venue for each different place, the oldest gathering first: four, and not five.
                assertEquals(4, venues.size, venues.toString())
                assertEquals("Casa sul lago" to "Casa sul lago", venues[0])
                assertEquals("CASA per ritiri del bosco, località esempio" to "CASA per ritiri del bosco, località esempio", venues[1], "the text of the most recent gathering is the one kept")
                // Two long places with the same first 60 characters: the name of the first is the place cut and trimmed, the second is made unique with a number, still within 60.
                assertEquals(north.take(60).trim(), venues[2].first)
                assertTrue(venues[3].first.endsWith(" (2)") && venues[3].first.length <= 60, venues[3].first)
                assertTrue(venues[3].first.startsWith("Casa per ritiri di esempio con un nome davvero molto lun"), venues[3].first)
                assertEquals(listOf(north, south), venues.drop(2).map { it.second }, "the whole place is kept, only the name is cut")
                // Every name is unique, whatever the capitals and the spaces, and within 60 characters.
                assertEquals(venues.size, venues.map { it.first.trim().lowercase() }.toSet().size)
                assertTrue(venues.all { it.first.length in 1..60 && it.second.length in 1..200 })

                // The gatherings choose the venue that has their place, whatever the capitals and the spaces at the sides.
                val ids = db.fetch("select id from venue order by id").map { it.get(0, Long::class.java) }
                assertEquals(ids[0], db.venueOf("migr000000000001"))
                assertEquals(ids[1], db.venueOf("migr000000000002"))
                assertEquals(ids[1], db.venueOf("migr000000000003"))
                assertEquals(ids[2], db.venueOf("migr000000000004"))
                assertEquals(ids[3], db.venueOf("migr000000000005"))
                assertNull(db.venueOf("migr000000000006"), "a draft with no place stays with no venue")
                // The pages and the texts read the place of the venue, and it is the place of before (without the capitals and the spaces at the sides).
                for ((code, before) in places) {
                    if (before == null) continue
                    val now = db.fetchOne("select venue.place from gathering join venue on venue.id = gathering.venue_id where gathering.public_code = ?", code)!!.get(0, String::class.java)
                    assertEquals(before.trim().lowercase(), now.trim().lowercase(), code)
                }
                // The rule «complete outside the draft» is on the venue now: a gathering that is not a draft needs one.
                val refused = runCatching {
                    db.execute("insert into gathering (public_code, title, start_date, end_date, full_fee_cents, status) values ('migr000000000007', 'Senza struttura', date '2028-09-15', date '2028-09-17', 6000, 'open')")
                }.exceptionOrNull()
                assertTrue(refused?.message.orEmpty().contains("gathering_complete_when_not_draft"), refused?.message)
            }
        }
    }

    @Test
    fun `t20 c11 a database with no gathering migrates to no venue, and a place of 200 characters passes`() {
        val long = "L".repeat(200)

        MigrationDatabase.use { database ->
            database.migrateTo("12")
            database.dsl { db ->
                db.gathering("migr000000000011", "Convivenza di settembre 2025", "2025-09-12", "2025-09-14", long)
            }
            database.migrateTo("13")
            database.dsl { db ->
                val venue = db.fetchOne("select name, place from venue")!!
                assertEquals("L".repeat(60), venue.get(0, String::class.java))
                assertEquals(long, venue.get(1, String::class.java))
            }
        }
        MigrationDatabase.use { database ->
            database.migrateTo("13")
            database.dsl { db -> assertEquals(0, db.fetchOne("select count(*) from venue")!!.get(0, Int::class.java)) }
        }
    }
}
