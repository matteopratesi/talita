package talita.teacher

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.stereotype.Component
import talita.ClockConfiguration
import talita.account.CurrentAccount
import talita.account.EmailAddress
import talita.account.TeacherName
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.TEACHER_INVITATION
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** Settings of the teachers (`talita.teacher.*`): how long an invitation to be a teacher lasts. */
@ConfigurationProperties("talita.teacher")
data class TeacherSettings(@DefaultValue("7d") val invitationValidity: Duration)

/**
 * The first teacher (`talita.bootstrap.teacher`, in production `TALITA_BOOTSTRAP_TEACHER`): the address to
 * invite at the start when there is no teacher yet. Empty means none.
 */
@ConfigurationProperties("talita.bootstrap")
data class BootstrapSettings(@DefaultValue("") val teacher: String)

/** An invitation to be a teacher: the address, and until when it can be accepted. */
data class Invitation(val email: String, val expiresAt: Instant)

internal fun Instant.toOffset(): OffsetDateTime = OffsetDateTime.ofInstant(this, ClockConfiguration.ZONE)

/**
 * The invitations to be a teacher. An invitation is an address and an expiry, nothing else: it carries
 * no token and opens nothing. Whoever enters with that address finds it (they enter with the link of
 * the mail, like everyone), and accepts it by writing their name. One invitation per address: inviting
 * again renews the expiry.
 */
@Component
class Invitations(private val dsl: DSLContext, private val clock: Clock, private val settings: TeacherSettings) {

    /**
     * The invitation that the person of [email] can accept now: it exists, has not expired and the
     * person is not already a teacher. Null otherwise. An invitation that was withdrawn is gone, and an
     * expired one is not valid: in both cases the person enters as a parent.
     */
    fun pendingFor(email: String): Invitation? =
        dsl.select(TEACHER_INVITATION.EMAIL, TEACHER_INVITATION.EXPIRES_AT)
            .from(TEACHER_INVITATION)
            .where(TEACHER_INVITATION.EMAIL.eq(email))
            .and(TEACHER_INVITATION.EXPIRES_AT.gt(clock.instant().toOffset()))
            .andNotExists(
                DSL.selectOne().from(TEACHER).join(ACCOUNT).on(ACCOUNT.ID.eq(TEACHER.ACCOUNT_ID)).where(ACCOUNT.EMAIL.eq(TEACHER_INVITATION.EMAIL)),
            )
            .fetchOne { Invitation(it[TEACHER_INVITATION.EMAIL]!!, it[TEACHER_INVITATION.EXPIRES_AT]!!.toInstant()) }

    /** Makes the invitation for [address], or renews it (a new expiry): there is one per address. Returns the expiry. */
    fun invite(address: EmailAddress): Instant {
        val expiresAt = clock.instant().plus(settings.invitationValidity)
        dsl.insertInto(TEACHER_INVITATION)
            .set(TEACHER_INVITATION.EMAIL, address.value)
            .set(TEACHER_INVITATION.EXPIRES_AT, expiresAt.toOffset())
            .onConflict(TEACHER_INVITATION.EMAIL).doUpdate()
            .set(TEACHER_INVITATION.EXPIRES_AT, expiresAt.toOffset())
            .execute()
        return expiresAt
    }

    /** All the invitations, those that expired too, soonest to expire first: the list of the teachers shows them (part D). */
    fun all(): List<Invitation> =
        dsl.select(TEACHER_INVITATION.EMAIL, TEACHER_INVITATION.EXPIRES_AT).from(TEACHER_INVITATION)
            .orderBy(TEACHER_INVITATION.EXPIRES_AT, TEACHER_INVITATION.EMAIL)
            .fetch { Invitation(it[TEACHER_INVITATION.EMAIL]!!, it[TEACHER_INVITATION.EXPIRES_AT]!!.toInstant()) }

    /** Takes the invitation back: from now on whoever enters with that address enters as a parent. False if there was none. */
    fun withdraw(email: String): Boolean = dsl.deleteFrom(TEACHER_INVITATION).where(TEACHER_INVITATION.EMAIL.eq(email)).execute() > 0

    /**
     * The person of [account] accepts: in one transaction the invitation is used up (if it is still valid at
     * this moment, and not only when the page was opened) and the person becomes a teacher with [name]. If
     * the invitation expired or was withdrawn meanwhile, nothing changes.
     */
    fun accept(account: CurrentAccount, name: TeacherName): Boolean = dsl.transactionResult { configuration ->
        val transaction = DSL.using(configuration)
        val used = transaction.deleteFrom(TEACHER_INVITATION)
            .where(TEACHER_INVITATION.EMAIL.eq(account.email))
            .and(TEACHER_INVITATION.EXPIRES_AT.gt(clock.instant().toOffset()))
            .execute()
        if (used == 0) return@transactionResult false
        transaction.insertInto(TEACHER)
            .set(TEACHER.ACCOUNT_ID, account.id)
            .set(TEACHER.FIRST_NAME, name.first)
            .set(TEACHER.LAST_NAME, name.last)
            .onConflict(TEACHER.ACCOUNT_ID).doNothing()
            .execute()
        true
    }

    fun isExpired(invitation: Invitation): Boolean = !invitation.expiresAt.isAfter(clock.instant())

    /** The validity of an invitation, for the texts (the help under the field says «7 giorni»). */
    val validity: Duration get() = settings.invitationValidity
}
