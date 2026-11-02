package talita.registration

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import talita.LockWait
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.gathering.GatheringReader
import talita.gathering.body
import talita.gathering.status
import talita.gathering.text
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.VENUE
import talita.venue.VenueSave
import talita.venue.Venues
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The venue and the text of the custody (TALITA-20, part 1, criteria 4 and 5). The text that a parent accepts has the place of the venue and not its name: a change of the place makes a text that a parent has open an
 * old one (`TextChanged`: nothing is saved), a change of the name does not, and the texts that were accepted never change. A parent who confirms and a teacher who changes the place of the venue are in a queue,
 * in both orders (two real transactions), and the text that is saved has always the place that the database has at that moment. Names and places are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class VenueTextTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val venues: Venues,
    @Autowired private val reader: GatheringReader,
) {

    private val kit = RegistrationTestKit(mockMvc, tokens, dsl, clock)

    private val oldPlace = "Casa per ritiri di esempio"
    private val newPlace = "Casa per ritiri sul lago, Località Esempio"
    private val textChanged = "Mentre leggevi, i maestri hanno cambiato il testo dell’affidamento"

    private fun venueOf(setup: Setup): Long = kit.kit.gatherings.venueIdOf(setup.id)!!

    private fun second(parent: MockBrowser, setup: Setup, form: Map<String, String>): String = parent.submit(setup.form, setup.form, form).body()

    private fun confirm(parent: MockBrowser, setup: Setup, html: String): MvcResult =
        parent.submit(setup.form, "${setup.form}/conferma", kit.hiddenFields(html) + ("textHash" to kit.hashIn(html)) + kit.boxes())

    @Test
    fun `t20 c04 the text to accept has the place of the venue and not its name, and the place that changes makes an open text old while the name does not`() {
        val setup = kit.start()
        val venue = venueOf(setup)
        val parent = kit.parent()
        val html = second(parent, setup, kit.form(kit.child()))
        assertTrue(text(html).contains("che si svolge a $oldPlace dal"), text(html).take(2000))

        // The name changes: the text has not changed, so the confirmation goes through.
        assertIs<VenueSave.Saved>(venues.update(venue, "Casa del bosco, con un altro nome", oldPlace))
        val saved = confirm(parent, setup, html)
        assertEquals(303, saved.status())
        val accepted = kit.registrations(setup.id).single().custodyText!!
        assertTrue(accepted.contains(oldPlace), accepted)
        assertFalse(accepted.contains("con un altro nome"), "the name of the venue is not in the text of the custody")

        // The place changes while another parent reads: nothing is saved, and the text that comes back has the new place.
        val other = kit.parent("altro")
        val open = second(other, setup, kit.form(kit.child(first = "Anna")))
        assertIs<VenueSave.Saved>(venues.update(venue, "Casa del bosco, con un altro nome", newPlace))
        val refused = confirm(other, setup, open)
        assertEquals(409, refused.status())
        assertTrue(text(refused.body()).contains(textChanged), text(refused.body()))
        assertTrue(text(refused.body()).contains(newPlace), "the page has the text of now, to read again")
        assertEquals(1, kit.registrationCount(setup.id), "nothing else was saved")
        // The text that was accepted does not change: it keeps the place of then, and the detail of the registration says it.
        assertEquals(accepted, kit.registrations(setup.id).single().custodyText)
        assertTrue(accepted.contains(oldPlace) && !accepted.contains(newPlace))
    }

    @Test
    fun `t20 c04 another venue for the gathering also makes an open text old, and the text that comes back has the place of the new venue`() {
        val setup = kit.start()
        val parent = kit.parent()
        val html = second(parent, setup, kit.form(kit.child()))
        val lake = kit.kit.gatherings.makeVenue(newPlace, "Casa sul lago")

        dsl.update(GATHERING).set(GATHERING.VENUE_ID, lake).where(GATHERING.ID.eq(setup.id)).execute()
        val answer = confirm(parent, setup, html)

        assertEquals(409, answer.status())
        assertTrue(text(answer.body()).contains(newPlace))
        assertEquals(0, kit.registrationCount(setup.id))
    }

    @Test
    fun `t20 c05 a teacher who changes the place of the venue waits for a registration that is being confirmed, and the text that was read is the one that is saved`() {
        val setup = kit.start()
        val venue = venueOf(setup)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reading = CountDownLatch(1)
            val finish = CountDownLatch(1)
            // The confirmation reads the gathering and its venue for sharing, as `Registrations` does, and goes on to write.
            val confirming = pool.submit<String?> {
                dsl.transactionResult { configuration ->
                    val tx = DSL.using(configuration)
                    val info = reader.sharedIn(tx, setup.id)!!
                    reading.countDown()
                    finish.await(20, TimeUnit.SECONDS)
                    info.place
                }
            }
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            val changing = LockWait.awaitBlocked(dsl, "the change of the place should wait for the confirmation that is reading it") {
                pool.submit<VenueSave> { venues.update(venue, "Casa del bosco", newPlace) }
            }
            assertFalse(changing.isDone)

            finish.countDown()

            assertEquals(oldPlace, confirming.get(20, TimeUnit.SECONDS), "the confirmation saw the place of that moment")
            assertIs<VenueSave.Saved>(changing.get(20, TimeUnit.SECONDS))
            assertEquals(newPlace, dsl.select(VENUE.PLACE).from(VENUE).where(VENUE.ID.eq(venue)).fetchSingle(VENUE.PLACE))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `t20 c05 a registration that is confirmed while the place of the venue is being changed waits, then finds the new place and saves nothing`() {
        val setup = kit.start()
        val venue = venueOf(setup)
        val parent = kit.parent()
        val html = second(parent, setup, kit.form(kit.child()))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val changed = CountDownLatch(1)
            val finish = CountDownLatch(1)
            // The change of the place that has written its row and not ended: the row of the venue is locked.
            val changing = pool.submit {
                dsl.transaction { configuration ->
                    val tx = DSL.using(configuration)
                    tx.select(VENUE.ID).from(VENUE).where(VENUE.ID.eq(venue)).forUpdate().fetch()
                    tx.update(VENUE).set(VENUE.PLACE, newPlace).where(VENUE.ID.eq(venue)).execute()
                    changed.countDown()
                    finish.await(20, TimeUnit.SECONDS)
                }
            }
            assertTrue(changed.await(10, TimeUnit.SECONDS))
            val confirming = LockWait.awaitBlocked(dsl, "the confirmation should wait for the change of the place") {
                pool.submit<MvcResult> { confirm(parent, setup, html) }
            }
            assertFalse(confirming.isDone)

            finish.countDown()
            changing.get(20, TimeUnit.SECONDS)

            val answer = confirming.get(20, TimeUnit.SECONDS)
            assertEquals(409, answer.status(), "the text was read with the old place and the database has the new one")
            assertTrue(text(answer.body()).contains(textChanged))
            assertEquals(0, kit.registrationCount(setup.id), "nothing was saved")
        } finally {
            pool.shutdownNow()
        }
    }
}
