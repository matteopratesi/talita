package talita.reminder

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.TEACHER_INVITATION
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who receives each reminder (TALITA-13, criteria 1 and 10), on the real database: the examples of the analysis (A, B, C, D, Nicolò, M, N) and every case that is left
 * out. The rule is one (`Reminders.recipients`), and the counts that the teachers see are the same people, so a test compares the two.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ReminderRecipientsTests(
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val reminders: Reminders,
) {

    private val kit = ReminderTestKit(dsl, clock)

    @BeforeEach
    fun clean() = kit.reset()

    private fun who(gathering: Long, type: ReminderType): List<Long> =
        reminders.recipients(dsl, gathering, type).map { it.accountId }.also { assertEquals(it.size, reminders.count(gathering, type), "the count and the people are the same") }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock)

    @Test
    fun `t13 c01 the example of the analysis, in forse goes to A alone and the avviso to B alone`() {
        val s = kit.scenario()

        assertEquals(listOf(s.a.id), who(s.gathering, ReminderType.MAYBE), "A has Anna in forse; Nicolò has no address; B and D have nothing at this gathering")
        assertEquals(listOf(s.b.id), who(s.gathering, ReminderType.NOTICE), "B has Sara from last year and asked for the avviso; D did not ask; A has registered; C has nothing")
        assertEquals(listOf(s.a.email), reminders.recipients(dsl, s.gathering, ReminderType.MAYBE).map { it.email })
        assertEquals(listOf(s.b.email), reminders.recipients(dsl, s.gathering, ReminderType.NOTICE).map { it.email })
    }

    @Test
    fun `t13 c01 two children in forse are one mail, and a confirmed child alone is none`() {
        val s = kit.scenario()
        kit.rows.register(s.gathering, s.a.id, "Marta", "Rossi", LocalDate.of(2020, 5, 5), status = RegistrationStatus.maybe, email = s.a.email)
        dsl.update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.maybe).where(REGISTRATION.ID.eq(s.luca)).execute()

        assertEquals(listOf(s.a.id), who(s.gathering, ReminderType.MAYBE), "three children in forse, one mail")

        dsl.update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.confirmed).where(REGISTRATION.ACCOUNT_ID.eq(s.a.id)).execute()
        assertEquals(emptyList(), who(s.gathering, ReminderType.MAYBE), "everything is confirmed")
    }

    @Test
    fun `t13 c01 a withdrawn registration is not in forse, and one in forse at another gathering does not count`() {
        val s = kit.scenario()
        dsl.update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.withdrawn).where(REGISTRATION.ID.eq(s.anna)).execute()
        // Sara was in forse last year: that is not this gathering.
        dsl.update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.maybe).where(REGISTRATION.ID.eq(s.sara)).execute()

        assertEquals(emptyList(), who(s.gathering, ReminderType.MAYBE))
        assertEquals(listOf(s.b.id), who(s.previous, ReminderType.MAYBE), "at last year's gathering the one in forse is B, and the other gathering is another question")
    }

    @Test
    fun `t13 c01 the avviso is not for who has registered at this gathering, nor for who has withdrawn, nor for who has nothing`() {
        val s = kit.scenario()
        assertEquals(listOf(s.b.id), who(s.gathering, ReminderType.NOTICE))

        // B registers a child at this gathering, and withdraws it: still not for the avviso («nemmeno ritirate»).
        val sara = kit.rows.register(s.gathering, s.b.id, "Giulia", "Neri", LocalDate.of(2018, 2, 2), email = s.b.email)
        assertEquals(emptyList(), who(s.gathering, ReminderType.NOTICE), "B has registered")
        dsl.update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.withdrawn).where(REGISTRATION.ID.eq(sara)).execute()
        assertEquals(emptyList(), who(s.gathering, ReminderType.NOTICE), "B has withdrawn: not a person to tell that the gathering is open")

        // C is empty even with the avviso asked for: no children, nobody to tell (and a child that the account has lost is no child).
        dsl.update(ACCOUNT).set(ACCOUNT.NOTICE_CONSENT_AT, now()).where(ACCOUNT.ID.eq(s.c.id)).execute()
        assertEquals(emptyList(), who(s.gathering, ReminderType.NOTICE), "C has no children")
        val detached = kit.rows.register(s.previous, s.c.id, "Leo", "Bruni", LocalDate.of(2015, 9, 9), email = s.c.email)
        dsl.update(CHILD).setNull(CHILD.ACCOUNT_ID).where(CHILD.ID.eq(dsl.select(REGISTRATION.CHILD_ID).from(REGISTRATION).where(REGISTRATION.ID.eq(detached)))).execute()
        assertEquals(emptyList(), who(s.gathering, ReminderType.NOTICE), "a child whose data the parent had deleted is not among «I tuoi figli»")
    }

    @Test
    fun `t13 c01 the avviso is for whoever has children and asked for it, with no other condition`() {
        val s = kit.scenario()
        // D asks for the avviso: now D is for it too.
        dsl.update(ACCOUNT).set(ACCOUNT.NOTICE_CONSENT_AT, now()).where(ACCOUNT.ID.eq(s.d.id)).execute()

        assertEquals(listOf(s.b.id, s.d.id), who(s.gathering, ReminderType.NOTICE))
        // The children of the year before and the registrations of the paper are not in it: Nicolò has no account.
        assertTrue(s.a.id !in who(s.gathering, ReminderType.NOTICE))
    }

    @Test
    fun `t13 c10 who stopped is in neither list, whatever else they have, and comes back when the reminders are taken back`() {
        val s = kit.scenario()
        dsl.update(ACCOUNT).set(ACCOUNT.REMINDERS_OFF_AT, now()).where(ACCOUNT.ID.eq(s.a.id)).execute()
        dsl.update(ACCOUNT).set(ACCOUNT.REMINDERS_OFF_AT, now()).setNull(ACCOUNT.NOTICE_CONSENT_AT).where(ACCOUNT.ID.eq(s.b.id)).execute()

        assertEquals(emptyList(), who(s.gathering, ReminderType.MAYBE), "A stopped: Anna is in forse and nobody writes")
        assertEquals(emptyList(), who(s.gathering, ReminderType.NOTICE), "B stopped")

        dsl.update(ACCOUNT).setNull(ACCOUNT.REMINDERS_OFF_AT).where(ACCOUNT.ID.`in`(s.a.id, s.b.id)).execute()
        assertEquals(listOf(s.a.id), who(s.gathering, ReminderType.MAYBE), "A took the reminders back")
        assertEquals(emptyList(), who(s.gathering, ReminderType.NOTICE), "B has to ask for the avviso again: the consent went with the stop")
    }

    @Test
    fun `t13 c01 an address that is not an account is never a recipient, invitations to be a teacher included`() {
        val s = kit.scenario()
        val invited = Addresses.unique("invitato")
        dsl.insertInto(TEACHER_INVITATION).set(TEACHER_INVITATION.EMAIL, invited).set(TEACHER_INVITATION.EXPIRES_AT, now().plusDays(7)).set(TEACHER_INVITATION.INVITED_AT, now()).execute()

        val everybody = reminders.recipients(dsl, s.gathering, ReminderType.MAYBE) + reminders.recipients(dsl, s.gathering, ReminderType.NOTICE)

        assertTrue(everybody.none { it.email == invited })
        val accounts = dsl.select(ACCOUNT.EMAIL).from(ACCOUNT).fetch(ACCOUNT.EMAIL).toSet()
        assertTrue(everybody.all { it.email in accounts }, "every recipient is an account")
        // A teacher is an account like the others: M has no children and nothing in forse, so M and N receive nothing.
        assertTrue(everybody.none { it.accountId == s.m.id || it.accountId == s.n.id })
    }

    @Test
    fun `t13 c01 a teacher who is also a parent receives what a parent receives`() {
        val s = kit.scenario()
        kit.rows.register(s.gathering, s.n.id, "Elia", "Verdi", LocalDate.of(2017, 7, 7), status = RegistrationStatus.maybe, email = s.n.email)

        assertEquals(listOf(s.a.id, s.n.id).sorted(), who(s.gathering, ReminderType.MAYBE), "in the order of the accounts")
    }

    @Test
    fun `t13 c01 a gathering that does not exist has nobody`() {
        kit.scenario()

        assertEquals(emptyList(), who(Long.MAX_VALUE - 3, ReminderType.MAYBE))
    }

    @Test
    fun `t13 c01 the kinds are two, with the words of the code`() {
        assertEquals(listOf("maybe", "notice"), ReminderType.entries.map { it.code })
        assertEquals(ReminderType.MAYBE, ReminderType.ofCode("maybe"))
        assertEquals(ReminderType.NOTICE, ReminderType.ofCode("notice"))
        for (other in listOf(null, "", "Maybe", "sms", "maybe ", "all")) assertEquals(null, ReminderType.ofCode(other), "«$other»")
    }
}
