package talita.gathering

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.TestAccounts
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.GATHERING_CUSTODIAN
import talita.jooq.tables.references.TEACHER
import java.sql.SQLException
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The schema of the gathering (V4): what the database refuses by itself, whatever the application does (criteria 3, 15, 22). The
 * application checks the same things first and with kinder words; this is the net under it.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class GatheringSchemaTests(
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

    private fun insert(
        title: String = "Convivenza di settembre 2027",
        start: LocalDate = LocalDate.of(2027, 9, 17),
        end: LocalDate = LocalDate.of(2027, 9, 19),
        code: String = GatheringSupport.nextCode(),
        status: GatheringStatus = GatheringStatus.draft,
        place: String? = null,
        fullFee: Int? = null,
        dailyFee: Int? = null,
        info: String? = null,
        bring: String? = null,
    ) = dsl.insertInto(GATHERING)
        .set(GATHERING.PUBLIC_CODE, code).set(GATHERING.TITLE, title).set(GATHERING.START_DATE, start).set(GATHERING.END_DATE, end)
        .set(GATHERING.PLACE, place).set(GATHERING.FULL_FEE_CENTS, fullFee).set(GATHERING.DAILY_FEE_CENTS, dailyFee)
        .set(GATHERING.INFO_TEXT, info).set(GATHERING.WHAT_TO_BRING, bring).set(GATHERING.STATUS, status)
        .execute()

    @Test
    fun `t4 c03 the database refuses an empty title, one of only spaces and one of more than 100 characters`() {
        support.reset()

        assertEquals("gathering_title_length", refusedBy { insert(title = "") })
        assertEquals("gathering_title_length", refusedBy { insert(title = "   ") })
        assertEquals("gathering_title_length", refusedBy { insert(title = "a".repeat(101)) })
        insert(title = "a".repeat(100))
        support.reset()
    }

    @Test
    fun `t4 c03 the database refuses an end before the start and a gathering longer than seven days`() {
        support.reset()

        assertEquals("gathering_dates_order", refusedBy { insert(start = LocalDate.of(2027, 9, 19), end = LocalDate.of(2027, 9, 17)) })
        assertEquals("gathering_duration", refusedBy { insert(start = LocalDate.of(2027, 9, 17), end = LocalDate.of(2027, 9, 24)) })
        insert(start = LocalDate.of(2027, 9, 17), end = LocalDate.of(2027, 9, 23))
        support.reset()
    }

    @Test
    fun `t4 c07 the database refuses a fee that is negative or over 1000 euros, and takes zero and nothing`() {
        support.reset()

        assertEquals("gathering_full_fee_range", refusedBy { insert(fullFee = -1) })
        assertEquals("gathering_full_fee_range", refusedBy { insert(fullFee = 100001) })
        assertEquals("gathering_daily_fee_range", refusedBy { insert(dailyFee = -500) })
        insert(fullFee = 0, dailyFee = 100000)
        support.reset()
        insert(fullFee = null, dailyFee = null)
        support.reset()
    }

    @Test
    fun `t4 c05 the database refuses a place, an information text and a list that are too long`() {
        support.reset()

        assertEquals("gathering_place_length", refusedBy { insert(place = "a".repeat(201)) })
        assertEquals("gathering_place_length", refusedBy { insert(place = "") })
        assertEquals("gathering_info_text_length", refusedBy { insert(info = "a".repeat(5001)) })
        assertEquals("gathering_what_to_bring_length", refusedBy { insert(bring = "a".repeat(2001)) })
        insert(place = "a".repeat(200), info = "a".repeat(5000), bring = "a".repeat(2000))
        support.reset()
    }

    @Test
    fun `t4 c10 a gathering that is open or closed has a place and a full fee, a draft does not need them`() {
        support.reset()

        assertEquals("gathering_complete_when_not_draft", refusedBy { insert(status = GatheringStatus.`open`, place = null, fullFee = 6000) })
        assertEquals("gathering_complete_when_not_draft", refusedBy { insert(status = GatheringStatus.`open`, place = "Casa per ritiri di esempio", fullFee = null) })
        assertEquals("gathering_complete_when_not_draft", refusedBy { insert(status = GatheringStatus.closed, place = null, fullFee = null) })
        insert(status = GatheringStatus.`open`, place = "Casa per ritiri di esempio", fullFee = 0)
        support.reset()
        insert(status = GatheringStatus.draft, place = null, fullFee = null)
        support.reset()
    }

    @Test
    fun `t4 c15 the database lets only one gathering be a draft or open, and as many be closed as there are`() {
        support.reset()
        insert(status = GatheringStatus.draft)

        assertEquals("gathering_only_one_not_closed", refusedBy { insert(status = GatheringStatus.draft) })
        support.reset()
        insert(status = GatheringStatus.`open`, place = "Casa per ritiri di esempio", fullFee = 6000)
        assertEquals("gathering_only_one_not_closed", refusedBy { insert(status = GatheringStatus.draft) })
        support.reset()
        // Closed ones do not count: a new one can come, and the closed ones can be many.
        insert(status = GatheringStatus.closed, place = "Casa per ritiri di esempio", fullFee = 6000)
        insert(status = GatheringStatus.closed, place = "Casa per ritiri di esempio", fullFee = 6000)
        insert(status = GatheringStatus.draft)
        assertEquals(3, dsl.fetchCount(GATHERING))
        // Reopening a closed one while another is a draft is refused as well.
        val closedId = dsl.select(GATHERING.ID).from(GATHERING).where(GATHERING.STATUS.eq(GatheringStatus.closed)).limit(1).fetchSingle(GATHERING.ID)!!
        assertEquals("gathering_only_one_not_closed", refusedBy { dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.`open`).where(GATHERING.ID.eq(closedId)).execute() })
        support.reset()
    }

    @Test
    fun `t4 c22 the code has the shape of 16 characters of base64url and is unique`() {
        support.reset()

        for (bad in listOf("", "corto", "1234567890123456a", "1234567890 123456", "123456789012345è", "123456789012345=")) {
            assertEquals("gathering_public_code_format", refusedBy { insert(code = bad) }, bad)
        }
        insert(code = "q7Vd_3KmZ0xRb-Ew", status = GatheringStatus.closed, place = "Casa per ritiri di esempio", fullFee = 6000)
        assertEquals("gathering_public_code_key", refusedBy { insert(code = "q7Vd_3KmZ0xRb-Ew") })
        // A code that differs only by a capital letter is another code.
        insert(code = "Q7Vd_3KmZ0xRb-Ew")
        support.reset()
    }

    @Test
    fun `t4 c40 the custodians go with the gathering and with the teacher, and only a teacher can be one`() {
        support.reset()
        val me = Addresses.unique("schema-maestro")
        val teacher = TestAccounts.makeTeacher(dsl, me, "Marta", "Bianchi")
        val parent = dsl.insertInto(talita.jooq.tables.references.ACCOUNT).set(talita.jooq.tables.references.ACCOUNT.EMAIL, Addresses.unique("schema-genitore")).set(talita.jooq.tables.references.ACCOUNT.CREATED_ON, java.time.LocalDate.now()).returningResult(talita.jooq.tables.references.ACCOUNT.ID).fetchSingle().value1()!!
        val id = support.make(custodians = listOf(teacher))

        // Somebody who is only a parent is not a teacher: the key says no.
        assertTrue(refusedBy { dsl.insertInto(GATHERING_CUSTODIAN).set(GATHERING_CUSTODIAN.GATHERING_ID, id).set(GATHERING_CUSTODIAN.TEACHER_ACCOUNT_ID, parent).execute() }.contains("teacher_account_id"))
        // Twice the same teacher is one row.
        assertTrue(refusedBy { dsl.insertInto(GATHERING_CUSTODIAN).set(GATHERING_CUSTODIAN.GATHERING_ID, id).set(GATHERING_CUSTODIAN.TEACHER_ACCOUNT_ID, teacher).execute() }.contains("gathering_custodian_pkey"))

        // The teacher is taken away (the table, as «Togli» does once the application has let it): the link goes, the gathering stays.
        dsl.deleteFrom(TEACHER).where(TEACHER.ACCOUNT_ID.eq(teacher)).execute()
        assertEquals(emptyList(), support.custodiansOf(id))
        assertEquals(1, dsl.fetchCount(GATHERING, GATHERING.ID.eq(id)))
        // And the gathering goes with its links.
        val again = TestAccounts.makeTeacher(dsl, me, "Marta", "Bianchi")
        dsl.insertInto(GATHERING_CUSTODIAN).set(GATHERING_CUSTODIAN.GATHERING_ID, id).set(GATHERING_CUSTODIAN.TEACHER_ACCOUNT_ID, again).execute()
        dsl.deleteFrom(GATHERING).where(GATHERING.ID.eq(id)).execute()
        assertEquals(0, dsl.fetchCount(GATHERING_CUSTODIAN, GATHERING_CUSTODIAN.GATHERING_ID.eq(id)))
        support.reset()
    }
}
