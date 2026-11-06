package talita.venue

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import talita.gathering.GatheringVenues
import talita.gathering.VenueRef
import talita.gathering.isForeignKeyViolation
import talita.gathering.isUniqueViolation
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.ROOM
import talita.jooq.tables.references.VENUE
import talita.jooq.tables.references.VENUE_FORM

/**
 * A venue, the house where a gathering is held (TALITA-20): a name and a place, both public text. It is not a person and holds no personal data: no contact person, no telephone,
 * no address in fields. It stays for more than one gathering.
 */
data class Venue(val id: Long, val name: String, val place: String)

/** A venue with what the list says about it: how many gatherings use it (never which), how many rooms and beds it has and how many forms it asks for. */
data class VenueSummary(val venue: Venue, val gatherings: Int, val rooms: Int = 0, val beds: Int = 0, val forms: Int = 0)

/** What came of saving a venue. */
sealed interface VenueSave {
    /** Saved. [gatherings] is how many gatherings use it: if there are some, what the parents read has changed with it. */
    data class Saved(val venue: Venue, val gatherings: Int) : VenueSave

    /** The name is the one of another venue (whatever the capitals and the spaces at the sides). */
    data object NameTaken : VenueSave

    /** No such venue: another teacher took it away a moment before. */
    data object NotFound : VenueSave
}

/** What came of taking a venue away. */
sealed interface VenueRemoval {
    data class Removed(val name: String) : VenueRemoval

    /** [gatherings] gatherings use it: it is not taken away, and nothing changes. */
    data class InUse(val gatherings: Int) : VenueRemoval

    data object NotFound : VenueRemoval
}

/**
 * The venues (TALITA-20): what the pages of the teachers read and write, and what the gathering reads through [GatheringVenues].
 *
 * - **Every write is in a transaction that locks the row of the venue** (`for update`): the confirmation of a registration reads the venue for sharing, together with its gathering, so a teacher who changes
 *   the place and a parent who confirms are in a queue, and the text that is saved has always the place of that moment (criterion 5).
 * - **The name is unique** whatever the capitals and the spaces at the sides (the index of the table): two teachers who write the same name at the same moment leave one venue and an answer
 *   for the second, never an error.
 * - **A venue that a gathering uses is not taken away**: the key from `gathering` has no cascade, so a request made by hand is refused by the database too.
 * - **Nothing here writes a name or a place in the log**: this class does not log at all.
 */
@Component
class Venues(private val dsl: DSLContext) : GatheringVenues {

    // ---- Reading -------------------------------------------------------------------------------------------------------------

    /** Every venue by name, with the number of gatherings that use it and its rooms and beds. */
    fun list(): List<VenueSummary> {
        val gatherings = DSL.field(DSL.selectCount().from(GATHERING).where(GATHERING.VENUE_ID.eq(VENUE.ID)))
        val rooms = DSL.field(DSL.selectCount().from(ROOM).where(ROOM.VENUE_ID.eq(VENUE.ID)))
        val beds = DSL.field(DSL.select(DSL.coalesce(DSL.sum(ROOM.BEDS), java.math.BigDecimal.ZERO)).from(ROOM).where(ROOM.VENUE_ID.eq(VENUE.ID)))
        val forms = DSL.field(DSL.selectCount().from(VENUE_FORM).where(VENUE_FORM.VENUE_ID.eq(VENUE.ID)))
        return dsl.select(VENUE.ID, VENUE.NAME, VENUE.PLACE, gatherings, rooms, beds, forms).from(VENUE).orderBy(DSL.lower(DSL.trim(VENUE.NAME)), VENUE.ID)
            .fetch { VenueSummary(Venue(it[VENUE.ID]!!, it[VENUE.NAME]!!, it[VENUE.PLACE]!!), it.value4()!!, it.value5()!!, it.value6()!!.toInt(), it.value7()!!) }
    }

    /** The venue [id], or null. */
    fun byId(id: Long): Venue? =
        dsl.select(VENUE.ID, VENUE.NAME, VENUE.PLACE).from(VENUE).where(VENUE.ID.eq(id)).fetchOne { Venue(it[VENUE.ID]!!, it[VENUE.NAME]!!, it[VENUE.PLACE]!!) }

    /** How many gatherings use the venue [id]. */
    fun usage(id: Long): Int = dsl.fetchCount(GATHERING, GATHERING.VENUE_ID.eq(id))

    override fun find(id: Long): VenueRef? = byId(id)?.let { VenueRef(it.id, it.name, it.place) }

    override fun findIn(context: DSLContext, id: Long, lock: Boolean): VenueRef? {
        val query = context.select(VENUE.ID, VENUE.NAME, VENUE.PLACE).from(VENUE).where(VENUE.ID.eq(id))
        return (if (lock) query.forShare() else query).fetchOne { VenueRef(it[VENUE.ID]!!, it[VENUE.NAME]!!, it[VENUE.PLACE]!!) }
    }

    override fun all(): List<VenueRef> =
        dsl.select(VENUE.ID, VENUE.NAME, VENUE.PLACE).from(VENUE).orderBy(DSL.lower(DSL.trim(VENUE.NAME)), VENUE.ID)
            .fetch { VenueRef(it[VENUE.ID]!!, it[VENUE.NAME]!!, it[VENUE.PLACE]!!) }

    // ---- Writing -------------------------------------------------------------------------------------------------------------

    /** A new venue, with a [name] and a [place] that [VenueChecks] has already checked. */
    fun create(name: String, place: String): VenueSave = guarded {
        val id = dsl.insertInto(VENUE).set(VENUE.NAME, name).set(VENUE.PLACE, place).returningResult(VENUE.ID).fetchSingle().value1()!!
        VenueSave.Saved(Venue(id, name, place), 0)
    }

    /** The venue [id] has a new name and a new place. What the gatherings that use it show changes with it, and the answer says how many there are. */
    fun update(id: Long, name: String, place: String): VenueSave = guarded {
        dsl.transactionResult { configuration ->
            val tx = DSL.using(configuration)
            tx.select(VENUE.ID).from(VENUE).where(VENUE.ID.eq(id)).forUpdate().fetchOne() ?: return@transactionResult VenueSave.NotFound
            tx.update(VENUE).set(VENUE.NAME, name).set(VENUE.PLACE, place).where(VENUE.ID.eq(id)).execute()
            VenueSave.Saved(Venue(id, name, place), tx.fetchCount(GATHERING, GATHERING.VENUE_ID.eq(id)))
        }
    }

    /** Two teachers who write the same name at the same moment: the unique index says no to the second, and that is the same as the name being taken. */
    private fun guarded(save: () -> VenueSave): VenueSave = try {
        save()
    } catch (e: RuntimeException) {
        if (e.isUniqueViolation()) VenueSave.NameTaken else throw e
    }

    /**
     * Takes the venue away, with what hangs from it (a venue that no gathering uses). One that a gathering uses is not taken away ([VenueRemoval.InUse]): the count is made under the lock of the row, and the key
     * from the gathering says no to anything that slips through (a gathering that chooses it at the same moment).
     */
    fun remove(id: Long): VenueRemoval = try {
        dsl.transactionResult { configuration ->
            val tx = DSL.using(configuration)
            val name = tx.select(VENUE.NAME).from(VENUE).where(VENUE.ID.eq(id)).forUpdate().fetchOne(VENUE.NAME) ?: return@transactionResult VenueRemoval.NotFound
            val used = tx.fetchCount(GATHERING, GATHERING.VENUE_ID.eq(id))
            if (used > 0) return@transactionResult VenueRemoval.InUse(used)
            tx.deleteFrom(VENUE).where(VENUE.ID.eq(id)).execute()
            VenueRemoval.Removed(name)
        }
    } catch (e: RuntimeException) {
        if (e.isForeignKeyViolation()) VenueRemoval.InUse(usage(id).coerceAtLeast(1)) else throw e
    }
}
