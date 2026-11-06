package talita.venue

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import talita.TestcontainersConfiguration
import talita.gathering.GatheringSupport
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.VENUE
import java.sql.SQLException
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The schema of the venues (V13, TALITA-20, criteria 2, 6 and 9): what the database refuses by itself, whatever the application does. The application checks the same things first and with kinder
 * words; this is the net under it.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class VenueSchemaTests(
    @Autowired private val dsl: DSLContext,
    @Autowired clock: Clock,
) {

    private val support = GatheringSupport(dsl, clock)

    /** The name of the constraint (or index) that refused, found in the chain of the causes. */
    private fun refusedBy(block: () -> Unit): String {
        val failure = assertFailsWith<Exception> { block() }
        val sql = generateSequence<Throwable>(failure) { it.cause?.takeIf { cause -> cause !== it } }.filterIsInstance<SQLException>().first()
        return Regex("""(?:constraint|index) "([^"]+)"""").find(sql.message.orEmpty())?.groupValues?.get(1) ?: sql.message.orEmpty()
    }

    private fun insert(name: String = "Casa del bosco", place: String = "Casa per ritiri del bosco, Località Esempio") =
        dsl.insertInto(VENUE).set(VENUE.NAME, name).set(VENUE.PLACE, place).execute()

    @Test
    fun `t20 c02 the database refuses an empty name, one of only spaces and one of more than 60 characters, and takes 60`() {
        support.reset()

        assertEquals("venue_name_length", refusedBy { insert(name = "") })
        assertEquals("venue_name_length", refusedBy { insert(name = "    ") })
        assertEquals("venue_name_length", refusedBy { insert(name = "a".repeat(61)) })
        insert(name = "a".repeat(60))
        support.reset()
    }

    @Test
    fun `t20 c02 the database refuses an empty place and one of more than 200 characters, and takes 200`() {
        support.reset()

        assertEquals("venue_place_length", refusedBy { insert(place = "") })
        assertEquals("venue_place_length", refusedBy { insert(place = "   ") })
        assertEquals("venue_place_length", refusedBy { insert(place = "a".repeat(201)) })
        insert(place = "a".repeat(200))
        support.reset()
    }

    @Test
    fun `t20 c02 a name is unique whatever the capitals and the spaces at the sides, so two teachers who write it together leave one venue`() {
        support.reset()
        insert(name = "Casa del bosco")

        assertEquals("venue_name_unique", refusedBy { insert(name = "casa del BOSCO") })
        assertEquals("venue_name_unique", refusedBy { insert(name = "  Casa del bosco  ") })
        insert(name = "Casa sul lago")
        assertEquals(2, dsl.fetchCount(VENUE))
        support.reset()
    }

    @Test
    fun `t20 c06 the key from the gathering has no cascade, a venue that a gathering uses is not deleted and one that nothing uses is`() {
        support.reset()
        val used = support.makeVenue()
        val free = support.makeVenue("Casa per ritiri sul lago, Località Esempio")
        support.make(venue = used)

        val refused = refusedBy { dsl.deleteFrom(VENUE).where(VENUE.ID.eq(used)).execute() }
        assertTrue(refused.contains("fkey"), refused)
        assertEquals(2, dsl.fetchCount(VENUE), "nothing was deleted")
        assertEquals(1, dsl.deleteFrom(VENUE).where(VENUE.ID.eq(free)).execute())
        assertEquals(1, dsl.fetchCount(VENUE))
        // The gathering keeps its venue: the key points to a row that is there.
        assertEquals(used, dsl.select(GATHERING.VENUE_ID).from(GATHERING).fetchSingle(GATHERING.VENUE_ID))
        support.reset()
    }

    @Test
    fun `t20 c06 a gathering cannot point to a venue that does not exist`() {
        support.reset()
        val venue = support.makeVenue()

        val refused = refusedBy { support.make(venue = venue + 100_000) }

        assertTrue(refused.contains("fkey"), refused)
        support.reset()
    }

    @Test
    fun `t20 the table of the venues has only the columns of the design, and none of a person`() {
        val columns = dsl.fetch("select column_name from information_schema.columns where table_name = 'venue' order by ordinal_position").map { it.get(0, String::class.java) }

        assertEquals(listOf("id", "name", "place"), columns, "no referent, no telephone, no email, no address in fields, no map and no photo")
    }
}
