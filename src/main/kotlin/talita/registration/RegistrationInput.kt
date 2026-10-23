package talita.registration

import talita.gathering.GatheringChecks
import talita.jooq.enums.RegistrationStatus
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate

/** The two states a parent or a teacher can choose for a registration. The third, withdrawn, has a dialog of its own. */
enum class Status(val column: RegistrationStatus, val code: String, val label: String) {
    CONFIRMED(RegistrationStatus.confirmed, "confirmed", "Confermata"),
    MAYBE(RegistrationStatus.maybe, "maybe", "In forse"),
    WITHDRAWN(RegistrationStatus.withdrawn, "withdrawn", "Ritirata"),
    ;

    companion object {
        fun of(column: RegistrationStatus): Status = entries.single { it.column == column }

        /** The status that a form can send: «Confermata» or «In forse». */
        fun ofChoice(code: String): Status? = listOf(CONFIRMED, MAYBE).firstOrNull { it.code == code }
    }
}

/** Allergies, intolerances and medicines of a child, the empty ones left out; null when nothing is written at all. */
data class Notes(val allergies: String?, val intolerances: String?, val medications: String?)

/**
 * A block of a child as the person typed it, nothing cleaned: the names of the fields are those of the design (`children[0].firstName`...).
 * [knownId] is the child of the account that the block came from («I tuoi figli»): correcting the data of that child does not make another one.
 */
data class ChildInput(
    val knownId: Long? = null,
    val firstName: String = "",
    val lastName: String = "",
    val birthDay: String = "",
    val birthMonth: String = "",
    val birthYear: String = "",
    val presence: String = "",
    /** What was typed for each day, by its key (`fri`): empty, `full` or `lunch`. */
    val days: Map<String, String> = emptyMap(),
    val status: String = Status.CONFIRMED.code,
    val allergies: String = "",
    val intolerances: String = "",
    val medications: String = "",
    val healthConsent: Boolean = false,
) {
    /** Nothing was written: «Aggiungi» of «I tuoi figli» fills this block, instead of making another. */
    val untouched: Boolean
        get() = knownId == null && listOf(firstName, lastName, birthDay, birthMonth, birthYear, presence, allergies, intolerances, medications).all { it.isBlank() } &&
            days.values.all { it.isBlank() } && !healthConsent

    /** The fields of this block as a form sends them, for the hidden fields of the second step. */
    fun fields(index: Int): List<Pair<String, String>> = buildList {
        val prefix = "children[$index]."
        knownId?.let { add(prefix + "childId" to it.toString()) }
        add(prefix + "firstName" to firstName)
        add(prefix + "lastName" to lastName)
        add(prefix + "birthDay" to birthDay)
        add(prefix + "birthMonth" to birthMonth)
        add(prefix + "birthYear" to birthYear)
        add(prefix + "presence" to presence)
        days.forEach { (key, kind) -> add(prefix + "day.$key" to kind) }
        add(prefix + "status" to status)
        add(prefix + "allergies" to allergies)
        add(prefix + "intolerances" to intolerances)
        add(prefix + "medications" to medications)
        if (healthConsent) add(prefix + "healthConsent" to "true")
    }
}

/** The whole form: the phone and the blocks. [tooMany]: the request had more blocks than the 10 a person can register at a time. */
data class FormInput(val phone: String, val children: List<ChildInput>, val tooMany: Boolean = false) {
    fun fields(): List<Pair<String, String>> = listOf("phone" to phone) + children.flatMapIndexed { index, child -> child.fields(index) }
}

/** Reads the fields `phone` and `children[i].…` of a request. It is the one place that knows how the blocks are named. */
object FormParser {

    /** The most blocks of one registration (criterion 1). */
    const val MAX_CHILDREN = 10

    /** The most indexes looked at: a request made by hand cannot make the server walk through thousands of them. */
    private const val MAX_INDEX = 50

    private const val MAX_TEXT = 5000

    private val key = Regex("""^children\[(\d{1,3})]\.(.+)$""")

    fun parse(params: Map<String, String>): FormInput {
        val byIndex = sortedMapOf<Int, MutableMap<String, String>>()
        for ((name, value) in params) {
            val match = key.matchEntire(name) ?: continue
            val index = match.groupValues[1].toInt()
            if (index >= MAX_INDEX) continue
            byIndex.getOrPut(index) { mutableMapOf() }[match.groupValues[2]] = value.take(MAX_TEXT)
        }
        val blocks = byIndex.values.map(::child)
        return FormInput(params["phone"].orEmpty().take(MAX_TEXT), blocks.take(MAX_CHILDREN).ifEmpty { listOf(ChildInput()) }, tooMany = blocks.size > MAX_CHILDREN)
    }

    private fun child(fields: Map<String, String>) = ChildInput(
        knownId = fields["childId"]?.trim()?.toLongOrNull(),
        firstName = fields["firstName"].orEmpty(),
        lastName = fields["lastName"].orEmpty(),
        birthDay = fields["birthDay"].orEmpty(),
        birthMonth = fields["birthMonth"].orEmpty(),
        birthYear = fields["birthYear"].orEmpty(),
        presence = fields["presence"].orEmpty(),
        days = fields.filterKeys { it.startsWith("day.") }.mapKeys { it.key.removePrefix("day.") },
        status = fields["status"]?.trim() ?: Status.CONFIRMED.code,
        allergies = fields["allergies"].orEmpty(),
        intolerances = fields["intolerances"].orEmpty(),
        medications = fields["medications"].orEmpty(),
        healthConsent = fields["healthConsent"]?.trim() == "true",
    )
}

/** What a block of a child is, once checked: the cleaned values (when the block is good) and an error for each field that is not. */
data class ChildCheck(
    val input: ChildInput,
    /** The error under each field, by the name of the field: `firstName`, `lastName`, `birth`, `presence`, `status`, `allergies`, `intolerances`, `medications`, `consent`. */
    val errors: Map<String, String>,
    val firstName: String,
    val lastName: String,
    val birthDate: LocalDate?,
    val presence: Presence?,
    val status: Status?,
    val notes: Notes?,
    val dueCents: Int?,
    /** The name error is «già iscritto…» and has the link to «Iscrizioni» in it. */
    val taken: Boolean = false,
) {
    val valid: Boolean get() = errors.isEmpty()

    val name: String get() = "$firstName $lastName"
}

/** The check of the whole form: the phone as it will be saved and the blocks. */
data class FormCheck(val input: FormInput, val phone: String, val phoneError: String?, val children: List<ChildCheck>) {
    val valid: Boolean get() = phoneError == null && children.isNotEmpty() && children.all { it.valid } && !input.tooMany

    /** How many fields are wrong: with two or more, a notice at the top says it. */
    val errorCount: Int get() = (if (phoneError != null) 1 else 0) + children.sumOf { it.errors.size }
}

/**
 * The checks of the fields of a registration (criterion 3), one place for every form (the parent's two steps, the modification, the paper form of
 * the teachers and their corrections). One error per field, the first that applies, with the words of the design. Lengths are counted as the
 * database counts them: a code point, not a UTF-16 unit.
 */
object RegistrationChecks {

    const val PHONE_MISSING = "Scrivi un numero di telefono."
    const val PHONE_INCOMPLETE = "Scrivi un numero di telefono completo, per esempio 333 123 4567."
    const val FIRST_MISSING = "Scrivi il nome."
    const val LAST_MISSING = "Scrivi il cognome."
    const val NAME_TOO_LONG = "Usa al massimo 100 caratteri."
    const val BIRTH_INCOMPLETE = "Scrivi giorno, mese e anno: per esempio 4 4 2016."
    const val BIRTH_NOT_REAL = "Scrivi una data vera, per esempio 4 4 2016."
    const val BIRTH_FUTURE = "La data di nascita non può essere nel futuro."
    const val BIRTH_ADULT = "Controlla la data: il bambino ha già 18 anni il primo giorno della convivenza."
    const val STATUS_NOT_OFFERED = "Scegli «Confermata» o «In forse»."
    const val TWICE = "Questo bambino c’è già in questo modulo."
    const val CONSENT_MISSING = "Per salvare le segnalazioni serve il tuo consenso: spunta la casella. Se non vuoi darlo, svuota i campi: l’iscrizione vale lo stesso."
    const val CONSENT_MISSING_ON_PAPER = "Per salvare le segnalazioni serve il consenso: spunta la casella. Se il genitore non l’ha dato sul modulo, svuota i campi."
    const val TOO_MANY = "Puoi iscrivere fino a 10 bambini alla volta."

    const val NAME_MAX = 100
    const val NOTE_MAX = 1000

    private fun length(text: String): Int = text.codePointCount(0, text.length)

    /** A name as it is saved: spaces at the sides gone, a run of spaces and line breaks one space, no control characters. */
    fun name(raw: String): String = raw.replace(Regex("""[\p{Cntrl}\s]+"""), " ").trim()

    /** The person written as a name, with the capitals, accents and spaces at the sides that do not count taken away: two blocks that say the same child. */
    fun identity(first: String, last: String, birth: LocalDate?): String =
        listOf(first, last).joinToString("|") { Normalizer.normalize(name(it), Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "").lowercase() } + "|" + birth

    /**
     * The phone as it will be saved, or the error. From 6 to 15 digits, spaces, dots and dashes between them and a «+» at the start. It is kept with a
     * space for each separator; when that is longer than the column (20) the separators go and only the digits stay.
     */
    fun phone(raw: String): Pair<String, String?> {
        val typed = raw.trim()
        if (typed.isEmpty()) return typed to PHONE_MISSING
        if (!Regex("""\+?\s*[0-9][0-9 .\-]*""").matches(typed)) return typed to PHONE_INCOMPLETE
        val digits = typed.count { it.isDigit() }
        if (digits !in 6..15) return typed to PHONE_INCOMPLETE
        val plus = if (typed.startsWith("+")) "+" else ""
        val spaced = plus + typed.removePrefix("+").trim().split(Regex("""[ .\-]+""")).filter { it.isNotEmpty() }.joinToString(" ")
        return (if (spaced.length <= 20) spaced else plus + typed.filter { it.isDigit() }) to null
    }

    private fun birth(input: ChildInput, firstDay: LocalDate, today: LocalDate): Pair<LocalDate?, String?> {
        val (day, month, year) = Triple(input.birthDay.trim(), input.birthMonth.trim(), input.birthYear.trim())
        if (day.isEmpty() || month.isEmpty() || year.isEmpty()) return null to BIRTH_INCOMPLETE
        val digits = Regex("""[0-9]{1,4}""")
        if (!digits.matches(day) || !digits.matches(month) || !digits.matches(year) || year.length != 4 || year.toInt() < 1900) return null to BIRTH_NOT_REAL
        val date = try {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
        } catch (_: DateTimeException) {
            return null to BIRTH_NOT_REAL
        }
        return when {
            date.isAfter(today) -> null to BIRTH_FUTURE
            !date.plusYears(18).isAfter(firstDay) -> null to BIRTH_ADULT
            else -> date to null
        }
    }

    private fun note(raw: String, field: String, errors: MutableMap<String, String>): String? {
        val value = GatheringChecks.clean(raw)
        val size = length(value)
        if (size > NOTE_MAX) errors[field] = "Usa al massimo 1.000 caratteri: ne hai scritti ${java.text.NumberFormat.getIntegerInstance(java.util.Locale.ITALIAN).format(size)}."
        return value.ifEmpty { null }
    }

    /**
     * The notes on health of a form (criteria 3 and 18), for the blocks of the registration and for the page where the parent changes them: each field is
     * at most 1,000 characters, and notes without the consent are not kept. Null when nothing is written; the errors are by field (`allergies`,
     * `intolerances`, `medications`, `consent`). [paper]: the consent is the one on the paper form.
     */
    fun notes(allergiesRaw: String, intolerancesRaw: String, medicationsRaw: String, consent: Boolean, paper: Boolean = false): Pair<Notes?, Map<String, String>> {
        val errors = linkedMapOf<String, String>()
        val allergies = note(allergiesRaw, "allergies", errors)
        val intolerances = note(intolerancesRaw, "intolerances", errors)
        val medications = note(medicationsRaw, "medications", errors)
        val written = allergies != null || intolerances != null || medications != null
        if (written && !consent) errors["consent"] = if (paper) CONSENT_MISSING_ON_PAPER else CONSENT_MISSING
        return (if (written) Notes(allergies, intolerances, medications) else null) to errors
    }

    /**
     * Checks the blocks of [input] against the gathering [offer] (what the fees offer; its days), the [firstDay] and [today] for the dates of birth.
     * [withHealth]: the blocks carry the notes on health (the modification and the correction do not). [paper]: the consent is the one on the paper
     * form, and the error says so.
     */
    fun check(input: FormInput, offer: Offer, firstDay: LocalDate, today: LocalDate, withHealth: Boolean = true, paper: Boolean = false): FormCheck {
        val (phone, phoneError) = phone(input.phone)
        val seen = mutableSetOf<String>()
        val children = input.children.map { block ->
            val errors = linkedMapOf<String, String>()
            val first = name(block.firstName)
            val last = name(block.lastName)
            if (first.isEmpty()) errors["firstName"] = FIRST_MISSING else if (length(first) > NAME_MAX) errors["firstName"] = NAME_TOO_LONG
            if (last.isEmpty()) errors["lastName"] = LAST_MISSING else if (length(last) > NAME_MAX) errors["lastName"] = NAME_TOO_LONG

            val (born, birthError) = birth(block, firstDay, today)
            birthError?.let { errors["birth"] = it }

            val (presence, presenceError) = Quota.parse(offer, block.presence) { key -> block.days[key].orEmpty() }
            presenceError?.let { errors["presence"] = it.text }

            val status = Status.ofChoice(block.status.trim())
            if (status == null) errors["status"] = STATUS_NOT_OFFERED

            var notes: Notes? = null
            if (withHealth) {
                val (checked, noteErrors) = notes(block.allergies, block.intolerances, block.medications, block.healthConsent, paper)
                notes = checked
                errors += noteErrors
            }

            if ("firstName" !in errors && "lastName" !in errors && born != null && !seen.add(identity(first, last, born))) errors["firstName"] = TWICE

            ChildCheck(
                input = block,
                errors = errors,
                firstName = first,
                lastName = last,
                birthDate = born,
                presence = presence,
                status = status,
                notes = notes,
                dueCents = presence?.let { Quota.due(offer, it) },
            )
        }
        return FormCheck(input, phone, phoneError, children)
    }
}
