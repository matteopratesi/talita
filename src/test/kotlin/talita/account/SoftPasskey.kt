package talita.account

import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse
import org.springframework.security.web.webauthn.api.AuthenticatorTransport
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.api.PublicKeyCredential
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * A phone, for the tests of the passkeys (TALITA-15): a key pair, a credential and the answers that a phone gives to the options of a registration and of an assertion, written by hand
 * from the specification (the authenticator data, its flags, the counter, the signature over the data and the hash of the client data). It is in the tests, not in the jar, and runs nowhere
 * but in them.
 *
 * Whatever a test wants a bad phone to do it says in a parameter: a person that was not verified ([userVerified] false: the flag UV is off), another origin, another site, a
 * counter that does not grow, a handle that is not the one of the account. The Chromium of the e2e tests has a virtual authenticator of its own for what a real browser does.
 */
class SoftPasskey(val origin: String, val rpId: String, private val synchronizedByPhone: Boolean = false) {

    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val objects = ObjectConverter()

    /** The identifier of the credential: what the phone chooses. */
    val credentialId: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)

    /** The handle that the phone keeps with the passkey (the one of the options of the registration). */
    var userHandle: Bytes? = null
        private set

    var counter: Long = 0

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun clientData(type: String, challenge: Bytes, origin: String): ByteArray =
        """{"type":"$type","challenge":"${challenge.toBase64UrlString()}","origin":"$origin","crossOrigin":false}""".toByteArray()

    private fun flags(userVerified: Boolean, userPresent: Boolean, attested: Boolean): Byte {
        var flags = 0
        if (userPresent) flags = flags or 0x01
        if (userVerified) flags = flags or 0x04
        if (synchronizedByPhone) flags = flags or 0x08 or 0x10
        if (attested) flags = flags or 0x40
        return flags.toByte()
    }

    private fun authenticatorData(rp: String, flags: Byte, attestedCredentialData: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(sha256(rp.toByteArray()))
        out.write(flags.toInt())
        out.write(ByteBuffer.allocate(4).putInt(counter.toInt()).array())
        if (attestedCredentialData != null) out.write(attestedCredentialData)
        return out.toByteArray()
    }

    /** The answer of a phone to the options of a registration. */
    fun create(
        options: PublicKeyCredentialCreationOptions,
        userVerified: Boolean = true,
        origin: String = this.origin,
        rp: String = this.rpId,
        transports: List<AuthenticatorTransport> = listOf(AuthenticatorTransport.INTERNAL, AuthenticatorTransport.HYBRID),
    ): PublicKeyCredential<AuthenticatorAttestationResponse> {
        userHandle = options.user.id
        val coseKey = objects.cborConverter.writeValueAsBytes(EC2COSEKey.create(keys.public as ECPublicKey, com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier.ES256))
        val attested = ByteArrayOutputStream().apply {
            write(ByteArray(16))
            write(ByteBuffer.allocate(2).putShort(credentialId.size.toShort()).array())
            write(credentialId)
            write(coseKey)
        }.toByteArray()
        val authData = authenticatorData(rp, flags(userVerified, userPresent = true, attested = true), attested)
        val attestationObject = objects.cborConverter.writeValueAsBytes(mapOf("fmt" to "none", "attStmt" to emptyMap<String, Any>(), "authData" to authData))
        val response = AuthenticatorAttestationResponse.builder()
            .attestationObject(Bytes(attestationObject))
            .clientDataJSON(Bytes(clientData("webauthn.create", options.challenge, origin)))
            .transports(transports)
            .build()
        return credential(response)
    }

    /** The answer of a phone to the options of an assertion. */
    fun get(
        options: PublicKeyCredentialRequestOptions,
        userVerified: Boolean = true,
        userPresent: Boolean = true,
        origin: String = this.origin,
        rp: String = this.rpId,
        handle: Bytes? = this.userHandle,
        counterAfter: Long? = null,
    ): PublicKeyCredential<AuthenticatorAssertionResponse> {
        counter = counterAfter ?: (counter + 1)
        val authData = authenticatorData(rp, flags(userVerified, userPresent, attested = false))
        val clientDataJson = clientData("webauthn.get", options.challenge, origin)
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keys.private)
            update(authData)
            update(sha256(clientDataJson))
        }.sign()
        val response = AuthenticatorAssertionResponse.builder()
            .authenticatorData(Bytes(authData))
            .clientDataJSON(Bytes(clientDataJson))
            .signature(Bytes(signature))
            .userHandle(handle)
            .build()
        return credential(response)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <R : org.springframework.security.web.webauthn.api.AuthenticatorResponse> credential(response: R): PublicKeyCredential<R> =
        PublicKeyCredential.builder<R>().id(Bytes(credentialId).toBase64UrlString()).type(PublicKeyCredentialType.PUBLIC_KEY).rawId(Bytes(credentialId)).response(response).build() as PublicKeyCredential<R>

    companion object {
        /** The JSON that a browser sends, in the form that Spring reads (the module of WebAuthn of Spring Security). */
        val json: JsonMapper = JsonMapper.builder().addModule(WebauthnJacksonModule()).build()
    }
}
