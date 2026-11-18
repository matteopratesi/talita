package talita.api

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import talita.checkin.ArrivalForms
import talita.checkin.VenueFormRef
import talita.room.Room
import talita.room.Rooms
import talita.venue.Venue
import talita.venue.VenueEdit
import talita.venue.VenueEdits
import talita.venue.VenueRemoval
import talita.venue.VenueTexts
import talita.venue.Venues

/** A venue in the list of the APIs (TALITA-21, criterion 22): its name and place (public text), how many gatherings use it (never which), and how many rooms, beds and forms it has. No person. */
data class VenueListItem(val id: Long, val name: String, val place: String, val gatherings: Int, val rooms: Int, val beds: Int, val forms: Int)

/** A room as the APIs say it: the house, never who sleeps in it. */
data class RoomItem(val id: Long, val name: String, val beds: Int)

/** A form of a venue as the APIs say it: a name and four choices, never a value. */
data class FormItem(val id: Long, val name: String, val withAge: Boolean, val withDays: Boolean, val withTeacherDocument: Boolean, val withTeacherTaxCode: Boolean)

/** A venue with its rooms and its forms (criterion 22). [notice] is the sentence that the form would have added (criterion 23): a venue that a gathering uses has changed what the parents read. */
data class VenueDetail(
    val id: Long,
    val name: String,
    val place: String,
    val gatherings: Int,
    val rooms: List<RoomItem>,
    val forms: List<FormItem>,
    val notice: String? = null,
)

/** The body of `POST` and `PUT` of a venue: its name and its place. */
data class VenueRequest(val name: String? = null, val place: String? = null)

/** The answer of a `DELETE`: what the app would have said. */
data class RemovedAnswer(val message: String)

/**
 * The venues for the APIs of configuration (TALITA-21, criteria 22 and 23, `docs/06-api.md`). **The writes go through [VenueEdits]**, the same place that the pages of the teachers call: the same checks, the same sentences,
 * the same locks. A request that fails says it as a JSON error ([ApiProblem]), with the sentences of the form for each field.
 */
@RestController
class VenueApi(
    private val venues: Venues,
    private val rooms: Rooms,
    private val forms: ArrivalForms,
    private val edits: VenueEdits,
) {

    private fun notFound(): Nothing = throw ApiProblem(404, "Non c’è questa struttura.")

    /** The venue with what hangs from it. [notice] only for the answer of a write. */
    private fun detail(venue: Venue, notice: String? = null, used: Int = venues.usage(venue.id)): VenueDetail = VenueDetail(
        venue.id, venue.name, venue.place, used, rooms.rooms(venue.id).map(::room), forms.ofVenue(venue.id).map(::form), notice,
    )

    private fun room(room: Room) = RoomItem(room.id, room.name, room.beds)

    private fun form(form: VenueFormRef) = FormItem(form.id, form.name, form.age, form.days, form.teacherDocument, form.teacherTaxCode)

    @GetMapping("/api/strutture", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun list(): List<VenueListItem> = venues.list().map { VenueListItem(it.venue.id, it.venue.name, it.venue.place, it.gatherings, it.rooms, it.beds, it.forms) }

    @GetMapping("/api/strutture/{id:[0-9]{1,18}}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun get(@PathVariable id: Long): VenueDetail = detail(venues.byId(id) ?: notFound())

    @ApiRequest(VenueRequest::class)
    @PostMapping("/api/strutture", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun create(@RequestBody raw: ByteArray): ResponseEntity<VenueDetail> {
        val body = ApiBody.read(raw, VenueRequest::class, required = emptySet())
        return when (val result = edits.create(body.name, body.place)) {
            is VenueEdit.Saved -> ResponseEntity.status(HttpStatus.CREATED).body(detail(result.venue))
            is VenueEdit.Invalid -> throw invalid(result)
            VenueEdit.NotFound -> notFound()
        }
    }

    @ApiRequest(VenueRequest::class)
    @PutMapping("/api/strutture/{id:[0-9]{1,18}}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun change(@PathVariable id: Long, @RequestBody raw: ByteArray): VenueDetail {
        val body = ApiBody.read(raw, VenueRequest::class, required = setOf("name", "place"))
        venues.byId(id) ?: notFound()
        return when (val result = edits.update(id, body.name, body.place)) {
            // A venue that a gathering uses: what the parents read has changed, and the registrations already made keep the text of that time (the sentence of the form).
            is VenueEdit.Saved -> detail(result.venue, if (result.gatherings > 0) VenueTexts.SAVED_USED else null, result.gatherings)
            is VenueEdit.Invalid -> throw invalid(result)
            VenueEdit.NotFound -> notFound()
        }
    }

    @DeleteMapping("/api/strutture/{id:[0-9]{1,18}}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun remove(@PathVariable id: Long): RemovedAnswer = when (val result = edits.remove(id)) {
        is VenueRemoval.Removed -> RemovedAnswer(VenueTexts.REMOVED)
        // A venue that a gathering uses is not taken away, and nothing changes.
        is VenueRemoval.InUse -> throw ApiProblem(409, VenueTexts.notRemoved(result.gatherings))
        VenueRemoval.NotFound -> notFound()
    }

    private fun invalid(result: VenueEdit.Invalid): ApiProblem {
        val fields = linkedMapOf<String, String>()
        result.name.error?.let { fields["name"] = it }
        result.place.error?.let { fields["place"] = it }
        return ApiProblem(422, ApiTexts.INVALID, fields)
    }
}
