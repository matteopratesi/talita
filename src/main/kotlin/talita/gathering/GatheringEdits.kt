package talita.gathering

import org.springframework.stereotype.Component
import java.time.LocalDate

/** What came of «Nuova convivenza» through [GatheringEdits]. */
sealed interface CreateEdit {
    data class Created(val id: Long) : CreateEdit

    /** There is a gathering that is a draft or open already: nothing is created. */
    data class AlreadyCurrent(val id: Long, val title: String, val state: GatheringState) : CreateEdit

    /** The checks said no: what was written, and the error of each field. */
    data class Invalid(val title: TextCheck, val dates: DatesCheck) : CreateEdit {
        val errors: Int get() = listOfNotNull(title.error, dates.startError, dates.endError).size
    }
}

/** What came of «Modifica titolo, date e luogo» through [GatheringEdits]. */
sealed interface DatesEdit {
    data object NotFound : DatesEdit

    /** The gathering is over and the request carries other dates: it is refused whole (409, [GatheringChecks.DATES_CLOSED]), nothing is saved. */
    data class Closed(val title: TextCheck, val chosen: String) : DatesEdit

    /** The checks said no. [dates] is null for a gathering that is over (its dates are not asked). */
    data class Invalid(val title: TextCheck, val dates: DatesCheck?, val venueError: String?, val chosen: String) : DatesEdit {
        val errors: Int get() = listOfNotNull(title.error, dates?.startError, dates?.endError, venueError).size
    }

    /** The checks are good and the gathering was asked to save: [result] says what came of it. */
    data class Done(val title: TextCheck, val dates: DatesCheck?, val chosen: String, val chosenId: Long?, val result: DatesResult) : DatesEdit
}

/** What came of «Modifica le quote» through [GatheringEdits]. */
sealed interface FeesEdit {
    data object NotFound : FeesEdit

    data object Saved : FeesEdit

    data class Invalid(val full: FeeCheck, val daily: FeeCheck, val lunchOnly: FeeCheck) : FeesEdit {
        val errors: Int get() = listOfNotNull(full.error, daily.error, lunchOnly.error).size
    }

    /** A fee that registrations use cannot be left empty (rule R2): the sentence of each, null for one that is not the problem. */
    data class InUse(val full: FeeCheck, val daily: FeeCheck, val lunchOnly: FeeCheck, val dailyError: String?, val lunchOnlyError: String?) : FeesEdit
}

/** What came of «Modifica le informazioni» through [GatheringEdits]. */
sealed interface InfoEdit {
    data object NotFound : InfoEdit

    data object Saved : InfoEdit

    data class Invalid(val info: TextCheck, val whatToBring: TextCheck) : InfoEdit {
        val errors: Int get() = listOfNotNull(info.error, whatToBring.error).size
    }
}

/**
 * **The one place that changes a gathering from a form or from the APIs** (TALITA-21, criteria 13 and 14): «Nuova convivenza», title-dates-venue, fees and information. It holds what used to be in the controller of the
 * pages: the rule that after the end the dates do not change, that the venue and the full fee are not asked of a draft only, the question about the names of people ([PublicNames]), the order of the checks, and the sentences that
 * say why a fee or a day cannot be taken away. The pages of the teachers ([GatheringController]) and the APIs of configuration (`talita.api`) both call it and each turns the result into a page or into JSON, so the
 * same values have the same outcome, the same sentences and the same database; the locks and the transactions are those of [Gatherings].
 *
 * The APIs never confirm a change of venue that takes people out of their rooms: they pass no number of people ([saveDates] with an empty [change]), and the answer is [DatesResult.PeopleInRooms].
 */
@Component
class GatheringEdits(
    private val gatherings: Gatherings,
    private val usage: RegistrationUsage,
    private val arrivals: ArrivalUsage,
    private val assignments: RoomAssignments,
    private val names: PublicNames,
) {

    // ---- A new gathering -----------------------------------------------------------------------------------------------------

    fun create(title: String, startDate: String, endDate: String): CreateEdit {
        // Somebody made one (or it was there all along, for a request made by hand): nothing is created.
        gatherings.current()?.let { return CreateEdit.AlreadyCurrent(it.id, it.title, it.state) }
        val titleCheck = GatheringChecks.withNames(names, GatheringChecks.title(title)).single()
        val dates = GatheringChecks.dates(startDate, endDate, gatherings.today())
        val invalid = CreateEdit.Invalid(titleCheck, dates)
        if (invalid.errors > 0) return invalid
        return when (val result = gatherings.create(titleCheck.value, dates.start!!, dates.end!!)) {
            is CreateResult.Created -> CreateEdit.Created(result.id)
            is CreateResult.AlreadyCurrent -> CreateEdit.AlreadyCurrent(result.id, result.title, result.state)
        }
    }

    // ---- Title, dates and place ----------------------------------------------------------------------------------------------

    /**
     * [startDate] and [endDate] are null when the request does not carry them (the form of a gathering that is over has no field for them). [venue] is the text of the choice (the number of the venue, or empty); [change]
     * is how many people the teacher saw leave their rooms, if the venue changes (the dialog says it), and empty for the APIs.
     */
    fun saveDates(id: Long, title: String, startDate: String?, endDate: String?, venue: String, change: String): DatesEdit {
        val gathering = gatherings.find(id) ?: return DatesEdit.NotFound
        val today = gatherings.today()
        val over = gathering.isOver(today)
        val chosen = venue.trim().take(MAX_ID_TEXT)
        val chosenId = chosen.toLongOrNull()?.takeIf { it > 0 }
        // How many people the teacher saw leave their rooms (the dialog, or the page that stands in for it), if the venue changes: nothing in the request says it any other way.
        val confirmed = change.trim().take(MAX_ID_TEXT).toIntOrNull()?.takeIf { it >= 0 }
        // After the end the dates do not change: a request that carries other dates is refused whole, nothing is saved.
        val changesDates = over && ((startDate != null && startDate.trim() != gathering.startDate.toString()) || (endDate != null && endDate.trim() != gathering.endDate.toString()))
        val titleCheck = GatheringChecks.withNames(names, GatheringChecks.title(title)).single()
        // The venue: a draft may have none, a gathering that is not a draft needs one, and an id that is not a number is never a choice (TALITA-20, criteria 8 and 9).
        val venueError = if (chosenId == null && (chosen.isNotEmpty() || gathering.state != GatheringState.DRAFT)) GatheringChecks.VENUE_MISSING else null
        if (changesDates) return DatesEdit.Closed(titleCheck, chosen)
        val dates = if (over) null else GatheringChecks.dates(startDate, endDate, today, gathering.startDate to gathering.endDate)
        val invalid = DatesEdit.Invalid(titleCheck, dates, venueError, chosen)
        if (invalid.errors > 0) return invalid
        val result = gatherings.saveDates(id, titleCheck.value, dates?.start, dates?.end, chosenId, usage, arrivals, assignments, confirmed)
        return DatesEdit.Done(titleCheck, dates, chosen, chosenId, result)
    }

    // ---- Fees ----------------------------------------------------------------------------------------------------------------

    fun saveFees(id: Long, full: String, daily: String, lunchOnly: String): FeesEdit {
        val gathering = gatherings.find(id) ?: return FeesEdit.NotFound
        val fullCheck = GatheringChecks.fee(full, required = gathering.state != GatheringState.DRAFT)
        val dailyCheck = GatheringChecks.fee(daily)
        val lunchCheck = GatheringChecks.fee(lunchOnly)
        val invalid = FeesEdit.Invalid(fullCheck, dailyCheck, lunchCheck)
        if (invalid.errors > 0) return invalid
        return when (val result = gatherings.saveFees(id, Fees(fullCheck.cents, dailyCheck.cents, lunchCheck.cents), usage)) {
            FeesResult.Saved -> FeesEdit.Saved
            FeesResult.NotFound -> FeesEdit.NotFound
            is FeesResult.InUse -> FeesEdit.InUse(fullCheck, dailyCheck, lunchCheck, result.daily.takeIf { it > 0 }?.let(::inUse), result.lunchOnly.takeIf { it > 0 }?.let(::inUse))
        }
    }

    /** Rule R2: with registrations that are not withdrawn the form of the fees says how many are recalculated. Null with none. */
    fun feesNotice(id: Long): String? {
        val active = usage.counts(id).active
        return when {
            active == 0 -> null
            active == 1 -> "Hai già 1 iscrizione: se cambi le quote, la sua quota dovuta si ricalcola. Quello che è già stato pagato non cambia."
            else -> "Hai già $active iscrizioni: se cambi le quote, la quota dovuta di ognuna si ricalcola. Quello che è già stato pagato non cambia."
        }
    }

    // ---- Information ---------------------------------------------------------------------------------------------------------

    fun saveInfo(id: Long, info: String, whatToBring: String): InfoEdit {
        gatherings.find(id) ?: return InfoEdit.NotFound
        val (infoCheck, bringCheck) = GatheringChecks.withNames(names, GatheringChecks.info(info), GatheringChecks.whatToBring(whatToBring))
        val invalid = InfoEdit.Invalid(infoCheck, bringCheck)
        if (invalid.errors > 0) return invalid
        return if (gatherings.saveInfo(id, infoCheck.value.ifEmpty { null }, bringCheck.value.ifEmpty { null })) InfoEdit.Saved else InfoEdit.NotFound
    }

    companion object {
        /** More than this is not an id: a choice of the venue that is longer is cut (and is not one). */
        const val MAX_ID_TEXT = 40

        /** The error of a fee that registrations use and that was left empty. */
        fun inUse(count: Int): String =
            (if (count == 1) "La usa 1 iscrizione" else "La usano $count iscrizioni") + ": non puoi lasciarla vuota. Se diventa gratuita, scrivi 0."

        /** What holds a day that the new dates would take away: the registrations that chose it, the presences marked on it, or both (one sentence). */
        fun holdsTheDay(use: DayInUse): String {
            val registrations = when (use.registrations) {
                0 -> null
                1 -> "l’ha scelto 1 iscrizione"
                else -> "l’hanno scelto ${use.registrations} iscrizioni"
            }
            val presences = when (use.presences) {
                0 -> null
                1 -> "c’è 1 presenza segnata"
                else -> "ci sono ${use.presences} presenze segnate"
            }
            return listOfNotNull(registrations, presences).joinToString(" e ") + "."
        }

        /**
         * One sentence for each day that would go (rule R3), under the field that would lose it: the first day (the days before the new first day) or the last. The first is the error of the first day, the
         * second the error of the last; null where no day goes.
         */
        fun daysInUse(result: DatesResult.DaysInUse, newStart: LocalDate): Pair<String?, String?> {
            val sentences = result.days.map { use -> use.day to "Non puoi togliere ${GatheringFormat.weekday(use.day)} ${GatheringFormat.date(use.day)}: " + holdsTheDay(use) }
            val startError = sentences.filter { it.first.isBefore(newStart) }.joinToString(" ") { it.second }.ifEmpty { null }
            val endError = sentences.filter { !it.first.isBefore(newStart) }.joinToString(" ") { it.second }.ifEmpty { null }
            return startError to endError
        }

        /** «Nuova convivenza» when there is one already: the sentence that says which, and what to do. */
        fun blocked(title: String, state: GatheringState): String =
            if (state == GatheringState.DRAFT) {
                "C’è già una convivenza in bozza: «$title». Puoi modificarla invece di crearne un’altra."
            } else {
                "C’è già una convivenza con le iscrizioni aperte: «$title». Chiudi le iscrizioni prima di crearne un’altra."
            }
    }
}
