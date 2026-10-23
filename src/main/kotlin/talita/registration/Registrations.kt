package talita.registration

import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.stereotype.Component
import talita.account.CurrentAccount
import talita.gathering.GatheringInfo
import talita.gathering.GatheringReader
import talita.gathering.isUniqueViolation
import talita.jooq.enums.RegistrationAttendance
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.REGISTRATION_DAY
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

/** A child of an account that can be picked again for a gathering («I tuoi figli»). */
data class KnownChild(val id: Long, val firstName: String, val lastName: String, val birthDate: LocalDate) {
    val name: String get() = "$firstName $lastName"
}

/** What came of confirming the registrations (criteria 7-10). */
sealed interface SubmitResult {
    data class Created(val registrationIds: List<Long>) : SubmitResult

    /** The same confirmation again (a double tap, a network that came back): there was already one registration for each child, with this very text. */
    data object Repeated : SubmitResult

    /** The registrations are closed, or the gathering is over, or it does not exist any more: nothing was saved. */
    data class Closed(val over: Boolean) : SubmitResult

    /** The text that the person read is not the text now (the custodians, the title, the dates or the place changed): nothing was saved. */
    data class TextChanged(val text: CustodyText.Rendered) : SubmitResult

    /** These children (by position in the form) are already registered at this gathering. */
    data class Taken(val positions: List<Int>) : SubmitResult
}

/**
 * The registrations: the writes of the parent's two steps and what the pages of the parent and of the teachers need to read. A registration is
 * a child at a gathering, and it is also the custody (the text accepted is kept whole, with the version of the model).
 *
 * What is decided inside a transaction that first locks the gathering **for sharing** (`select … for share`): closing the registrations, changing
 * the dates or the custodians take the same row for update, so they wait for a registration that is being made and the other way round. A
 * registration either exists before the closing, or it does not exist (criterion 9). The text shown at step 2 is made again here, from what the
 * database says now, and compared with the fingerprint of what the person read (criterion 8).
 *
 * Nothing here writes a name, a date of birth, a phone or a note in the log: only ids and counts.
 */
@Component
class Registrations(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val gatherings: GatheringReader,
    private val healthNotes: HealthNotes,
) {

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock)

    // ---- What the first step needs --------------------------------------------------------------------------------------------

    /** `unaccent(lower(btrim(x)))`: how two names are compared (capitals, accents and spaces at the sides do not count). */
    private fun same(field: Field<String?>): Field<String> = DSL.field("unaccent(lower(btrim({0})))", SQLDataType.VARCHAR, field)

    private fun same(typed: String): Field<String> = DSL.field("unaccent(lower(btrim({0})))", SQLDataType.VARCHAR, DSL.`val`(typed))

    /** The children of [accountId] that are not registered at [gatheringId] yet: «I tuoi figli», the youngest registered last. */
    fun knownChildren(accountId: Long, gatheringId: Long): List<KnownChild> =
        dsl.select(CHILD.ID, CHILD.FIRST_NAME, CHILD.LAST_NAME, CHILD.BIRTH_DATE).from(CHILD)
            .where(CHILD.ACCOUNT_ID.eq(accountId))
            .andNotExists(DSL.selectOne().from(REGISTRATION).where(REGISTRATION.CHILD_ID.eq(CHILD.ID)).and(REGISTRATION.GATHERING_ID.eq(gatheringId)))
            .orderBy(CHILD.BIRTH_DATE.desc(), CHILD.ID)
            .fetch { KnownChild(it[CHILD.ID]!!, it[CHILD.FIRST_NAME]!!, it[CHILD.LAST_NAME]!!, it[CHILD.BIRTH_DATE]!!) }

    /** A known child of the account, if [id] is one of theirs. */
    fun knownChild(accountId: Long, id: Long): KnownChild? =
        dsl.select(CHILD.ID, CHILD.FIRST_NAME, CHILD.LAST_NAME, CHILD.BIRTH_DATE).from(CHILD).where(CHILD.ID.eq(id)).and(CHILD.ACCOUNT_ID.eq(accountId))
            .fetchOne { KnownChild(it[CHILD.ID]!!, it[CHILD.FIRST_NAME]!!, it[CHILD.LAST_NAME]!!, it[CHILD.BIRTH_DATE]!!) }

    /** The phone of the last registration of [accountId] at [gatheringId], for «Iscrivi un altro figlio»: the phone is one for the gathering. */
    fun latestPhone(accountId: Long, gatheringId: Long): String? =
        dsl.select(REGISTRATION.PHONE).from(REGISTRATION).where(REGISTRATION.ACCOUNT_ID.eq(accountId)).and(REGISTRATION.GATHERING_ID.eq(gatheringId))
            .orderBy(REGISTRATION.CREATED_AT.desc(), REGISTRATION.ID.desc()).limit(1).fetchOne(REGISTRATION.PHONE)

    /**
     * The id of the child of [accountId] that [first], [last] and [birth] write (the same child, by the rules of [same]), or null. [knownId] is
     * the child the block came from: if it is the account's, it is that child, whatever it was corrected to.
     */
    private fun childOf(context: DSLContext, accountId: Long, knownId: Long?, first: String, last: String, birth: LocalDate): Long? {
        if (knownId != null) {
            context.select(CHILD.ID).from(CHILD).where(CHILD.ID.eq(knownId)).and(CHILD.ACCOUNT_ID.eq(accountId)).fetchOne(CHILD.ID)?.let { return it }
        }
        return context.select(CHILD.ID).from(CHILD).where(CHILD.ACCOUNT_ID.eq(accountId)).and(CHILD.BIRTH_DATE.eq(birth))
            .and(same(CHILD.FIRST_NAME).eq(same(first))).and(same(CHILD.LAST_NAME).eq(same(last))).orderBy(CHILD.ID).limit(1).fetchOne(CHILD.ID)
    }

    /** The registration of [childId] at [gatheringId] with its custody text, or null. */
    private fun registrationOf(context: DSLContext, gatheringId: Long, childId: Long): Pair<Long, String?>? =
        context.select(REGISTRATION.ID, REGISTRATION.CUSTODY_TEXT).from(REGISTRATION).where(REGISTRATION.GATHERING_ID.eq(gatheringId)).and(REGISTRATION.CHILD_ID.eq(childId))
            .fetchOne { it[REGISTRATION.ID]!! to it[REGISTRATION.CUSTODY_TEXT] }

    /**
     * Adds to [check] the error «già iscritto» to each block that is a child of [accountId] already registered at [gatheringId] (withdrawn too: it
     * is put back from «Iscrizioni»), except the one of the registration [except] (the modification of it). The blocks that are not good yet are left alone.
     */
    fun markTaken(check: FormCheck, accountId: Long, gatheringId: Long, except: Long? = null): FormCheck = check.copy(
        children = check.children.map { block ->
            if (block.firstName.isEmpty() || block.lastName.isEmpty() || block.birthDate == null) return@map block
            val childId = childOf(dsl, accountId, block.input.knownId, block.firstName, block.lastName, block.birthDate) ?: return@map block
            val registration = registrationOf(dsl, gatheringId, childId)?.first
            if (registration == null || registration == except) {
                block
            } else {
                block.copy(errors = block.errors + ("firstName" to "${block.name} $TAKEN"), taken = true)
            }
        },
    )

    // ---- The text -------------------------------------------------------------------------------------------------------------

    /** The text of the custody for [children] at [gathering], with the custodians of that moment and the address of whoever accepts (criterion 6). */
    fun text(gathering: GatheringInfo, email: String, children: List<ChildCheck>): CustodyText.Rendered = CustodyText.render(
        CustodyData(
            email = email,
            children = children.map { CustodyChild(it.name, it.birthDate!!) },
            custodians = gathering.custodians.map { it.name },
            title = gathering.title,
            place = gathering.place.orEmpty(),
            start = gathering.start,
            end = gathering.end,
        ),
    )

    // ---- Confirming -----------------------------------------------------------------------------------------------------------

    /**
     * Makes a registration for each good block of [check] (criterion 7): the exact text shown, the version of the model, the moment, the account
     * and its address of that moment. [shownHash] is the fingerprint of the text the person read; [religionConsent] the third box. All or none.
     */
    fun submit(account: CurrentAccount, gatheringId: Long, check: FormCheck, shownHash: String, religionConsent: Boolean): SubmitResult {
        repeat(2) {
            try {
                return dsl.transactionResult { configuration -> submitIn(DSL.using(configuration), account, gatheringId, check, shownHash, religionConsent) }
            } catch (e: RuntimeException) {
                // Two taps at the same moment: the second one lost the race on the unique key. Asked again it finds the first registration.
                if (!e.isUniqueViolation()) throw e
            }
        }
        error("A registration was refused twice by its unique key")
    }

    private fun submitIn(context: DSLContext, account: CurrentAccount, gatheringId: Long, check: FormCheck, shownHash: String, religionConsent: Boolean): SubmitResult {
        // The registrations of one account are made one at a time: a double tap waits for the first, and then finds what it made.
        context.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.ID.eq(account.id)).forUpdate().fetch()
        val gathering = gatherings.sharedIn(context, gatheringId) ?: return SubmitResult.Closed(over = false)
        if (!gathering.registrationsPossibleToday || gathering.custodians.isEmpty()) return SubmitResult.Closed(gathering.over)

        val text = text(gathering, account.email, check.children)
        if (CustodyText.hash(text.full) != shownHash) return SubmitResult.TextChanged(text)

        val found = check.children.map { block -> childOf(context, account.id, block.input.knownId, block.firstName, block.lastName, block.birthDate!!) }
        val already = found.map { childId -> childId?.let { registrationOf(context, gatheringId, it) } }
        if (already.all { it != null && it.second == text.full }) return SubmitResult.Repeated
        val taken = already.withIndex().filter { it.value != null }.map { it.index }
        if (taken.isNotEmpty()) return SubmitResult.Taken(taken)

        val at = now()
        val ids = check.children.mapIndexed { index, block ->
            val childId = found[index]?.also { updateChild(context, it, block) } ?: insertChild(context, account.id, block)
            val registration = insertRegistration(
                context, gatheringId, childId, account.id, check.phone, block.presence!!, block.status!!, at,
                Custody(text.full, CustodyText.VERSION, at, account.email, religionConsent), enteredBy = null,
            )
            block.notes?.let { healthNotes.save(context, registration, it, byAccount = account.id, onPaper = false) }
            registration
        }
        return SubmitResult.Created(ids)
    }

    // ---- The rows --------------------------------------------------------------------------------------------------------------

    /** The custody that was accepted, as it is kept. */
    data class Custody(val text: String, val version: String, val at: OffsetDateTime, val email: String, val religionConsent: Boolean)

    private fun insertChild(context: DSLContext, accountId: Long?, block: ChildCheck): Long =
        context.insertInto(CHILD).set(CHILD.ACCOUNT_ID, accountId).set(CHILD.FIRST_NAME, block.firstName).set(CHILD.LAST_NAME, block.lastName)
            .set(CHILD.BIRTH_DATE, block.birthDate).returningResult(CHILD.ID).fetchSingle().value1()!!

    /** The child is the same one: the data are as the parent has just written them (a correction of a spelling is a correction). */
    private fun updateChild(context: DSLContext, childId: Long, block: ChildCheck) {
        context.update(CHILD).set(CHILD.FIRST_NAME, block.firstName).set(CHILD.LAST_NAME, block.lastName).set(CHILD.BIRTH_DATE, block.birthDate)
            .where(CHILD.ID.eq(childId)).execute()
    }

    private fun insertRegistration(
        context: DSLContext,
        gatheringId: Long,
        childId: Long,
        accountId: Long?,
        phone: String,
        presence: Presence,
        status: Status,
        at: OffsetDateTime,
        custody: Custody?,
        enteredBy: Long?,
        paperReceived: Boolean = false,
    ): Long {
        val id = context.insertInto(REGISTRATION)
            .set(REGISTRATION.GATHERING_ID, gatheringId)
            .set(REGISTRATION.CHILD_ID, childId)
            .set(REGISTRATION.ACCOUNT_ID, accountId)
            .set(REGISTRATION.PHONE, phone)
            .set(REGISTRATION.ATTENDANCE, attendanceOf(presence))
            .set(REGISTRATION.STATUS, status.column)
            .set(REGISTRATION.CUSTODY_TEXT, custody?.text)
            .set(REGISTRATION.CUSTODY_TEXT_VERSION, custody?.version)
            .set(REGISTRATION.CUSTODY_ACCEPTED_AT, custody?.at)
            .set(REGISTRATION.CUSTODY_ACCEPTED_BY_EMAIL, custody?.email)
            .set(REGISTRATION.CUSTODY_RELIGION_CONSENT, custody?.religionConsent ?: false)
            .set(REGISTRATION.PAPER_FORM_RECEIVED, paperReceived)
            .set(REGISTRATION.ENTERED_BY_ACCOUNT_ID, enteredBy)
            .set(REGISTRATION.CREATED_AT, at)
            .set(REGISTRATION.UPDATED_AT, at)
            .returningResult(REGISTRATION.ID).fetchSingle().value1()!!
        writeDays(context, id, presence)
        return id
    }

    private fun attendanceOf(presence: Presence): RegistrationAttendance = if (presence.allDays) RegistrationAttendance.all_days else RegistrationAttendance.days

    /** The days of a registration of some days, replacing the ones there were (none for every day). */
    private fun writeDays(context: DSLContext, registrationId: Long, presence: Presence) {
        context.deleteFrom(REGISTRATION_DAY).where(REGISTRATION_DAY.REGISTRATION_ID.eq(registrationId)).execute()
        for ((day, kind) in presence.days) {
            context.insertInto(REGISTRATION_DAY).set(REGISTRATION_DAY.REGISTRATION_ID, registrationId).set(REGISTRATION_DAY.DAY, day).set(REGISTRATION_DAY.KIND, kind.column).execute()
        }
    }

    companion object {
        /** What follows the name in the error of a child that is registered already: the page goes on with the link «Iscrizioni» and a full stop. */
        const val TAKEN = "è già iscritto a questa convivenza: puoi cambiarne l’iscrizione da "
    }
}
