package talita.room

import java.text.Normalizer

/** A name of a room as it will be saved (a line, no spaces at the sides), and what is wrong with it (null: nothing). */
data class NameCheck(val value: String, val error: String?) {
    val valid: Boolean get() = error == null
}

/** The beds as typed, as a number when they are good, and what is wrong with them. */
data class BedsCheck(val typed: String, val beds: Int?, val error: String?) {
    val valid: Boolean get() = error == null
}

/**
 * The checks of the form of a room, in one place so that «Nuova camera» and «Modifica la camera» say the same words (criteria 2, 3). One error per field: the first
 * that applies. A name that is already used by another room of the gathering is not checked here: it is the database that knows (`Rooms`).
 *
 * A room is the house and not who sleeps in it. [describesPeople] is a net, like `PublicText` of the gathering: it finds the words that say the sex of the people («Maschi»,
 * «camera delle femmine», «Bambine»), because the app never asks for the sex and a name of a room must not be the way to write it down. It does not find the name of a
 * person («Camera di Paolo»): the help under the field says to use the name of the house. The words are one list, here, with its unit test.
 */
object RoomChecks {

    const val MAX_NAME = 50
    const val MIN_BEDS = 1
    const val MAX_BEDS = 50

    const val NAME_MISSING = "Scrivi il nome della camera."
    const val NAME_TOO_LONG = "Usa al massimo $MAX_NAME caratteri."
    const val NAME_TAKEN = "C’è già una camera con questo nome."
    const val NAME_DESCRIBES_PEOPLE = "Scrivi il nome della camera nella casa, non chi ci dorme."
    const val BEDS_WRONG = "Scrivi quanti posti ci sono, da $MIN_BEDS a $MAX_BEDS."

    /**
     * The words that say who sleeps in a room by their sex, as they are written without accents and in lowercase. A word is found when it is equal to one of them, or, for the
     * ones that end with a star, when it starts with them («maschi*» is maschio, maschi, maschile, maschietti; «femmin*» is femmina, femmine, femminile, femminucce).
     * The plural «bambini» and «ragazzi» are not here on purpose: they are also how Italian says children, and a room called «Bambini piccoli» says an age.
     */
    internal val SEX_WORDS: List<String> = listOf(
        "maschi*", "femmin*", "bambina", "bambine", "bambino", "ragazza", "ragazze", "ragazzo", "uomo", "uomini", "donna", "donne", "signorina", "signorine",
        "boy", "boys", "girl", "girls", "male", "males", "female", "females",
    )

    private val controls = Regex("""[\p{Cntrl}]+""")

    private val notLetters = Regex("""[^\p{L}]+""")

    private fun length(text: String): Int = text.codePointCount(0, text.length)

    /** A text of one line: line breaks and control characters become a space, and the spaces at the sides are taken away. */
    private fun oneLine(raw: String?): String = raw.orEmpty().replace(controls, " ").replace(Regex("""\s+"""), " ").trim()

    /** The words of a text without accents and capitals. */
    private fun words(text: String): List<String> =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "").lowercase().split(notLetters).filter { it.isNotEmpty() }

    /** The name says who sleeps in the room (the sex of the people): refused. */
    fun describesPeople(name: String): Boolean = words(name).any { word ->
        SEX_WORDS.any { if (it.endsWith("*")) word.startsWith(it.dropLast(1)) else word == it }
    }

    fun name(raw: String?): NameCheck {
        val value = oneLine(raw)
        val error = when {
            value.isEmpty() -> NAME_MISSING
            length(value) > MAX_NAME -> NAME_TOO_LONG
            describesPeople(value) -> NAME_DESCRIBES_PEOPLE
            else -> null
        }
        return NameCheck(value, error)
    }

    private val whole = Regex("""\d{1,9}""")

    /** The beds: a whole number from 1 to 50. Empty, a word, a negative number, a decimal or more than 50 are refused with the same sentence. */
    fun beds(raw: String?): BedsCheck {
        val typed = raw.orEmpty().trim()
        val number = if (whole.matches(typed)) typed.toInt() else null
        return if (number != null && number in MIN_BEDS..MAX_BEDS) BedsCheck(typed, number, null) else BedsCheck(typed, null, BEDS_WRONG)
    }
}
