package talita.registration

import talita.gathering.Fees
import talita.jooq.enums.RegistrationDayKind
import java.time.DayOfWeek
import java.time.LocalDate

/** How a child spends a day: the whole day (a «giornata»), or only the lunch. */
enum class DayKind(val column: RegistrationDayKind, val code: String) {
    FULL(RegistrationDayKind.full, "full"),
    LUNCH(RegistrationDayKind.lunch, "lunch"),
    ;

    companion object {
        fun of(column: RegistrationDayKind): DayKind = entries.single { it.column == column }

        fun ofCode(code: String): DayKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * When a child comes: every day of the gathering ([allDays]), or the days in [days] (each a whole day or a lunch only). A choice of
 * every day in «whole day» by hand is the same as «every day»: [Quota.normalize] says so, so that the fee and the words are one.
 */
data class Presence(val allDays: Boolean, val days: Map<LocalDate, DayKind> = emptyMap()) {

    companion object {
        val ALL = Presence(allDays = true)
    }
}

/** Why a choice of presence is not good (criteria 3 and 4). The text is the one of the design. */
enum class PresenceError(val text: String) {
    NONE_CHOSEN("Scegli quando viene."),
    NO_DAY("Scegli almeno un giorno."),
    NOT_OFFERED("Questa scelta non è prevista per questa convivenza."),
}

/**
 * What the fees of a gathering allow a parent to choose (the fees are the ones that the gathering has now): «Tutti i giorni» always, and
 * «Alcuni giorni» with a whole day if the daily fee is set and with a lunch only if the lunch fee is set. A gathering of one day has only
 * «Tutto il giorno» and, if the lunch fee is set, «Solo pranzo». A fee that is not set never appears among the choices.
 */
data class Offer(val fees: Fees, val days: List<LocalDate>) {
    val oneDay: Boolean get() = days.size == 1

    /** The kinds of day that can be picked, in the order they are shown. */
    val kinds: List<DayKind>
        get() = listOfNotNull(DayKind.FULL.takeIf { fees.dailyCents != null && !oneDay }, DayKind.LUNCH.takeIf { fees.lunchOnlyCents != null })

    /** «Alcuni giorni» is offered (not for a gathering of one day, which has «Solo pranzo» instead). */
    val someDays: Boolean get() = !oneDay && kinds.isNotEmpty()

    /** «Solo pranzo» of a gathering of one day. */
    val lunchOnlyOneDay: Boolean get() = oneDay && fees.lunchOnlyCents != null

    /** There is something to choose: otherwise the page says «Tutti i giorni» and sends it by itself. */
    val choice: Boolean get() = someDays || lunchOnlyOneDay
}

/**
 * The one function of the fee that is due (criterion 4, rule R5 of TALITA-4): every form, every list and every total comes from here, and
 * nothing is worked out twice. It is never saved: it follows the fees of the gathering and the days of the registration (R2).
 *
 * - every day: the full fee;
 * - some days: for each day, the daily fee for a whole day and the lunch fee for a lunch only;
 * - the three whole days picked one by one are worth every day, so the full fee;
 * - a fee that is not set is not offered, and a choice that needs it is refused;
 * - a withdrawn registration owes nothing (the callers do not ask).
 */
object Quota {

    /** The key of a day in a form: `mon`, `tue`... A gathering lasts seven days at most, so a key is one day. */
    fun key(day: LocalDate): String = day.dayOfWeek.key()

    private fun DayOfWeek.key(): String = name.take(3).lowercase()

    /**
     * The presence that a form means, from its [choice] (`all_days`, `days`, or `lunch` for a gathering of one day) and the kind of day typed
     * for each day by its key. The error is the first that applies.
     */
    fun parse(offer: Offer, choice: String, kindOfDay: (String) -> String): Pair<Presence?, PresenceError?> {
        return when (choice.trim()) {
            "" -> null to PresenceError.NONE_CHOSEN
            "all_days" -> Presence.ALL to null
            "lunch" -> if (offer.lunchOnlyOneDay) Presence(false, mapOf(offer.days.single() to DayKind.LUNCH)) to null else null to PresenceError.NOT_OFFERED
            "days" -> {
                if (!offer.someDays) return null to PresenceError.NOT_OFFERED
                val picked = linkedMapOf<LocalDate, DayKind>()
                for (day in offer.days) {
                    val typed = kindOfDay(key(day)).trim()
                    if (typed.isEmpty()) continue
                    val kind = DayKind.ofCode(typed)?.takeIf { it in offer.kinds } ?: return null to PresenceError.NOT_OFFERED
                    picked[day] = kind
                }
                if (picked.isEmpty()) null to PresenceError.NO_DAY else normalize(offer, Presence(false, picked)) to null
            }

            else -> null to PresenceError.NOT_OFFERED
        }
    }

    /** Every day as a whole day is «every day». */
    fun normalize(offer: Offer, presence: Presence): Presence =
        if (!presence.allDays && presence.days.size == offer.days.size && presence.days.values.all { it == DayKind.FULL }) Presence.ALL else presence

    /** The fee that is due in cents, or null when the presence is not one the fees allow. */
    fun due(offer: Offer, presence: Presence): Int? {
        val fees = offer.fees
        if (presence.allDays) return fees.fullCents
        if (presence.days.isEmpty()) return null
        var total = 0
        for ((day, kind) in presence.days) {
            if (day !in offer.days) return null
            total += when (kind) {
                DayKind.FULL -> fees.dailyCents
                DayKind.LUNCH -> fees.lunchOnlyCents
            } ?: return null
        }
        return total
    }
}
