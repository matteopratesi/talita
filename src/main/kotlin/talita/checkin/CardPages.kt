package talita.checkin

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import talita.gathering.Badge
import talita.gathering.GatheringFormat
import talita.registration.DayKind
import talita.registration.NotesRead
import talita.registration.RegistrationData
import talita.registration.RegistrationFormat
import talita.registration.Status
import talita.registration.TeacherPresenter
import talita.retention.NotesDeleted
import talita.retention.RetentionPolicy
import talita.retention.RetentionTexts
import talita.room.ChildRoomCard
import talita.room.RoomLookup
import talita.ui.AlertVariant
import talita.ui.ConfirmModel
import java.io.Serializable
import java.time.LocalDate
import java.util.UUID

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

/** The telephone of the parent as it was written, and its digits (with a «+» at the start, if there is one) for the link that calls. */
data class ContactView(val phone: String, val telephone: String)

/**
 * The card «Segnalazioni» (criteria 17, 18): closed, it is the same for every child (it does not say whether there is anything); open, it shows who read and when,
 * what is written, and the consent. It is open only in the answer to the touch that wrote the reading, and never after any other action.
 */
data class NotesView(val shown: Boolean, val readLine: String?, val rows: List<Pair<String, String>>, val consent: String?, val deleted: NotesDeleted? = null)

/** A payment of the card: the amount, who registered it and when, and the dialog that takes it away (criterion 13). */
data class PaymentRow(val id: Long, val amount: String, val meta: String, val removeHref: String, val removeLabel: String, val dialog: ConfirmModel)

/** The form of a payment: the code of the request, the amount written in the field (what is still to pay, or what was typed), the help and the error under the field. */
data class PaymentForm(val request: String, val value: String, val hint: String, val error: String?)

/** What the card says of the money (criteria 10-13): what is due, what was paid, the balance in words, the payments and the form. */
data class MoneyView(val due: String, val paid: String, val balance: Badge, val payments: List<PaymentRow>, val form: PaymentForm)

/** What was typed in the form of a payment, when the answer is the form again with an error. */
data class PaymentTyped(val value: String, val error: String)

/**
 * The card of a child (criteria 5-18; design part 2): everything about one registration in one block, `#scheda`, which every action of the card answers with
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
    val contact: ContactView,
    val notes: NotesView,
    val money: MoneyView,
    val custody: CustodyView,
    val registration: RegistrationView,
    val siblings: List<SiblingView>,
    /** The card «Camera» (TALITA-11): null for a registration that was withdrawn, which has no room. */
    val room: ChildRoomCard? = null,
)

/** Builds the card of a child from what [ArrivalsData] read: the sentences are those of the design (part 2, tables 2 and 3). */
@Component
class CardPresenter(
    private val arrivals: ArrivalsPresenter,
    private val teacher: TeacherPresenter,
    private val payments: Payments,
    private val policy: RetentionPolicy,
    private val texts: RetentionTexts,
    private val rooms: RoomLookup,
) {

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

    /** From the day of their date the card says that the notes were deleted, the same for every child, with no button: nothing can be read, so no reading is written (criterion 30). */
    private fun notes(read: NotesRead?, reader: String, deleted: NotesDeleted?): NotesView {
        if (deleted != null) return NotesView(shown = false, readLine = null, rows = emptyList(), consent = null, deleted = deleted)
        if (read == null) return NotesView(shown = false, readLine = null, rows = emptyList(), consent = null)
        val note = read.note
        val rows = buildList {
            note?.notes?.allergies?.let { add("Allergie" to it) }
            note?.notes?.intolerances?.let { add("Intolleranze" to it) }
            note?.notes?.medications?.let { add("Farmaci" to it) }
        }
        val consent = note?.let { if (it.onPaper) "Il genitore ha dato il consenso sul modulo cartaceo." else "Consenso dato dal genitore il ${RegistrationFormat.moment(it.consentAt)}." }
        return NotesView(shown = true, readLine = "Lettura registrata: $reader, ${CheckinFormat.momentWithYear(read.at)}.", rows = rows, consent = consent)
    }

    private fun money(data: ArrivalsData, registration: RegistrationData, choice: DayChoice, typed: PaymentTyped?): MoneyView {
        val withdrawn = data.withdrawn(registration)
        val due = data.due(registration)
        val paid = data.paid(registration)
        val balance = data.balance(registration)
        val rows = payments.ofRegistration(registration.id).map { payment ->
            val amount = RegistrationFormat.money(payment.cents)
            val by = arrivals.teacherName(payment.byAccountId)
            val id = "togli-pagamento-${payment.id}"
            PaymentRow(
                id = payment.id,
                amount = amount,
                meta = "$by · ${CheckinFormat.moment(payment.at)}",
                removeHref = "/arrivi/${registration.id}/pagamenti/${payment.id}/togli?giorno=${choice.selected}",
                removeLabel = "Togli il pagamento di $amount",
                dialog = removeDialog(registration, payment, choice, id, amount, by),
            )
        }
        val missing = (balance as? Balance.ToPay)?.cents
        val form = PaymentForm(
            request = UUID.randomUUID().toString(),
            value = typed?.value ?: missing?.let(GatheringFormat::amountField).orEmpty(),
            hint = if (typed == null && missing != null) "Abbiamo scritto quanto manca: cambia l’importo se hai ricevuto una cifra diversa." else "Scrivi quanto hai ricevuto, per esempio 25 o 12,50.",
            error = typed?.error,
        )
        return MoneyView(
            due = if (withdrawn) "Non dovuta" else due?.let(RegistrationFormat::money) ?: "Da definire",
            paid = if (paid == 0) "Niente" else RegistrationFormat.money(paid),
            balance = balanceBadge(balance) ?: Badge("Quota da definire", "neutral"),
            payments = rows,
            form = form,
        )
    }

    /** The dialog that takes a payment away (design part 2, «Dialogo Togli»): what goes, from whom, and what changes. The same words are the page without JavaScript. */
    fun removeDialog(registration: RegistrationData, payment: Payment, choice: DayChoice, id: String, amount: String, by: String): ConfirmModel = ConfirmModel(
        id = id,
        title = "Togliere il pagamento di $amount?",
        text = "Il pagamento di $amount registrato da $by il ${CheckinFormat.moment(payment.at)} sparisce da ${registration.name}. Il saldo e il totale incassato cambiano. Per rimetterlo lo devi registrare di nuovo.",
        confirmLabel = "Togli il pagamento",
        busyLabel = "Tolgo…",
        action = "/arrivi/${registration.id}/pagamenti/${payment.id}/togli",
        field = "giorno",
        value = choice.selected.toString(),
        target = "#scheda",
        cancelHref = "/arrivi/${registration.id}?giorno=${choice.selected}",
        danger = true,
    )

    /**
     * The card. [read] is the notes of the touch that wrote their reading (null for any other answer: they are closed and nobody is told whether there are any);
     * [reader] is who read. [typed] is what was typed in the form of the payment when the answer is the form again with an error.
     */
    fun page(data: ArrivalsData, registration: RegistrationData, choice: DayChoice, flash: CardAlert?, read: NotesRead? = null, reader: String = "", typed: PaymentTyped? = null): CardPage {
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
            contact = ContactView(registration.phone, CheckinFormat.dialable(registration.phone)),
            notes = notes(read, reader, if (policy.notesExpired(data.gathering.end, choice.today)) texts.notesDeleted(data.gathering.end) else null),
            money = money(data, registration, choice, typed),
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
            room = if (withdrawn) null else rooms.cardOf(data.gathering, registration),
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
