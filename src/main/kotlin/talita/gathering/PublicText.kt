package talita.gathering

/**
 * What a text that goes on the public page must not hold (criterion 6 of TALITA-4): an email address, a phone number, a bank account (IBAN) and, since TALITA-16, the first and last name
 * of a child or a teacher that the application knows. The page is read by whoever has the link, and a teacher writing «chiama Marco al 333…» by mistake would publish a
 * private number. It is a net, not a proof: a name written alone, a nickname or the name of someone who is not registered yet are not recognised, which is why the help under the field says
 * not to write them.
 *
 * - **email**: `@` with something before it and a domain after it;
 * - **phone**: nine digits or more in a row, also split by one space, dot or dash between two digits, or by a round bracket with
 *   a space next to it (`+39 (02) 12345678`, `(02) 1234 5678`, `+39(02)12345678`), with or without a leading `+`.
 *   `17/09/2027 alle 18:30`, `dalle 9.30` and `60 €` pass;
 * - **IBAN**: two letters, two digits and a long run of letters and digits (also in groups of four);
 * - **name** (TALITA-16): the database says it, in one query for all the texts of a form ([PublicNames]), and only yes or no: the names never reach the application. [check] does not
 *   know about it: the controller adds it with [Findings.copy] once it has the answer.
 *
 * The words of the help under the four fields are here too, so that the three forms and the test that reads them say the same thing.
 */
object PublicText {

    /** The last sentence of the help under each of the four texts: what the check refuses, in the words of the check (the name and the surname, not «names» in general). */
    const val RULE = "Non scrivere nome e cognome di bambini o maestri, numeri di telefono né email."

    /** The help under «Titolo» («Nuova convivenza» and «Modifica titolo, date e luogo»): where the title appears, and the rule. */
    const val TITLE_HELP = "Per esempio «Convivenza di settembre 2027». Compare nella pagina per i genitori, nell’anteprima del link e nei promemoria. $RULE"

    /** The help under «Luogo». «Serve per aprire le iscrizioni» is not here: the detail of the gathering says it. */
    const val PLACE_HELP = "Il nome della casa o della struttura. Compare nella pagina per i genitori e nell’anteprima del link. $RULE"

    /** The help under «Informazioni per i genitori». */
    const val INFO_HELP = "Chi può partecipare, gli orari, come si arriva, l’aiuto per i trasporti. Compare nella pagina per i genitori. $RULE"

    /** The help under «Cosa portare». */
    const val BRING_HELP = "Scrivi una cosa per riga: nella pagina per i genitori diventa un elenco. $RULE"

    /**
     * What was found in a text. [name] is the first and the last name of a person that the application knows: [check] leaves it `false`, because only the database can say it
     * ([PublicNames]), and the controller sets it with `copy(name = true)`.
     */
    data class Findings(val email: Boolean, val phone: Boolean, val iban: Boolean, val name: Boolean = false) {
        val any: Boolean get() = email || phone || iban || name

        /**
         * The error under the field, or null when there is nothing to take away. One finding: «Qui c’è un indirizzo email. Toglilo: …»;
         * two or three: «Qui ci sono un indirizzo email e un numero di telefono. Toglili: …». With the name, always plural, and the name first:
         * «Qui ci sono nome e cognome di una persona e un numero di telefono. Toglili: …». It never says whose name it is, nor repeats it.
         */
        fun message(): String? {
            val found = listOfNotNull(
                "nome e cognome di una persona".takeIf { name },
                "un indirizzo email".takeIf { email },
                "un numero di telefono".takeIf { phone },
                "un IBAN".takeIf { iban },
            )
            val list = when (found.size) {
                0 -> return null
                1 -> found.single()
                else -> found.dropLast(1).joinToString(", ") + " e " + found.last()
            }
            return if (found.size == 1 && !name) {
                "Qui c’è $list. Toglilo: la pagina la legge chiunque abbia il link."
            } else {
                "Qui ci sono $list. Toglili: la pagina la legge chiunque abbia il link."
            }
        }
    }

    private val email = Regex("""[\p{L}\p{N}._%+\-]+@[\p{L}\p{N}\-]+(?:\.[\p{L}\p{N}\-]+)+""")

    private val iban = Regex("""(?<![\p{L}\p{N}])[A-Za-z]{2}\d{2}(?:[ \-]?[A-Za-z0-9]{4}){3,7}(?:[ \-]?[A-Za-z0-9]{1,3})?(?![\p{L}\p{N}])""")

    /** A run of digits, with at most one space, dot, dash or round bracket (a space may be next to it) between two of them. */
    private val digits = Regex("""\d(?:(?:[ .\-]|[ ]?[()][ ]?)?\d)*""")

    private const val PHONE_DIGITS = 9

    fun check(text: String): Findings {
        val withoutIban = iban.replace(text, " ")
        val hasPhone = digits.findAll(withoutIban).any { run -> run.value.count { it.isDigit() } >= PHONE_DIGITS }
        return Findings(email = email.containsMatchIn(text), phone = hasPhone, iban = iban.containsMatchIn(text))
    }
}
