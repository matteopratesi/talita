package talita.gathering

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** The limits that the forms, the database and the help texts agree on. */
object Limits {
    const val TITLE = 100
    const val PLACE = 200
    const val INFO = 5000
    const val WHAT_TO_BRING = 2000

    /** The most days a gathering can last: the first and the last included. */
    const val MAX_DAYS = 7

    const val MAX_FEE_CENTS = 100_000
}

/** A text as it will be saved, and what is wrong with it (null: nothing). */
data class TextCheck(val value: String, val error: String?) {
    val valid: Boolean get() = error == null
}

/** A fee as typed, in cents when it is good (null when it is empty), and what is wrong with it. */
data class FeeCheck(val typed: String, val cents: Int?, val error: String?) {
    val valid: Boolean get() = error == null
}

/** What an amount of euros typed in a field is: not an amount, more than the most that a field takes, or a number of cents. The payments of the arrivals read amounts the same way. */
sealed interface Amount {
    data object Malformed : Amount

    data object TooMuch : Amount

    data class Cents(val cents: Int) : Amount
}

/** The two dates as typed, as dates when they are good, and what is wrong with each. */
data class DatesCheck(
    val startTyped: String,
    val endTyped: String,
    val start: LocalDate?,
    val end: LocalDate?,
    val startError: String?,
    val endError: String?,
) {
    val valid: Boolean get() = startError == null && endError == null
}

/**
 * The checks of the forms of the gathering, in one place so that «new» and the four forms of the detail say the same words. One
 * error per field: the first that applies, in the order of the design (criteria 3, 5, 6, 7).
 *
 * Lengths are counted in characters as the database counts them (a code point, not a UTF-16 unit: an emoji is one), after the
 * spaces at the sides are taken away.
 */
object GatheringChecks {

    const val TITLE_MISSING = "Scrivi il titolo."
    const val START_MISSING = "Scegli il primo giorno."
    const val END_MISSING = "Scegli l’ultimo giorno."
    const val NOT_A_DATE = "Scegli una data vera, per esempio 17/09/2027."
    const val END_BEFORE_START = "L’ultimo giorno non può essere prima del primo."
    const val TOO_LONG_GATHERING = "La convivenza può durare al massimo ${Limits.MAX_DAYS} giorni: controlla le date."
    const val START_PAST = "Il primo giorno è già passato. Scegli una data da oggi in poi."
    const val END_PAST = "L’ultimo giorno è già passato. Scegli una data da oggi in poi."
    const val FEE_FORMAT = "Scrivi un importo in euro, per esempio 60 oppure 12,50. Il massimo è 1000 €."
    const val PLACE_MISSING = "Scrivi il luogo: la pagina per i genitori lo mostra."
    const val FULL_FEE_MISSING = "Scrivi la quota intera. Se è gratuita, scrivi 0."
    const val CUSTODIAN_MISSING = "Scegli almeno un maestro: il testo dell’affidamento ha bisogno di almeno un nome."
    const val DATES_CLOSED = "Le date non si cambiano più, perché la convivenza è finita."

    /** Line breaks as the server keeps them (`\n`), and no space at the sides. */
    fun clean(raw: String?): String = raw.orEmpty().replace("\r\n", "\n").replace('\r', '\n').trim()

    private fun length(text: String): Int = text.codePointCount(0, text.length)

    private val controls = Regex("""[\p{Cntrl}&&[^\n]]+""")

    /** A text of one line: control characters (and line breaks) become a space. */
    private fun oneLine(raw: String?): String = clean(raw).replace(Regex("""\s*\n\s*"""), " ").replace(controls, " ").trim()

    /** The text that goes on the public page: not longer than [max] and with no email, phone number or IBAN. */
    private fun publicText(value: String, max: Int, tooLong: (Int) -> String): TextCheck {
        val size = length(value)
        if (size > max) return TextCheck(value, tooLong(size))
        return TextCheck(value, PublicText.check(value).message())
    }

    fun title(raw: String?): TextCheck {
        val value = oneLine(raw)
        if (value.isEmpty()) return TextCheck(value, TITLE_MISSING)
        return publicText(value, Limits.TITLE) { "Il titolo può avere al massimo ${Limits.TITLE} caratteri: ne hai scritti $it." }
    }

    /** The place; empty is fine for a draft, and it is not for a gathering that is no longer one ([required]). */
    fun place(raw: String?, required: Boolean): TextCheck {
        val value = oneLine(raw)
        if (value.isEmpty()) return TextCheck(value, if (required) PLACE_MISSING else null)
        return publicText(value, Limits.PLACE) { "Il luogo può avere al massimo ${Limits.PLACE} caratteri: ne hai scritti $it." }
    }

    fun info(raw: String?): TextCheck {
        val value = clean(raw)
        if (value.isEmpty()) return TextCheck(value, null)
        return publicText(value, Limits.INFO) { "Le informazioni possono avere al massimo ${Limits.INFO.thousands()} caratteri: ne hai scritti ${it.thousands()}." }
    }

    fun whatToBring(raw: String?): TextCheck {
        val value = clean(raw)
        if (value.isEmpty()) return TextCheck(value, null)
        return publicText(value, Limits.WHAT_TO_BRING) { "L’elenco può avere al massimo ${Limits.WHAT_TO_BRING.thousands()} caratteri: ne hai scritti ${it.thousands()}." }
    }

    /** `5000` → `5.000`, as the guide writes the thousands. */
    private fun Int.thousands(): String = java.text.NumberFormat.getIntegerInstance(java.util.Locale.ITALIAN).format(this)

    private val feeFormat = Regex("""(\d{1,4})(?:[.,](\d{1,2}))?""")

    /**
     * An amount of euros as a person types it: whole euros of up to four digits and, after a comma or a dot, one or two decimals (`60`, `12,50`, `12.5`), up
     * to 1000 €. Negatives, letters, thousands separators (`1.000` is a third decimal) and a third decimal are not amounts. [typed] is taken as it is: the
     * caller takes the spaces away (a fee, the ends; a payment, all).
     */
    fun amount(typed: String): Amount {
        val match = feeFormat.matchEntire(typed) ?: return Amount.Malformed
        val units = match.groupValues[1].toInt()
        val decimals = match.groupValues[2].let { if (it.length == 1) it.toInt() * 10 else if (it.isEmpty()) 0 else it.toInt() }
        val cents = units * 100 + decimals
        return if (cents > Limits.MAX_FEE_CENTS) Amount.TooMuch else Amount.Cents(cents)
    }

    /**
     * A fee: nothing typed is «not provided» (null), «0» is free, up to 1000 € with at most two decimals, a comma or a dot
     * (`60`, `12,50`, `12.5`). Negatives, letters, thousands separators and a third decimal are refused, with an example.
     * [required]: the full fee of a gathering that is no longer a draft cannot be empty.
     */
    fun fee(raw: String?, required: Boolean = false): FeeCheck {
        val typed = raw.orEmpty().trim()
        if (typed.isEmpty()) return FeeCheck(typed, null, if (required) FULL_FEE_MISSING else null)
        return when (val amount = amount(typed)) {
            Amount.Malformed, Amount.TooMuch -> FeeCheck(typed, null, FEE_FORMAT)
            is Amount.Cents -> FeeCheck(typed, amount.cents, null)
        }
    }

    private fun parse(typed: String): Result<LocalDate> = try {
        Result.success(LocalDate.parse(typed))
    } catch (e: DateTimeParseException) {
        Result.failure(e)
    }

    /**
     * The first and the last day. [previous] holds the dates already saved (the form of a gathering that exists): a date that is
     * the same as before may be in the past (a draft whose first day has passed can still change its title), a new or changed
     * one may not. Dates come as `yyyy-MM-dd`, which is what `<input type="date">` sends.
     */
    fun dates(startRaw: String?, endRaw: String?, today: LocalDate, previous: Pair<LocalDate, LocalDate>? = null): DatesCheck {
        val startTyped = startRaw.orEmpty().trim()
        val endTyped = endRaw.orEmpty().trim()
        val start = if (startTyped.isEmpty()) null else parse(startTyped).getOrNull()
        val end = if (endTyped.isEmpty()) null else parse(endTyped).getOrNull()

        val startError = when {
            startTyped.isEmpty() -> START_MISSING
            start == null -> NOT_A_DATE
            start.isBefore(today) && start != previous?.first -> START_PAST
            else -> null
        }
        val endError = when {
            endTyped.isEmpty() -> END_MISSING
            end == null -> NOT_A_DATE
            start != null && end.isBefore(start) -> END_BEFORE_START
            start != null && ChronoUnit.DAYS.between(start, end) > Limits.MAX_DAYS - 1 -> TOO_LONG_GATHERING
            end.isBefore(today) && end != previous?.second -> END_PAST
            else -> null
        }
        return DatesCheck(startTyped, endTyped, start, end, startError, endError)
    }
}
