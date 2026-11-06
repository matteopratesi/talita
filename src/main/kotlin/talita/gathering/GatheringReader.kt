package talita.gathering

import org.jooq.DSLContext
import org.springframework.stereotype.Component

/**
 * What the other tickets (the registrations, the arrivals, the cancellations) read of a gathering: one service, read only, with
 * the data already worked out (the days, whether it is over, whether a parent can register today) so that nobody redoes the rules
 * of the dates with a clock of their own. It is the only way in: nothing else outside this package reads the table.
 *
 * The name and the place of the house are the venue's (TALITA-20): they are asked of [GatheringVenues] every time, never copied into the gathering.
 */
@Component
class GatheringReader(private val gatherings: Gatherings, private val venues: GatheringVenues) {

    /** The gathering with this public code, in any state (a draft too: the caller decides what to show), or null. */
    fun byCode(code: String): GatheringInfo? = gatherings.findByCode(code)?.let { info(it) }

    /** The gathering that is a draft or open now, or null when there is none (all closed, or none yet). */
    fun current(): GatheringInfo? = gatherings.current()?.let { info(it) }

    /** The day of Rome, from the one clock of the application: what «today» means for the dates of a gathering. */
    fun today(): java.time.LocalDate = gatherings.today()

    /** The gathering with this id, or null. */
    fun byId(id: Long): GatheringInfo? = gatherings.find(id)?.let { info(it) }

    /**
     * The gathering with this id inside the transaction of [context], locked for sharing: a registration that is being made reads it like this,
     * so that the closing of the registrations, a change of the dates or of the custodians cannot come in the middle. Its venue is locked for sharing too, in this order (the gathering,
     * then the venue): a teacher who changes the place of the venue waits for the transaction, and the text that is saved has the place of that moment (TALITA-20, criterion 5). Null if there is none.
     */
    fun sharedIn(context: DSLContext, id: Long): GatheringInfo? {
        val gathering = gatherings.findLocked(context, id) ?: return null
        val venue = gathering.venueId?.let { venues.findIn(context, it, lock = true) }
        return info(gathering, gatherings.custodians(context, id), venue)
    }

    /** The gathering that is not a draft and starts last (the one that the registrations of the teachers are about), or null. */
    fun latestNotDraft(): GatheringInfo? = gatherings.latestNotDraft()?.let { info(it) }

    private fun info(
        gathering: Gathering,
        custodians: List<Custodian> = gatherings.custodians(gathering.id),
        venue: VenueRef? = gathering.venueId?.let(venues::find),
    ): GatheringInfo {
        val today = gatherings.today()
        return GatheringInfo(
            id = gathering.id,
            code = gathering.publicCode,
            title = gathering.title,
            start = gathering.startDate,
            end = gathering.endDate,
            days = gathering.days,
            venueId = gathering.venueId,
            venueName = venue?.name,
            place = venue?.place,
            fees = gathering.fees,
            custodians = custodians,
            state = gathering.state,
            over = gathering.isOver(today),
            registrationsPossibleToday = gathering.registrationsPossible(today),
        )
    }
}
