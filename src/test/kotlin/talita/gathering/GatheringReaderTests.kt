package talita.gathering

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.jooq.enums.GatheringStatus
import talita.teacher.Person
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The service that TALITA-5, 6 and 7 read the gathering from (criterion 36): by code and the current one, with the days and the rules worked out. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class GatheringReaderTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired clock: Clock,
    @Autowired private val reader: GatheringReader,
) {

    private val kit = GatheringTestKit(mockMvc, tokens, dsl, clock)
    private val gatherings = kit.gatherings

    private val marta = Person(Addresses.unique("lettura-marta"), "Marta", "Bianchi")
    private val paolo = Person(Addresses.unique("lettura-paolo"), "Paolo", "Verdi")

    @Test
    fun `t4 c36 by code it gives id, code, title, dates, the days, the place, the fees in cents, the custodians in order, the state and the two answers about today`() {
        kit.start(marta, paolo)
        val start = gatherings.today().plusDays(30)
        val id = gatherings.make(
            title = "Convivenza di settembre 2027", start = start, end = start.plusDays(2), status = GatheringStatus.`open`,
            place = "Casa per ritiri di esempio", full = 6000, daily = 2500, lunchOnly = null,
            code = "q7Vd_3KmZ0xRb-Ew", custodians = listOf(kit.accountId(paolo.email), kit.accountId(marta.email)),
        )

        val info = reader.byCode("q7Vd_3KmZ0xRb-Ew")

        assertNotNull(info)
        assertEquals(id, info.id)
        assertEquals("q7Vd_3KmZ0xRb-Ew", info.code)
        assertEquals("Convivenza di settembre 2027", info.title)
        assertEquals(start, info.start)
        assertEquals(start.plusDays(2), info.end)
        assertEquals(listOf(start, start.plusDays(1), start.plusDays(2)), info.days)
        assertEquals("Casa per ritiri di esempio", info.place)
        assertEquals(Fees(6000, 2500, null), info.fees)
        assertEquals(listOf("Marta Bianchi", "Paolo Verdi"), info.custodians.map { it.name }, "by surname and name")
        assertEquals(listOf(kit.accountId(marta.email), kit.accountId(paolo.email)), info.custodians.map { it.accountId })
        assertEquals(listOf("Marta", "Paolo"), info.custodians.map { it.first })
        assertEquals(listOf("Bianchi", "Verdi"), info.custodians.map { it.last })
        assertEquals(GatheringState.OPEN, info.state)
        assertFalse(info.over)
        assertTrue(info.registrationsPossibleToday)
    }

    @Test
    fun `t4 c36 a code that does not exist, or that differs by a capital letter, is nobody`() {
        kit.start()
        gatherings.make(code = "q7Vd_3KmZ0xRb-Ew")

        assertNull(reader.byCode("Q7Vd_3KmZ0xRb-Ew"))
        assertNull(reader.byCode("q7Vd_3KmZ0xRb-E"))
        assertNull(reader.byCode(""))
        assertNotNull(reader.byCode("q7Vd_3KmZ0xRb-Ew"))
    }

    @Test
    fun `t4 c36 the current one is the draft or open one, and there is none when everything is closed`() {
        kit.start()
        assertNull(reader.current())

        gatherings.make(title = "Convivenza di settembre 2026", status = GatheringStatus.closed)
        assertNull(reader.current())
        val draft = gatherings.make(title = "Convivenza di settembre 2027", status = GatheringStatus.draft)
        assertEquals(draft, reader.current()?.id)
        assertEquals(GatheringState.DRAFT, reader.current()?.state)
        assertFalse(reader.current()!!.registrationsPossibleToday, "a draft takes no registrations")
    }

    @Test
    fun `t4 c36 over and registrations possible today follow the end and the state, with the day of Rome`() {
        kit.start()
        val today = gatherings.today()

        val endsToday = gatherings.make(status = GatheringStatus.`open`, start = today.minusDays(2), end = today)
        reader.byId(endsToday)!!.let {
            assertFalse(it.over, "the last day is today")
            assertTrue(it.registrationsPossibleToday)
        }
        gatherings.reset()
        val endedYesterday = gatherings.make(status = GatheringStatus.`open`, start = today.minusDays(3), end = today.minusDays(1))
        reader.byId(endedYesterday)!!.let {
            assertTrue(it.over)
            assertFalse(it.registrationsPossibleToday, "open but over")
        }
        gatherings.reset()
        val closed = gatherings.make(status = GatheringStatus.closed, start = today.plusDays(5), end = today.plusDays(7))
        reader.byId(closed)!!.let {
            assertFalse(it.over)
            assertFalse(it.registrationsPossibleToday, "closed")
        }
    }

    @Test
    fun `t4 c36 a gathering with no custodian and no fee says so with an empty list and nulls`() {
        kit.start()
        val id = gatherings.make(status = GatheringStatus.draft, place = null, full = null, daily = null, lunchOnly = null)

        val info = reader.byId(id)!!

        assertEquals(emptyList(), info.custodians)
        assertNull(info.place)
        assertEquals(Fees(null, null, null), info.fees)
        assertTrue(info.fees.none)
    }
}
