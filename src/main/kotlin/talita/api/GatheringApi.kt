package talita.api

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import talita.gathering.CreateEdit
import talita.gathering.DatesEdit
import talita.gathering.DatesResult
import talita.gathering.FeesEdit
import talita.gathering.Gathering
import talita.gathering.GatheringEdits
import talita.gathering.GatheringFormat
import talita.gathering.GatheringReader
import talita.gathering.GatheringState
import talita.gathering.Gatherings
import talita.gathering.GatheringChecks
import talita.gathering.InfoEdit
import talita.gathering.VenueChangeTexts
import talita.gathering.GatheringVenues

/** A gathering in the list of the APIs (TALITA-21, criterion 15): what a list says, with the dates as `2027-09-17`. No custodian, no public code and no count of registrations. */
data class GatheringListItem(
    val id: Long,
    val title: String,
    val startDate: String,
    val endDate: String,
    val state: String,
    val over: Boolean,
    val venueId: Long?,
)

/** The venue of a gathering as the APIs say it: its number, its name and its place (public text). */
data class GatheringVenueItem(val id: Long, val name: String, val place: String)

/**
 * A gathering as the APIs say it (TALITA-21, criterion 16) and nothing more: title, dates, state, whether it is over, the venue (or `null`), the three fees as the form writes them (`"12,50"`, `"0"` or `null`), the text of
 * the information and «cosa portare», and what a draft still lacks before the registrations can open (the sentences of «Prima di aprire le iscrizioni»). A test fixes the list of the fields: no custodian, no public
 * code, no count of registrations and no menu. [notice] is the sentence that the form would have added (criterion 21).
 */
data class GatheringDetail(
    val id: Long,
    val title: String,
    val startDate: String,
    val endDate: String,
    val state: String,
    val over: Boolean,
    val venue: GatheringVenueItem?,
    val fullFee: String?,
    val dailyFee: String?,
    val lunchOnlyFee: String?,
    val infoText: String?,
    val whatToBring: String?,
    val missing: List<String>,
    val notice: String? = null,
)

/** The body of `POST /api/convivenza`: a new draft needs the title and the two dates. */
data class NewGatheringRequest(val title: String? = null, val startDate: String? = null, val endDate: String? = null)

/** The body of `PUT /api/convivenza/{id}/date`: title, dates and venue (the number of the venue, or `null` for a draft that has none). */
data class DatesRequest(val title: String? = null, val startDate: String? = null, val endDate: String? = null, val venue: Long? = null)

/** The body of `PUT /api/convivenza/{id}/quote`: the three fees as the form writes them (`"12,50"`, `"0"`, or empty or `null` for not provided). */
data class FeesRequest(val fullFee: String? = null, val dailyFee: String? = null, val lunchOnlyFee: String? = null)

/** The body of `PUT /api/convivenza/{id}/informazioni`: the information and «cosa portare» (empty or `null` for none). */
data class InfoRequest(val infoText: String? = null, val whatToBring: String? = null)

/**
 * The gathering for the APIs of configuration (TALITA-21, criteria 15-21, `docs/06-api.md`). The reads go through the services of the gathering and the writes through [GatheringEdits], the same place that the pages of
 * the teachers call: the same checks, the same sentences, the same locks. The state of the gathering is never changed from here (opening and closing the registrations are in the app), and the custodians are not
 * here (they are names of teachers).
 */
@RestController
class GatheringApi(
    private val gatherings: GatheringReader,
    private val services: Gatherings,
    private val venues: GatheringVenues,
    private val edits: GatheringEdits,
) {

    private fun notFound(): Nothing = throw ApiProblem(404, "Non c’è questa convivenza.")

    private fun detail(gathering: Gathering, notice: String? = null): GatheringDetail {
        val today = services.today()
        val venue = gathering.venueId?.let(venues::find)
        val missing = if (gathering.state == GatheringState.DRAFT) services.missing(gathering, services.custodians(gathering.id).size, today).map { it.text } else emptyList()
        return GatheringDetail(
            gathering.id, gathering.title, gathering.startDate.toString(), gathering.endDate.toString(), state(gathering.state), gathering.isOver(today),
            venue?.let { GatheringVenueItem(it.id, it.name, it.place) },
            gathering.fees.fullCents?.let(GatheringFormat::amountField), gathering.fees.dailyCents?.let(GatheringFormat::amountField), gathering.fees.lunchOnlyCents?.let(GatheringFormat::amountField),
            gathering.infoText, gathering.whatToBring, missing, notice,
        )
    }

    private fun fresh(id: Long, notice: String? = null): GatheringDetail = detail(services.find(id) ?: notFound(), notice)

    @GetMapping("/api/convivenza", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun list(): List<GatheringListItem> = gatherings.summaries().map {
        GatheringListItem(it.id, it.title, it.start.toString(), it.end.toString(), state(it.state), it.over, it.venueId)
    }

    @GetMapping("/api/convivenza/{id:[0-9]{1,18}}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun get(@PathVariable id: Long): GatheringDetail = fresh(id)

    @ApiRequest(NewGatheringRequest::class)
    @PostMapping("/api/convivenza", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun create(@RequestBody raw: ByteArray): ResponseEntity<GatheringDetail> {
        val body = ApiBody.read(raw, NewGatheringRequest::class, required = emptySet())
        return when (val result = edits.create(body.title.orEmpty(), body.startDate.orEmpty(), body.endDate.orEmpty())) {
            is CreateEdit.Created -> ResponseEntity.status(HttpStatus.CREATED).body(fresh(result.id))
            // A draft or an open gathering is there already: the sentence of «Nuova convivenza», and nothing new.
            is CreateEdit.AlreadyCurrent -> throw ApiProblem(409, GatheringEdits.blocked(result.title, result.state))
            is CreateEdit.Invalid -> throw ApiProblem(
                422, ApiTexts.INVALID,
                fields("title" to result.title.error, "startDate" to result.dates.startError, "endDate" to result.dates.endError),
            )
        }
    }

    @ApiRequest(DatesRequest::class)
    @PutMapping("/api/convivenza/{id:[0-9]{1,18}}/date", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun saveDates(@PathVariable id: Long, @RequestBody raw: ByteArray): GatheringDetail {
        val body = ApiBody.read(raw, DatesRequest::class, required = setOf("title", "startDate", "endDate", "venue"))
        services.find(id) ?: notFound()
        // The APIs never confirm a change of venue that takes people out of their rooms: no number of people, so the answer is a 409 that says how many.
        val edit = edits.saveDates(id, body.title.orEmpty(), body.startDate, body.endDate, body.venue?.toString().orEmpty(), "")
        return when (edit) {
            DatesEdit.NotFound -> notFound()
            is DatesEdit.Closed -> throw ApiProblem(409, GatheringChecks.DATES_CLOSED)
            is DatesEdit.Invalid -> throw ApiProblem(
                422, ApiTexts.INVALID, fields("title" to edit.title.error, "startDate" to edit.dates?.startError, "endDate" to edit.dates?.endError, "venue" to edit.venueError),
            )
            is DatesEdit.Done -> when (val result = edit.result) {
                DatesResult.Saved, is DatesResult.VenueChanged -> fresh(id)
                DatesResult.NotFound -> notFound()
                DatesResult.VenueNotFound -> throw ApiProblem(422, ApiTexts.INVALID, fields("venue" to GatheringChecks.VENUE_MISSING))
                is DatesResult.PeopleInRooms -> throw ApiProblem(409, VenueChangeTexts.apiLeave(result.people, result.from))
                is DatesResult.DaysInUse -> {
                    val (startError, endError) = GatheringEdits.daysInUse(result, checkNotNull(edit.dates?.start))
                    throw ApiProblem(422, ApiTexts.INVALID, fields("startDate" to startError, "endDate" to endError))
                }
            }
        }
    }

    @ApiRequest(FeesRequest::class)
    @PutMapping("/api/convivenza/{id:[0-9]{1,18}}/quote", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun saveFees(@PathVariable id: Long, @RequestBody raw: ByteArray): GatheringDetail {
        val body = ApiBody.read(raw, FeesRequest::class, required = setOf("fullFee", "dailyFee", "lunchOnlyFee"))
        services.find(id) ?: notFound()
        return when (val edit = edits.saveFees(id, body.fullFee.orEmpty(), body.dailyFee.orEmpty(), body.lunchOnlyFee.orEmpty())) {
            FeesEdit.NotFound -> notFound()
            // The sentence of the form about the registrations that already have a fee that is being recalculated.
            FeesEdit.Saved -> fresh(id, edits.feesNotice(id))
            is FeesEdit.Invalid -> throw ApiProblem(422, ApiTexts.INVALID, fields("fullFee" to edit.full.error, "dailyFee" to edit.daily.error, "lunchOnlyFee" to edit.lunchOnly.error))
            is FeesEdit.InUse -> throw ApiProblem(422, ApiTexts.INVALID, fields("dailyFee" to edit.dailyError, "lunchOnlyFee" to edit.lunchOnlyError))
        }
    }

    @ApiRequest(InfoRequest::class)
    @PutMapping("/api/convivenza/{id:[0-9]{1,18}}/informazioni", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun saveInfo(@PathVariable id: Long, @RequestBody raw: ByteArray): GatheringDetail {
        val body = ApiBody.read(raw, InfoRequest::class, required = setOf("infoText", "whatToBring"))
        services.find(id) ?: notFound()
        return when (val edit = edits.saveInfo(id, body.infoText.orEmpty(), body.whatToBring.orEmpty())) {
            InfoEdit.NotFound -> notFound()
            InfoEdit.Saved -> fresh(id)
            is InfoEdit.Invalid -> throw ApiProblem(422, ApiTexts.INVALID, fields("infoText" to edit.info.error, "whatToBring" to edit.whatToBring.error))
        }
    }

    /** The fields that have an error, in the order given. */
    private fun fields(vararg pairs: Pair<String, String?>): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        for ((name, error) in pairs) if (error != null) fields[name] = error
        return fields
    }

    companion object {
        fun state(state: GatheringState): String = when (state) {
            GatheringState.DRAFT -> "draft"
            GatheringState.OPEN -> "open"
            GatheringState.CLOSED -> "closed"
        }
    }
}
