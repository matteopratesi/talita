package talita.api

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import talita.venue.Venues

/** A venue in the list of the APIs (TALITA-21, criterion 22): its name and place (public text), how many gatherings use it (never which), and how many rooms, beds and forms it has. No person. */
data class VenueListItem(val id: Long, val name: String, val place: String, val gatherings: Int, val rooms: Int, val beds: Int, val forms: Int)

/** The venues for the APIs of configuration (TALITA-21, `docs/06-api.md`): like [GatheringApi], it goes only through the services that the forms use. */
@RestController
class VenueApi(private val venues: Venues) {

    @GetMapping("/api/strutture", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun list(): List<VenueListItem> = venues.list().map { VenueListItem(it.venue.id, it.venue.name, it.venue.place, it.gatherings, it.rooms, it.beds, it.forms) }
}
