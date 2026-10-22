package talita.account

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import talita.mail.MailResult
import talita.mail.Mailer
import talita.mail.OutgoingMail
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Settings of the login link (`talita.login.*`).
 *
 * @property linkValidity how long a link works. 15 minutes: the 5 of Spring are few for someone who
 * goes through the mail app with little signal
 * @property linksPerAddress most links that can be asked for one address in [window]
 * @property window the time in which [linksPerAddress] count
 */
@ConfigurationProperties("talita.login")
data class LoginSettings(
    @DefaultValue("15m") val linkValidity: Duration,
    @DefaultValue("3") val linksPerAddress: Int,
    @DefaultValue("15m") val window: Duration,
)

/**
 * The address of the site that the links of the mails start with: `talita.public-url`
 * (`TALITA_PUBLIC_URL`). It is never taken from the `Host` header of a request, which whoever attacks
 * can write as they like: a link in a mail that points to another site would be a trap.
 * Without a valid address no mail with a link leaves.
 */
@Component
class PublicUrl(@Value("\${talita.public-url:}") raw: String) {

    private val base: String? = raw.trim().removeSuffix("/").takeIf { SHAPE.matches(it) }

    /** The address of [path] on the site, or null if the public address is missing or wrong. */
    fun of(path: String): String? = base?.let { it + path }

    private companion object {
        /** `https://host` or `http://host:port`, nothing else. */
        val SHAPE = Regex("^https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?$")
    }
}

/**
 * Most links that can be asked for one address in a few minutes (3 in 15 by default). In memory:
 * it is a brake for a person who taps ten times and for who tries to fill a stranger's mailbox, not
 * a record. By address and not by IP: at the gathering all the teachers go out from the same Wi-Fi.
 */
@Component
class LinkRequestLimiter(private val settings: LoginSettings, private val clock: Clock) {

    private val requests = HashMap<String, ArrayDeque<Instant>>()

    /** Counts a request for [address]. False if the address has already asked for [LoginSettings.linksPerAddress] links. */
    @Synchronized
    fun tryAcquire(address: EmailAddress): Boolean {
        val now = clock.instant()
        val since = now - settings.window
        requests.values.removeIf { times ->
            while (times.isNotEmpty() && times.first() <= since) times.removeFirst()
            times.isEmpty()
        }
        val times = requests.getOrPut(address.value) { ArrayDeque() }
        if (times.size >= settings.linksPerAddress) return false
        times.addLast(now)
        return true
    }

    /** The link was not sent after all: the request does not count. */
    @Synchronized
    fun release(address: EmailAddress) {
        val times = requests[address.value] ?: return
        if (times.isNotEmpty()) times.removeLast()
        if (times.isEmpty()) requests.remove(address.value)
    }
}

/** What came of the request for a link. The same for an address with an account and one without. */
enum class LinkOutcome {
    /** The mail with the link has left. */
    SENT,

    /** This address has asked for too many links in a few minutes. */
    TOO_MANY_FOR_ADDRESS,

    /** Talità has sent all the mail it can send today. */
    DAILY_LIMIT,

    /** The mail could not be sent (no server, server down or too slow, no public address). */
    NOT_SENT,
}

/**
 * The only place where a login link is born. It is not Spring's own address `/ott/generate`, which
 * does nothing here (see [SecurityConfiguration]): every link goes through the checks of this class,
 * in this order. The address has already been validated by [EmailAddress]; then the limit per address,
 * the token (valid [LoginSettings.linkValidity], once), the mail. If the mail does not leave, the token
 * is thrown away and the request does not count.
 *
 * It never writes the address, the token or the link in the log.
 */
@Service
class LoginLinks(
    private val tokens: OneTimeTokenService,
    private val mailer: Mailer,
    private val limiter: LinkRequestLimiter,
    private val publicUrl: PublicUrl,
    private val settings: LoginSettings,
) {

    private val log = LoggerFactory.getLogger(LoginLinks::class.java)

    fun request(address: EmailAddress): LinkOutcome {
        if (!limiter.tryAcquire(address)) return LinkOutcome.TOO_MANY_FOR_ADDRESS

        val token = tokens.generate(GenerateOneTimeTokenRequest(address.value, settings.linkValidity))
        val link = publicUrl.of("/accedi/conferma?token=${token.tokenValue}")
        val result = if (link == null) {
            log.error("Login link not sent: talita.public-url is not set or is not an address.")
            MailResult.FAILED
        } else {
            mailer.send(LoginLinkMail.of(address, link, settings.linkValidity))
        }
        if (result == MailResult.SENT) return LinkOutcome.SENT

        // The link did not leave: nobody can use it, and the person can ask again.
        tokens.consume(OneTimeTokenAuthenticationToken(token.tokenValue))
        limiter.release(address)
        return if (result == MailResult.DAILY_LIMIT) LinkOutcome.DAILY_LIMIT else LinkOutcome.NOT_SENT
    }
}

/** The text of the mail with the link (design, part B, section 7). */
object LoginLinkMail {

    fun of(to: EmailAddress, link: String, validity: Duration): OutgoingMail = OutgoingMail(
        to = to.value,
        subject = "Il tuo link per entrare in Talità",
        text = """
            Ciao,

            tocca questo link per entrare in Talità:

            $link

            Il link vale ${validity.toMinutes()} minuti e si usa una volta sola. Se lo apri su un altro telefono o computer, entri lì.

            Non hai chiesto tu di entrare? Ignora questa mail: senza il link nessuno può accedere.

            Questa mail è automatica: non risponderle. Se hai bisogno di aiuto, scrivi a chi organizza la convivenza.

            Talità
        """.trimIndent() + "\n",
    )
}
