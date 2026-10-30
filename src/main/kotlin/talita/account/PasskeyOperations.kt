package talita.account

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.RegistrationRequest
import com.webauthn4j.data.attestation.authenticator.AAGUID
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.authenticator.COSEKey
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.server.ServerProperty
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.Authentication
import org.springframework.security.web.webauthn.api.AttestationConveyancePreference
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria
import org.springframework.security.web.webauthn.api.AuthenticatorTransport
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.api.CredentialRecord
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions
import org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor
import org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement
import org.springframework.security.web.webauthn.api.UserVerificationRequirement
import org.springframework.security.web.webauthn.management.PublicKeyCredentialCreationOptionsRequest
import org.springframework.security.web.webauthn.management.PublicKeyCredentialRequestOptionsRequest
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest
import org.springframework.security.web.webauthn.management.RelyingPartyRegistrationRequest
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Where passkeys are possible: only with a public address of the site (`talita.public-url`, TALITA-15 criterion 28). A passkey is tied for good to the name of the site that made it
 * (the `rpId`, here the host of that address, `localhost` in development) and accepts only its origin: both come from the setting and never from the `Host` of a request, which whoever
 * attacks can write. Without the setting there are no buttons, no pages that offer them and no addresses that answer, the rest of the access works, and the log says so at the start.
 */
@Component
class PasskeySupport(publicUrl: PublicUrl) {

    /** The name of the site that a passkey is tied to, or null if there is no public address: then there are no passkeys. */
    val rpId: String? = publicUrl.host

    /** The only origin that an answer of a phone is accepted from. */
    val origin: String? = publicUrl.origin

    val enabled: Boolean get() = rpId != null && origin != null
}

/** An assertion or a registration that cannot be accepted, whatever the reason (a challenge that is old or used, a wrong signature, the person not verified, another origin, another account): the answer says nothing more. */
class PasskeyRefusedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The account already has [Passkeys.MAX_PER_ACCOUNT] passkeys. */
class PasskeyLimitException : RuntimeException("the account has the most passkeys")

/**
 * **WebAuthn with webauthn4j, as Talità wants it** (TALITA-15, criteria 20-23, 38): the one implementation of what Spring Security asks of [WebAuthnRelyingPartyOperations]. It is ours and not
 * `Webauthn4JRelyingPartyOperations` of Spring because that one needs the attestation of every passkey in the table to verify an assertion, and does not let us leave it out (criterion 21).
 *
 * - **The person is verified, always**: the options ask for `userVerification: required` (a fingerprint, a face or the code of the phone, not only the touch), and the verification
 *   refuses an answer that says the person was not verified.
 * - **No attestation**: the options ask `none`. Whatever comes with the registration (the attestation, the client data, the label) is checked and then thrown away: only the public key,
 *   the counter, the flags, the transports and the day are saved ([Passkeys]). No FIDO Metadata Service and no other call outside the app: the library runs inside it.
 * - **The identity of the phone is a random handle**, one per account (`account.passkey_handle`): the phone shows «Talità» and never the address (decision of Matteo), and the account is
 *   found from the handle that the phone sends back, or from the credential. The «name» that Spring reads from the entity is the id of the account, which only we read.
 * - **The site comes from the setting** ([PasskeySupport]): the `rpId` and the origin are those of `talita.public-url`.
 *
 * The confirmation before the notes on health ([PasskeyConfirmations]) uses the same verification, with options of its own ([confirmationOptions]).
 */
@Component
class PasskeyOperations(private val support: PasskeySupport, private val passkeys: Passkeys) : WebAuthnRelyingPartyOperations {

    private val manager = WebAuthnManager.createNonStrictWebAuthnManager()
    private val objects = ObjectConverter()

    private fun rpId(): String = checkNotNull(support.rpId) { "passkeys are off: talita.public-url is not set" }

    private fun origins(): Set<Origin> = setOf(Origin(checkNotNull(support.origin) { "passkeys are off: talita.public-url is not set" }))

    private fun rp(): PublicKeyCredentialRpEntity = PublicKeyCredentialRpEntity.builder().id(rpId()).name(PHONE_NAME).build()

    private fun accountOf(authentication: Authentication?): Long? = (authentication as? AccountAuthentication)?.account?.id

    // ---- The options of a registration ---------------------------------------------------------------------------------------------------

    /** What the phone is asked to make: a passkey that it can find by itself, with the person verified, no attestation, and not one that the account already has. */
    override fun createPublicKeyCredentialCreationOptions(request: PublicKeyCredentialCreationOptionsRequest): PublicKeyCredentialCreationOptions {
        val accountId = accountOf(request.authentication) ?: throw AuthenticationCredentialsNotFoundException("nobody is inside")
        val user: PublicKeyCredentialUserEntity = ImmutablePublicKeyCredentialUserEntity.builder().name(PHONE_NAME).displayName(PHONE_NAME).id(Bytes(passkeys.handleOf(accountId))).build()
        return PublicKeyCredentialCreationOptions.builder()
            .attestation(AttestationConveyancePreference.NONE)
            .pubKeyCredParams(PublicKeyCredentialParameters.ES256, PublicKeyCredentialParameters.EdDSA, PublicKeyCredentialParameters.RS256)
            .authenticatorSelection(AuthenticatorSelectionCriteria.builder().userVerification(UserVerificationRequirement.REQUIRED).residentKey(ResidentKeyRequirement.REQUIRED).build())
            .challenge(Bytes.random())
            .timeout(Duration.ofMinutes(5))
            .user(user)
            .rp(rp())
            .excludeCredentials(passkeys.credentialsOf(accountId).map { descriptor(it.credentialId, it.transports) })
            .build()
    }

    private fun descriptor(id: Bytes, transports: Set<AuthenticatorTransport>): PublicKeyCredentialDescriptor =
        PublicKeyCredentialDescriptor.builder().id(id).transports(transports).build()

    // ---- The registration -------------------------------------------------------------------------------------------------------------------

    /**
     * Verifies what the phone made and saves the passkey: the signature, the challenge of the options, the origin, the `rpId`, the person verified. Returns what was saved. A credential
     * that exists already, or an account that has the most passkeys, is refused.
     */
    override fun registerCredential(relyingPartyRegistrationRequest: RelyingPartyRegistrationRequest): CredentialRecord {
        val options = relyingPartyRegistrationRequest.creationOptions
        val credential = relyingPartyRegistrationRequest.publicKey.credential
        val response = credential.response
        val credentialId = credential.rawId
        if (passkeys.findByCredentialId(credentialId) != null) throw PasskeyRefusedException("the credential exists already")
        val accountId = passkeys.accountOfHandle(options.user.id.bytes) ?: throw PasskeyRefusedException("the handle belongs to nobody")

        val serverProperty = ServerProperty(origins(), options.rp.id, DefaultChallenge(options.challenge.bytes))
        val transports = response.transports?.map { it.value }?.toSet()
        val parameters = RegistrationParameters(
            serverProperty,
            options.pubKeyCredParams.map { com.webauthn4j.data.PublicKeyCredentialParameters(com.webauthn4j.data.PublicKeyCredentialType.PUBLIC_KEY, com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier.create(it.alg.value)) },
            options.authenticatorSelection?.userVerification == UserVerificationRequirement.REQUIRED,
            true,
        )
        val data = try {
            manager.verify(RegistrationRequest(response.attestationObject.bytes, response.clientDataJSON.bytes, transports), parameters)
        } catch (e: RuntimeException) {
            throw PasskeyRefusedException("the registration was not accepted", e)
        }
        val authenticatorData = checkNotNull(data.attestationObject).authenticatorData
        val attested = checkNotNull(authenticatorData.attestedCredentialData) { "no credential in the registration" }
        val record = ImmutableCredentialRecord.builder()
            .credentialType(credential.type ?: PublicKeyCredentialType.PUBLIC_KEY)
            .credentialId(credentialId)
            .userEntityUserId(options.user.id)
            .publicKey(ImmutablePublicKeyCose(objects.cborConverter.writeValueAsBytes(attested.coseKey)))
            .signatureCount(authenticatorData.signCount)
            .uvInitialized(authenticatorData.isFlagUV)
            .transports(data.transports?.map { AuthenticatorTransport.valueOf(it.value) }?.toSet().orEmpty())
            .backupEligible(authenticatorData.isFlagBE)
            .backupState(authenticatorData.isFlagBS)
            .label("")
            .build()
        if (!passkeys.addChecked(accountId, record)) throw PasskeyLimitException()
        return record
    }

    // ---- The options of an assertion ---------------------------------------------------------------------------------------------------------

    /**
     * The options of the access: the phone finds the passkey by itself (an empty list, so that the person does not write the address), with the person verified. A person who is inside
     * already gets the passkeys of their account (it is not the access page that asks, but the answer is the same shape).
     */
    override fun createCredentialRequestOptions(request: PublicKeyCredentialRequestOptionsRequest): PublicKeyCredentialRequestOptions {
        val allowed = accountOf(request.authentication)?.let { account -> passkeys.credentialsOf(account).map { descriptor(it.credentialId, it.transports) } }.orEmpty()
        return options(allowed)
    }

    /**
     * The options of the confirmation before the notes on health: only the passkeys of [accountId], each with its transports and `hybrid` as well, so that on a computer the browser always offers
     * the phone with the QR code (criteria 38, 41). Talità does not choose that channel and does not call it: the browser does.
     */
    fun confirmationOptions(accountId: Long): PublicKeyCredentialRequestOptions =
        options(passkeys.credentialsOf(accountId).map { descriptor(it.credentialId, withHybrid(it.transports)) })

    private fun withHybrid(transports: Set<AuthenticatorTransport>): Set<AuthenticatorTransport> =
        if (transports.isEmpty() || AuthenticatorTransport.HYBRID in transports) transports else transports + AuthenticatorTransport.HYBRID

    private fun options(allowed: List<PublicKeyCredentialDescriptor>): PublicKeyCredentialRequestOptions = PublicKeyCredentialRequestOptions.builder()
        .allowCredentials(allowed)
        .challenge(Bytes.random())
        .rpId(rpId())
        .timeout(Duration.ofMinutes(5))
        .userVerification(UserVerificationRequirement.REQUIRED)
        .build()

    // ---- The assertion ----------------------------------------------------------------------------------------------------------------------

    /**
     * Verifies what the phone answered to [request]: the challenge of its options, the origin, the `rpId`, the signature, the person present and (as the options said) verified, and that the
     * credential is one of those that the options allowed. Writes the new counter and the flags. Returns the identity of the account (its handle and, as the name, its id). Refuses with
     * [PasskeyRefusedException] and no more detail: the library does not say anything that the log or the page could carry.
     */
    override fun authenticate(request: RelyingPartyAuthenticationRequest): PublicKeyCredentialUserEntity {
        val options = request.requestOptions
        val assertion = request.publicKey
        val response = assertion.response
        val record = passkeys.findByCredentialId(assertion.rawId) ?: throw PasskeyRefusedException("unknown credential")
        // The phone says which handle the passkey is of when it finds the passkey by itself: it must be the one of the account that owns the credential.
        val sentHandle = response.userHandle
        if (sentHandle != null && sentHandle != record.userEntityUserId) throw PasskeyRefusedException("the handle is not the one of the credential")
        val accountId = passkeys.accountOfHandle(record.userEntityUserId.bytes) ?: throw PasskeyRefusedException("the handle belongs to nobody")

        val serverProperty = ServerProperty(origins(), checkNotNull(options.rpId), DefaultChallenge(options.challenge.bytes))
        val allowed = options.allowCredentials.mapNotNull { it.id?.bytes }
        val parameters = AuthenticationParameters(serverProperty, stored(record), allowed.ifEmpty { null }, options.userVerification == UserVerificationRequirement.REQUIRED)
        val data = try {
            manager.verify(AuthenticationRequest(assertion.rawId.bytes, response.authenticatorData.bytes, response.clientDataJSON.bytes, response.signature.bytes), parameters)
        } catch (e: RuntimeException) {
            throw PasskeyRefusedException("the assertion was not accepted", e)
        }
        val authenticatorData = checkNotNull(data.authenticatorData)
        passkeys.save(ImmutableCredentialRecord.fromCredentialRecord(record).signatureCount(authenticatorData.signCount).backupEligible(authenticatorData.isFlagBE).backupState(authenticatorData.isFlagBS).build())
        return ImmutablePublicKeyCredentialUserEntity.builder().name(accountId.toString()).displayName(PHONE_NAME).id(record.userEntityUserId).build()
    }

    /** What webauthn4j needs to verify an assertion, from what the table holds: the credential, its public key, the counter and the flags. No attestation: it is not needed and is not kept. */
    private fun stored(record: CredentialRecord): CredentialRecordImpl {
        val coseKey = checkNotNull(objects.cborConverter.readValue(record.publicKey.bytes, COSEKey::class.java)) { "the public key cannot be read" }
        return CredentialRecordImpl(
            null,
            record.isUvInitialized,
            record.isBackupEligible,
            record.isBackupState,
            record.signatureCount,
            AttestedCredentialData(AAGUID.ZERO, record.credentialId.bytes, coseKey),
            null,
            null,
            null,
            record.transports.map { com.webauthn4j.data.AuthenticatorTransport.create(it.value) }.toSet(),
        )
    }

    companion object {
        /** What the phone shows in its list of passkeys, for every account: the name of the app, never the address (decision of Matteo, question 4). */
        const val PHONE_NAME = "Talità"
    }
}

/**
 * Says at the start, once, that there are no passkeys on this site (TALITA-15, criterion 28): without a public address of the site (`talita.public-url`, `TALITA_PUBLIC_URL`) a passkey would have no
 * `rpId` and no origin, so there are no buttons, no pages that offer them and no addresses that answer, and the rest of the access works. Otherwise the first person to look for them would be the one to find out.
 */
@Component
class PasskeyStartupCheck(private val support: PasskeySupport) {

    private val log = org.slf4j.LoggerFactory.getLogger(PasskeyStartupCheck::class.java)

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent::class)
    fun warnIfPasskeysAreOff() {
        if (!support.enabled) log.warn("Passkeys are off: talita.public-url is not set or is not an address, so the site has no rpId and no origin. The rest of the access works.")
    }
}
