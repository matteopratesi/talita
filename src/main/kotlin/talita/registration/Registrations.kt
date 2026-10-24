package talita.registration

import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.stereotype.Component
import talita.account.CurrentAccount
import talita.gathering.GatheringInfo
import talita.gathering.GatheringReader
import talita.gathering.FeeUsers
import talita.gathering.GatheringState
import talita.gathering.RegistrationCounts
import talita.gathering.RegistrationUsage
import talita.gathering.isUniqueViolation
import talita.jooq.enums.RegistrationAttendance
import talita.jooq.enums.RegistrationDayKind
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.REGISTRATION_DAY
import talita.jooq.tables.references.TEACHER
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

/** What came of changing a registration (criteria 12-15, 19, 26): the same words for the parent and for the teachers. */
sealed interface ChangeResult {
    data object Done : ChangeResult

    /** It was done already, by a tap a moment before (a withdrawal of a withdrawn registration): the same confirmation, never an error. */
    data object AlreadyDone : ChangeResult

    /** There is no such registration for this person: the page is the one of an id that does not exist. */
    data object NotFound : ChangeResult

    /** The registrations are closed, or the gathering is over: nothing was changed. [over] says which, for the words. */
    data class NotAllowed(val over: Boolean) : ChangeResult

    /** The registration is withdrawn: it has no notes to change. */
    data object IsWithdrawn : ChangeResult

    /** The new data of the child are those of another child of the same account that is already registered here. */
    data class Taken(val name: String) : ChangeResult
}

/** What came of «Rimetti confermata» (criterion 8 of TALITA-6). */
sealed interface RestoreResult {
    data object Done : RestoreResult

    /** It was not withdrawn (a tap a moment before, or another teacher): the same confirmation, never an error. */
    data object AlreadyDone : RestoreResult

    data object NotFound : RestoreResult

    /** A draft: no registration to put back. */
    data object NotAllowed : RestoreResult

    /** The days that it had are not those of the gathering any more (they were taken away while it was withdrawn): it has to be corrected, from the form of the corrections. */
    data object MustBeCorrected : RestoreResult
}

/** A registration as the pages read it, with its child and its days. [presence] is what the child comes for. */
data class RegistrationData(
    val id: Long,
    val gatheringId: Long,
    val childId: Long,
    val accountId: Long?,
    val firstName: String,
    val lastName: String,
    val birthDate: LocalDate,
    val phone: String,
    val presence: Presence,
    val status: Status,
    val custodyText: String?,
    val custodyVersion: String?,
    val custodyAcceptedAt: OffsetDateTime?,
    val custodyEmail: String?,
    val paperFormReceived: Boolean,
    val enteredBy: Long?,
    val createdAt: OffsetDateTime,
) {
    val name: String get() = "$firstName $lastName"

    /** Typed in by a teacher from the paper form: there is no account and no acceptance online. */
    val onPaper: Boolean get() = accountId == null
}

/**
 * Who can do what and when (rule R1 of TALITA-4, criterion 29), in one place: a draft has no registrations; with the registrations open and the last
 * day not passed the parent registers, changes and withdraws; closed, or open after the end, the parent only looks, and only the notes on health
 * change until the last day; after the end the parent can only take the notes away. The teachers register and correct in every state but the draft.
 */
object Rules {
    fun parentCanChange(gathering: GatheringInfo): Boolean = gathering.registrationsPossibleToday

    fun parentCanChangeHealth(gathering: GatheringInfo): Boolean = gathering.state != GatheringState.DRAFT && !gathering.over

    fun teacherCanChange(gathering: GatheringInfo): Boolean = gathering.state != GatheringState.DRAFT
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
) : RegistrationUsage {

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

    // ---- Reading ----------------------------------------------------------------------------------------------------------------

    private fun select(context: DSLContext, condition: Condition): List<RegistrationData> {
        val rows = context.select(REGISTRATION.asterisk(), CHILD.FIRST_NAME, CHILD.LAST_NAME, CHILD.BIRTH_DATE)
            .from(REGISTRATION).join(CHILD).on(CHILD.ID.eq(REGISTRATION.CHILD_ID))
            .where(condition).orderBy(REGISTRATION.CREATED_AT, REGISTRATION.ID).fetch()
        val ids = rows.map { it[REGISTRATION.ID]!! }
        val days = if (ids.isEmpty()) emptyMap() else context.select(REGISTRATION_DAY.REGISTRATION_ID, REGISTRATION_DAY.DAY, REGISTRATION_DAY.KIND).from(REGISTRATION_DAY)
            .where(REGISTRATION_DAY.REGISTRATION_ID.`in`(ids)).fetch().groupBy({ it[REGISTRATION_DAY.REGISTRATION_ID]!! }) { it[REGISTRATION_DAY.DAY]!! to DayKind.of(it[REGISTRATION_DAY.KIND]!!) }
        return rows.map { row ->
            val id = row[REGISTRATION.ID]!!
            RegistrationData(
                id = id,
                gatheringId = row[REGISTRATION.GATHERING_ID]!!,
                childId = row[REGISTRATION.CHILD_ID]!!,
                accountId = row[REGISTRATION.ACCOUNT_ID],
                firstName = row[CHILD.FIRST_NAME]!!,
                lastName = row[CHILD.LAST_NAME]!!,
                birthDate = row[CHILD.BIRTH_DATE]!!,
                phone = row[REGISTRATION.PHONE]!!,
                presence = if (row[REGISTRATION.ATTENDANCE] == RegistrationAttendance.all_days) Presence.ALL else Presence(false, days[id].orEmpty().toMap()),
                status = Status.of(row[REGISTRATION.STATUS]!!),
                custodyText = row[REGISTRATION.CUSTODY_TEXT],
                custodyVersion = row[REGISTRATION.CUSTODY_TEXT_VERSION],
                custodyAcceptedAt = row[REGISTRATION.CUSTODY_ACCEPTED_AT],
                custodyEmail = row[REGISTRATION.CUSTODY_ACCEPTED_BY_EMAIL],
                paperFormReceived = row[REGISTRATION.PAPER_FORM_RECEIVED]!!,
                enteredBy = row[REGISTRATION.ENTERED_BY_ACCOUNT_ID],
                createdAt = row[REGISTRATION.CREATED_AT]!!,
            )
        }
    }

    /** The registration with this id, whoever it belongs to: only the services and the filter [OwnRegistrations] ask for it. */
    fun find(id: Long): RegistrationData? = select(dsl, REGISTRATION.ID.eq(id)).singleOrNull()

    /** Every registration of [accountId], in every gathering, in the order they were made. */
    fun ownedBy(accountId: Long): List<RegistrationData> = select(dsl, REGISTRATION.ACCOUNT_ID.eq(accountId))

    /** The registrations of [gatheringId]: by surname and name of the child. */
    fun ofGathering(gatheringId: Long): List<RegistrationData> =
        select(dsl, REGISTRATION.GATHERING_ID.eq(gatheringId)).sortedWith(compareBy({ it.lastName.lowercase() }, { it.firstName.lowercase() }, { it.id }))

    // ---- Changing -------------------------------------------------------------------------------------------------------------------

    private fun lockRegistration(context: DSLContext, id: Long): Record? = context.selectFrom(REGISTRATION).where(REGISTRATION.ID.eq(id)).forUpdate().fetchOne()

    /**
     * The registration [id] for a change, inside the transaction of [context]: locked, and only if it is the one of [owner] (a parent; null for a teacher).
     * The gathering is read after it, locked for sharing, so that the closing waits for the change and the change finds the closing.
     */
    private fun target(context: DSLContext, id: Long, owner: Long?): Pair<RegistrationData, GatheringInfo>? {
        val locked = lockRegistration(context, id) ?: return null
        if (owner != null && locked[REGISTRATION.ACCOUNT_ID] != owner) return null
        val registration = select(context, REGISTRATION.ID.eq(id)).single()
        return registration to gatherings.sharedIn(context, registration.gatheringId)!!
    }

    private fun allowed(gathering: GatheringInfo, teacher: Boolean): Boolean = if (teacher) Rules.teacherCanChange(gathering) else Rules.parentCanChange(gathering)

    /**
     * The registration [id] locked for update inside the transaction of [context], then its gathering locked for sharing: the order that every change of a
     * registration follows (a withdrawal, a correction, and the presence of a child at the check-in), so that a presence and a withdrawal are never in the
     * middle of each other: the presence is made before the withdrawal or it is not made. Null when there is no such registration.
     */
    fun lock(context: DSLContext, id: Long): Pair<RegistrationData, GatheringInfo>? = target(context, id, null)

    /**
     * «In forse» becomes «Confermata» inside the transaction of [context], which holds the row of the registration locked: a child who is there is coming,
     * so marking the presence confirms the registration (criterion 8 of TALITA-6). Returns whether it changed.
     */
    fun confirmIn(context: DSLContext, id: Long): Boolean =
        context.update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.confirmed).set(REGISTRATION.UPDATED_AT, now())
            .where(REGISTRATION.ID.eq(id)).and(REGISTRATION.STATUS.eq(RegistrationStatus.maybe)).execute() > 0

    /**
     * «Rimetti confermata» (criterion 8 of TALITA-6): a withdrawn registration is confirmed again, with its acceptance as it was (the notes on health were
     * deleted at the withdrawal: they are the parent's to write again). The teachers do it in every state but the draft, as they correct. Putting back what
     * is not withdrawn is the same confirmation. If the days that it had are not days of the gathering any more it is not put back: the form of the
     * corrections is where its days are chosen again.
     */
    fun restoreConfirmed(id: Long): RestoreResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        val (registration, gathering) = target(tx, id, null) ?: return@transactionResult RestoreResult.NotFound
        if (registration.status != Status.WITHDRAWN) return@transactionResult RestoreResult.AlreadyDone
        if (!Rules.teacherCanChange(gathering)) return@transactionResult RestoreResult.NotAllowed
        if (Quota.due(Offer(gathering.fees, gathering.days), registration.presence) == null) return@transactionResult RestoreResult.MustBeCorrected
        tx.update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.confirmed).set(REGISTRATION.UPDATED_AT, now()).where(REGISTRATION.ID.eq(id)).execute()
        RestoreResult.Done
    }

    /**
     * Changes the registration [id] with the data of [block] (criteria 12 and 26): name and date of birth of the child, what it comes for, the status
     * (a withdrawn registration is put back by choosing «Confermata» or «In forse»), the phone. The acceptance is never touched. A parent ([owner])
     * changes the phone of all their registrations at this gathering; a teacher ([owner] null) the phone of this one.
     */
    fun update(id: Long, owner: Long?, phone: String, block: ChildCheck, paperReceived: Boolean? = null): ChangeResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        val (registration, gathering) = target(tx, id, owner) ?: return@transactionResult ChangeResult.NotFound
        if (!allowed(gathering, teacher = owner == null)) return@transactionResult ChangeResult.NotAllowed(gathering.over)
        // The data of the child may become those of another child of the same person: the other one is a child already.
        val account = registration.accountId
        if (account != null) {
            val same = childOf(tx, account, null, block.firstName, block.lastName, block.birthDate!!)
            if (same != null && same != registration.childId) return@transactionResult ChangeResult.Taken(block.name)
        }
        updateChild(tx, registration.childId, block)
        val at = now()
        val presence = block.presence!!
        // The days go first: the key refuses an attendance of every day while a day is there.
        writeDays(tx, id, Presence.ALL)
        tx.update(REGISTRATION).set(REGISTRATION.ATTENDANCE, attendanceOf(presence)).set(REGISTRATION.STATUS, block.status!!.column)
            .set(REGISTRATION.UPDATED_AT, at).where(REGISTRATION.ID.eq(id)).execute()
        writeDays(tx, id, presence)
        val samePhone = if (owner != null) REGISTRATION.GATHERING_ID.eq(registration.gatheringId).and(REGISTRATION.ACCOUNT_ID.eq(owner)) else REGISTRATION.ID.eq(id)
        tx.update(REGISTRATION).set(REGISTRATION.PHONE, phone).set(REGISTRATION.UPDATED_AT, at).where(samePhone).execute()
        // The paper form that was received is only for a paper registration (the key of the table says so too).
        if (paperReceived != null && registration.onPaper) tx.update(REGISTRATION).set(REGISTRATION.PAPER_FORM_RECEIVED, paperReceived).where(REGISTRATION.ID.eq(id)).execute()
        ChangeResult.Done
    }

    /**
     * Withdraws the registration [id] (criterion 14): it stays, as «Ritirata», the fee is not due and the days do not count; the notes on health are
     * deleted at once. Withdrawing what is withdrawn already is the same confirmation, not an error.
     */
    fun withdraw(id: Long, owner: Long?): ChangeResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        val (registration, gathering) = target(tx, id, owner) ?: return@transactionResult ChangeResult.NotFound
        if (registration.status == Status.WITHDRAWN) return@transactionResult ChangeResult.AlreadyDone
        if (!allowed(gathering, teacher = owner == null)) return@transactionResult ChangeResult.NotAllowed(gathering.over)
        tx.update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.withdrawn).set(REGISTRATION.UPDATED_AT, now()).where(REGISTRATION.ID.eq(id)).execute()
        healthNotes.remove(tx, id)
        ChangeResult.Done
    }

    /**
     * The parent writes, changes or empties the notes of their registration (criterion 19): until the last day, also with the registrations closed.
     * [notes] null takes them away. A withdrawn registration has none and gets none.
     */
    fun saveHealth(id: Long, owner: Long, notes: Notes?): ChangeResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        val (registration, gathering) = target(tx, id, owner) ?: return@transactionResult ChangeResult.NotFound
        if (registration.status == Status.WITHDRAWN) return@transactionResult ChangeResult.IsWithdrawn
        if (!Rules.parentCanChangeHealth(gathering)) return@transactionResult ChangeResult.NotAllowed(gathering.over)
        if (notes == null) healthNotes.remove(tx, id) else healthNotes.save(tx, id, notes, byAccount = owner, onPaper = false)
        tx.update(REGISTRATION).set(REGISTRATION.UPDATED_AT, now()).where(REGISTRATION.ID.eq(id)).execute()
        ChangeResult.Done
    }

    /** The parent takes the notes away and with them the consent (criterion 19): always possible while they exist, even when the gathering is over. */
    fun revokeHealth(id: Long, owner: Long): ChangeResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        lockRegistration(tx, id)?.takeIf { it[REGISTRATION.ACCOUNT_ID] == owner } ?: return@transactionResult ChangeResult.NotFound
        healthNotes.remove(tx, id)
        ChangeResult.Done
    }

    // ---- The paper forms (criteria 25, 26) ------------------------------------------------------------------------------------------

    /** What came of typing in a paper registration. */
    sealed interface PaperResult {
        data class Created(val id: Long) : PaperResult
        data object NotFound : PaperResult
        data class NotAllowed(val over: Boolean) : PaperResult
    }

    /**
     * A teacher types in the paper form of a parent (criterion 25): a child that has no account, no acceptance to give and the teacher who typed it in.
     * [paperReceived] is the signed form in the hands of the teachers; the notes, if any, come with the consent that the parent signed on paper. Possible
     * in every state of the gathering but the draft.
     */
    fun insertPaper(teacher: Long, gatheringId: Long, phone: String, block: ChildCheck, paperReceived: Boolean): PaperResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        val gathering = gatherings.sharedIn(tx, gatheringId) ?: return@transactionResult PaperResult.NotFound
        if (!Rules.teacherCanChange(gathering)) return@transactionResult PaperResult.NotAllowed(gathering.over)
        val childId = insertChild(tx, null, block)
        val id = insertRegistration(tx, gatheringId, childId, null, phone, block.presence!!, block.status!!, now(), null, enteredBy = teacher, paperReceived = paperReceived)
        block.notes?.let { healthNotes.save(tx, id, it, byAccount = null, onPaper = true) }
        PaperResult.Created(id)
    }

    /** The teacher says that the signed paper form has arrived, or that it has not (criterion 21): only for a paper registration. */
    fun markPaper(id: Long, received: Boolean): ChangeResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        val (registration, gathering) = target(tx, id, null) ?: return@transactionResult ChangeResult.NotFound
        if (!registration.onPaper) return@transactionResult ChangeResult.NotFound
        if (!Rules.teacherCanChange(gathering)) return@transactionResult ChangeResult.NotAllowed(gathering.over)
        tx.update(REGISTRATION).set(REGISTRATION.PAPER_FORM_RECEIVED, received).set(REGISTRATION.UPDATED_AT, now()).where(REGISTRATION.ID.eq(id)).execute()
        ChangeResult.Done
    }

    /**
     * A teacher corrects the notes of a paper registration (criterion 26): the online ones are the parent's. Saving is a reading too, so the register
     * has a row for it. [notes] null takes them away.
     */
    fun saveHealthOnPaper(id: Long, teacher: Long, notes: Notes?): ChangeResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        val (registration, gathering) = target(tx, id, null) ?: return@transactionResult ChangeResult.NotFound
        if (!registration.onPaper) return@transactionResult ChangeResult.NotFound
        if (registration.status == Status.WITHDRAWN) return@transactionResult ChangeResult.IsWithdrawn
        if (!Rules.teacherCanChange(gathering)) return@transactionResult ChangeResult.NotAllowed(gathering.over)
        if (notes == null) healthNotes.remove(tx, id) else healthNotes.save(tx, id, notes, byAccount = null, onPaper = true)
        healthNotes.recordReading(tx, id, teacher)
        tx.update(REGISTRATION).set(REGISTRATION.UPDATED_AT, now()).where(REGISTRATION.ID.eq(id)).execute()
        ChangeResult.Done
    }

    // ---- What the gathering and the list of the teachers ask ------------------------------------------------------------------------

    override fun counts(gatheringId: Long): RegistrationCounts {
        val byStatus = dsl.select(REGISTRATION.STATUS, DSL.count()).from(REGISTRATION).where(REGISTRATION.GATHERING_ID.eq(gatheringId)).groupBy(REGISTRATION.STATUS)
            .fetch().associate { it[REGISTRATION.STATUS]!! to it.value2() }
        return RegistrationCounts(byStatus[RegistrationStatus.confirmed] ?: 0, byStatus[RegistrationStatus.maybe] ?: 0, byStatus[RegistrationStatus.withdrawn] ?: 0)
    }

    override fun feeUsers(context: DSLContext, gatheringId: Long): FeeUsers {
        fun using(kind: RegistrationDayKind): Int =
            context.selectCount().from(REGISTRATION).where(REGISTRATION.GATHERING_ID.eq(gatheringId)).and(REGISTRATION.STATUS.ne(RegistrationStatus.withdrawn))
                .andExists(DSL.selectOne().from(REGISTRATION_DAY).where(REGISTRATION_DAY.REGISTRATION_ID.eq(REGISTRATION.ID)).and(REGISTRATION_DAY.KIND.eq(kind)))
                .fetchSingle().value1()
        return FeeUsers(using(RegistrationDayKind.full), using(RegistrationDayKind.lunch))
    }

    override fun dayUsers(context: DSLContext, gatheringId: Long, days: Collection<LocalDate>): Map<LocalDate, Int> {
        if (days.isEmpty()) return emptyMap()
        val everyDay = context.selectCount().from(REGISTRATION).where(REGISTRATION.GATHERING_ID.eq(gatheringId)).and(REGISTRATION.STATUS.ne(RegistrationStatus.withdrawn))
            .and(REGISTRATION.ATTENDANCE.eq(RegistrationAttendance.all_days)).fetchSingle().value1()
        val chosen = context.select(REGISTRATION_DAY.DAY, DSL.count()).from(REGISTRATION_DAY).join(REGISTRATION).on(REGISTRATION.ID.eq(REGISTRATION_DAY.REGISTRATION_ID))
            .where(REGISTRATION.GATHERING_ID.eq(gatheringId)).and(REGISTRATION.STATUS.ne(RegistrationStatus.withdrawn)).and(REGISTRATION_DAY.DAY.`in`(days))
            .groupBy(REGISTRATION_DAY.DAY).fetch().associate { it[REGISTRATION_DAY.DAY]!! to it.value2() }
        return days.associateWith { everyDay + (chosen[it] ?: 0) }.filterValues { it > 0 }
    }

    /**
     * For each registration of [gatheringId] that is not withdrawn, the others that have the same name, surname (compared with `unaccent`, in lowercase,
     * without the spaces at the sides) and date of birth (criterion 24): «Possibile doppione». It helps, it blocks nothing: a pair that is the same child
     * is solved by withdrawing one of them.
     */
    fun duplicates(gatheringId: Long): Map<Long, List<Long>> {
        val first = REGISTRATION.`as`("r1")
        val second = REGISTRATION.`as`("r2")
        val firstChild = CHILD.`as`("c1")
        val secondChild = CHILD.`as`("c2")
        return dsl.select(first.ID, second.ID).from(first)
            .join(firstChild).on(firstChild.ID.eq(first.CHILD_ID))
            .join(second).on(second.GATHERING_ID.eq(first.GATHERING_ID).and(second.ID.ne(first.ID)))
            .join(secondChild).on(secondChild.ID.eq(second.CHILD_ID))
            .where(first.GATHERING_ID.eq(gatheringId)).and(first.STATUS.ne(RegistrationStatus.withdrawn)).and(second.STATUS.ne(RegistrationStatus.withdrawn))
            .and(firstChild.BIRTH_DATE.eq(secondChild.BIRTH_DATE))
            .and(same(firstChild.FIRST_NAME).eq(same(secondChild.FIRST_NAME))).and(same(firstChild.LAST_NAME).eq(same(secondChild.LAST_NAME)))
            .orderBy(first.ID, second.ID)
            .fetch().groupBy({ it[first.ID]!! }) { it[second.ID]!! }
    }

    /** The name of a teacher as they wrote it, or null when the account is not a teacher any more (who typed in a paper form, who read the notes). */
    fun teacherName(accountId: Long): String? =
        dsl.select(TEACHER.FIRST_NAME, TEACHER.LAST_NAME).from(TEACHER).where(TEACHER.ACCOUNT_ID.eq(accountId)).fetchOne { "${it[TEACHER.FIRST_NAME]} ${it[TEACHER.LAST_NAME]}" }

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
