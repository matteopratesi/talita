package talita.retention

import org.springframework.stereotype.Component
import java.time.Period

/**
 * **The words for the times of the retention**, from the periods of [RetentionPolicy] and from nothing else (criterion 32): the short notice at step 2 of the
 * registration, the complete notice at `/informativa`, the dialogs of the profile and the card «Conservazione dei dati» take every number from here, so a number
 * that changes in the policy changes in all of them. A test compares the texts with the constants, and a second one builds a policy with other periods and checks
 * that every text follows it.
 *
 * Italian, singular and plural right (`P1M` is «un mese», `P2M` «2 mesi», `P1Y` «un anno», `P30D` «30 giorni»), the guide's rule for numbers.
 */
@Component
class RetentionTexts(private val policy: RetentionPolicy) {

    /** The period in words: `un mese`, `2 mesi`, `un anno`, `30 giorni`, `un anno e 6 mesi`. A period of zero is «0 giorni»: never used by a policy that makes sense. */
    fun words(period: Period): String {
        val parts = buildList {
            if (period.years != 0) add(count(period.years, "un anno", "anni"))
            if (period.months != 0) add(count(period.months, "un mese", "mesi"))
            if (period.days != 0) add(count(period.days, "un giorno", "giorni"))
        }.ifEmpty { listOf("0 giorni") }
        return if (parts.size == 1) parts.single() else parts.dropLast(1).joinToString(", ") + " e " + parts.last()
    }

    private fun count(amount: Int, one: String, many: String): String = if (amount == 1) one else "$amount $many"

    /** The first letter in capital: for the start of a sentence. */
    fun capital(text: String): String = text.replaceFirstChar { it.uppercase() }

    val notes: String get() = words(policy.notes)
    val registrations: String get() = words(policy.registrations)
    val invitations: String get() = words(policy.invitations)
    val emptyAccounts: String get() = words(policy.emptyAccounts)
    val backups: String get() = words(policy.backups)

    // ---- The complete notice, `/informativa` (design part 4, 1) -----------------------------------------------------------------

    /** What the card «Per quanto tempo» says for each datum: the datum and the time, in the order of the notice. */
    fun completeRows(): List<TimeRow> = listOf(
        TimeRow("Segnalazioni su allergie, intolleranze e farmaci", "${capital(notes)} dopo la fine della convivenza. Le puoi togliere prima, quando vuoi."),
        TimeRow(
            "Iscrizione, accettazione dell’affidamento, presenze, pagamenti e registro delle letture",
            "${capital(registrations)} dopo la fine della convivenza: servono come prova dell’affidamento.",
        ),
        TimeRow(
            "Nome, cognome e data di nascita dei bambini",
            "Finché c’è un’iscrizione del bambino, cioè fino a $registrations dopo la fine della sua ultima convivenza: così non li riscrivi l’anno dopo. " +
                "Li puoi cancellare quando vuoi dal tuo profilo; resta solo l’iscrizione che serve come prova.",
        ),
        TimeRow(
            "Il tuo indirizzo email",
            "Finché ci sono bambini o iscrizioni collegati al tuo account. Quando non ce ne sono più, l’account si cancella da solo, purché siano passati $emptyAccounts da quando " +
                "l’hai creato. Lo puoi cancellare quando vuoi dal tuo profilo.",
        ),
        TimeRow(
            "Dati dei maestri",
            "Nome, cognome e indirizzo email finché sono tra i maestri o finché non cancellano l’account. Un invito non accettato si cancella $invitations dopo la scadenza.",
        ),
        TimeRow("Copie di sicurezza", "Al massimo $backups, cifrate. Un dato cancellato sparisce anche dalle copie entro questo tempo."),
        TimeRow(
            "Moduli e registro di carta",
            "Le manleve firmate e il registro stampato si distruggono dopo $registrations dalla fine della convivenza; il foglio con i recapiti per i maestri, alla fine della convivenza.",
        ),
    )

    /** The introduction of the card «Per quanto tempo». */
    val completeIntro: String = "Talità cancella i dati da sola, ogni notte, quando il loro tempo è scaduto."

    /** The three paragraphs of the card «I tuoi diritti» of the complete notice. */
    val rights: List<String> = listOf(
        "Puoi chiedere di vedere, correggere, cancellare o limitare l’uso dei dati, riceverne una copia, opporti e togliere il consenso quando vuoi.",
        "Dalla pagina «Iscrizioni» correggi l’iscrizione finché le iscrizioni sono aperte e togli le segnalazioni con un tocco. Dal «Profilo» cancelli i dati dei tuoi figli o " +
            "il tuo account: resta solo l’iscrizione con l’affidamento accettato, fino alla data scritta, perché serve come prova dell’affidamento.",
        "Per il resto scrivi al titolare, anche se non hai un account: risponde entro un mese.",
    )

    // ---- The short notice, at step 2 of the registration (design part 4, 1) --------------------------------------------------------

    /** «Per quanto tempo» of the short notice. */
    fun briefTime(): String =
        "Le segnalazioni le cancelliamo $notes dopo la fine della convivenza. L’iscrizione e l’accettazione si conservano per $registrations, poi si cancellano. " +
            "I dati dei bambini restano finché c’è un’iscrizione, e li puoi cancellare quando vuoi dal tuo profilo. Un account senza bambini né iscrizioni si cancella da solo dopo " +
            "$emptyAccounts, gli inviti ai maestri non accettati $invitations dopo la scadenza e le copie di sicurezza durano al massimo $backups."

    /** «I tuoi diritti» of the short notice. */
    val briefRights: String =
        "Puoi vedere, correggere o cancellare i dati e togliere il consenso quando vuoi, dalle pagine «Iscrizioni» e «Profilo» o scrivendo al titolare. " +
            "Se pensi che i dati non siano trattati bene, puoi rivolgerti al Garante per la protezione dei dati personali."
}

/** A row of the card «Per quanto tempo»: what, and for how long. */
data class TimeRow(val what: String, val time: String)
