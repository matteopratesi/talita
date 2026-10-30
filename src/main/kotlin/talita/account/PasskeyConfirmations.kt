package talita.account

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.api.PublicKeyCredential
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseBody
import talita.ClockConfiguration
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.io.Serializable
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The proof that the check before the notes on health was made (TALITA-15, criterion 39): `HealthNotes.readForTeacher` wants one as a parameter, so **a call that did not go through the check does
 * not compile**. Nothing outside [PasskeyConfirmations] can make one: the interface is sealed, and its only implementation is a private class inside it. A proof is for a teacher, who is [accountId].
 */
sealed interface ConfirmationProof {
    val accountId: Long
}

/** What the check says: the teacher may read (they have no passkey, or a confirmation that is still valid: the [ConfirmationProof] says so), or must confirm first. */
sealed interface Gate {
    data class Granted(val proof: ConfirmationProof) : Gate

    /** A teacher with a passkey and no valid confirmation: the page says «Serve la conferma», and nothing is read and nothing is written in the register. */
    data object Needed : Gate
}

/** What the pages that hold the notes show of the confirmation: nothing, the sentence that says that the phone will ask, the one that says until when it holds, or the invitation to add a passkey. */
enum class ConfirmationMode { NONE, CONFIRM, VALID, INVITE }

/** [until] is the time until which a confirmation holds (`10:15`, a time of Rome), for [ConfirmationMode.VALID]. */
data class ConfirmationView(val mode: ConfirmationMode, val until: String? = null)

/** The moment of the last confirmation of an account, in the session: nothing else (not which passkey, not how many times, not those that were cancelled or refused). */
internal data class Confirmed(val accountId: Long, val at: Instant) : Serializable

/** The challenge of a confirmation, in the session, for one use and five minutes. */
internal data class PendingChallenge(val accountId: Long, val options: PublicKeyCredentialRequestOptions, val at: Instant) : Serializable

/**
 * **The confirmation with the passkey before the notes on health** (TALITA-15, criteria 37-48, decisions of Matteo, comments 10965 and 10970). A teacher who has a passkey confirms with it, with the
 * fingerprint, the face or the code of the phone (WebAuthn with the person verified), before the notes of a child are opened from any of the three places (the detail of the registration, the card in
 * the arrivals, the correction of a paper form) and before taking away or adding a passkey. A teacher without a passkey does not confirm anything (choice 1 of Matteo), and a parent never does.
 *
 * - **It is not an access.** It uses the passkeys of the account on addresses of its own (`POST /verifica-passkey/opzioni` and `POST /verifica-passkey`, for who is inside, with the CSRF code), changes
 *   the id of the session and writes the moment in the session; it makes no `loginSuccess` and sends no cookie.
 * - **It holds ten minutes, from the moment** ([LoginSettings.confirmationValidity]): using the app does not lengthen it, and it ends with the session. An access with a passkey counts as a
 *   confirmation (the phone has just verified the person); the link and the cookie of «Ricordami» do not, and neither does adding the first passkey.
 * - **The challenge** is 32 random bytes in the session, answered once and for five minutes; the answer is checked for the signature, the origin, the `rpId`, the person present **and verified**, and
 *   that the passkey is one of this account.
 * - **The session keeps nothing else**: the moment, and the challenge until it is used. The register of the readings is the one of always, with no sign of the confirmation; the log has a WARN
 *   without data for an answer that is refused, and nothing for the others.
 */
@Component
class PasskeyConfirmations(
    private val passkeys: Passkeys,
    private val operations: PasskeyOperations,
    private val support: PasskeySupport,
    private val settings: LoginSettings,
    private val clock: Clock,
) : PasskeySignInListener {

    private val log = LoggerFactory.getLogger(PasskeyConfirmations::class.java)

    private class Proof(override val accountId: Long) : ConfirmationProof

    private val time: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ClockConfiguration.ZONE)

    // ---- Is it needed? ------------------------------------------------------------------------------------------------------------------

    private fun confirmedAt(session: HttpSession?, accountId: Long): Instant? =
        (session?.getAttribute(CONFIRMED) as? Confirmed)?.takeIf { it.accountId == accountId }?.at

    /** The confirmation of the account holds as of now: made in the last [LoginSettings.confirmationValidity] and in this session. */
    fun valid(session: HttpSession?, accountId: Long): Boolean {
        val at = confirmedAt(session, accountId) ?: return false
        return clock.instant().isBefore(at.plus(settings.confirmationValidity))
    }

    /**
     * Whether the teacher has to confirm now: they are a teacher **at this moment**, have at least one passkey **now**, and have no confirmation that is still valid. The server decides at every
     * request, whatever the page said when it was drawn (criterion 44). Without passkeys on this site (no public address) nothing is asked.
     */
    fun needed(account: CurrentAccount, session: HttpSession?): Boolean =
        support.enabled && account.isTeacher && passkeys.hasAny(account.id) && !valid(session, account.id)

    /** The check before the notes: a proof if they may be read, or [Gate.Needed]. */
    fun gate(account: CurrentAccount, session: HttpSession?): Gate = if (needed(account, session)) Gate.Needed else Gate.Granted(Proof(account.id))

    /** What the card of the notes says now. The invitation is only for a teacher with no passkey on a site that can have them. */
    fun view(account: CurrentAccount, session: HttpSession?): ConfirmationView = when {
        !support.enabled || !account.isTeacher -> ConfirmationView(ConfirmationMode.NONE)
        !passkeys.hasAny(account.id) -> ConfirmationView(ConfirmationMode.INVITE)
        valid(session, account.id) -> ConfirmationView(ConfirmationMode.VALID, time.format(checkNotNull(confirmedAt(session, account.id)).plus(settings.confirmationValidity)))
        else -> ConfirmationView(ConfirmationMode.CONFIRM)
    }

    /** Writes the moment of the confirmation in the session. */
    fun mark(session: HttpSession, accountId: Long) {
        session.setAttribute(CONFIRMED, Confirmed(accountId, clock.instant()))
    }

    /** An access with a passkey is a confirmation as well (criterion 40): the phone has just verified the person. */
    override fun signedIn(request: HttpServletRequest, accountId: Long) {
        mark(request.getSession(true), accountId)
    }

    // ---- The two requests of the phone ---------------------------------------------------------------------------------------------------------

    /**
     * The answer to `POST /verifica-passkey/opzioni`: `{"serve":false}` if there is nothing to confirm (no passkey, or a confirmation that still holds), else `{"serve":true,"options":{…}}` with the
     * options of the assertion (`userVerification: required`, the passkeys of the account with their transports and `hybrid`), whose challenge is kept in the session for one use.
     */
    fun options(account: CurrentAccount, request: HttpServletRequest): String {
        val session = request.getSession(true)
        if (!needed(account, session)) return """{"serve":false}"""
        val options = operations.confirmationOptions(account.id)
        session.setAttribute(CHALLENGE, PendingChallenge(account.id, options, clock.instant()))
        return JSON.writeValueAsString(mapOf("serve" to true, "options" to options))
    }

    /**
     * The answer to `POST /verifica-passkey`: checks the assertion of the phone against the challenge that was given (which is spent whatever the result) and, if it is good, writes the moment of the
     * confirmation in the session and changes the id of the session (the CSRF code and the attributes stay). Returns whether the confirmation was accepted; a refusal says nothing of why.
     */
    fun verify(account: CurrentAccount, request: HttpServletRequest, body: String): Boolean {
        val session = request.getSession(false) ?: return false
        val pending = session.getAttribute(CHALLENGE) as? PendingChallenge
        session.removeAttribute(CHALLENGE)
        if (pending == null || pending.accountId != account.id || !clock.instant().isBefore(pending.at.plus(settings.confirmationChallengeValidity))) {
            log.warn("Passkey: a confirmation was refused.")
            return false
        }
        return try {
            val credential = JSON.readValue(body, object : TypeReference<PublicKeyCredential<AuthenticatorAssertionResponse>>() {})
            val identity = operations.authenticate(RelyingPartyAuthenticationRequest(pending.options, credential))
            check(identity.id == Bytes(passkeys.handleOf(account.id))) { "not a passkey of this account" }
            mark(session, account.id)
            request.changeSessionId()
            true
        } catch (e: RuntimeException) {
            // The type and nothing else: the message of the library or of a check could carry data of the assertion.
            log.warn("Passkey: a confirmation was refused ({}).", e.javaClass.simpleName)
            false
        }
    }

    companion object {
        internal const val CONFIRMED = "talita.passkey.confirmed"
        internal const val CHALLENGE = "talita.passkey.challenge"

        /** The JSON of WebAuthn, as Spring writes and reads it (binary fields in base64url). */
        val JSON: JsonMapper = JsonMapper.builder().addModule(org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule()).build()
    }
}

/**
 * The two addresses of the confirmation (TALITA-15, criterion 38): `POST /verifica-passkey/opzioni` and `POST /verifica-passkey`, with JSON, for a person who is inside and with the CSRF code. They are not in
 * `PublicPaths` and not in `TeacherPaths`: a parent who asks gets «there is nothing to confirm», and a request without access goes to the access page like any other.
 */
@Controller
class PasskeyConfirmationController(private val confirmations: PasskeyConfirmations) {

    @PostMapping("/verifica-passkey/opzioni", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun options(account: CurrentAccount, request: HttpServletRequest): String = confirmations.options(account, request)

    @PostMapping("/verifica-passkey", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun verify(account: CurrentAccount, request: HttpServletRequest, @RequestBody body: String): ResponseEntity<String> =
        if (confirmations.verify(account, request, body)) {
            ResponseEntity.ok("""{"ok":true}""")
        } else {
            ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body("""{"stato":"rifiutata"}""")
        }
}

/** What the card «Segnalazioni» says about the confirmation with the passkey (TALITA-15, criteria 37-46). */
enum class GateMode {
    /** Nothing: a parent, a teacher without passkeys on a site that has none, or a card that is open already. */
    NONE,

    /** A teacher with a passkey and no confirmation that holds: the phone will ask, and the form carries `data-conferma`. */
    CONFIRM,

    /** The confirmation holds until [NotesGate.until]: the button opens at once, with a single tap. */
    VALID,

    /** A teacher with no passkey (on a site that can have them): a discreet line invites them to add one. It never blocks and never closes. */
    INVITE,

    /** The answer to a tap that the server refused (409): the card says «Serve la conferma.» and keeps the button, which now asks the phone. */
    SERVE,

    /** The same without JavaScript: there is nobody to ask the phone, so the page says that the confirmation wants it, and has no button. */
    SERVE_NO_JS,
}

/**
 * The state of the confirmation, as a card of the notes draws it. The properties are what the templates read (a template compares no enums). It is worked out when the page is drawn;
 * **the server decides again at the tap** (criterion 44), so a card that says something old costs one thing: the phone asks, or does not ask.
 */
data class NotesGate(val mode: GateMode, val until: String? = null) {

    /** The form of «Mostra le segnalazioni» holds its request until the phone has verified the person. */
    val confirm: Boolean get() = mode == GateMode.CONFIRM || mode == GateMode.SERVE

    /** The sentence under the button that says that the phone will ask. */
    val asks: Boolean get() = mode == GateMode.CONFIRM

    /** «Hai già confermato…», until [until]. */
    val valid: Boolean get() = mode == GateMode.VALID

    val invite: Boolean get() = mode == GateMode.INVITE

    /** The notice «Serve la conferma.» above the button, with the button. */
    val serve: Boolean get() = mode == GateMode.SERVE

    /** The notice «Serve la conferma.» with no button and no note. */
    val serveNoJs: Boolean get() = mode == GateMode.SERVE_NO_JS

    companion object {
        val NONE = NotesGate(GateMode.NONE)

        fun of(view: ConfirmationView): NotesGate = when (view.mode) {
            ConfirmationMode.NONE -> NONE
            ConfirmationMode.CONFIRM -> NotesGate(GateMode.CONFIRM)
            ConfirmationMode.VALID -> NotesGate(GateMode.VALID, view.until)
            ConfirmationMode.INVITE -> NotesGate(GateMode.INVITE)
        }
    }
}
