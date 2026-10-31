package talita.account

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.web.webauthn.api.AttestationConveyancePreference
import org.springframework.security.web.webauthn.api.AuthenticatorTransport
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement
import org.springframework.security.web.webauthn.api.UserVerificationRequirement
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialRequestOptionsRequest
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.PASSKEY
import java.time.Duration
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The passkeys against the real library and the real database, with a phone written by hand ([SoftPasskey]) (TALITA-15, criteria 20, 21, 23, 38): the options that ask for the person to be
 * verified and for no attestation, what a registration saves and what it throws away, what the verification of an assertion refuses (a person not verified, another origin, another
 * site, a handle that is not the one of the passkey, a counter that does not grow, a passkey that was taken away), and the options of the confirmation. The site is the one of
 * `talita.public-url` of the tests. Addresses are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class PasskeyOperationsTests(
    @Autowired private val dsl: DSLContext,
    @Autowired private val operations: PasskeyOperations,
    @Autowired private val passkeys: Passkeys,
    @Autowired private val support: PasskeySupport,
    @Autowired private val settings: LoginSettings,
    @Autowired private val clock: java.time.Clock,
) {

    private val origin = TestcontainersConfiguration.PUBLIC_URL
    private val rpId = "talita.example.org"

    private val made = mutableListOf<Long>()

    private fun account(): Long =
        dsl.insertInto(ACCOUNT).set(ACCOUNT.EMAIL, Addresses.unique("passkey")).set(ACCOUNT.CREATED_ON, java.time.LocalDate.now()).returningResult(ACCOUNT.ID).fetchSingle().value1()!!.also { made += it }

    /** The database is shared by every test: the accounts of this one go, with their passkeys (the keys to the account have no cascade). */
    @org.junit.jupiter.api.AfterEach
    fun clean() {
        dsl.deleteFrom(PASSKEY).where(PASSKEY.ACCOUNT_ID.`in`(made)).execute()
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.`in`(made)).execute()
        made.clear()
    }

    private fun inside(account: Long): AccountAuthentication =
        AccountAuthentication(CurrentAccount(account, Addresses.of("passkey")), emptyList())

    private fun phone(synchronized: Boolean = false) = SoftPasskey(origin, rpId, synchronized)

    private fun creationOptions(account: Long) = operations.createPublicKeyCredentialCreationOptions(ImmutablePublicKeyCredentialCreationOptionsRequest(inside(account)))

    /** The phone makes a passkey for [account]; returns the phone and the options it answered. */
    private fun register(account: Long, phone: SoftPasskey = phone()): SoftPasskey {
        val options = creationOptions(account)
        val credential = phone.create(options)
        operations.registerCredential(ImmutableRelyingPartyRegistrationRequest(options, RelyingPartyPublicKey(credential, "")))
        return phone
    }

    private fun accessOptions(): PublicKeyCredentialRequestOptions = operations.createCredentialRequestOptions(ImmutablePublicKeyCredentialRequestOptionsRequest(null))

    private fun assertion(phone: SoftPasskey, options: PublicKeyCredentialRequestOptions = accessOptions(), configure: (PublicKeyCredentialRequestOptions) -> org.springframework.security.web.webauthn.api.PublicKeyCredential<org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse> = { phone.get(it) }) =
        RelyingPartyAuthenticationRequest(options, configure(options))

    // ---- The options of a registration (criteria 20, 21) -------------------------------------------------------------------------------

    @Test
    fun `t15 c20 the passkeys are on only with the public address of the site, and the site is its host`() {
        assertTrue(support.enabled)
        assertEquals("talita.example.org", support.rpId)
        assertEquals("https://talita.example.org", support.origin)
        assertEquals(null, PasskeySupport(PublicUrl("")).rpId)
        assertFalse(PasskeySupport(PublicUrl("")).enabled)
        assertFalse(PasskeySupport(PublicUrl("not an address")).enabled)
        assertEquals("localhost", PasskeySupport(PublicUrl("http://localhost:8081")).rpId, "in development the site is localhost, without the port")
        assertEquals("http://localhost:8081", PasskeySupport(PublicUrl("http://localhost:8081")).origin)
    }

    @Test
    fun `t15 c20 the options of a registration ask for a passkey that the phone finds by itself, the person verified, no attestation and not the passkeys that the account has`() {
        val account = account()
        val first = register(account)

        val options = creationOptions(account)

        assertEquals(UserVerificationRequirement.REQUIRED, options.authenticatorSelection.userVerification)
        assertEquals(ResidentKeyRequirement.REQUIRED, options.authenticatorSelection.residentKey)
        assertEquals(AttestationConveyancePreference.NONE, options.attestation)
        assertEquals(rpId, options.rp.id)
        assertEquals("Talità", options.rp.name)
        assertEquals(32, options.challenge.bytes.size)
        assertEquals(Duration.ofMinutes(5), options.timeout)
        assertEquals(listOf(Bytes(first.credentialId)), options.excludeCredentials.map { it.id }, "the passkey of the account is excluded: the phone that has it is told so")
        assertEquals(listOf(-7L, -8L, -257L), options.pubKeyCredParams.map { it.alg.value })
        // Two requests never get the same challenge.
        assertFalse(options.challenge == creationOptions(account).challenge)
    }

    @Test
    fun `t15 c21 the name in the phone is Talità and the identifier of the account is a random handle, never the id, the address or a name`() {
        val account = account()
        val email = dsl.select(ACCOUNT.EMAIL).from(ACCOUNT).where(ACCOUNT.ID.eq(account)).fetchSingle(ACCOUNT.EMAIL)!!

        val options = creationOptions(account)

        assertEquals("Talità", options.user.name)
        assertEquals("Talità", options.user.displayName)
        assertEquals(64, options.user.id.bytes.size)
        val text = String(options.user.id.bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains(email), "the handle is not the address")
        // The same on the second request (another tab), and another for another account.
        assertEquals(options.user.id, creationOptions(account).user.id)
        assertFalse(options.user.id == creationOptions(account()).user.id)
    }

    @Test
    fun `t15 c21 D2 the handle is saved with the first passkey and not when the options are asked, so a registration that is cancelled leaves no handle`() {
        val account = account()

        val options = creationOptions(account)

        assertNull(passkeys.handleOf(account), "the options are not a passkey: nothing is saved")
        assertNull(passkeys.accountOfHandle(options.user.id.bytes))
        // The person cancels on the phone and asks again: still nothing, and the same provisional handle for the tabs that are open.
        assertEquals(options.user.id, creationOptions(account).user.id)
        assertNull(passkeys.handleOf(account))
        // The phone makes the passkey: the handle that it was given is the one that is saved, with the passkey, and it is the one of the next options.
        val phone = SoftPasskey(origin, rpId)
        operations.registerCredential(ImmutableRelyingPartyRegistrationRequest(options, RelyingPartyPublicKey(phone.create(options), "")))
        assertContentEquals(options.user.id.bytes, passkeys.handleOf(account))
        assertEquals(account, passkeys.accountOfHandle(options.user.id.bytes))
        assertEquals(options.user.id, creationOptions(account).user.id)
    }

    @Test
    fun `t15 c21 D2 two accounts that ask together are each given a handle of their own, and each registration saves the handle of its own account`() {
        val first = account()
        val second = account()
        val optionsFirst = creationOptions(first)
        val optionsSecond = creationOptions(second)

        operations.registerCredential(ImmutableRelyingPartyRegistrationRequest(optionsFirst, RelyingPartyPublicKey(phone().create(optionsFirst), "")))

        assertContentEquals(optionsFirst.user.id.bytes, passkeys.handleOf(first))
        assertNull(passkeys.handleOf(second), "the other account has not made a passkey")
        operations.registerCredential(ImmutableRelyingPartyRegistrationRequest(optionsSecond, RelyingPartyPublicKey(phone().create(optionsSecond), "")))
        assertContentEquals(optionsSecond.user.id.bytes, passkeys.handleOf(second))
        assertFalse(optionsFirst.user.id == optionsSecond.user.id)
    }

    /** A clock of this test alone, which the test moves by hand: the clock of the application is shared by every test, and a test that moves it makes the others (the cleanup of the links) fail. */
    private class Stepping(var now: java.time.Instant) : java.time.Clock() {
        override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): java.time.Clock = this

        override fun instant(): java.time.Instant = now
    }

    @Test
    fun `t15 c21 D2 the handle that was given to a phone lives as long as the newest options that carry it, so a registration that answers late still succeeds`() {
        val at = Stepping(java.time.Instant.parse("2027-10-05T10:00:00Z"))
        val own = PasskeyOperations(support, passkeys, at, settings)
        val account = account()
        val options = { own.createPublicKeyCredentialCreationOptions(ImmutablePublicKeyCredentialCreationOptionsRequest(inside(account))) }

        // At T the options are asked and the phone is cancelled; at T+4:30 they are asked again (another tap, the same session) and the phone answers at T+5:30: the first handle would be
        // gone at T+5:00, but the options that the phone answers were made at T+4:30 and are good until T+9:30.
        options()
        at.now = at.now.plus(Duration.ofMinutes(4).plusSeconds(30))
        val latest = options()
        at.now = at.now.plus(Duration.ofMinutes(1))

        own.registerCredential(ImmutableRelyingPartyRegistrationRequest(latest, RelyingPartyPublicKey(phone().create(latest), "")))

        assertContentEquals(latest.user.id.bytes, passkeys.handleOf(account))
        assertEquals(1, passkeys.count(account))
    }

    @Test
    fun `t15 c21 D2 a handle that was given and not used is not good after its five minutes`() {
        val at = Stepping(java.time.Instant.parse("2027-10-05T10:00:00Z"))
        val own = PasskeyOperations(support, passkeys, at, settings)
        val account = account()
        val options = own.createPublicKeyCredentialCreationOptions(ImmutablePublicKeyCredentialCreationOptionsRequest(inside(account)))
        at.now = at.now.plus(Duration.ofMinutes(5).plusSeconds(1))

        assertFailsWith<PasskeyRefusedException> {
            own.registerCredential(ImmutableRelyingPartyRegistrationRequest(options, RelyingPartyPublicKey(phone().create(options), "")))
        }

        assertNull(passkeys.handleOf(account), "nothing was saved")
    }

    // ---- The registration (criteria 20, 21) --------------------------------------------------------------------------------------------

    @Test
    fun `t15 c20 a registration saves the public key, the counter, the flags, the transports and the day, and not the attestation, the client data, the label, the model or the last use`() {
        val account = account()

        val phone = register(account, phone(synchronized = true))

        val row = dsl.selectFrom(PASSKEY).where(PASSKEY.ACCOUNT_ID.eq(account)).fetchSingle()
        assertContentEquals(phone.credentialId, row.credentialId)
        assertTrue(row.publicKey!!.size > 20, "the public key in COSE form")
        assertEquals(0L, row.signatureCount)
        assertEquals(true, row.uvInitialized)
        assertEquals(true, row.backupEligible)
        assertEquals(true, row.backupState)
        assertEquals("hybrid,internal", row.transports)
        // The day of the clock of the application: the one of the tests is shared and other tests move it forward, so the day of the PC is not the one that was saved.
        assertEquals(java.time.LocalDate.now(clock), row.createdOn)
        // Not even a column for what a registration carries and the application does not keep.
        assertEquals(
            setOf("credential_id", "account_id", "public_key", "signature_count", "uv_initialized", "backup_eligible", "backup_state", "transports", "created_on"),
            dsl.meta().getTables("passkey").single().fields().map { it.name }.toSet(),
        )
        assertEquals(1, passkeys.count(account))
        assertEquals(listOf(true), passkeys.ofAccount(account).map { it.synced })
    }

    @Test
    fun `t15 c20 a registration of a person who was not verified is refused, and so is another origin, another site and the same credential twice`() {
        val account = account()
        val options = creationOptions(account)

        val notVerified = phone().create(options, userVerified = false)
        assertFailsWith<PasskeyRefusedException> { operations.registerCredential(ImmutableRelyingPartyRegistrationRequest(options, RelyingPartyPublicKey(notVerified, ""))) }
        val elsewhere = phone().create(options, origin = "https://altro.example.org")
        assertFailsWith<PasskeyRefusedException> { operations.registerCredential(ImmutableRelyingPartyRegistrationRequest(options, RelyingPartyPublicKey(elsewhere, ""))) }
        val otherSite = phone().create(options, rp = "altro.example.org")
        assertFailsWith<PasskeyRefusedException> { operations.registerCredential(ImmutableRelyingPartyRegistrationRequest(options, RelyingPartyPublicKey(otherSite, ""))) }
        assertEquals(0, passkeys.count(account), "nothing was saved")

        val good = phone()
        val credential = good.create(options)
        operations.registerCredential(ImmutableRelyingPartyRegistrationRequest(options, RelyingPartyPublicKey(credential, "")))
        assertFailsWith<PasskeyRefusedException> { operations.registerCredential(ImmutableRelyingPartyRegistrationRequest(options, RelyingPartyPublicKey(credential, ""))) }
        assertEquals(1, passkeys.count(account))
    }

    @Test
    fun `t15 c20 an account has at most ten passkeys, and the eleventh is refused`() {
        val account = account()
        repeat(Passkeys.MAX_PER_ACCOUNT) { register(account) }
        assertEquals(10, passkeys.count(account))

        val options = creationOptions(account)
        val credential = phone().create(options)

        assertFailsWith<PasskeyLimitException> { operations.registerCredential(ImmutableRelyingPartyRegistrationRequest(options, RelyingPartyPublicKey(credential, ""))) }
        assertEquals(10, passkeys.count(account))
    }

    // ---- The options of an assertion (criteria 22, 38) -----------------------------------------------------------------------------------

    @Test
    fun `t15 c22 the options of the access ask for the person verified and let the phone find the passkey by itself`() {
        val options = accessOptions()

        assertEquals(UserVerificationRequirement.REQUIRED, options.userVerification)
        assertEquals(emptyList(), options.allowCredentials, "no list: the person does not write the address")
        assertEquals(rpId, options.rpId)
        assertEquals(32, options.challenge.bytes.size)
        assertFalse(options.challenge == accessOptions().challenge)
    }

    @Test
    fun `t15 c38 the options of the confirmation are the passkeys of the account, with their transports and hybrid, and the person verified`() {
        val mine = account()
        val first = register(mine)
        val second = register(mine, phone(synchronized = true))
        register(account())

        val options = operations.confirmationOptions(mine)

        assertEquals(UserVerificationRequirement.REQUIRED, options.userVerification)
        assertEquals(rpId, options.rpId)
        assertEquals(setOf(Bytes(first.credentialId), Bytes(second.credentialId)), options.allowCredentials.map { it.id }.toSet(), "only the passkeys of this account")
        for (descriptor in options.allowCredentials) {
            assertEquals(setOf(AuthenticatorTransport.HYBRID, AuthenticatorTransport.INTERNAL), descriptor.transports!!.toSet(), "the transports of the phone, and hybrid so that a computer offers the phone with the QR code")
        }
    }

    // ---- The verification of an assertion (criteria 22, 23, 38) --------------------------------------------------------------------------

    @Test
    fun `t15 c22 an assertion of a phone that finds its passkey enters the account, with the counter and the flags updated and no last use`() {
        val account = account()
        val phone = register(account)

        val identity = operations.authenticate(assertion(phone))

        assertEquals(account.toString(), identity.name, "the name that Spring reads is the id of the account, which only the application reads")
        assertEquals(Bytes(passkeys.handleOf(account)!!), identity.id)
        assertEquals(1L, dsl.select(PASSKEY.SIGNATURE_COUNT).from(PASSKEY).where(PASSKEY.ACCOUNT_ID.eq(account)).fetchSingle(PASSKEY.SIGNATURE_COUNT))
        // The phone synchronized the passkey after the registration: the flag that the answer carries is kept.
        val synced = SoftPasskey(origin, rpId, synchronizedByPhone = true)
        val syncedRegistration = account()
        val options = creationOptions(syncedRegistration)
        val credential = synced.create(options, userVerified = true)
        operations.registerCredential(ImmutableRelyingPartyRegistrationRequest(options, RelyingPartyPublicKey(credential, "")))
        operations.authenticate(assertion(synced))
        assertEquals(listOf(true), passkeys.ofAccount(syncedRegistration).map { it.synced })
    }

    @Test
    fun `t15 c23 an assertion without the person verified is refused, though the phone says it was there`() {
        val account = account()
        val phone = register(account)

        assertFailsWith<PasskeyRefusedException> { operations.authenticate(assertion(phone) { phone.get(it, userVerified = false) }) }
        assertFailsWith<PasskeyRefusedException> { operations.authenticate(assertion(phone) { phone.get(it, userVerified = false, userPresent = false) }) }
        assertEquals(0L, dsl.select(PASSKEY.SIGNATURE_COUNT).from(PASSKEY).where(PASSKEY.ACCOUNT_ID.eq(account)).fetchSingle(PASSKEY.SIGNATURE_COUNT), "nothing changed")
    }

    @Test
    fun `t15 c23 an assertion for another origin or another site is refused`() {
        val account = account()
        val phone = register(account)

        assertFailsWith<PasskeyRefusedException> { operations.authenticate(assertion(phone) { phone.get(it, origin = "https://altro.example.org") }) }
        assertFailsWith<PasskeyRefusedException> { operations.authenticate(assertion(phone) { phone.get(it, rp = "altro.example.org") }) }
        operations.authenticate(assertion(phone))
    }

    @Test
    fun `t15 c23 an assertion with the handle of another account, or of a passkey that was taken away, is refused`() {
        val account = account()
        val other = account()
        val phone = register(account)
        register(other)
        val otherHandle = Bytes(passkeys.handleOf(other)!!)

        assertFailsWith<PasskeyRefusedException> { operations.authenticate(assertion(phone) { phone.get(it, handle = otherHandle) }) }
        operations.authenticate(assertion(phone))

        assertEquals(true, passkeys.deleteOwn(account, Passkeys.reference(phone.credentialId)))
        assertFailsWith<PasskeyRefusedException> { operations.authenticate(assertion(phone)) }
    }

    @Test
    fun `t15 c23 an answer to a challenge that was never given, or a counter that goes back, is refused`() {
        val account = account()
        val phone = register(account)
        operations.authenticate(assertion(phone))
        operations.authenticate(assertion(phone))
        assertEquals(2L, dsl.select(PASSKEY.SIGNATURE_COUNT).from(PASSKEY).where(PASSKEY.ACCOUNT_ID.eq(account)).fetchSingle(PASSKEY.SIGNATURE_COUNT))

        // The phone answers with a counter that is not above the last: a copy of the passkey.
        assertFailsWith<PasskeyRefusedException> { operations.authenticate(assertion(phone) { phone.get(it, counterAfter = 2) }) }
        // The answer is to other options than the ones that the server remembers.
        val forgotten = accessOptions()
        val answer = phone.get(forgotten)
        assertFailsWith<PasskeyRefusedException> { operations.authenticate(RelyingPartyAuthenticationRequest(accessOptions(), answer)) }
    }

    @Test
    fun `t15 c38 the confirmation takes only the passkeys that the options allow`() {
        val mine = account()
        val theirs = account()
        register(mine)
        val other = register(theirs)
        val options = operations.confirmationOptions(mine)

        assertFailsWith<PasskeyRefusedException> { operations.authenticate(RelyingPartyAuthenticationRequest(options, other.get(options))) }
        assertNotNull(options.allowCredentials.firstOrNull())
        assertNull(options.allowCredentials.firstOrNull { it.id == Bytes(other.credentialId) })
    }
}
