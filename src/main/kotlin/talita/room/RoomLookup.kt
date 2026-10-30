package talita.room

import org.jooq.DSLContext
import org.springframework.stereotype.Component
import talita.gathering.GatheringInfo
import talita.jooq.tables.references.ROOM
import talita.jooq.tables.references.ROOM_CHILD
import talita.jooq.tables.references.ROOM_TEACHER
import talita.registration.RegistrationData
import talita.retention.RetentionTexts

/**
 * The room of a child, as the card of the child in Arrivi says it (criterion 15; design TALITA-11, part 2, 4): the name of the room with the way to the page «Camere», or
 * «Senza camera» with the way to choose one, or, after the end of the gathering, that who slept where was deleted; no card for who comes for lunch only and has no room. The card is for
 * the teachers, like the page of the rooms.
 *
 * @property name the room, null when the child is in none
 * @property href where the page «Camere» is, at the room or at the list of who has none
 * @property deleted the sentence that says that who slept where was deleted, after the end; the card shows it in the place of the room
 */
data class ChildRoomCard(val name: String?, val href: String, val deleted: String?)

/** Reads the room of one registration for the card of Arrivi. Read only, and it writes no name in the log. */
@Component
class RoomLookup(private val dsl: DSLContext, private val texts: RetentionTexts) {

    /**
     * The card of [registration] in [gathering]: null for a child that is not meant to have a room. That is who comes for lunch only and is in none (the same rule as the page «Camere»,
     * [RoomBoard.comesAtNight]: it does not list them, so «Senza camera» would lead to a list where they are not); one that was in a room before the registration became lunch only keeps it
     * and the card says it (criterion 12). A withdrawn registration has no card: the caller does not ask.
     */
    fun cardOf(gathering: GatheringInfo, registration: RegistrationData): ChildRoomCard? {
        val room = dsl.select(ROOM.ID, ROOM.NAME).from(ROOM_CHILD).join(ROOM).on(ROOM.ID.eq(ROOM_CHILD.ROOM_ID))
            .where(ROOM_CHILD.REGISTRATION_ID.eq(registration.id)).and(ROOM_CHILD.GATHERING_ID.eq(gathering.id)).fetchOne()
        val base = "/convivenza/${gathering.id}/camere"
        if (room != null) return ChildRoomCard(room[ROOM.NAME]!!, "$base#camera-${room[ROOM.ID]}", null)
        if (!RoomBoard.comesAtNight(registration)) return null
        // After the end, with rooms and nobody in any of them: what was there was deleted (the job does it the first run of the day after the last day). The same rule as
        // `RoomBoard.peopleDeleted`: with no rooms there was nothing to delete, and the child is «Senza camera» (criterion 13).
        if (gathering.over && dsl.fetchExists(ROOM, ROOM.GATHERING_ID.eq(gathering.id))) {
            val nobody = !dsl.fetchExists(ROOM_CHILD, ROOM_CHILD.GATHERING_ID.eq(gathering.id)) && !dsl.fetchExists(ROOM_TEACHER, ROOM_TEACHER.GATHERING_ID.eq(gathering.id))
            if (nobody) return ChildRoomCard(null, base, texts.roomsDeletedOn(gathering.end))
        }
        return ChildRoomCard(null, "$base#senza-camera", null)
    }
}
