package talita.gathering

import org.jooq.DSLContext

/**
 * What the rooms of a gathering say in the card «Camere» of its detail (TALITA-11, TALITA-20): how many rooms, how many beds and, while the gathering is going on, how many children
 * are not in a room yet. Counts only, never a name. The rooms are the venue's: [venueId] and [venueName] are the venue of the gathering, null while it has none (the card says to choose it), and a venue
 * with no rooms says to write them. [unassigned] is null when it makes no sense: a draft has no children, and after the end who slept where is deleted.
 */
data class RoomSummary(val rooms: Int, val beds: Int, val unassigned: Int?, val venueId: Long? = null, val venueName: String? = null)

/**
 * What the rooms tell the gathering (as [RegistrationUsage] does for the registrations): the gathering asks, `talita.room` answers, and this package depends on neither
 * the tables nor the code of the rooms, which in their turn read the gathering.
 */
interface RoomSummaries {

    /** The counts of the rooms of the venue of [gatheringId]. */
    fun summary(gatheringId: Long): RoomSummary
}

/**
 * Who sleeps where, as the change of the venue of a gathering needs it (TALITA-20, criterion 12): the rooms are the venue's, so another venue leaves the people of the gathering without a room, and the teacher is told
 * how many they are before. The functions that take the [DSLContext] of a transaction are for the transaction that changes the venue, under the lock of the row of the gathering. The gathering asks, `talita.room`
 * answers, as for [RoomSummaries].
 */
interface RoomAssignments {

    /** How many people, children and teachers, are in a room of [gatheringId]. */
    fun peopleIn(context: DSLContext, gatheringId: Long): Int

    /** The same, for a page. */
    fun peopleIn(gatheringId: Long): Int

    /** Takes everybody out of the rooms of [gatheringId]; returns how many they were. */
    fun clearAll(context: DSLContext, gatheringId: Long): Int
}

/** The card «Camere» of the detail of a gathering: a row that leads to the page, with the counts and never a name (TALITA-11, criterion 16); without a venue, or with one that has no rooms, the row says what to do (TALITA-20). */
data class RoomsCard(val title: String, val href: String, val summary: String)
