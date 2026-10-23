package talita.gathering

import org.jooq.DSLContext
import org.springframework.stereotype.Component

/**
 * What the other tickets (the registrations, the arrivals, the cancellations) read of a gathering: one service, read only, with
 * the data already worked out (the days, whether it is over, whether a parent can register today) so that nobody redoes the rules
 * of the dates with a clock of their own. It is the only way in: nothing else outside this package reads the table.
 */
@Component
class GatheringReader(private val gatherings: Gatherings) {

    /** The gathering with this public code, in any state (a draft too: the caller decides what to show), or null. */
    fun byCode(code: String): GatheringInfo? = gatherings.findByCode(code)?.let(::info)

    /** The gathering that is a draft or open now, or null when there is none (all closed, or none yet). */
    fun current(): GatheringInfo? = gatherings.current()?.let(::info)

    /** The gathering with this id, or null. */
    fun byId(id: Long): GatheringInfo? = gatherings.find(id)?.let(::info)

    /**
     * The gathering with this id inside the transaction of [context], locked for sharing: a registration that is being made reads it like this,
     * so that the closing of the registrations, a change of the dates or of the custodians cannot come in the middle. Null if there is none.
     */
    fun sharedIn(context: DSLContext, id: Long): GatheringInfo? = gatherings.findLocked(context, id)?.let { info(it, gatherings.custodians(context, id)) }

    /** The gathering that is not a draft and starts last (the one that the registrations of the teachers are about), or null. */
    fun latestNotDraft(): GatheringInfo? = gatherings.latestNotDraft()?.let(::info)

    private fun info(gathering: Gathering, custodians: List<Custodian> = gatherings.custodians(gathering.id)): GatheringInfo {
        val today = gatherings.today()
        return GatheringInfo(
            id = gathering.id,
            code = gathering.publicCode,
            title = gathering.title,
            start = gathering.startDate,
            end = gathering.endDate,
            days = gathering.days,
            place = gathering.place,
            fees = gathering.fees,
            custodians = custodians,
            state = gathering.state,
            over = gathering.isOver(today),
            registrationsPossibleToday = gathering.registrationsPossible(today),
        )
    }
}
