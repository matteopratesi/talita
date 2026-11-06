package talita.venue

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import talita.LockWait
import talita.TestcontainersConfiguration
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.VENUE
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The service of the venues (TALITA-20, part 1, criteria 2, 5 and 6), without the pages: the answers of each write, what the gathering reads through its interface, and the cases of two teachers at the
 * same moment, with real transactions. Names and places are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class VenueServiceTests(
    @Autowired private val dsl: DSLContext,
    @Autowired clock: Clock,
    @Autowired private val venues: Venues,
) {

    private val support = GatheringSupport(dsl, clock)

    @Test
    fun `t20 c02 two teachers who write the same name at the same moment leave one venue, and the second gets the answer that the name is taken`() {
        support.reset()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val results = listOf("Casa del bosco", "  casa DEL bosco ").map { name ->
                pool.submit<VenueSave> { start.await(10, TimeUnit.SECONDS); venues.create(name, "Casa per ritiri del bosco, Località Esempio") }
            }
            start.countDown()

            val answers = results.map { it.get(20, TimeUnit.SECONDS) }

            assertEquals(1, answers.count { it is VenueSave.Saved }, answers.toString())
            assertEquals(1, answers.count { it == VenueSave.NameTaken }, answers.toString())
            assertEquals(1, dsl.fetchCount(VENUE), "one venue, never two with the same name")
        } finally {
            pool.shutdownNow()
            support.reset()
        }
    }

    @Test
    fun `t20 c02 the change of a venue that is not there is an answer, and so is a name that another venue has, and nothing is written`() {
        support.reset()
        val wood = support.makeVenue("Casa per ritiri del bosco", "Casa del bosco")
        val lake = support.makeVenue("Casa per ritiri sul lago", "Casa sul lago")

        assertEquals(VenueSave.NotFound, venues.update(lake + 100_000, "Casa nuova", "Luogo"))
        assertEquals(VenueSave.NameTaken, venues.update(lake, "CASA DEL BOSCO", "Luogo"))
        assertEquals(VenueSave.NameTaken, venues.create(" casa sul lago ", "Luogo"))
        val saved = assertIs<VenueSave.Saved>(venues.update(wood, "Casa del bosco grande", "Un luogo"))
        assertEquals(Venue(wood, "Casa del bosco grande", "Un luogo"), saved.venue)
        assertEquals(0, saved.gatherings, "no gathering uses it")
        assertEquals(listOf("Casa del bosco grande", "Casa sul lago"), venues.list().map { it.venue.name })
        support.reset()
    }

    @Test
    fun `t20 c06 the list counts the gatherings that use each venue, and what the gathering reads is the same as the page`() {
        support.reset()
        val wood = support.makeVenue("Casa per ritiri del bosco", "Casa del bosco")
        val lake = support.makeVenue("Casa per ritiri sul lago", "Casa sul lago")
        support.make(status = GatheringStatus.draft, venue = wood)
        val end = support.today().minusDays(300)
        support.make(title = "Convivenza di settembre 2026", start = end.minusDays(2), end = end, status = GatheringStatus.closed, venue = wood)

        assertEquals(listOf(Venue(wood, "Casa del bosco", "Casa per ritiri del bosco") to 2, Venue(lake, "Casa sul lago", "Casa per ritiri sul lago") to 0), venues.list().map { it.venue to it.gatherings })
        assertEquals(2, venues.usage(wood))
        assertEquals(0, venues.usage(lake))
        // What the gathering reads through its interface.
        assertEquals(talita.gathering.VenueRef(wood, "Casa del bosco", "Casa per ritiri del bosco"), venues.find(wood))
        assertNull(venues.find(lake + 100_000))
        assertEquals(listOf("Casa del bosco", "Casa sul lago"), venues.all().map { it.name })
        dsl.transaction { configuration ->
            val tx = DSL.using(configuration)
            assertEquals("Casa del bosco", venues.findIn(tx, wood)?.name)
            assertEquals("Casa sul lago", venues.findIn(tx, lake, lock = true)?.name)
            assertNull(venues.findIn(tx, lake + 100_000, lock = true))
        }
        assertEquals(VenueRemoval.InUse(2), venues.remove(wood))
        assertEquals(VenueRemoval.Removed("Casa sul lago"), venues.remove(lake))
        assertEquals(VenueRemoval.NotFound, venues.remove(lake))
        support.reset()
    }

    @Test
    fun `t20 c06 a venue that a gathering is choosing at the same moment is not taken away, and the gathering keeps it`() {
        support.reset()
        val wood = support.makeVenue("Casa per ritiri del bosco", "Casa del bosco")
        val gathering = support.make(status = GatheringStatus.draft, place = null, full = null)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val choosing = CountDownLatch(1)
            val finish = CountDownLatch(1)
            // The gathering chooses the venue as `saveDates` does: the venue is locked for sharing, then the gathering writes its key. It has not ended yet.
            val choose = pool.submit {
                dsl.transaction { configuration ->
                    val tx = DSL.using(configuration)
                    assertEquals("Casa del bosco", venues.findIn(tx, wood, lock = true)?.name)
                    choosing.countDown()
                    finish.await(20, TimeUnit.SECONDS)
                    tx.update(GATHERING).set(GATHERING.VENUE_ID, wood).where(GATHERING.ID.eq(gathering)).execute()
                }
            }
            assertTrue(choosing.await(10, TimeUnit.SECONDS))
            val removing = LockWait.awaitBlocked(dsl, "the removal should wait for the gathering that is choosing the venue") { pool.submit<VenueRemoval> { venues.remove(wood) } }
            assertFalse(removing.isDone)

            finish.countDown()
            choose.get(20, TimeUnit.SECONDS)

            assertEquals(VenueRemoval.InUse(1), removing.get(20, TimeUnit.SECONDS))
            assertEquals(wood, support.venueIdOf(gathering))
            assertEquals(1, dsl.fetchCount(VENUE))
        } finally {
            pool.shutdownNow()
            support.reset()
        }
    }

    @Test
    fun `t20 c06 a gathering that chooses a venue that is being taken away finds it gone, and the answer says so`() {
        support.reset()
        val wood = support.makeVenue("Casa per ritiri del bosco", "Casa del bosco")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val removed = CountDownLatch(1)
            val finish = CountDownLatch(1)
            // The removal has deleted the row and not ended: the row of the venue is locked.
            val removing = pool.submit {
                dsl.transaction { configuration ->
                    val tx = DSL.using(configuration)
                    tx.select(VENUE.NAME).from(VENUE).where(VENUE.ID.eq(wood)).forUpdate().fetch()
                    tx.deleteFrom(VENUE).where(VENUE.ID.eq(wood)).execute()
                    removed.countDown()
                    finish.await(20, TimeUnit.SECONDS)
                }
            }
            assertTrue(removed.await(10, TimeUnit.SECONDS))
            val choosing = LockWait.awaitBlocked(dsl, "the choice of the venue should wait for its removal") {
                pool.submit<talita.gathering.VenueRef?> { dsl.transactionResult { configuration -> venues.findIn(DSL.using(configuration), wood, lock = true) } }
            }

            finish.countDown()
            removing.get(20, TimeUnit.SECONDS)

            assertNull(choosing.get(20, TimeUnit.SECONDS), "once the removal has ended the venue is not there")
        } finally {
            pool.shutdownNow()
            support.reset()
        }
    }
}
