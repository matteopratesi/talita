package talita.checkin

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import talita.gathering.Badge
import talita.gathering.GatheringFormat
import talita.registration.DayKind
import talita.registration.RegistrationData
import talita.registration.RegistrationFormat
import talita.registration.Status
import talita.registration.TeacherPresenter
import talita.ui.AlertVariant
import java.io.Serializable
import java.time.LocalDate

/**
 * What the last action says, at the top of the card of the child: a confirmation, a refusal, or something that was done already. [lead] is the first words in
 * bold; the action is a link (the correction of the registration). It is announced by the screen reader (the alert is `live`, see `components/alert.jte`).
 */
data class CardAlert(
    val variant: AlertVariant,
    val lead: String?,
    val text: String,
    val actionLabel: String? = null,
    val actionHref: String? = null,
) : Serializable

/**
 * What the card says after a request that was not made by htmx (a form of a page without JavaScript): it waits in the session, in memory, for the next page and
 * is read once, like the notices of the other pages (`talita.ui.Flash`, which carries only a sentence). It holds a sentence and a link, never a name or an amount
 * beyond what the sentence already says to the person who made the request.
 */
object CardFlash {

    private const val KEY = "talita.checkin.flash"

    fun put(request: HttpServletRequest, alert: CardAlert) {
        request.getSession(true).setAttribute(KEY, alert)
    }

    fun take(request: HttpServletRequest): CardAlert? {
        val session = request.getSession(false) ?: return null
        val alert = session.getAttribute(KEY) as? CardAlert
        if (alert != null) session.removeAttribute(KEY)
        return alert
    }
}

/** Another registration of the same child, for «Apri l’altra scheda». */
data class OtherCard(val href: String, val ariaLabel: String)

/** What the card of a day offers: mark the presence, take it away, nothing yet (the day has not come), or nothing at all (a withdrawn registration). */
enum class DayAction { MARK, UNMARK, LATER, NONE }

/** A day of the gathering in the card «Giorni» (criteria 5, 6; design 2, 3). */
data class CardDay(
    /** `Sabato 18`. */
    val label: String,
    val today: Boolean,
    /** `Giornata`, `Solo pranzo` or `Non previsto`. */
    val plan: String,
    val notExpected: Boolean,
    val action: DayAction,
    /** The day that the button is about is the one that was chosen in the list: it is the main button; the others are secondary. */
    val primary: Boolean,
    /** `2027-09-18`, as the forms send it. */
    val day: String,
    val markLabel: String,
    val unmarkLabel: String,
    /** `Segnata da Paolo Verdi alle 9:12`, when there is a presence. */
    val presentBy: String?,
    /** What a day that cannot be marked yet says. */
    val later: String?,
)

/** The card «Manleva»: the label, the sentence, and whether the signed form can be marked as arrived. */
data class CustodyView(val badge: Badge, val text: String, val canReceive: Boolean)

/** The card «Iscrizione». */
data class RegistrationView(val status: Badge, val age: String, val openHref: String)

/** A child of the same parent, as a row of the card «Altri figli dello stesso genitore». */
data class SiblingView(val href: String, val name: String, val labels: List<Badge>)

/**
 * The card of a child (criteria 5-9; design part 2): everything about one registration in one block, `#scheda`, which every action of the card answers with
 * whole (the days, the labels under the title, the notices): so nothing is left old, and nothing that is health is ever left open.
 */
data class CardPage(
    val id: Long,
    val name: String,
    val lead: String,
    val backHref: String,
    /** The day of the address, for the forms (`2027-09-18`). */
    val day: String,
    val badges: List<Badge>,
    val flash: CardAlert?,
    val maybe: Boolean,
    val withdrawn: Boolean,
    val duplicates: List<OtherCard>,
    val phaseNotice: String?,
    val days: List<CardDay>,
    val custody: CustodyView,
    val registration: RegistrationView,
    val siblings: List<SiblingView>,
)

/** Builds the card of a child from what [ArrivalsData] read: the sentences are those of the design (part 2, tables 2 and 3). */
@Component
class CardPresenter(private val arrivals: ArrivalsPresenter, private val teacher: TeacherPresenter) {

    private fun days(data: ArrivalsData, registration: RegistrationData, choice: DayChoice): List<CardDay> {
        val withdrawn = data.withdrawn(registration)
        return data.gathering.days.map { day ->
            val kind = registration.presence.days[day]
            val plan = when {
                registration.presence.allDays -> "Giornata"
                kind == null -> "Non previsto"
                kind == DayKind.LUNCH -> "Solo pranzo"
                else -> "Giornata"
            }
            val presence = data.presence(registration, day)
            val after = day.isAfter(choice.today)
            val action = when {
                presence != null -> DayAction.UNMARK
                withdrawn -> DayAction.NONE
                after -> DayAction.LATER
                else -> DayAction.MARK
            }
            val on = "${registration.name}, ${CheckinFormat.longDay(day)}"
            CardDay(
                label = RegistrationFormat.dayLabel(day),
                today = day == choice.today,
                plan = plan,
                notExpected = !registration.includes(day),
                action = action,
                primary = day == choice.selected,
                day = day.toString(),
                markLabel = "Segna presente $on",
                unmarkLabel = "Togli la presenza di $on",
                presentBy = presence?.let { "Segnata da ${arrivals.teacherName(it.byAccountId)} ${whenMarked(it, day)}" },
                later = if (action == DayAction.LATER) {
                    if (choice.phase == Phase.BEFORE) "Si potrà segnare da ${CheckinFormat.longDay(data.gathering.days.first())}." else "Si potrà segnare da quel giorno."
                } else {
                    null
                },
            )
        }
    }

    /** `alle 9:12`, and with the day when the presence was marked on another day than the one it is for (the register of paper copied after the end). */
    fun whenMarked(presence: Checkin, day: LocalDate): String {
        val marked = CheckinFormat.dayOf(presence.at)
        return if (marked == day) "alle ${CheckinFormat.time(presence.at)}" else "il ${CheckinFormat.dayAndMonth(marked)} alle ${CheckinFormat.time(presence.at)}"
    }

    private fun custody(registration: RegistrationData): CustodyView = when {
        !registration.onPaper -> CustodyView(
            Badge("Accettata online", "positive"),
            "Accettata dal genitore il ${registration.custodyAcceptedAt?.let(RegistrationFormat::moment).orEmpty()}.",
            canReceive = false,
        )

        registration.paperFormReceived -> CustodyView(Badge("Cartacea ricevuta", "positive"), "Il modulo firmato è arrivato. Resta di carta, fuori da Talità.", canReceive = false)
        else -> CustodyView(Badge("Da ricevere", "warning"), "Il modulo firmato non è ancora arrivato.", canReceive = true)
    }

    private fun phaseNotice(choice: DayChoice): String? = when (choice.phase) {
        Phase.BEFORE -> "La convivenza non è ancora iniziata: le presenze si segnano da ${CheckinFormat.longDay(choice.days.first())}. I pagamenti si possono già registrare."
        Phase.AFTER -> "La convivenza è finita. Puoi ancora correggere presenze e pagamenti, per esempio quando trascrivi il registro di carta."
        Phase.DURING -> null
    }

    fun page(data: ArrivalsData, registration: RegistrationData, choice: DayChoice, flash: CardAlert?): CardPage {
        val withdrawn = data.withdrawn(registration)
        val duplicates = data.duplicates[registration.id].orEmpty().mapNotNull { other -> data.registrations.firstOrNull { it.id == other } }
        val siblings = data.registrations.filter { it.accountId != null && it.accountId == registration.accountId && it.id != registration.id }
        return CardPage(
            id = registration.id,
            name = registration.name,
            lead = "${data.gathering.title} · ${GatheringFormat.range(data.gathering.start, data.gathering.end)}",
            backHref = "/arrivi?giorno=${choice.selected}",
            day = choice.selected.toString(),
            badges = listOfNotNull(teacher.statusBadge(registration), teacher.manleva(registration), Badge("Possibile doppione", "warning").takeIf { duplicates.isNotEmpty() }),
            flash = flash,
            maybe = registration.status == Status.MAYBE,
            withdrawn = withdrawn,
            duplicates = duplicates.map { OtherCard(arrivals.hrefOf(it.id, choice.selected), "Apri l’altra scheda, iscrizione fatta il ${RegistrationFormat.day(it.createdAt)}") },
            phaseNotice = phaseNotice(choice),
            days = days(data, registration, choice),
            custody = custody(registration),
            registration = RegistrationView(
                status = teacher.statusBadge(registration),
                age = RegistrationFormat.ageText(registration.birthDate, data.gathering.start) + " il primo giorno",
                openHref = "/iscrizioni/${registration.id}",
            ),
            siblings = siblings.map { sibling ->
                val balance = data.balance(sibling)
                SiblingView(
                    arrivals.hrefOf(sibling.id, choice.selected), sibling.name,
                    listOf(teacher.statusBadge(sibling)) + (balanceBadge(balance)?.let { listOf(it) } ?: emptyList()),
                )
            },
        )
    }

    /** The label that says in words what is left to pay (design part 2, «Saldo»): the colour of the badge follows it. */
    fun balanceBadge(balance: Balance): Badge? = when (balance) {
        is Balance.ToPay -> Badge("Da pagare ${RegistrationFormat.money(balance.cents)}", "warning")
        Balance.Paid -> Badge("Pagata", "positive")
        is Balance.Over -> Badge("Pagato ${RegistrationFormat.money(balance.cents)} in più", "info")
        Balance.Free -> Badge("Gratuita", "neutral")
        Balance.NothingDue -> Badge("Niente da pagare", "neutral")
        Balance.Undefined -> Badge("Quota da definire", "neutral")
    }
}
