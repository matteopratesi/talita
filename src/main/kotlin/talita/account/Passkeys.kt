package talita.account

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.security.web.webauthn.api.AuthenticatorTransport
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.api.CredentialRecord
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType
import org.springframework.security.web.webauthn.management.UserCredentialRepository
import org.springframework.stereotype.Component
import talita.ClockConfiguration
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.PASSKEY
import java.security.SecureRandom
import java.time.Clock
import java.time.LocalDate
import java.util.Base64

/**
 * A passkey as the profile shows it: the day it was added and whether the phone says that it is synchronized with the account of the person (Apple, Google), which is the one thing that
 * tells two passkeys apart. [reference] is what a form sends to say which one («Togli»): the identifier of the credential, in base64url, never in an address.
 */
class PasskeyRow(val credentialId: ByteArray, val createdOn: LocalDate, val synced: Boolean) {
    val reference: String get() = Passkeys.reference(credentialId)

    override fun toString(): String = "PasskeyRow"
}

/**
 * **The passkeys of the accounts, and the only class that reads or writes their table** (TALITA-15, criteria 20, 21, 25, 26, 29-31): a test fails if another class of the application uses
 * the table that jOOQ generates for it. It is also the repository of credentials that Spring Security's WebAuthn asks for ([UserCredentialRepository]), so that what is saved is what this
 * class decides: the public key, the counter, the flags that the phone gave, the transports and the day. **Not** the attestation, the client data, the label, the last use: Spring's
 * schema has them, ours has not, and what Spring hands over for them is thrown away here.
 *
 * It also holds the identifier that the phone sends back with a passkey to say which account it is of (`account.passkey_handle`, the «user handle»): 64 random bytes, one per account, saved
 * with the first passkey (the options of a registration that is cancelled leave nothing). It is never the id nor the address of the account.
 */
@Component
class Passkeys(private val dsl: DSLContext, private val clock: Clock) : UserCredentialRepository {

    private val random = SecureRandom()

    private fun today(): LocalDate = LocalDate.now(clock)

    // ---- The handle of an account ------------------------------------------------------------------------------------------------

    /** The handle of [accountId], or null if it has none: a handle is saved with the first passkey of the account and not before ([addChecked]), so an account without passkeys has none (unless it had some). */
    fun handleOf(accountId: Long): ByteArray? = dsl.select(ACCOUNT.PASSKEY_HANDLE).from(ACCOUNT).where(ACCOUNT.ID.eq(accountId)).fetchOne(ACCOUNT.PASSKEY_HANDLE)

    /** A new handle: 64 random bytes. It is not saved: [PasskeyOperations] holds it until the phone has made the first passkey. */
    fun newHandle(): ByteArray = ByteArray(HANDLE_BYTES).also(random::nextBytes)

    private fun requireHandle(accountId: Long): ByteArray = checkNotNull(handleOf(accountId)) { "an account that has a passkey has a handle" }

    /** The account that [handle] belongs to, or null if none does. */
    fun accountOfHandle(handle: ByteArray): Long? = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.PASSKEY_HANDLE.eq(handle)).fetchOne(ACCOUNT.ID)

    // ---- What the profile and the confirmation ask --------------------------------------------------------------------------------

    /** The passkeys of [accountId], the most recent first. */
    fun ofAccount(accountId: Long): List<PasskeyRow> =
        dsl.select(PASSKEY.CREDENTIAL_ID, PASSKEY.CREATED_ON, PASSKEY.BACKUP_STATE).from(PASSKEY).where(PASSKEY.ACCOUNT_ID.eq(accountId))
            .orderBy(PASSKEY.CREATED_ON.desc(), PASSKEY.CREDENTIAL_ID).fetch { PasskeyRow(it[PASSKEY.CREDENTIAL_ID]!!, it[PASSKEY.CREATED_ON]!!, it[PASSKEY.BACKUP_STATE]!!) }

    fun count(accountId: Long): Int = dsl.fetchCount(PASSKEY, PASSKEY.ACCOUNT_ID.eq(accountId))

    fun hasAny(accountId: Long): Boolean = dsl.fetchExists(PASSKEY, PASSKEY.ACCOUNT_ID.eq(accountId))

    /** The credentials of [accountId] with their transports, for what the browser looks for (`allowCredentials`, `excludeCredentials`). */
    fun credentialsOf(accountId: Long): List<CredentialRecord> =
        dsl.selectFrom(PASSKEY).where(PASSKEY.ACCOUNT_ID.eq(accountId)).orderBy(PASSKEY.CREATED_ON, PASSKEY.CREDENTIAL_ID).fetch().map { toRecord(it, requireHandle(accountId)) }

    /** The account that owns the credential, or null if there is no such credential. */
    fun accountOfCredential(credentialId: ByteArray): Long? = dsl.select(PASSKEY.ACCOUNT_ID).from(PASSKEY).where(PASSKEY.CREDENTIAL_ID.eq(credentialId)).fetchOne(PASSKEY.ACCOUNT_ID)

    /**
     * Takes away the passkey of [accountId] with this [reference] (what a form sent). An id that is not the person's, or that is not there, takes away nothing: the answer is the same as for one that
     * does not exist (criterion 26). Returns whether a row went.
     */
    fun deleteOwn(accountId: Long, reference: String): Boolean {
        val id = decodeReference(reference) ?: return false
        return dsl.deleteFrom(PASSKEY).where(PASSKEY.CREDENTIAL_ID.eq(id)).and(PASSKEY.ACCOUNT_ID.eq(accountId)).execute() > 0
    }

    /**
     * Adds the passkey, unless the account has [MAX_PER_ACCOUNT] already (checked under the lock of the account, so two phones that add together do not make an eleventh). Returns whether it
     * was added. **The handle is saved here, with the first passkey, and not when the options were asked** (a person who cancels on the phone leaves nothing): [handle] is the one that the options
     * gave to the phone. If the account has one already it must be the same, else two phones that made their first passkey together had two handles, and the second is refused (it asks again and
     * gets the one that was saved).
     */
    fun addChecked(accountId: Long, record: CredentialRecord, handle: ByteArray): Boolean = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        val stored = tx.select(ACCOUNT.PASSKEY_HANDLE).from(ACCOUNT).where(ACCOUNT.ID.eq(accountId)).forUpdate().fetchOne(ACCOUNT.PASSKEY_HANDLE)
        if (tx.fetchCount(PASSKEY, PASSKEY.ACCOUNT_ID.eq(accountId)) >= MAX_PER_ACCOUNT) return@transactionResult false
        if (stored == null) {
            tx.update(ACCOUNT).set(ACCOUNT.PASSKEY_HANDLE, handle).where(ACCOUNT.ID.eq(accountId)).execute()
        } else {
            require(stored.contentEquals(handle)) { "the account has another handle" }
        }
        insert(tx, accountId, record)
        true
    }

    // ---- What Spring's WebAuthn asks (UserCredentialRepository) ---------------------------------------------------------------------

    override fun findByCredentialId(credentialId: Bytes): CredentialRecord? {
        val row = dsl.selectFrom(PASSKEY).where(PASSKEY.CREDENTIAL_ID.eq(credentialId.bytes)).fetchOne() ?: return null
        return toRecord(row, requireHandle(row.accountId!!))
    }

    override fun findByUserId(userId: Bytes): List<CredentialRecord> {
        val account = accountOfHandle(userId.bytes) ?: return emptyList()
        return credentialsOf(account)
    }

    /**
     * Saves a credential: a new one (only through [addChecked], which knows the account) or the changes of one that exists, which are the counter and the flags that the phone sends at
     * every assertion. Nothing else is ever written: not a last use, not a label.
     */
    override fun save(credentialRecord: CredentialRecord) {
        val updated = dsl.update(PASSKEY)
            .set(PASSKEY.SIGNATURE_COUNT, credentialRecord.signatureCount)
            .set(PASSKEY.UV_INITIALIZED, credentialRecord.isUvInitialized)
            .set(PASSKEY.BACKUP_ELIGIBLE, credentialRecord.isBackupEligible)
            .set(PASSKEY.BACKUP_STATE, credentialRecord.isBackupState)
            .where(PASSKEY.CREDENTIAL_ID.eq(credentialRecord.credentialId.bytes)).execute()
        check(updated == 1) { "a passkey is added only through addChecked" }
    }

    override fun delete(credentialId: Bytes) {
        dsl.deleteFrom(PASSKEY).where(PASSKEY.CREDENTIAL_ID.eq(credentialId.bytes)).execute()
    }

    // ---- What deletes (TALITA-7, criteria 29-31) -------------------------------------------------------------------------------------

    /** Deletes every passkey of [accountId] (the account is deleted, or the request is made again after a restore). Returns how many rows went. */
    fun removeOfAccount(context: DSLContext, accountId: Long): Int = context.deleteFrom(PASSKEY).where(PASSKEY.ACCOUNT_ID.eq(accountId)).execute()

    /** The same for several accounts (the job that deletes the accounts that stayed empty). */
    fun removeOfAccounts(context: DSLContext, accountIds: Collection<Long>): Int =
        if (accountIds.isEmpty()) 0 else context.deleteFrom(PASSKEY).where(PASSKEY.ACCOUNT_ID.`in`(accountIds)).execute()

    /** Empties the handle of [accountId]: the request to delete the account left the account for the readings, with the address alone, and the handle is not needed any more. */
    fun clearHandle(context: DSLContext, accountId: Long): Int = context.update(ACCOUNT).setNull(ACCOUNT.PASSKEY_HANDLE).where(ACCOUNT.ID.eq(accountId)).and(ACCOUNT.PASSKEY_HANDLE.isNotNull).execute()

    // ---- The rows ----------------------------------------------------------------------------------------------------------------------

    private fun insert(tx: DSLContext, accountId: Long, record: CredentialRecord) {
        tx.insertInto(PASSKEY)
            .set(PASSKEY.CREDENTIAL_ID, record.credentialId.bytes)
            .set(PASSKEY.ACCOUNT_ID, accountId)
            .set(PASSKEY.PUBLIC_KEY, record.publicKey.bytes)
            .set(PASSKEY.SIGNATURE_COUNT, record.signatureCount)
            .set(PASSKEY.UV_INITIALIZED, record.isUvInitialized)
            .set(PASSKEY.BACKUP_ELIGIBLE, record.isBackupEligible)
            .set(PASSKEY.BACKUP_STATE, record.isBackupState)
            .set(PASSKEY.TRANSPORTS, record.transports.map { it.value }.sorted().joinToString(",").ifEmpty { null })
            .set(PASSKEY.CREATED_ON, today())
            .execute()
    }

    private fun toRecord(row: org.jooq.Record, handle: ByteArray): CredentialRecord = ImmutableCredentialRecord.builder()
        .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
        .credentialId(Bytes(row[PASSKEY.CREDENTIAL_ID]!!))
        .userEntityUserId(Bytes(handle))
        .publicKey(ImmutablePublicKeyCose(row[PASSKEY.PUBLIC_KEY]!!))
        .signatureCount(row[PASSKEY.SIGNATURE_COUNT]!!)
        .uvInitialized(row[PASSKEY.UV_INITIALIZED]!!)
        .backupEligible(row[PASSKEY.BACKUP_ELIGIBLE]!!)
        .backupState(row[PASSKEY.BACKUP_STATE]!!)
        .transports(row[PASSKEY.TRANSPORTS]?.split(',')?.filter { it.isNotBlank() }?.map { AuthenticatorTransport.valueOf(it) }?.toSet().orEmpty())
        .created(row[PASSKEY.CREATED_ON]!!.atStartOfDay(ClockConfiguration.ZONE).toInstant())
        .lastUsed(row[PASSKEY.CREATED_ON]!!.atStartOfDay(ClockConfiguration.ZONE).toInstant())
        .label("")
        .build()

    companion object {
        /** The most passkeys of an account (criterion 20). */
        const val MAX_PER_ACCOUNT = 10

        /** The size of the handle, which WebAuthn caps at 64 bytes. */
        const val HANDLE_BYTES = 64

        private val base64 = Base64.getUrlEncoder().withoutPadding()

        /** What a form sends to say which passkey: the identifier of the credential, in base64url. */
        fun reference(credentialId: ByteArray): String = base64.encodeToString(credentialId)

        /** The identifier in a reference, or null if it is not one (too long, not base64url): such a reference is the one of a passkey that does not exist. */
        fun decodeReference(reference: String): ByteArray? {
            if (reference.length > 1400 || !reference.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) return null
            return runCatching { Base64.getUrlDecoder().decode(reference) }.getOrNull()?.takeIf { it.size in 16..1023 }
        }
    }
}
