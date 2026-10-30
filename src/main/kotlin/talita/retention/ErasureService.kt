package talita.retention

import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import talita.ClockConfiguration
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHECKIN
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.PAYMENT
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.RETENTION_LOG
import talita.jooq.tables.references.ROOM_CHILD
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.TEACHER_INVITATION
import talita.registration.HealthNotes
import talita.teacher.RemovalResult
import talita.teacher.Teachers
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

/** A child of an account, for the list «I tuoi figli» of the profile: only what a row needs. */
data class OwnChild(val id: Long, val firstName: String, val lastName: String)

/**
 * **The deletions on request** (criteria 16-28): a parent deletes the data of a child, a person deletes the account. One class, because the pages that say what will
 * happen, the transaction that does it and the job that does it again after a restore have to be the same code: the sentences in a dialog cannot say anything other
 * than what is done, and a request redone after a backup is restored does exactly what the request did.
 *
 * - **What goes and what stays**: the notes on health always go (a withdrawal does it too, and so does taking them away: [HealthNotes]); a registration that was
 *   withdrawn, with no presence and no payment, goes now; any other stays until its own date (one year after the end of the gathering), as the proof of the custody
 *   (or, if it is withdrawn and has presences or payments, as the register of the gathering), and **the child, if something stays, is detached from the account**: it
 *   is no longer among «I tuoi figli». With nothing left the child is deleted. An account that is deleted takes the same road for each child, loses the link of what stays
 *   (the acceptance, with the address copied into it, stays), and loses the teacher row and the invitations; the account row goes too, unless it is in the register of the
 *   readings: then it stays with the address alone, until the readings go.
 * - **What blocks**: a registration that is not withdrawn in a gathering that is not finished (the parent withdraws it first, or asks the teachers if the registrations are
 *   closed); for the account, also the last teacher and a custodian of a gathering that is not finished: the same rule and the same lock as «Togli» ([Teachers.lockAndCheck]).
 *   The check is made **inside the transaction**, with the registrations locked for update and their gatherings for share, in the order of every other change of a registration, so a
 *   request made by hand or from a page that was left open cannot go round it.
 * - **Real deletions** (`delete`), in one transaction, with a row in the register (`child-request`, `account-request`, with the number of the child or of the account for 37
 *   days). The keys to `account` that have no cascade (`child`, `registration`, the consent of the notes, the register of the readings) are settled here **before** the
 *   `delete`: a mistake is an error in a test and not data detached in silence.
 *
 * Nothing here writes a name, an address, a date of birth or a note in the log; the answers carry the names only to the one who asked.
 *
 * To add a rule to the deletion of a registration is to change [deleteRegistrations], the single place that deletes them. The rooms of TALITA-11 hang from the registration
 * (`on delete cascade`), so the ones that go take their room with them; the ones that stay are taken out of their room by [removeFromRooms]: who sleeps where is the data
 * of the child, and it goes at once (criterion 19 of TALITA-7 and 20 of TALITA-11).
 */
@Component
class ErasureService(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val policy: RetentionPolicy,
    private val healthNotes: HealthNotes,
    private val teachers: Teachers,
) {

    private val log = LoggerFactory.getLogger(ErasureService::class.java)

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock)

    private fun dayOf(at: OffsetDateTime): LocalDate = at.atZoneSameInstant(ClockConfiguration.ZONE).toLocalDate()

    // ---- What is there ------------------------------------------------------------------------------------------------------------

    private class Row(
        val id: Long,
        val childId: Long,
        val childFirstName: String,
        val childLastName: String,
        val gatheringId: Long,
        val withdrawn: Boolean,
        /** The child is not attached to any account (its parent had its data deleted and the registration stayed). */
        val detached: Boolean,
    )

    private class GatheringRow(val id: Long, val title: String, val end: LocalDate, val state: GatheringStatus)

    private class Scope(val rows: List<Row>, val gatherings: Map<Long, GatheringRow>, val marked: Set<Long>)

    /**
     * The registrations that [condition] says, with their gatherings and which of them have presences or payments. [lock]: inside a transaction that is going to change
     * them, the registrations are locked for update and then the gatherings for share, in the order of `Registrations.lock`.
     */
    private fun load(tx: DSLContext, condition: Condition, lock: Boolean): Scope {
        val base = tx.select(REGISTRATION.ID, REGISTRATION.CHILD_ID, CHILD.FIRST_NAME, CHILD.LAST_NAME, REGISTRATION.GATHERING_ID, REGISTRATION.STATUS, CHILD.ACCOUNT_ID)
            .from(REGISTRATION).join(CHILD).on(CHILD.ID.eq(REGISTRATION.CHILD_ID)).where(condition).orderBy(REGISTRATION.ID)
        val fetched = if (lock) base.forUpdate().of(REGISTRATION).fetch() else base.fetch()
        val rows = fetched.map {
            Row(it[REGISTRATION.ID]!!, it[REGISTRATION.CHILD_ID]!!, it[CHILD.FIRST_NAME]!!, it[CHILD.LAST_NAME]!!, it[REGISTRATION.GATHERING_ID]!!, it[REGISTRATION.STATUS] == RegistrationStatus.withdrawn, it[CHILD.ACCOUNT_ID] == null)
        }
        val gatheringIds = rows.map { it.gatheringId }.distinct()
        val gatheringQuery = tx.select(GATHERING.ID, GATHERING.TITLE, GATHERING.END_DATE, GATHERING.STATUS).from(GATHERING).where(GATHERING.ID.`in`(gatheringIds)).orderBy(GATHERING.ID)
        val gatherings = (if (lock) gatheringQuery.forShare().fetch() else gatheringQuery.fetch())
            .associate { it[GATHERING.ID]!! to GatheringRow(it[GATHERING.ID]!!, it[GATHERING.TITLE]!!, it[GATHERING.END_DATE]!!, it[GATHERING.STATUS]!!) }
        val ids = rows.map { it.id }
        val marked = if (ids.isEmpty()) {
            emptySet()
        } else {
            (tx.select(CHECKIN.REGISTRATION_ID).from(CHECKIN).where(CHECKIN.REGISTRATION_ID.`in`(ids)).fetch(CHECKIN.REGISTRATION_ID) +
                tx.select(PAYMENT.REGISTRATION_ID).from(PAYMENT).where(PAYMENT.REGISTRATION_ID.`in`(ids)).fetch(PAYMENT.REGISTRATION_ID)).map { it!! }.toSet()
        }
        return Scope(rows, gatherings, marked)
    }

    /** The registrations of a child. */
    private fun ofChild(childId: Long): Condition = REGISTRATION.CHILD_ID.eq(childId)

    /** The registrations of an account: those in its name, and those of its children (a child that was detached keeps the registrations in the name of the account). */
    private fun ofAccount(accountId: Long): Condition =
        REGISTRATION.ACCOUNT_ID.eq(accountId).or(REGISTRATION.CHILD_ID.`in`(DSL.select(CHILD.ID).from(CHILD).where(CHILD.ACCOUNT_ID.eq(accountId))))

    private class Classified(val blockers: List<Blocker>, val fates: List<RegistrationFate>, val hidden: List<Long>)

    /**
     * What becomes of each registration of [scope] as of [today]: one that is past its date is gone already (it is not shown, and goes with the rest); a withdrawn one with no
     * presence and no payment goes now; one that is not withdrawn and whose gathering is not finished blocks; any other stays until its date.
     */
    private fun classify(scope: Scope, today: LocalDate): Classified {
        val blockers = mutableListOf<Blocker>()
        val fates = mutableListOf<RegistrationFate>()
        val hidden = mutableListOf<Long>()
        for (row in scope.rows) {
            val gathering = scope.gatherings.getValue(row.gatheringId)
            if (policy.registrationsExpired(gathering.end, today)) {
                hidden += row.id
                continue
            }
            val marked = row.id in scope.marked
            val over = gathering.state != GatheringStatus.draft && gathering.end.isBefore(today)
            fun fate(until: LocalDate?, reason: KeepReason?) = RegistrationFate(
                row.id, row.childId, row.childFirstName, row.childLastName, row.gatheringId, gathering.title, row.withdrawn, marked, until, reason,
            )
            when {
                row.withdrawn && !marked -> fates += fate(null, null)
                !row.withdrawn && !over -> blockers += Blocker(
                    row.id, row.childId, row.childFirstName, row.childLastName, row.gatheringId, gathering.title,
                    parentCanWithdraw = gathering.state == GatheringStatus.open && !row.detached, detached = row.detached,
                )
                else -> fates += fate(policy.registrationsDeletionDate(gathering.end), if (row.withdrawn) KeepReason.REGISTER else KeepReason.PROOF)
            }
        }
        return Classified(blockers, fates, hidden)
    }

    // ---- What the pages ask (nothing is changed, nothing is locked) ------------------------------------------------------------

    /** The children of [accountId], by surname and name: «I tuoi figli». A child that was detached is not among them. */
    fun childrenOf(accountId: Long): List<OwnChild> =
        dsl.select(CHILD.ID, CHILD.FIRST_NAME, CHILD.LAST_NAME).from(CHILD).where(CHILD.ACCOUNT_ID.eq(accountId))
            .orderBy(DSL.lower(CHILD.LAST_NAME), DSL.lower(CHILD.FIRST_NAME), CHILD.ID).fetch { OwnChild(it[CHILD.ID]!!, it[CHILD.FIRST_NAME]!!, it[CHILD.LAST_NAME]!!) }

    /** The register still has the number of this child: its data were deleted on request (a second request for it is not an error, criterion 22). */
    fun childWasErased(childId: Long): Boolean = RetentionLog.hasRequest(dsl, RetentionLog.CHILD_REQUEST, childId)

    private fun childPlan(child: OwnChild, classified: Classified): ChildPlan {
        val fates = classified.fates.filter { it.childId == child.id }
        return ChildPlan(
            childId = child.id,
            firstName = child.firstName,
            lastName = child.lastName,
            blockers = classified.blockers.filter { it.childId == child.id },
            registrations = fates,
            hasNotes = healthNotes.existing(fates.map { it.registrationId } + classified.blockers.filter { it.childId == child.id }.map { it.registrationId }).isNotEmpty(),
        )
    }

    /** What deleting the data of the child [childId] of [accountId] would do now; null if the child is not one of theirs. */
    fun planChild(accountId: Long, childId: Long): ChildPlan? {
        val child = childrenOf(accountId).firstOrNull { it.id == childId } ?: return null
        return childPlan(child, classify(load(dsl, ofChild(childId), lock = false), dayOf(now())))
    }

    /** The last day of each gathering of the registrations of [condition] that the teacher [accountId] marked or typed in, past-their-date ones left out. */
    private fun endsOfWhatWasMarked(accountId: Long, today: LocalDate): List<LocalDate> {
        fun ends(registrations: Condition): List<LocalDate> =
            dsl.selectDistinct(GATHERING.END_DATE).from(REGISTRATION).join(GATHERING).on(GATHERING.ID.eq(REGISTRATION.GATHERING_ID)).where(registrations).fetch(GATHERING.END_DATE).map { it!! }
        return (
            ends(REGISTRATION.ID.`in`(DSL.select(CHECKIN.REGISTRATION_ID).from(CHECKIN).where(CHECKIN.RECORDED_BY_ACCOUNT_ID.eq(accountId)))) +
                ends(REGISTRATION.ID.`in`(DSL.select(PAYMENT.REGISTRATION_ID).from(PAYMENT).where(PAYMENT.RECORDED_BY_ACCOUNT_ID.eq(accountId)))) +
                ends(REGISTRATION.ENTERED_BY_ACCOUNT_ID.eq(accountId))
            ).filterNot { policy.registrationsExpired(it, today) }
    }

    private fun farthest(ends: List<LocalDate>): LocalDate? = ends.maxOrNull()?.let { policy.registrationsDeletionDate(it) }

    /** What deleting the account [accountId] would do now; null if there is no such account. */
    fun planAccount(accountId: Long): AccountPlan? {
        val email = dsl.select(ACCOUNT.EMAIL).from(ACCOUNT).where(ACCOUNT.ID.eq(accountId)).fetchOne(ACCOUNT.EMAIL) ?: return null
        val today = dayOf(now())
        val classified = classify(load(dsl, ofAccount(accountId), lock = false), today)
        val block = teachers.blockOf(accountId, today)
        return AccountPlan(
            accountId = accountId,
            isTeacher = dsl.fetchExists(TEACHER, TEACHER.ACCOUNT_ID.eq(accountId)),
            hasInvitations = dsl.fetchExists(TEACHER_INVITATION, TEACHER_INVITATION.EMAIL.eq(email)),
            children = childrenOf(accountId).map { childPlan(it, classified) },
            kept = classified.fates.filter { !it.goes },
            readingsUntil = farthest(healthNotes.readGatheringEnds(dsl, accountId).filterNot { policy.registrationsExpired(it, today) }),
            markedUntil = farthest(endsOfWhatWasMarked(accountId, today)),
            blockers = classified.blockers,
            teacherBlock = TeacherBlock(block is RemovalResult.LastTeacher, (block as? RemovalResult.IsCustodian)?.gatherings.orEmpty()),
        )
    }

    // ---- The deletion of the data of a child (criteria 17, 19, 21, 22) ---------------------------------------------------------

    /**
     * Deletes the data of the child [childId] on the request of its parent [accountId], in one transaction (criterion 19). [ChildErasure.NotFound] for a child that is
     * not theirs (the answer is the page of an id that does not exist); [ChildErasure.AlreadyDone] for one that the register remembers (the request was made already).
     */
    fun eraseChild(accountId: Long, childId: Long): ChildErasure = dsl.transactionResult { configuration ->
        eraseChildIn(DSL.using(configuration), accountId, childId, now(), RetentionLog.CHILD_REQUEST)
    }

    internal fun eraseChildIn(tx: DSLContext, accountId: Long, childId: Long, at: OffsetDateTime, rule: String): ChildErasure {
        // The requests of one account are made one at a time (as the registrations are): a double tap waits for the first and finds what it did.
        tx.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.ID.eq(accountId)).forUpdate().fetch()
        val child = tx.select(CHILD.FIRST_NAME, CHILD.LAST_NAME).from(CHILD).where(CHILD.ID.eq(childId)).and(CHILD.ACCOUNT_ID.eq(accountId)).forUpdate().fetchOne()
            ?: return if (RetentionLog.hasRequest(tx, RetentionLog.CHILD_REQUEST, childId)) ChildErasure.AlreadyDone else ChildErasure.NotFound
        val scope = load(tx, ofChild(childId), lock = true)
        val classified = classify(scope, dayOf(at))
        if (classified.blockers.isNotEmpty()) return ChildErasure.Blocked(classified.blockers)

        var rows = healthNotes.removeOfRegistrations(tx, scope.rows.map { it.id })
        rows += deleteRegistrations(tx, classified.hidden + classified.fates.filter { it.goes }.map { it.registrationId })
        val kept = classified.fates.filter { !it.goes }
        rows += removeFromRooms(tx, kept.map { it.registrationId })
        if (kept.isEmpty()) {
            rows += tx.deleteFrom(CHILD).where(CHILD.ID.eq(childId)).execute()
        } else {
            tx.update(CHILD).set(CHILD.ACCOUNT_ID, null as Long?).where(CHILD.ID.eq(childId)).execute()
        }
        // The number goes in the register only for a request: a request made again after a restore leaves no number (the first one still has it).
        RetentionLog.write(tx, at, rule, rows, if (rule == RetentionLog.CHILD_REQUEST) childId else null)
        return ChildErasure.Done(child[CHILD.FIRST_NAME]!!, child[CHILD.LAST_NAME]!!, kept, childWent = kept.isEmpty(), rows = rows)
    }

    /** The one place that deletes registrations (and, with them, their days, notes, readings, presences and payments: `on delete cascade`). Returns how many went. */
    private fun deleteRegistrations(tx: DSLContext, ids: List<Long>): Int = if (ids.isEmpty()) 0 else tx.deleteFrom(REGISTRATION).where(REGISTRATION.ID.`in`(ids)).execute()

    /** The registrations that stay (the proof of the custody, the register of the gathering) are taken out of their room: where a child sleeps is its data, and goes now. Returns how many rows went. */
    private fun removeFromRooms(tx: DSLContext, ids: List<Long>): Int = if (ids.isEmpty()) 0 else tx.deleteFrom(ROOM_CHILD).where(ROOM_CHILD.REGISTRATION_ID.`in`(ids)).execute()

    // ---- The deletion of an account (criteria 24-26) -----------------------------------------------------------------------------

    /**
     * Deletes the account [accountId] on the request of the person, in one transaction (criterion 25). The session of the person is closed by the caller.
     * [AccountErasure.NotFound] if the account is not there any more.
     */
    fun eraseAccount(accountId: Long): AccountErasure = dsl.transactionResult { configuration ->
        eraseAccountIn(DSL.using(configuration), accountId, now(), RetentionLog.ACCOUNT_REQUEST)
    }

    /**
     * The deletion of the account, for the request of the person ([since] null: everything is theirs to delete) and for the request made again after a restore
     * ([since] is the moment of the request, see [redo]): then **only what is older than it** is deleted, what the person did after (a registration of a child, the
     * teacher row, an invitation, the consent to some notes) is theirs and stays, with the account that holds it. Same steps, same code: the rows that [since] spares
     * are left out of the registrations that are loaded, out of the teacher that is checked and deleted, and out of the invitation, the children and the consents.
     */
    internal fun eraseAccountIn(tx: DSLContext, accountId: Long, at: OffsetDateTime, rule: String, since: OffsetDateTime? = null): AccountErasure {
        val email = tx.select(ACCOUNT.EMAIL).from(ACCOUNT).where(ACCOUNT.ID.eq(accountId)).forUpdate().fetchOne(ACCOUNT.EMAIL) ?: return AccountErasure.NotFound
        val today = dayOf(at)
        // The registrations that [since] spares are not even loaded: they are not classified, never block and are never deleted.
        fun upToRequest(moment: Field<OffsetDateTime?>): Condition = if (since == null) DSL.trueCondition() else moment.le(since)
        val scope = load(tx, ofAccount(accountId).and(upToRequest(REGISTRATION.CREATED_AT)), lock = true)
        val classified = classify(scope, today)
        // The teachers are locked after the registrations and their gatherings, as `Gatherings.saveCustodians` takes them (the gathering, then the teachers).
        // A teacher row made after the request is not taken away, so there is nothing to check: it is not the last one that goes, nor a custodian that is left.
        val teacherKept = since != null && tx.fetchExists(TEACHER, TEACHER.ACCOUNT_ID.eq(accountId).and(TEACHER.CREATED_AT.gt(since)))
        val teacherCheck =
            if (!teacherKept && tx.fetchExists(TEACHER, TEACHER.ACCOUNT_ID.eq(accountId))) teachers.lockAndCheck(tx, accountId, today) else Teachers.TeacherCheck.NotATeacher
        val block = (teacherCheck as? Teachers.TeacherCheck.Blocked)?.result
        val teacherBlock = TeacherBlock(block is RemovalResult.LastTeacher, (block as? RemovalResult.IsCustodian)?.gatherings.orEmpty())
        if (classified.blockers.isNotEmpty() || teacherBlock.any) return AccountErasure.Blocked(classified.blockers, teacherBlock)

        // Whatever the person has on file goes with the notes: the consent they gave points at the account.
        var rows = healthNotes.removeOfRegistrations(tx, scope.rows.map { it.id }) + healthNotes.removeConsentedBy(tx, accountId, until = since)
        rows += deleteRegistrations(tx, classified.hidden + classified.fates.filter { it.goes }.map { it.registrationId })
        rows += removeFromRooms(tx, classified.fates.filter { !it.goes }.map { it.registrationId })
        // What stays loses the link with the account (the acceptance, with the address copied into it, stays).
        tx.update(REGISTRATION).set(REGISTRATION.ACCOUNT_ID, null as Long?).where(REGISTRATION.ACCOUNT_ID.eq(accountId)).and(upToRequest(REGISTRATION.CREATED_AT)).execute()
        for (child in tx.select(CHILD.ID).from(CHILD).where(CHILD.ACCOUNT_ID.eq(accountId)).orderBy(CHILD.ID).forUpdate().fetch(CHILD.ID).map { it!! }) {
            // A child with a registration made after the request is the person's, with that registration: it stays attached (the older ones of it went or lost the link above).
            if (since != null && tx.fetchExists(REGISTRATION, REGISTRATION.CHILD_ID.eq(child).and(REGISTRATION.CREATED_AT.gt(since)))) continue
            if (tx.fetchExists(REGISTRATION, REGISTRATION.CHILD_ID.eq(child))) {
                tx.update(CHILD).set(CHILD.ACCOUNT_ID, null as Long?).where(CHILD.ID.eq(child)).execute()
            } else {
                rows += tx.deleteFrom(CHILD).where(CHILD.ID.eq(child)).execute()
            }
        }
        if (teacherCheck is Teachers.TeacherCheck.Free) {
            teachers.deleteRow(tx, accountId)
            rows += 1
        }
        rows += tx.deleteFrom(TEACHER_INVITATION).where(TEACHER_INVITATION.EMAIL.eq(email)).and(upToRequest(TEACHER_INVITATION.INVITED_AT)).execute()
        // The register of the readings must say who read: the account stays, with the address alone, until the readings go. It stays also for what it was left with after a
        // request made again: `teacher` goes with the account (`on delete cascade`) and the other keys have no cascade.
        val stays = healthNotes.hasReadings(tx, accountId) || (since != null && holdsNewerActivity(tx, accountId, email))
        if (!stays) rows += tx.deleteFrom(ACCOUNT).where(ACCOUNT.ID.eq(accountId)).execute()
        // A request that finds nothing to change (an account that only stays for the readings, asked again after a restore) leaves nothing in the register.
        if (rule == RetentionLog.ACCOUNT_REQUEST || rows > 0) RetentionLog.write(tx, at, rule, rows, if (rule == RetentionLog.ACCOUNT_REQUEST) accountId else null)
        return AccountErasure.Done(stays, rows)
    }

    /** What the account is left with after the older part of a request was made again: a registration or a child in its name, a teacher row, an invitation, the consent to some notes. */
    private fun holdsNewerActivity(tx: DSLContext, accountId: Long, email: String): Boolean =
        tx.fetchExists(REGISTRATION, REGISTRATION.ACCOUNT_ID.eq(accountId)) ||
            tx.fetchExists(CHILD, CHILD.ACCOUNT_ID.eq(accountId)) ||
            tx.fetchExists(TEACHER, TEACHER.ACCOUNT_ID.eq(accountId)) ||
            tx.fetchExists(TEACHER_INVITATION, TEACHER_INVITATION.EMAIL.eq(email)) ||
            tx.fetchExists(ACCOUNT, ACCOUNT.ID.eq(accountId).and(healthNotes.holdsAccount(ACCOUNT.ID)))

    // ---- The requests made again after a restore (criterion 28) ------------------------------------------------------------------

    /**
     * After a database is restored from a backup older than a request, the register has the number of the request (the job reads it from the rows that were copied
     * back): this makes the request again, with the same code as the request, for what is there. A child or an account that is not there is nothing to do; one that is
     * there is deleted as the request did. A request that is blocked now (the backup has a registration that was not withdrawn yet) is left, with a WARNING without any
     * data: the holder decides. The register is written here, inside the transaction of the request (`child-redo`, `account-redo`, without a number); the lines that
     * come back are what the job says in the log. [at] is the moment of the job.
     *
     * **An account that stayed after its request** (it is in the register of the readings, so the address stayed) is a person who can come back: what they did after
     * the request is theirs. The request is made again for **what is older than it** (what a backup brought back: the children and their registrations, the notes, the
     * teacher row, the invitation) and the rest is left: a registration made after the request with its child, a teacher row or an invitation made after it, and the
     * account that holds them ([eraseAccountIn], `since`: the last request of that account, `teacher.created_at`, `teacher_invitation.invited_at`,
     * `registration.created_at`, `health_note.consent_at`). A child has no such case: after its request it is gone or detached for good, so a child that is of an
     * account again is one that a backup brought back, whole.
     */
    internal fun redo(tx: DSLContext, at: OffsetDateTime): List<Deleted> {
        val requests = tx.select(RETENTION_LOG.RULE, RETENTION_LOG.TARGET_ID).from(RETENTION_LOG)
            .where(RETENTION_LOG.RULE.`in`(RetentionLog.CHILD_REQUEST, RetentionLog.ACCOUNT_REQUEST)).and(RETENTION_LOG.TARGET_ID.isNotNull)
            .orderBy(RETENTION_LOG.ID).fetch().map { it[RETENTION_LOG.RULE]!! to it[RETENTION_LOG.TARGET_ID]!! }.distinct()
        val done = mutableListOf<Deleted>()
        for ((rule, target) in requests) {
            if (rule == RetentionLog.CHILD_REQUEST) {
                // A child that is not there, or that was detached, has nothing to do: only one that is still of an account is a request to make again.
                val owner = tx.select(CHILD.ACCOUNT_ID).from(CHILD).where(CHILD.ID.eq(target)).fetchOne(CHILD.ACCOUNT_ID) ?: continue
                when (val result = eraseChildIn(tx, owner, target, at, RetentionLog.CHILD_REDO)) {
                    is ChildErasure.Done -> done += Deleted(RetentionLog.CHILD_REDO, result.rows)
                    is ChildErasure.Blocked -> log.warn("Retention: a request that the register remembers cannot be made again now, because something has to be done first. The holder decides.")
                    else -> Unit
                }
            } else {
                if (!tx.fetchExists(ACCOUNT, ACCOUNT.ID.eq(target))) continue
                val requestedAt = tx.select(DSL.max(RETENTION_LOG.DONE_AT)).from(RETENTION_LOG)
                    .where(RETENTION_LOG.RULE.eq(RetentionLog.ACCOUNT_REQUEST)).and(RETENTION_LOG.TARGET_ID.eq(target)).fetchOne(0, OffsetDateTime::class.java) ?: continue
                when (val result = eraseAccountIn(tx, target, at, RetentionLog.ACCOUNT_REDO, since = requestedAt)) {
                    is AccountErasure.Done -> if (result.rows > 0) done += Deleted(RetentionLog.ACCOUNT_REDO, result.rows)
                    is AccountErasure.Blocked -> log.warn("Retention: a request that the register remembers cannot be made again now, because something has to be done first. The holder decides.")
                    AccountErasure.NotFound -> Unit
                }
            }
        }
        return done
    }
}
