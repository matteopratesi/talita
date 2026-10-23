package talita.gathering

/**
 * What a text that goes on the public page must not hold (criterion 6): an email address, a phone number, a bank account (IBAN).
 * The page is read by whoever has the link, and a teacher writing «chiama Marco al 333…» by mistake would publish a
 * private number. It is a net, not a proof: a name in the free text is not recognised, which is why the help under the field says
 * not to write them.
 *
 * - **email**: `@` with something before it and a domain after it;
 * - **phone**: nine digits or more in a row, also split by one space, dot or dash between two digits, with or without a leading `+`.
 *   `17/09/2027 alle 18:30`, `dalle 9.30` and `60 €` pass;
 * - **IBAN**: two letters, two digits and a long run of letters and digits (also in groups of four).
 */
object PublicText {

    /** What was found in a text. */
    data class Findings(val email: Boolean, val phone: Boolean, val iban: Boolean) {
        val any: Boolean get() = email || phone || iban

        /**
         * The error under the field, or null when there is nothing to take away. One finding: «Qui c’è un indirizzo email. Toglilo: …»;
         * two or three: «Qui ci sono un indirizzo email e un numero di telefono. Toglili: …».
         */
        fun message(): String? {
            val found = listOfNotNull(
                "un indirizzo email".takeIf { email },
                "un numero di telefono".takeIf { phone },
                "un IBAN".takeIf { iban },
            )
            val list = when (found.size) {
                0 -> return null
                1 -> found.single()
                else -> found.dropLast(1).joinToString(", ") + " e " + found.last()
            }
            return if (found.size == 1) {
                "Qui c’è $list. Toglilo: la pagina la legge chiunque abbia il link."
            } else {
                "Qui ci sono $list. Toglili: la pagina la legge chiunque abbia il link."
            }
        }
    }

    private val email = Regex("""[\p{L}\p{N}._%+\-]+@[\p{L}\p{N}\-]+(?:\.[\p{L}\p{N}\-]+)+""")

    private val iban = Regex("""(?<![\p{L}\p{N}])[A-Za-z]{2}\d{2}(?:[ \-]?[A-Za-z0-9]{4}){3,7}(?:[ \-]?[A-Za-z0-9]{1,3})?(?![\p{L}\p{N}])""")

    /** A run of digits, with at most one space, dot or dash between two of them. */
    private val digits = Regex("""\d(?:[ .\-]?\d)*""")

    private const val PHONE_DIGITS = 9

    fun check(text: String): Findings {
        val withoutIban = iban.replace(text, " ")
        val hasPhone = digits.findAll(withoutIban).any { run -> run.value.count { it.isDigit() } >= PHONE_DIGITS }
        return Findings(email = email.containsMatchIn(text), phone = hasPhone, iban = iban.containsMatchIn(text))
    }
}
