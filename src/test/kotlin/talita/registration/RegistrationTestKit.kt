package talita.registration

import org.jooq.DSLContext
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.Addresses
import talita.account.MockBrowser
import talita.gathering.GatheringTestKit
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.HEALTH_NOTE
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.REGISTRATION_DAY
import talita.teacher.Person
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate

/** A gathering as the tests of the registrations need it: open, three days from a Friday, with Marta Bianchi and Paolo Verdi as custodians. */
internal data class Setup(
    val id: Long,
    val code: String,
    /** The teacher browser (Marta Bianchi) and her account. */
    val marta: MockBrowser,
    val martaId: Long,
    val paoloId: Long,
    val friday: LocalDate,
) {
    val saturday: LocalDate get() = friday.plusDays(1)
    val sunday: LocalDate get() = friday.plusDays(2)
    val form: String get() = "/info/$code/iscrizione"
}

/**
 * What the tests of the registrations have in common (criteria 1-38): the gathering with its teachers, the parents who have entered with the link,
 * the fields of a form as the pages name them, and a few readings of the tables. Names, dates and numbers are synthetic; the dates of the gathering
 * come from the day of the test clock, from the first Friday that is a month away, so that the days of the week are always the same.
 */
internal class RegistrationTestKit(mockMvc: MockMvc, tokens: OneTimeTokenService, private val dsl: DSLContext, clock: Clock) {

    val kit = GatheringTestKit(mockMvc, tokens, dsl, clock)

    /** Resets everything and makes an open gathering of three days (Friday to Sunday) with Marta Bianchi and Paolo Verdi; the parameters change how it is made. */
    fun start(
        status: GatheringStatus = GatheringStatus.`open`,
        full: Int? = 6000,
        daily: Int? = 2500,
        lunch: Int? = 1000,
        days: Int = 3,
        startOffset: Long = 30,
    ): Setup {
        val marta = Person(Addresses.unique("marta"), "Marta", "Bianchi")
        val paolo = Person(Addresses.unique("paolo"), "Paolo", "Verdi")
        val (browser, martaId) = kit.start(marta, paolo)
        val paoloId = kit.accountId(paolo.email)
        var friday = kit.gatherings.today().plusDays(startOffset)
        // From a Friday, with the days in one month, so that the sentences about the days are always the same.
        while (friday.dayOfWeek != DayOfWeek.FRIDAY || friday.plusDays(days - 1L).month != friday.month) friday = friday.plusDays(1)
        val id = kit.gatherings.make(
            start = friday, end = friday.plusDays(days - 1L), status = status, full = full, daily = daily, lunchOnly = lunch, custodians = listOf(martaId, paoloId),
        )
        return Setup(id, kit.codeOf(id), browser, martaId, paoloId, friday)
    }

    /** The gathering of [setup] is closed and another one opens, from a fortnight later, with the same custodians: the year after. */
    fun nextGathering(setup: Setup, status: GatheringStatus = GatheringStatus.`open`): Setup {
        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).where(GATHERING.ID.eq(setup.id)).execute()
        val friday = setup.friday.plusDays(14)
        val id = kit.gatherings.make(title = "Convivenza dell’anno dopo", start = friday, end = friday.plusDays(2), status = status, custodians = listOf(setup.martaId, setup.paoloId))
        return setup.copy(id = id, code = kit.codeOf(id), friday = friday)
    }

    /** A parent who has entered. */
    fun parent(name: String = "genitore"): MockBrowser = kit.parent(Addresses.unique(name))

    // ---- The fields of a form -------------------------------------------------------------------------------------------------

    /** The fields of one block, as the page names them. */
    fun child(
        index: Int = 0,
        first: String = "Luca",
        last: String = "Rossi",
        day: String = "4",
        month: String = "4",
        year: String = "2016",
        presence: String = "all_days",
        status: String = "confirmed",
        vararg more: Pair<String, String>,
    ): Map<String, String> {
        val p = "children[$index]."
        return mapOf(
            p + "firstName" to first, p + "lastName" to last, p + "birthDay" to day, p + "birthMonth" to month, p + "birthYear" to year,
            p + "presence" to presence, p + "status" to status,
        ) + more.associate { (key, value) -> p + key to value }
    }

    /** A whole form: the phone and the blocks. */
    fun form(vararg children: Map<String, String>, phone: String = "333 123 4567"): Map<String, String> =
        children.fold(mapOf("phone" to phone)) { all, block -> all + block }

    /** The boxes of step 2 ticked: the three, or the ones that are given. */
    fun boxes(accept: Boolean = true, other: Boolean = true, religion: Boolean = true): Map<String, String> =
        buildMap {
            if (accept) put("acceptCustody", "true")
            if (other) put("otherParent", "true")
            if (religion) put("religionConsent", "true")
        }

    /** The fingerprint of the text that step 2 shows, read from its page. */
    fun hashIn(html: String): String = checkNotNull(Regex("""name="textHash" value="([0-9a-f]{64})"""").find(html)?.groupValues?.get(1)) { "the page has no fingerprint of the text" }

    /** The fields of step 2 as the browser sends them back: the hidden fields of the page and the boxes. */
    fun hiddenFields(html: String): Map<String, String> =
        Regex("""<input type="hidden" name="([^"]+)" value="([^"]*)">""").findAll(html)
            .filter { it.groupValues[1] != "_csrf" }
            .associate { it.groupValues[1] to unescape(it.groupValues[2]) }

    private fun unescape(text: String): String = text.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /**
     * A good form sent through the two steps by a parent, the way a person does: the first step, then the confirmation with the boxes and what the second
     * step carries. Returns the answer to the confirmation.
     */
    fun register(parent: MockBrowser, setup: Setup, form: Map<String, String>, boxes: Map<String, String> = boxes()): org.springframework.test.web.servlet.MvcResult {
        val second = parent.submit(setup.form, setup.form, form)
        check(second.response.status == 200) { "the first step did not lead to the second: ${second.response.status}\n${second.response.contentAsString.take(3000)}" }
        val html = second.response.contentAsString
        return parent.submit(setup.form, "${setup.form}/conferma", hiddenFields(html) + ("textHash" to hashIn(html)) + boxes)
    }

    // ---- The tables -------------------------------------------------------------------------------------------------------------

    fun registrationCount(gathering: Long): Int = dsl.fetchCount(REGISTRATION, REGISTRATION.GATHERING_ID.eq(gathering))

    fun childCount(): Int = dsl.fetchCount(CHILD)

    fun healthNoteCount(): Int = dsl.fetchCount(HEALTH_NOTE)

    fun readingCount(): Int = dsl.fetchCount(HEALTH_NOTE_ACCESS)

    fun accountId(email: String): Long = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(email)).fetchSingle(ACCOUNT.ID)!!

    fun emailOf(accountId: Long): String = dsl.select(ACCOUNT.EMAIL).from(ACCOUNT).where(ACCOUNT.ID.eq(accountId)).fetchSingle(ACCOUNT.EMAIL)!!

    /** The registrations of a gathering, by when they were made. */
    fun registrations(gathering: Long): List<talita.jooq.tables.records.RegistrationRecord> =
        dsl.selectFrom(REGISTRATION).where(REGISTRATION.GATHERING_ID.eq(gathering)).orderBy(REGISTRATION.ID).fetch()

    fun daysOf(registration: Long): Map<LocalDate, String> =
        dsl.select(REGISTRATION_DAY.DAY, REGISTRATION_DAY.KIND).from(REGISTRATION_DAY).where(REGISTRATION_DAY.REGISTRATION_ID.eq(registration))
            .fetch().associate { it[REGISTRATION_DAY.DAY]!! to it[REGISTRATION_DAY.KIND]!!.literal }

    /** The id of the registration of the child with this first name at the gathering. */
    fun idOf(gathering: Long, firstName: String): Long =
        dsl.select(REGISTRATION.ID).from(REGISTRATION).join(CHILD).on(CHILD.ID.eq(REGISTRATION.CHILD_ID))
            .where(REGISTRATION.GATHERING_ID.eq(gathering)).and(CHILD.FIRST_NAME.eq(firstName)).fetchSingle(REGISTRATION.ID)!!

    /** The gathering ended three days ago (a gathering over, in the state it was in). */
    fun makeOver(gathering: Long) {
        val today = kit.gatherings.today()
        dsl.update(GATHERING).set(GATHERING.START_DATE, today.minusDays(5)).set(GATHERING.END_DATE, today.minusDays(3)).where(GATHERING.ID.eq(gathering)).execute()
    }

    fun setState(gathering: Long, status: GatheringStatus) {
        dsl.update(GATHERING).set(GATHERING.STATUS, status).where(GATHERING.ID.eq(gathering)).execute()
    }

    fun setStatus(registration: Long, status: RegistrationStatus) {
        dsl.update(REGISTRATION).set(REGISTRATION.STATUS, status).where(REGISTRATION.ID.eq(registration)).execute()
    }
}
