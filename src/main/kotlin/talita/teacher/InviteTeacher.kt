package talita.teacher

import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import talita.account.EmailAddress
import talita.account.PublicUrl
import talita.mail.MailResult
import talita.mail.Mailer
import talita.mail.OutgoingMail
import talita.ui.LONG_DATE
import java.time.Instant

/** What came of an invitation. */
sealed interface InviteResult {
    /** The invitation is saved and the mail has left: it expires at [expiresAt]. */
    data class Sent(val expiresAt: Instant) : InviteResult

    /** The address is already a teacher: nothing was created. */
    data object AlreadyTeacher : InviteResult

    /** Talità has sent all the mail it can send today. */
    data object DailyLimit : InviteResult

    /** The mail did not leave (no server, down, slow, no public address): nothing was saved. */
    data object NotSent : InviteResult
}

/** The text of the mail of an invitation (design, part D, section 7): a link to `/accedi`, never a token. */
object InvitationMail {

    fun of(to: EmailAddress, link: String, expiresAt: Instant): OutgoingMail = OutgoingMail(
        to = to.value,
        subject = "Ti hanno invitato come maestro su Talità",
        text = """
            Ciao,

            ti hanno invitato a organizzare la convivenza dei bambini come maestro su Talità.

            Per accettare, apri questa pagina e accedi con questo stesso indirizzo email, quello a cui è arrivata questa mail:

            $link

            Ti mandiamo un link per entrare e poi ti chiediamo il tuo nome. L’invito vale fino al ${LONG_DATE.format(expiresAt)}.

            Non te l’aspettavi? Ignora questa mail: l’invito scade da solo.

            Questa mail è automatica: non risponderle. Se hai bisogno di aiuto, scrivi a chi organizza la convivenza.

            Talità
        """.trimIndent() + "\n",
    )
}

/**
 * A teacher invites an address (or sends the invitation again: one invitation per address, with a new expiry).
 * The invitation is saved **only if the mail leaves**, in one transaction: so «Invito mandato.» is true, and a
 * person never has an invitation that nobody told them about. An address that is a teacher already is not
 * invited. The mail counts for the limit of the day like every other (see [Mailer]).
 *
 * The log never has the address of the person invited.
 */
@Service
class InviteTeacher(
    private val dsl: DSLContext,
    private val invitations: Invitations,
    private val mailer: Mailer,
    private val publicUrl: PublicUrl,
    private val teachers: Teachers,
) {

    private val log = LoggerFactory.getLogger(InviteTeacher::class.java)

    private class Rollback(val result: InviteResult) : RuntimeException(null, null, false, false)

    fun invite(address: EmailAddress): InviteResult {
        if (teachers.isTeacher(address.value)) return InviteResult.AlreadyTeacher
        val link = publicUrl.of("/accedi")
        if (link == null) {
            log.error("Invitation not sent: talita.public-url is not set or is not an address.")
            return InviteResult.NotSent
        }
        return try {
            dsl.transactionResult { _ ->
                val expiresAt = invitations.invite(address)
                when (mailer.send(InvitationMail.of(address, link, expiresAt))) {
                    MailResult.SENT -> InviteResult.Sent(expiresAt)
                    MailResult.DAILY_LIMIT -> throw Rollback(InviteResult.DailyLimit)
                    MailResult.FAILED -> throw Rollback(InviteResult.NotSent)
                }
            }
        } catch (rollback: org.jooq.exception.DataAccessException) {
            (rollback.cause as? Rollback)?.result ?: throw rollback
        } catch (rollback: Rollback) {
            rollback.result
        }
    }
}
