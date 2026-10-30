package talita.room

import org.springframework.stereotype.Component
import talita.gathering.GatheringFormat
import talita.gathering.GatheringInfo
import talita.registration.RegistrationData
import talita.registration.RegistrationFormat
import talita.registration.Status
import talita.retention.RetentionTexts
import talita.teacher.TeacherRow
import talita.ui.Alert
import talita.ui.AlertVariant
import talita.ui.ConfirmModel

// ---- What a message says, and where it goes ---------------------------------------------------------------------------------------

/**
 * Where the one message of an action is shown (design TALITA-11, part 1, 5): where the person touched, so that on a long page they do not have to look for it. [Top] is the
 * top of the block (the copy, «Togli la camera», a refusal that is about everything), [Room] the card of a room, [Person] the row of a person (the one who was just put in a
 * room stays in the list of those who are not, with the message, until the next load).
 */
sealed interface Spot {
    data object Top : Spot

    data class Room(val id: Long) : Spot

    data class Person(val key: String) : Spot

    /** The choice of «Aggiungi un maestro» of a room: the error of a choice that was not made goes under its select. */
    data class TeacherChoice(val roomId: Long) : Spot
}

/**
 * What an action leaves in the answer: the message ([alert]) and where it goes ([spot]), and, for a choice that was not made, the error under the select of the row or of the
 * form ([error], in the same spot). A `409` and a `422` carry it as well as a `200`.
 */
data class Feedback(val spot: Spot, val alert: Alert?, val error: String? = null) {
    companion object {
        val NONE = Feedback(Spot.Top, null)
    }
}

// ---- The view models ------------------------------------------------------------------------------------------------------------

/** An option of a `select`: what it sends and what it says. */
data class RoomOption(val value: String, val label: String)

/** A number of the summary: its label, the value (a word for zero), and a line under it. */
data class RoomStat(val label: String, val value: String, val unit: String?, val word: Boolean)

/** A room that has more people than beds, in the notice of the summary: the link goes to its card. */
data class OverfullLine(val anchor: String, val name: String, val text: String)

data class SummaryView(val stats: List<RoomStat>, val overfull: List<OverfullLine>, val printHref: String?, val freeLink: Boolean)

/**
 * A person in a room: the name, the label «In forse» for a child, the line under it, and what «Cambia» opens, the choice of another room (read-only: none). [alert] and
 * [error] are in the form of the choice when it is the one touched.
 */
data class PersonView(
    val key: String,
    val name: String,
    val maybe: Boolean,
    val line: String,
    val options: List<RoomOption>,
    val error: String?,
)

/** A room, as the page draws it. [people] are the children and then the teachers. [teacherOptions] are in «Aggiungi un maestro» (none: the entry is not there). */
data class RoomView(
    val id: Long,
    val name: String,
    val count: String,
    val overfull: String?,
    val people: List<PersonView>,
    val emptyLine: String?,
    val teacherOptions: List<RoomOption>,
    val teacherError: String?,
    val alert: Alert?,
    val removeDialog: ConfirmModel?,
    val canAct: Boolean,
)

/**
 * A row of «Senza camera»: the name, the label, the line, and the choice of the room. A row that [alert] holds is the person who was just put in a room, or whose registration
 * was withdrawn: it has the message and no choice.
 */
data class FreeRow(
    val key: String,
    val name: String,
    val maybe: Boolean,
    val line: String,
    val options: List<RoomOption>,
    val error: String?,
    val alert: Alert?,
)

data class FreeView(
    val total: Int,
    val childrenTitle: String?,
    val children: List<FreeRow>,
    val custodiansTitle: String?,
    val custodians: List<FreeRow>,
    /** Every child has a room (and there are children): the line that says so. */
    val allDone: Boolean,
    /** No child to place: a draft or a gathering without registrations. */
    val noChildren: Boolean,
    val canAct: Boolean,
)

/** The offer of the empty state: copy the rooms of the gathering of before. */
data class CopyOffer(val title: String, val text: String, val label: String)

/** What the block shows: the list of the rooms, the empty state, or the rooms whose people were deleted at the end of the gathering. */
enum class BlockKind { LIST, EMPTY, DELETED }

/** The block `#camere-contenuto` that every action of the page answers with, whole: the summary, the rooms and who has none, always from the database of the moment. */
data class RoomsBlock(
    val gatheringId: Long,
    val kind: BlockKind,
    /** The notice that is there while a situation lasts (a draft, the end, the deletion), before the message of the action. */
    val standing: Alert?,
    /** The message of an action that goes at the top of the block. */
    val alert: Alert?,
    val summary: SummaryView?,
    val rooms: List<RoomView>,
    val free: FreeView?,
    val copy: CopyOffer?,
    val canAct: Boolean,
    val newHref: String,
    /** What a screen reader says after an action (the same words as its message), in the region `#esito` of the page. */
    val announcement: String? = null,
    /** What the empty state says under «Nessuna camera»: to write them, or, once the gathering is over, that none was written. */
    val emptyText: String = RoomsPresenter.EMPTY_TEXT,
)

/** The page «Camere»: the head, the introduction and the block. */
data class RoomsPage(val gatheringId: Long, val lead: String, val backHref: String, val intro: Boolean, val block: RoomsBlock)

/** The page of the form of a room, new or to change, with the fields as they were written and the error under each wrong one. */
data class RoomForm(
    val gatheringId: Long,
    val roomId: Long?,
    val title: String,
    val lead: String,
    val name: String,
    val beds: String,
    val nameError: String?,
    val bedsError: String?,
    val alert: Alert?,
    /** The help under the beds; in a change, with how many people sleep in the room now. */
    val hint: String,
    val action: String,
)

/**
 * Turns the board of a gathering into what the pages show (design TALITA-11): every sentence written here is the one of the design. The rules (what is over, who is free,
 * which room has more people than beds) come from [RoomBoard]: nothing is decided twice.
 */
@Component
class RoomsPresenter(private val rooms: Rooms, private val texts: RetentionTexts) {

    private fun href(gatheringId: Long, tail: String = ""): String = "/convivenza/$gatheringId/camere$tail"

    private fun roomAnchor(id: Long): String = "camera-$id"

    private fun lead(gathering: GatheringInfo): String = "${gathering.title} · ${GatheringFormat.range(gathering.start, gathering.end)}"

    private fun options(board: RoomBoard, except: Room? = null): List<RoomOption> =
        board.rooms.filter { it.id != except?.id }.map { RoomOption(it.id.toString(), RoomFormat.option(it.name, board.people(it), it.beds)) }

    private fun childRow(board: RoomBoard, registration: RegistrationData, feedback: Feedback, selectable: Boolean, alert: Alert? = null): FreeRow {
        val key = PersonRef.Child(registration.id).key
        return FreeRow(
            key = key,
            name = registration.name,
            maybe = registration.status == Status.MAYBE,
            line = RoomFormat.childLine(registration, board.gathering.start),
            options = if (selectable && alert == null) options(board) else emptyList(),
            error = if (feedback.spot == Spot.Person(key)) feedback.error else null,
            alert = alert,
        )
    }

    private fun teacherRow(board: RoomBoard, teacher: TeacherRow, feedback: Feedback, selectable: Boolean, alert: Alert? = null): FreeRow {
        val key = PersonRef.Teacher(teacher.accountId).key
        return FreeRow(
            key = key,
            name = teacher.name.full,
            maybe = false,
            line = "Maestro",
            options = if (selectable && alert == null) options(board) else emptyList(),
            error = if (feedback.spot == Spot.Person(key)) feedback.error else null,
            alert = alert,
        )
    }

    private fun summary(board: RoomBoard, hasFree: Boolean, deleted: Boolean): SummaryView {
        val people = board.peopleInRooms
        val free = board.freeChildren.size
        val stats = buildList {
            add(RoomStat("Camere", if (board.roomCount == 0) "Nessuna" else board.roomCount.toString(), null, board.roomCount == 0))
            add(RoomStat("Posti letto", board.beds.toString(), null, false))
            if (!deleted) {
                add(RoomStat("Persone in camera", if (people == 0) "Nessuna" else people.toString(), "bambini e maestri", people == 0))
                add(RoomStat("Bambini senza camera", if (free == 0) "Nessuno" else free.toString(), null, free == 0))
            }
        }
        val overfull = board.rooms.filter { board.overfull(it) }.map { OverfullLine("#" + roomAnchor(it.id), it.name, RoomFormat.overfull(board.people(it), it.beds)) }
        return SummaryView(stats, overfull, if (board.roomCount > 0 && !deleted) href(board.gathering.id, "/stampa") else null, hasFree && !deleted)
    }

    private fun roomView(board: RoomBoard, room: Room, feedback: Feedback, canAct: Boolean, deleted: Boolean): RoomView {
        val gathering = board.gathering
        val children = board.childrenIn(room)
        val teachers = board.teachersIn(room)
        val key = { person: PersonRef -> person.key }
        val people = children.map {
            val ref = key(PersonRef.Child(it.id))
            PersonView(ref, it.name, it.status == Status.MAYBE, RoomFormat.childLine(it, gathering.start), if (canAct) options(board, room) else emptyList(), if (feedback.spot == Spot.Person(ref)) feedback.error else null)
        } + teachers.map {
            val ref = key(PersonRef.Teacher(it.accountId))
            PersonView(ref, it.name.full, false, "Maestro", if (canAct) options(board, room) else emptyList(), if (feedback.spot == Spot.Person(ref)) feedback.error else null)
        }
        val count = board.people(room)
        val dialog = if (count > 0 && canAct) removalDialog(gathering.id, room, count) else null
        return RoomView(
            id = room.id,
            name = room.name,
            count = if (deleted) RoomFormat.beds(room.beds) else RoomFormat.count(count, room.beds),
            overfull = if (board.overfull(room)) RoomFormat.overfull(count, room.beds) else null,
            people = people,
            emptyLine = if (count == 0 && !deleted) "Nessuno ci dorme, per ora." else null,
            teacherOptions = if (canAct) board.teachersWithoutRoom.map { RoomOption("m" + it.accountId, it.name.full) } else emptyList(),
            teacherError = if (feedback.spot == Spot.TeacherChoice(room.id)) feedback.error else null,
            alert = if (feedback.spot == Spot.Room(room.id)) feedback.alert else null,
            removeDialog = dialog,
            canAct = canAct,
        )
    }

    /**
     * The dialog that «Togli» opens for a room with people (a room that is empty has none: the request goes at once). The same words are the page without JavaScript, which an empty room
     * can also have: another teacher may empty it between the page of the rooms and the page of the confirmation.
     */
    fun removalDialog(gatheringId: Long, room: Room, people: Int): ConfirmModel = ConfirmModel(
        id = "togli-${room.id}",
        title = "Togliere ${room.name}?",
        text = "${room.name} sparisce dall’elenco. " + when (people) {
            // Nobody: another teacher emptied the room before this page (without JavaScript) was opened. The zero is in words.
            0 -> "Nessuno ci dorme."
            1 -> "La persona che ci dorme torna fra quelle senza camera."
            else -> "Le $people persone che ci dormono tornano fra quelle senza camera."
        },
        confirmLabel = "Togli la camera",
        busyLabel = "Tolgo…",
        action = href(gatheringId, "/${room.id}/togli"),
        field = "camera",
        value = room.id.toString(),
        target = "#camere-contenuto",
        cancelHref = href(gatheringId),
        danger = false,
    )

    private fun standing(board: RoomBoard, deleted: Boolean): Alert? = when {
        deleted -> {
            val gone = texts.roomsDeleted(board.gathering.end)
            Alert(AlertVariant.INFO, gone.second, lead = gone.main)
        }
        board.over -> Alert(AlertVariant.INFO, FINISHED)
        board.draft -> Alert(AlertVariant.INFO, DRAFT)
        else -> null
    }

    /** The block of the page for [board]: [feedback] is what the last action says, and where. */
    fun block(board: RoomBoard, feedback: Feedback = Feedback.NONE, flash: Alert? = null): RoomsBlock {
        val gathering = board.gathering
        // After the end: who slept where was deleted (nobody is in any room) or is about to be. The rooms stay, without names, with their beds. With no rooms there was
        // nothing to delete: the gathering is over, and that is all the page says (criterion 13).
        val deleted = board.peopleDeleted
        val canAct = !board.over
        val previous = if (board.rooms.isEmpty() && canAct) rooms.previous(gathering.id) else null
        val top = (if (feedback.spot == Spot.Top) feedback.alert else null) ?: flash
        // The refusal of a request that the end of the gathering makes impossible says what the standing notice says: once, and the one that takes the focus.
        val standing = standing(board, deleted)?.takeUnless { it.text == top?.text }
        val newHref = href(gathering.id, "/nuova")
        val announcement = feedback.alert?.text
        if (board.rooms.isEmpty()) {
            val copy = previous?.let {
                CopyOffer(
                    it.title, "Nella «${it.title}» c’erano ${if (it.rooms == 1) "1 camera" else "${it.rooms} camere"} e ${RoomFormat.beds(it.beds)}. Le copiamo senza le persone: poi le sistemi come vuoi.",
                    "Copia le camere",
                )
            }
            return RoomsBlock(gathering.id, BlockKind.EMPTY, standing, top, null, emptyList(), null, copy, canAct, newHref, announcement, if (canAct) EMPTY_TEXT else EMPTY_OVER_TEXT)
        }
        val rooms = board.rooms.map { roomView(board, it, feedback, canAct, deleted) }
        val free = if (deleted) null else free(board, feedback, canAct)
        return RoomsBlock(
            gathering.id, if (deleted) BlockKind.DELETED else BlockKind.LIST, standing, top, summary(board, free != null && free.total > 0, deleted), rooms, free, null, canAct, newHref,
            announcement,
        )
    }

    private fun free(board: RoomBoard, feedback: Feedback, canAct: Boolean): FreeView {
        val selectable = canAct
        val freeChildren = board.freeChildren
        val children = freeChildren.map { childRow(board, it, feedback, selectable) }.toMutableList()
        val custodians = board.freeCustodians.map { teacherRow(board, it, feedback, selectable) }.toMutableList()
        // The person that the touch was about stays in the list, with the message and no choice: a child just put in a room (the row goes at the next load) or whose
        // registration was withdrawn meanwhile.
        val spot = feedback.spot
        if (spot is Spot.Person && feedback.alert != null) {
            val ref = PersonRef.parse(spot.key)
            if (ref is PersonRef.Child && children.none { it.key == spot.key }) {
                board.registrations.firstOrNull { it.id == ref.registrationId }?.let { registration ->
                    val row = childRow(board, registration, feedback, selectable, feedback.alert)
                    val at = freeChildren.indexOfFirst { compareNames(registration, it) < 0 }.let { if (it < 0) children.size else it }
                    children.add(at, row)
                }
            } else if (ref is PersonRef.Teacher && custodians.none { it.key == spot.key }) {
                board.teachers.firstOrNull { it.accountId == ref.accountId }?.let { teacher -> custodians.add(0, teacherRow(board, teacher, feedback, selectable, feedback.alert)) }
            }
        }
        val childrenCount = freeChildren.size
        val total = childrenCount + board.freeCustodians.size
        val noChildren = board.registrations.none { it.status != Status.WITHDRAWN && RoomBoard.comesAtNight(it) }
        return FreeView(
            total = total,
            childrenTitle = if (children.isEmpty()) null else "Bambini · $childrenCount",
            children = children,
            custodiansTitle = if (custodians.isEmpty()) null else "Maestri affidatari · ${board.freeCustodians.size}",
            custodians = custodians,
            allDone = !noChildren && childrenCount == 0,
            noChildren = noChildren,
            canAct = canAct,
        )
    }

    private fun compareNames(first: RegistrationData, second: RegistrationData): Int =
        compareValuesBy(first, second, { it.lastName.lowercase() }, { it.firstName.lowercase() }, { it.id })

    fun page(board: RoomBoard, feedback: Feedback = Feedback.NONE, flash: Alert? = null): RoomsPage {
        val gathering = board.gathering
        return RoomsPage(gathering.id, lead(gathering), "/convivenza/${gathering.id}", intro = !board.over, block = block(board, feedback, flash))
    }

    // ---- The form of a room -------------------------------------------------------------------------------------------------------

    fun newForm(gathering: GatheringInfo, name: String = "", beds: String = "", nameError: String? = null, bedsError: String? = null, alert: Alert? = null): RoomForm =
        RoomForm(gathering.id, null, "Nuova camera", lead(gathering), name, beds, nameError, bedsError, alert, BEDS_HINT, href(gathering.id, "/nuova"))

    fun changeForm(gathering: GatheringInfo, room: Room, people: Int, name: String? = null, beds: String? = null, nameError: String? = null, bedsError: String? = null, alert: Alert? = null): RoomForm =
        RoomForm(
            gathering.id, room.id, "Modifica la camera", lead(gathering), name ?: room.name, beds ?: room.beds.toString(), nameError, bedsError, alert,
            listOfNotNull(BEDS_HINT, nowSleeping(people)).joinToString(" "), href(gathering.id, "/${room.id}/modifica"),
        )

    /** The help under the beds of a room that has people: «Ora ci dormono 5 persone.» */
    fun nowSleeping(people: Int): String? = when (people) {
        0 -> null
        1 -> "Ora ci dorme 1 persona."
        else -> "Ora ci dormono $people persone."
    }

    companion object {
        const val BEDS_HINT = "Quanti letti ci sono nella camera."
        const val FINISHED = "La convivenza è finita: le camere non si cambiano più."
        const val EMPTY_TEXT = "Scrivi le camere della casa, poi metti ogni bambino e ogni maestro nella sua. Le camere le vedono solo i maestri."

        /** The empty state of a gathering that is over: it does not invite to write rooms (nobody can any more) and does not offer a copy (the page has no action). */
        const val EMPTY_OVER_TEXT = "In questa convivenza non sono state scritte camere."
        const val DRAFT = "La convivenza è ancora in bozza: le camere si scrivono già, i bambini compaiono quando i genitori li iscrivono."
    }
}
