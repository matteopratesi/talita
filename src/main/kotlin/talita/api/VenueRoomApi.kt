package talita.api

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import talita.checkin.ArrivalForms
import talita.checkin.VenueFormRef
import talita.room.RemoveResult
import talita.room.Rooms
import talita.venue.FormColumns
import talita.venue.FormEdit
import talita.venue.FormRemoval
import talita.venue.RoomEdit
import talita.venue.VenueFormEdits
import talita.venue.VenueFormTexts
import talita.venue.VenueRoomEdits
import talita.venue.VenueRoomTexts
import talita.venue.Venues

/** The body of `POST` and `PUT` of a room: its name and its beds (a whole number). */
data class RoomRequest(val name: String? = null, val beds: Int? = null)

/** The body of `POST` and `PUT` of a form of a venue: its name and the four choices (a choice that is not written is `false` in a `POST`; a `PUT` writes all of them). */
data class FormRequest(
    val name: String? = null,
    val withAge: Boolean = false,
    val withDays: Boolean = false,
    val withTeacherDocument: Boolean = false,
    val withTeacherTaxCode: Boolean = false,
)

/**
 * The rooms of a venue for the APIs of configuration (TALITA-21, criterion 24, `docs/06-api.md`): the writes go through [VenueRoomEdits], the same place that the pages of the teachers call, with the rules of
 * TALITA-11 and 20. A room is the house: its name never says who sleeps in it. **A room where somebody sleeps is not taken away by the APIs** (409): the confirmation is a dialog of the app.
 */
@RestController
class VenueRoomApi(private val venues: Venues, private val rooms: Rooms, private val edits: VenueRoomEdits) {

    private fun venueNotFound(): Nothing = throw ApiProblem(404, "Non c’è questa struttura.")

    private fun roomNotFound(): Nothing = throw ApiProblem(404, "Non c’è questa camera in questa struttura.")

    private fun invalid(result: RoomEdit.Invalid): ApiProblem {
        val fields = linkedMapOf<String, String>()
        result.name.error?.let { fields["name"] = it }
        result.beds.error?.let { fields["beds"] = it }
        return ApiProblem(422, ApiTexts.INVALID, fields)
    }

    @ApiRequest(RoomRequest::class)
    @PostMapping("/api/strutture/{id:[0-9]{1,18}}/camere", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun create(@PathVariable id: Long, @RequestBody raw: ByteArray): ResponseEntity<RoomItem> {
        val body = ApiBody.read(raw, RoomRequest::class, required = emptySet())
        venues.byId(id) ?: venueNotFound()
        return when (val result = edits.create(id, body.name, body.beds?.toString())) {
            is RoomEdit.Saved -> ResponseEntity.status(HttpStatus.CREATED).body(RoomItem(result.room.id, result.room.name, result.room.beds))
            is RoomEdit.Invalid -> throw invalid(result)
            RoomEdit.NotFound -> venueNotFound()
        }
    }

    @ApiRequest(RoomRequest::class)
    @PutMapping("/api/strutture/{id:[0-9]{1,18}}/camere/{camera:[1-9][0-9]{0,17}}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun change(@PathVariable id: Long, @PathVariable camera: Long, @RequestBody raw: ByteArray): RoomItem {
        val body = ApiBody.read(raw, RoomRequest::class, required = setOf("name", "beds"))
        venues.byId(id) ?: venueNotFound()
        rooms.find(id, camera) ?: roomNotFound()
        return when (val result = edits.update(id, camera, body.name, body.beds?.toString())) {
            is RoomEdit.Saved -> RoomItem(result.room.id, result.room.name, result.room.beds)
            is RoomEdit.Invalid -> throw invalid(result)
            RoomEdit.NotFound -> roomNotFound()
        }
    }

    @DeleteMapping("/api/strutture/{id:[0-9]{1,18}}/camere/{camera:[1-9][0-9]{0,17}}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun remove(@PathVariable id: Long, @PathVariable camera: Long): RemovedAnswer {
        venues.byId(id) ?: venueNotFound()
        return when (val result = edits.remove(id, camera, refusePeople = true)) {
            is RemoveResult.Removed -> RemovedAnswer(VenueRoomTexts.REMOVED)
            // Taken away by another teacher already: the same answer as the app, never an error.
            RemoveResult.AlreadyGone -> RemovedAnswer(VenueRoomTexts.ALREADY_REMOVED)
            is RemoveResult.HasPeople -> throw ApiProblem(409, VenueRoomTexts.hasPeople(result.people))
            RemoveResult.NotFound -> roomNotFound()
        }
    }
}

/** The forms of a venue for the APIs of configuration (TALITA-21, criterion 24): the writes go through [VenueFormEdits]. A form is a name and four choices, never a value. */
@RestController
class VenueFormApi(private val venues: Venues, private val forms: ArrivalForms, private val edits: VenueFormEdits) {

    private fun venueNotFound(): Nothing = throw ApiProblem(404, "Non c’è questa struttura.")

    private fun formNotFound(): Nothing = throw ApiProblem(404, "Non c’è questo modulo in questa struttura.")

    private fun item(form: VenueFormRef) = FormItem(form.id, form.name, form.age, form.days, form.teacherDocument, form.teacherTaxCode)

    /** The choices of a `POST` (the ones not written are `false`) or of a `PUT` (all written: [ApiBody] has refused it if one was missing). */
    private fun choices(body: FormRequest) = FormColumns(body.withAge, body.withDays, body.withTeacherDocument, body.withTeacherTaxCode)

    private fun invalid(result: FormEdit.Invalid) = ApiProblem(422, ApiTexts.INVALID, mapOf("name" to checkNotNull(result.name.error)))

    @ApiRequest(FormRequest::class)
    @PostMapping("/api/strutture/{id:[0-9]{1,18}}/moduli", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun create(@PathVariable id: Long, @RequestBody raw: ByteArray): ResponseEntity<FormItem> {
        val body = ApiBody.read(raw, FormRequest::class, required = emptySet())
        venues.byId(id) ?: venueNotFound()
        return when (val result = edits.create(id, body.name, choices(body))) {
            is FormEdit.Saved -> ResponseEntity.status(HttpStatus.CREATED).body(item(result.form))
            is FormEdit.Invalid -> throw invalid(result)
            FormEdit.NotFound -> venueNotFound()
        }
    }

    @ApiRequest(FormRequest::class)
    @PutMapping("/api/strutture/{id:[0-9]{1,18}}/moduli/{modulo:[1-9][0-9]{0,17}}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun change(@PathVariable id: Long, @PathVariable modulo: Long, @RequestBody raw: ByteArray): FormItem {
        val body = ApiBody.read(raw, FormRequest::class, required = setOf("name", "withAge", "withDays", "withTeacherDocument", "withTeacherTaxCode"))
        venues.byId(id) ?: venueNotFound()
        forms.find(id, modulo) ?: formNotFound()
        return when (val result = edits.update(id, modulo, body.name, choices(body))) {
            is FormEdit.Saved -> item(result.form)
            is FormEdit.Invalid -> throw invalid(result)
            FormEdit.NotFound -> formNotFound()
        }
    }

    @DeleteMapping("/api/strutture/{id:[0-9]{1,18}}/moduli/{modulo:[1-9][0-9]{0,17}}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun remove(@PathVariable id: Long, @PathVariable modulo: Long): RemovedAnswer {
        venues.byId(id) ?: venueNotFound()
        return when (edits.remove(id, modulo)) {
            is FormRemoval.Removed -> RemovedAnswer(VenueFormTexts.REMOVED)
            FormRemoval.AlreadyGone -> RemovedAnswer(VenueFormTexts.ALREADY_REMOVED)
            FormRemoval.NotFound -> formNotFound()
        }
    }
}
