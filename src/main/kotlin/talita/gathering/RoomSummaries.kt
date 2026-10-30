package talita.gathering

/**
 * What the rooms of a gathering say in the card «Camere» of its detail (TALITA-11): how many rooms, how many beds and, while the gathering is going on, how many children
 * are not in a room yet. Counts only, never a name. [unassigned] is null when it makes no sense: a draft has no children, and after the end who slept where is deleted.
 */
data class RoomSummary(val rooms: Int, val beds: Int, val unassigned: Int?)

/**
 * What the rooms tell the gathering (as [RegistrationUsage] does for the registrations): the gathering asks, `talita.room` answers, and this package depends on neither
 * the tables nor the code of the rooms, which in their turn read the gathering.
 */
interface RoomSummaries {

    /** The counts of the rooms of [gatheringId]. */
    fun summary(gatheringId: Long): RoomSummary
}

/** The card «Camere» of the detail of a gathering: a row that leads to the page, with the counts and never a name (TALITA-11, criterion 16). */
data class RoomsCard(val title: String, val href: String, val summary: String)
