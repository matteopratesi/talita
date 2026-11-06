package talita.venue

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import talita.room.Room
import talita.room.RoomFormat
import talita.room.RoomPeople
import talita.room.Rooms
import talita.room.RoomsPresenter
import talita.ui.Alert
import talita.ui.ConfirmModel

// ---- The words ---------------------------------------------------------------------------------------------------------------------

/** The sentences of the rooms in the page of the venue (design TALITA-20, guide 6 «Strutture»): written once, here, and read by the pages and by the tests. */
object VenueRoomTexts {
    const val NEW_TITLE = "Nuova camera"
    const val CHANGE_TITLE = "Modifica la camera"
    const val SAVED = "Camera salvata."
    const val REMOVED = "Camera tolta."
    const val ALREADY_REMOVED = "La camera era già stata tolta."
    const val EMPTY_TITLE = "Nessuna camera."
    const val EMPTY_TEXT = "Scrivi le camere della casa, con i posti letto: le trovi in ogni convivenza che usa questa struttura."
    const val NAME_HELP = "Scrivi il nome della camera nella casa, per esempio «Camera 3» o «Stanza azzurra». Non scrivere chi ci dorme."

    /** `1 camera · 4 posti` or `3 camere · 10 posti`, then what the rooms are for: «Le trovano tutte le convivenze che usano questa struttura.» */
    fun summary(rooms: Int, beds: Int): String =
        (if (rooms == 1) "1 camera" else "$rooms camere") + " · " + RoomFormat.beds(beds) + ". Le trovano tutte le convivenze che usano questa struttura."

    /** What the dialog «Togliere {camera}?» says: the room goes from the venue, and the people who sleep in it are without a room (the number is in all the gatherings that are not over). */
    fun removalText(name: String, people: Int): String = "$name sparisce dalla struttura. " + when (people) {
        0 -> "Nessuno ci dorme."
        1 -> "La persona che ci dorme torna fra quelle senza camera."
        else -> "Le $people persone che ci dormono tornano fra quelle senza camera."
    }

    /** The second paragraph of the dialog, when the people are in more than one gathering: «Succede in 2 convivenze.» Null for one gathering or none. */
    fun removalMore(gatherings: Int): String? = if (gatherings >= 2) "Succede in $gatherings convivenze." else null

    /** What goes with a venue that is taken away: the rooms (and, with the next step of the ticket, the forms). Null when it has nothing. */
    fun hangsFrom(rooms: Int): String? = when (rooms) {
        0 -> null
        1 -> "Si cancella anche la sua camera."
        else -> "Si cancellano anche le sue $rooms camere."
    }

    /** `3 camere, 10 posti`, or `Nessuna camera`: the line of the list of the venues. */
    fun inTheList(rooms: Int, beds: Int): String = if (rooms == 0) "Nessuna camera" else (if (rooms == 1) "1 camera" else "$rooms camere") + ", " + RoomFormat.beds(beds)
}

// ---- The view models ---------------------------------------------------------------------------------------------------------------

/** A room in the list of the venue: its name and beds, and what «Togli» does: a dialog when somebody sleeps in it, the request at once when nobody does. */
data class VenueRoomRow(val id: Long, val name: String, val beds: String, val removeHref: String, val removeDialog: ConfirmModel?)

/**
 * The block `#camere-struttura` of the page of a venue, which every action on a room answers with, whole: the sentence on what the rooms are for, the rooms, the notice of the action at the top, and the way to
 * write a new one. [alert] is the notice of the last action (`role="status"`, the focus goes to it); [announcement] is what a screen reader says (the region `#esito` of the page).
 */
data class VenueRoomsBlock(
    val venueId: Long,
    val summary: String?,
    val rows: List<VenueRoomRow>,
    val alert: Alert?,
    val announcement: String?,
    val newHref: String,
)

/** The page of the form of a room, new or to change: the title, the venue under it, the fields as they were written and the error under each wrong one. */
data class VenueRoomForm(
    val venueId: Long,
    val roomId: Long?,
    val title: String,
    val lead: String,
    val name: String,
    val beds: String,
    val nameError: String?,
    val bedsError: String?,
    val alert: Alert?,
    val hint: String,
    val action: String,
)

/** The notice of an action on a room that waits for the next page (a request without htmx): left in the session, read once, in a key of its own, because it is shown at the top of the block and not of the page. */
object RoomFlash {

    private const val KEY = "talita.flash.camere"

    fun put(request: HttpServletRequest, alert: Alert) {
        request.getSession(true).setAttribute(KEY, alert)
    }

    fun take(request: HttpServletRequest): Alert? {
        val session = request.getSession(false) ?: return null
        val alert = session.getAttribute(KEY) as? Alert
        if (alert != null) session.removeAttribute(KEY)
        return alert
    }
}

// ---- The presenter -----------------------------------------------------------------------------------------------------------------

/** Turns the rooms of a venue into what its page shows: every sentence written here is the one of the design. The people in each room come from [Rooms]. */
@Component
class VenueRoomsPresenter(private val rooms: Rooms) {

    private fun href(venueId: Long, tail: String = ""): String = "/strutture/$venueId$tail"

    /** The dialog «Togliere {camera}?», or the page that stands in for it without JavaScript (the same words): [people] sleep in it, in [gatherings] gatherings that are not over. */
    fun removalDialog(venueId: Long, room: Room, people: RoomPeople): ConfirmModel = ConfirmModel(
        id = "togli-camera-${room.id}",
        title = "Togliere ${room.name}?",
        text = VenueRoomTexts.removalText(room.name, people.people),
        confirmLabel = "Togli la camera",
        busyLabel = "Tolgo…",
        action = href(venueId, "/camere/${room.id}/togli"),
        field = "camera",
        value = room.id.toString(),
        target = "#camere-struttura",
        cancelHref = href(venueId, "#camere"),
        danger = false,
        more = listOfNotNull(VenueRoomTexts.removalMore(people.gatherings)),
    )

    /** The block of the rooms of the venue [venueId], from the database of the moment. [alert] is the notice of the action that this answers. */
    fun block(venueId: Long, alert: Alert? = null): VenueRoomsBlock {
        val all = rooms.rooms(venueId)
        val people = rooms.people(venueId)
        val rows = all.map { room ->
            val here = people[room.id] ?: RoomPeople(0, 0)
            VenueRoomRow(room.id, room.name, RoomFormat.beds(room.beds), href(venueId, "/camere/${room.id}/togli"), if (here.people > 0) removalDialog(venueId, room, here) else null)
        }
        val summary = if (all.isEmpty()) null else VenueRoomTexts.summary(all.size, all.sumOf { it.beds })
        return VenueRoomsBlock(venueId, summary, rows, alert, alert?.text, href(venueId, "/camere/nuova"))
    }

    fun newForm(venueId: Long, venueName: String, name: String = "", beds: String = "", nameError: String? = null, bedsError: String? = null, alert: Alert? = null): VenueRoomForm =
        VenueRoomForm(venueId, null, VenueRoomTexts.NEW_TITLE, venueName, name, beds, nameError, bedsError, alert, RoomsPresenter.BEDS_HINT, href(venueId, "/camere/nuova"))

    fun changeForm(
        venueId: Long,
        venueName: String,
        room: Room,
        people: Int,
        name: String? = null,
        beds: String? = null,
        nameError: String? = null,
        bedsError: String? = null,
        alert: Alert? = null,
    ): VenueRoomForm = VenueRoomForm(
        venueId, room.id, VenueRoomTexts.CHANGE_TITLE, venueName, name ?: room.name, beds ?: room.beds.toString(), nameError, bedsError, alert,
        listOfNotNull(RoomsPresenter.BEDS_HINT, RoomsPresenter.nowSleeping(people)).joinToString(" "), href(venueId, "/camere/${room.id}/modifica"),
    )
}
