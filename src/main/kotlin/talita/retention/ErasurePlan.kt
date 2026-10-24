package talita.retention

import talita.gathering.GatheringRef
import java.time.LocalDate

/** Why a registration stays when a parent asks to delete the data of a child: [PROOF] it is the proof of the custody, [REGISTER] it is the register of the gathering. */
enum class KeepReason { PROOF, REGISTER }

/**
 * What becomes of one registration of a child when the data of the child, or the account, are deleted on request (criteria 17, 19, 25): it goes now (withdrawn, with no
 * presence and no payment), or it stays until its date, with the reason.
 *
 * @property withdrawn the registration was withdrawn
 * @property marked it has presences or payments, which the teachers wrote: it stays with them
 * @property keptUntil the day it goes, if it stays (`RetentionPolicy.registrationsDeletionDate`); null if it goes now
 * @property reason why it stays, if it does
 */
data class RegistrationFate(
    val registrationId: Long,
    val childId: Long,
    val childFirstName: String,
    val childLastName: String,
    val gatheringId: Long,
    val gatheringTitle: String,
    val withdrawn: Boolean,
    val marked: Boolean,
    val keptUntil: LocalDate?,
    val reason: KeepReason?,
) {
    val childName: String get() = "$childFirstName $childLastName"

    /** It is deleted now. */
    val goes: Boolean get() = keptUntil == null
}

/**
 * A registration that stops the deletion: not withdrawn, in a gathering that is not finished (criterion 17). [parentCanWithdraw] says which words and which link:
 * with the registrations open the parent withdraws it from «Iscrizioni», with them closed it has to be asked of the teachers. [detached]: the child is not among
 * «I tuoi figli» (the parent had its data deleted and a teacher put the registration back), and «Iscrizioni» only shows the registration: the parent cannot withdraw it
 * whatever the state of the registrations, and has to ask the teachers (it is never [parentCanWithdraw]).
 */
data class Blocker(
    val registrationId: Long,
    val childId: Long,
    val childFirstName: String,
    val childLastName: String,
    val gatheringId: Long,
    val gatheringTitle: String,
    val parentCanWithdraw: Boolean,
    val detached: Boolean = false,
) {
    val childName: String get() = "$childFirstName $childLastName"
}

/**
 * What the deletion of the data of one child would do, worked out from the database as it is now (criteria 17-19): the same plan feeds the dialog of the profile,
 * the page that stands in for it without JavaScript and, inside the transaction, the deletion itself, so the sentences cannot say anything other than what is done.
 *
 * @property registrations every registration of the child that is still there, by gathering, with what becomes of it
 * @property hasNotes some registration has notes on health that are not past their date: they go in any case
 */
data class ChildPlan(
    val childId: Long,
    val firstName: String,
    val lastName: String,
    val blockers: List<Blocker>,
    val registrations: List<RegistrationFate>,
    val hasNotes: Boolean,
) {
    val name: String get() = "$firstName $lastName"

    /** Something has to be done before: the data cannot be deleted now. */
    val blocked: Boolean get() = blockers.isNotEmpty()

    /** The registrations that go now. */
    val going: List<RegistrationFate> get() = registrations.filter { it.goes }

    /** The registrations that stay until their date. */
    val kept: List<RegistrationFate> get() = registrations.filter { !it.goes }

    /** Nothing stays: the child is deleted too. */
    val childGoes: Boolean get() = kept.isEmpty()
}

/** Who asks to be taken away from the teachers and cannot: the last teacher, and the gatherings that are not finished of which they are a custodian. */
data class TeacherBlock(val lastTeacher: Boolean, val custodies: List<GatheringRef>) {
    val any: Boolean get() = lastTeacher || custodies.isNotEmpty()
}

/**
 * What the deletion of the account would do (criteria 23-25). [children] are the children of the account, each with its plan; [kept] every registration that stays
 * (also those of children that were detached before) with the address copied into it; [readingsUntil] is the day the register of the readings goes, if the account is in
 * it (the address stays until then); [markedUntil] the day what the teacher marked or typed in goes, if there is something. [blockers] are **all** the registrations that
 * stop the deletion, also those of children that were detached before (a teacher can put one back): the request counts them all, so the profile must say them all.
 */
data class AccountPlan(
    val accountId: Long,
    val isTeacher: Boolean,
    val hasInvitations: Boolean,
    val children: List<ChildPlan>,
    val kept: List<RegistrationFate>,
    val readingsUntil: LocalDate?,
    val markedUntil: LocalDate?,
    val blockers: List<Blocker>,
    val teacherBlock: TeacherBlock,
) {
    /** The registrations that are not withdrawn and the teacher block: the account cannot be deleted before. */
    val blocked: Boolean get() = blockers.isNotEmpty() || teacherBlock.any

    /** The address stays, without a name, because the account is in the register of the readings. */
    val addressStays: Boolean get() = readingsUntil != null

    /** Nothing at all stays (the account goes, and with it everything that was in its name). */
    val nothingStays: Boolean get() = kept.isEmpty() && readingsUntil == null && markedUntil == null
}

/** What came of the request to delete the data of a child. */
sealed interface ChildErasure {
    /** Done: the registrations that stay (with their date), whether the child went too, and the name for the notice (it lives only in the answer, never in an address or a log). */
    data class Done(val firstName: String, val lastName: String, val kept: List<RegistrationFate>, val childWent: Boolean, val rows: Int) : ChildErasure

    /** Not now: [blockers] says what to do first. Nothing was changed. */
    data class Blocked(val blockers: List<Blocker>) : ChildErasure

    /** The same request was made before (a double tap, a network that came back, another phone): the register has its number. Nothing was changed. */
    data object AlreadyDone : ChildErasure

    /** There is no such child for this account. */
    data object NotFound : ChildErasure
}

/** What came of the request to delete an account. */
sealed interface AccountErasure {
    /** Done: [accountStays] is true when the address stays, alone, because the account is in the register of the readings. */
    data class Done(val accountStays: Boolean, val rows: Int) : AccountErasure

    /** Not now: nothing was changed. */
    data class Blocked(val children: List<Blocker>, val teacher: TeacherBlock) : AccountErasure

    /** There is no such account any more. */
    data object NotFound : AccountErasure
}
