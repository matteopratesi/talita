package talita.retention

import org.springframework.stereotype.Component
import talita.gathering.GatheringFormat
import java.time.LocalDate
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

    /**
     * When who sleeps in which room goes (TALITA-11): «alla fine della convivenza» with the policy of the product, which has a period of zero (Matteo's decision: the rooms
     * are tied to the house); with a period, «un mese dopo la fine della convivenza» as the other texts. The words follow the policy and nothing is written by hand.
     */
    val roomsWhen: String get() = if (policy.rooms.isZero) "alla fine della convivenza" else "${words(policy.rooms)} dopo la fine della convivenza"

    /** How long a parent stays inside on a phone where they touched «Ricordami su questo telefono» (`30 giorni`), and the same for a teacher (`7 giorni`, and `7` alone for «7 se sei tra i maestri»). */
    val devicesParent: String get() = words(policy.devicesParent)
    val devicesTeacher: String get() = words(policy.devicesTeacher)
    val devicesTeacherNumber: String get() = policy.devicesTeacher.days.toString()

    val registrations: String get() = words(policy.registrations)
    val invitations: String get() = words(policy.invitations)
    val emptyAccounts: String get() = words(policy.emptyAccounts)
    val backups: String get() = words(policy.backups)

    // ---- What the pages of the teachers say when data are gone (design part 3) -------------------------------------------------------

    /** What takes the place of the button «Mostra le segnalazioni» from the day of their date (criterion 30): the same for every registration, it does not say whether there were any. */
    fun notesDeleted(lastDay: LocalDate): NotesDeleted = NotesDeleted(
        "Segnalazioni cancellate il ${GatheringFormat.date(policy.notesDeletionDate(lastDay))}, $notes dopo la fine.",
        "Talità le cancella da sola, per tutte le iscrizioni. Il registro delle letture resta fino al ${GatheringFormat.date(policy.registrationsDeletionDate(lastDay))}.",
    )

    /**
     * Why a registration stays, in the words of the dialogs and the notices of the profile and of the card in «Iscrizioni», so that they never say two things for the same
     * registration: a withdrawn one is the register of the gathering ([KeepReason.REGISTER]), the others are the proof of the custody ([KeepReason.PROOF]).
     */
    fun keptAs(reason: KeepReason): String = if (reason == KeepReason.REGISTER) "come registro della convivenza" else "come prova dell’affidamento"

    /**
     * What «Camere» says once who slept where is deleted (TALITA-11, criterion 20): when and why, and that the rooms stay for the copy of the year after. The first sentence
     * is the notice, the second says what is left.
     */
    fun roomsDeleted(lastDay: LocalDate): RoomsDeleted = RoomsDeleted(
        "Chi dormiva dove è stato cancellato il ${GatheringFormat.date(policy.roomsDeletionDate(lastDay))}, $roomsWhen.",
        "Le camere restano, senza nomi: le puoi copiare nella convivenza dell’anno dopo.",
    )

    /** The line of the card «Camera» of a child in Arrivi once who slept where is deleted. */
    fun roomsDeletedOn(lastDay: LocalDate): String = "Chi dormiva dove è stato cancellato il ${GatheringFormat.date(policy.roomsDeletionDate(lastDay))}."

    private fun when_(lastDay: LocalDate): String = GatheringFormat.date(policy.registrationsDeletionDate(lastDay))

    private val untilNothing = "Restano le date e le informazioni della convivenza, senza nomi."

    /** The empty state of «Iscrizioni» of a gathering whose registrations are gone (criterion 31): when and why. */
    fun registrationsDeleted(gatheringId: Long, lastDay: LocalDate): DeletedState = DeletedState(
        "Iscrizioni cancellate",
        "Le iscrizioni di questa convivenza, con le manleve, sono state cancellate il ${when_(lastDay)}, $registrations dopo la fine: servivano come prova dell’affidamento.",
        untilNothing, "/convivenza/$gatheringId", "Vai alla convivenza",
    )

    /** The empty state of «Arrivi» (and of the prints) of a gathering whose registrations are gone. */
    fun arrivalsDeleted(gatheringId: Long, lastDay: LocalDate): DeletedState = DeletedState(
        "Arrivi cancellati",
        "Gli arrivi, le presenze e i pagamenti di questa convivenza sono stati cancellati il ${when_(lastDay)}, $registrations dopo la fine: servivano come registro e come prova dell’affidamento.",
        untilNothing, "/convivenza/$gatheringId", "Vai alla convivenza",
    )

    // ---- The complete notice, `/informativa` (design part 4, 1) -----------------------------------------------------------------

    /**
     * What the card «Per quanto tempo» says for each datum: the datum and the time, in the order of the notice. [passkeys] false is a site that has no passkeys (no `talita.public-url`): the row
     * about them is not there, as the pages do not speak of them.
     */
    fun completeRows(passkeys: Boolean = true): List<TimeRow> = listOf(
        TimeRow("Segnalazioni su allergie, intolleranze e farmaci", "${capital(notes)} dopo la fine della convivenza. Le puoi togliere prima, quando vuoi."),
        TimeRow(
            "Chi dorme in quale camera",
            "${capital(roomsWhen)}. Le camere restano, senza nomi, per la convivenza dell’anno dopo.",
        ),
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
        TimeRow("La richiesta di avviso e lo stop ai promemoria", "Finché non li cambi o non cancelli l’account."),
        TimeRow(
            "Dispositivi collegati («Ricordami su questo telefono»)",
            "$devicesParent dal giorno in cui spunti la casella, $devicesTeacherNumber per i maestri. Prima, se esci, li togli dal tuo profilo o cancelli l’account. " +
                "Talità li cancella da sola, ogni notte, quando scadono.",
        ),
        TimeRow("Passkey", "Finché non le togli dal tuo profilo o non cancelli l’account. Se l’account si cancella da solo perché è vuoto, si cancellano con lui."),
        TimeRow(
            "Dati dei maestri",
            "Nome, cognome e indirizzo email finché sono tra i maestri o finché non cancellano l’account. Un invito non accettato si cancella $invitations dopo la scadenza.",
        ),
        TimeRow("Copie di sicurezza", "Al massimo $backups, cifrate. Un dato cancellato sparisce anche dalle copie entro questo tempo."),
        TimeRow(
            "Moduli e registro di carta",
            "Le manleve firmate e il registro stampato si distruggono dopo $registrations dalla fine della convivenza; il foglio con i recapiti per i maestri, alla fine della convivenza.",
        ),
    ).filter { passkeys || it.what != "Passkey" }

    /** The introduction of the card «Per quanto tempo». */
    val completeIntro: String = "Talità cancella i dati da sola, ogni notte, quando il loro tempo è scaduto."

    /** The three paragraphs of the card «I tuoi diritti» of the complete notice. */
    val rights: List<String> get() = rightsFor(true)

    /** The same for a site with or without passkeys: the sentence about taking them away from the profile is only there if there are any. */
    fun rightsFor(passkeys: Boolean): List<String> = listOf(
        "Puoi chiedere di vedere, correggere, cancellare o limitare l’uso dei dati, riceverne una copia, opporti e togliere il consenso quando vuoi.",
        "Dalla pagina «Iscrizioni» correggi l’iscrizione finché le iscrizioni sono aperte e togli le segnalazioni con un tocco. I promemoria li fermi con un tocco dalla mail o dal profilo, e la richiesta di avviso la togli quando vuoi. " +
            "Dal «Profilo» cancelli i dati dei tuoi figli o " +
            "il tuo account: resta solo l’iscrizione con l’affidamento accettato, fino alla data scritta, perché serve come prova dell’affidamento. Sempre dal «Profilo» togli i dispositivi collegati${if (passkeys) " e le passkey" else ""}.",
        "Per il resto scrivi al titolare, anche se non hai un account: risponde entro un mese.",
    )

    // ---- The short notice, at step 2 of the registration (design part 4, 1) --------------------------------------------------------

    /** «Per quanto tempo» of the short notice. */
    fun briefTime(): String =
        "Le segnalazioni le cancelliamo $notes dopo la fine della convivenza. L’iscrizione, l’accettazione, le presenze segnate e i pagamenti si conservano per $registrations dopo la fine della convivenza, poi si cancellano. " +
            "I dati dei bambini restano finché c’è un’iscrizione, e li puoi cancellare quando vuoi dal tuo profilo. Un account senza bambini né iscrizioni si cancella da solo dopo " +
            "$emptyAccounts, gli inviti ai maestri non accettati $invitations dopo la scadenza e le copie di sicurezza durano al massimo $backups. La richiesta di avviso, finché non la togli."

    /** «I tuoi diritti» of the short notice. */
    val briefRights: String =
        "Puoi vedere, correggere o cancellare i dati e togliere il consenso quando vuoi, dalle pagine «Iscrizioni» e «Profilo» o scrivendo al titolare. I promemoria li fermi con un tocco dalla mail o dal profilo. " +
            "Se pensi che i dati non siano trattati bene, puoi rivolgerti al Garante per la protezione dei dati personali."
}

/** A row of the card «Per quanto tempo»: what, and for how long. */
data class TimeRow(val what: String, val time: String)

/** The two sentences of the card «Segnalazioni» when the notes are gone: the first in bold, the second smaller. */
data class NotesDeleted(val main: String, val second: String)

/** The two sentences of «Camere» when who slept where is deleted: the first in the notice, the second says what is left. */
data class RoomsDeleted(val main: String, val second: String)

/** A page that says that some data are gone, when and why: the title, two paragraphs, and the link to the gathering. */
data class DeletedState(val title: String, val text: String, val text2: String, val href: String, val label: String)
