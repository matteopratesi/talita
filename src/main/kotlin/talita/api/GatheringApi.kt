package talita.api

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import talita.gathering.GatheringReader
import talita.gathering.GatheringState

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

/**
 * The gathering for the APIs of configuration (TALITA-21, `docs/06-api.md`). **This class does not touch the database**: it goes through the services of the gathering, which are the same ones that the forms use, with
 * their checks, their locks and their transactions (a test fails if a class of this package, but the two that own their table, reaches the database by itself).
 */
@RestController
class GatheringApi(private val gatherings: GatheringReader) {

    @GetMapping("/api/convivenza", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun list(): List<GatheringListItem> = gatherings.summaries().map {
        GatheringListItem(it.id, it.title, it.start.toString(), it.end.toString(), state(it.state), it.over, it.venueId)
    }

    companion object {
        fun state(state: GatheringState): String = when (state) {
            GatheringState.DRAFT -> "draft"
            GatheringState.OPEN -> "open"
            GatheringState.CLOSED -> "closed"
        }
    }
}
