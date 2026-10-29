package talita.checkin

import org.springframework.stereotype.Component
import talita.ClockConfiguration
import talita.gathering.Badge
import talita.gathering.GatheringFormat
import talita.gathering.GatheringInfo
import talita.gathering.MenuLink
import talita.gathering.MenuPresenter
import talita.registration.RegistrationData
import talita.registration.RegistrationFormat
import talita.registration.Registrations
import talita.registration.Status
import talita.registration.TeacherPresenter
import talita.retention.DeletedState
import talita.retention.RetentionTexts
import talita.ui.Alert
import talita.ui.AlertVariant
import talita.ui.Icon
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * How the arrivals say what they say, the same in the list, the card of the child and the prints (design guide, 6): the hours without a leading zero (`9:12`,
 * as people say them at the door), the days with their weekday, the money. Dates and amounts come from the formatters of the other tickets; nothing is written
 * by hand in a template.
 */
object CheckinFormat {

    private val locale: Locale = Locale.ITALIAN
    private val time = DateTimeFormatter.ofPattern("H:mm", locale).withZone(ClockConfiguration.ZONE)
    private val momentOfDay = DateTimeFormatter.ofPattern("d MMMM 'alle' H:mm", locale).withZone(ClockConfiguration.ZONE)
    private val momentWithYear = DateTimeFormatter.ofPattern("d MMMM uuuu 'alle' H:mm", locale).withZone(ClockConfiguration.ZONE)
    private val dayAndMonth = DateTimeFormatter.ofPattern("d MMMM", locale)
    private val dayOfMonth = DateTimeFormatter.ofPattern("d", locale)

    /** `9:12`. */
    fun time(at: OffsetDateTime): String = time.format(at)

    /** `17 settembre alle 18:40`: a payment. */
    fun moment(at: OffsetDateTime): String = momentOfDay.format(at)

    /** `18 settembre 2027 alle 10:05`: when the notes on health were read. */
    fun momentWithYear(at: OffsetDateTime): String = momentWithYear.format(at)

    /** The day of Rome of a moment. */
    fun dayOf(at: OffsetDateTime): LocalDate = at.atZoneSameInstant(ClockConfiguration.ZONE).toLocalDate()

    /** `18 settembre`. */
    fun dayAndMonth(day: LocalDate): String = dayAndMonth.format(day)

    /** `sabato 18 settembre`. */
    fun longDay(day: LocalDate): String = GatheringFormat.weekday(day) + " " + dayAndMonth.format(day)

    /** `Sabato 18 settembre`. */
    fun longDayCapital(day: LocalDate): String = RegistrationFormat.capital(longDay(day))

    /** `sabato 18`: in the title of a group. */
    fun shortDay(day: LocalDate): String = GatheringFormat.weekday(day) + " " + dayOfMonth.format(day)

    /** `Ven`: over the number of the day in the selector. */
    fun weekdayAbbreviation(day: LocalDate): String = RegistrationFormat.capital(GatheringFormat.weekday(day).take(3))

    /** `Venerdì`: what a screen reader says for the abbreviation. */
    fun weekdayName(day: LocalDate): String = RegistrationFormat.capital(GatheringFormat.weekday(day))

    fun dayNumber(day: LocalDate): String = dayOfMonth.format(day)

    /** `settembre`. */
    fun monthName(day: LocalDate): String = day.month.getDisplayName(TextStyle.FULL, locale)

    /** The number as the link that calls wants it: only the digits and a «+» at the start, if the number begins with one (`+39 333-123.4567` is `+393331234567`). */
    fun dialable(phone: String): String = (if (phone.trimStart().startsWith("+")) "+" else "") + phone.filter { it.isDigit() }

    /** `Marta Bianchi`, or the words for a person who is not among the teachers any more. */
    fun teacher(name: String?): String = name ?: "un maestro che non è più tra i maestri"

    /** An amount of money, with the non-breaking space before the euro sign; zero is «Niente» (the guide writes zero in words). */
    fun moneyOrNothing(cents: Int): String = if (cents == 0) "Niente" else RegistrationFormat.money(cents)
}

/** What a label of a row says: the words, the tone of the colour, and an icon when the label has one (a check for «Presente»). */
data class RowLabel(val text: String, val tone: String, val icon: Icon? = null)

/** How a row of the list behaves: a button to mark, a button to take away, or only a link to the card (a day that cannot be marked, a child who is not expected). */
enum class RowKind { MARK, UNMARK, LINK }

/** The line under a row after something happened that the person must know (another teacher was first, the registration was withdrawn). */
data class RowNote(val text: String, val warning: Boolean)

/**
 * A row of the list of the day (criteria 1, 4): the name, the labels in the order of the design, and what the button says to a screen reader. The id of the row is
 * the number of the registration, never a name.
 */
data class RowView(
    val id: Long,
    val href: String,
    val name: String,
    val labels: List<RowLabel>,
    val kind: RowKind,
    /** The day that the button is about, as it goes in the form (`2027-09-18`). */
    val day: String,
    /** What follows «Segna presente» and «Togli» for a screen reader: the child and the day. */
    val markFor: String,
    val unmarkFor: String,
    val present: Boolean,
    val note: RowNote?,
    /** The row comes in the answer to a touch: the new button takes the focus (`data-fuoco`). In the page that is drawn first no row has it: the focus goes to the title. */
    val focus: Boolean,
)

data class GroupView(val title: String, val count: Int, val note: String?, val rows: List<RowView>)

/** One day of the selector (criterion 1, design 3.1). */
data class DayLink(val href: String, val abbreviation: String, val number: String, val weekday: String, val month: String, val today: Boolean, val current: Boolean)

/** The notice about the day that is being looked at, with the link «Vai a oggi» when the day is not today. */
data class DayNotice(val alert: Alert, val actionLabel: String?, val actionHref: String?)

/** One of the four numbers of the summary: the number (or the word for zero), and what it is out of. */
data class StatView(val label: String, val value: String, val unit: String?, val word: Boolean)

/** A line of money of the summary: the label, the amount (or «Niente») and the line under it, if any. */
data class MoneyLine(val label: String, val value: String, val under: String?)

/** The summary of the day (criterion 2). */
data class SummaryView(val title: String, val tag: Badge, val stats: List<StatView>, val collected: MoneyLine, val owed: MoneyLine)

/** What the page is: a list of the children, a gathering with no registration, a draft, or no gathering at all. */
enum class ArrivalsState { LIST, EMPTY, DRAFT, NONE, DELETED }

data class ArrivalsPage(
    val state: ArrivalsState,
    val lead: String?,
    val alert: Alert?,
    val days: List<DayLink>,
    val notice: DayNotice?,
    val updated: String,
    val refreshHref: String,
    val summary: SummaryView?,
    val groups: List<GroupView>,
    val newHref: String?,
    val draftTitle: String?,
    val draftHref: String?,
    /** The registrations of the gathering are gone: the empty state that says when and why. */
    val deleted: DeletedState? = null,
    /** The row «Menù» that downloads the file of the menu of the gathering (TALITA-12, criterion 9): null when there is no menu, and the card is not there. */
    val menu: MenuLink? = null,
)

/** The answer to a touch on a row: the new row, the new summary and the sentence that a screen reader says (criterion 4, design 5). */
data class RowAnswer(val row: RowView, val summary: SummaryView, val announcement: String)

/**
 * The page of Arrivi as the design says it (parts 1 and 4): the selector of the days, the notice about the day, the summary, the list in groups and the
 * links to the prints. One class says the words; the numbers come from [ArrivalsData], where nothing is saved. The labels of the state and of the manleva are
 * those of the registrations of TALITA-5.
 */
@Component
class ArrivalsPresenter(private val registrations: Registrations, private val teacher: TeacherPresenter, private val texts: RetentionTexts, private val menus: MenuPresenter) {

    fun hrefOf(registration: Long, day: LocalDate): String = "/arrivi/$registration?giorno=$day"

    fun teacherName(accountId: Long?): String = CheckinFormat.teacher(accountId?.let { registrations.teacherName(it) })

    // ---- The selector and the notice --------------------------------------------------------------------------------------------------

    fun days(choice: DayChoice): List<DayLink> = choice.days.map { day ->
        DayLink(
            href = "/arrivi?giorno=$day",
            abbreviation = CheckinFormat.weekdayAbbreviation(day),
            number = CheckinFormat.dayNumber(day),
            weekday = CheckinFormat.weekdayName(day),
            month = CheckinFormat.monthName(day),
            today = day == choice.today,
            current = day == choice.selected,
        )
    }

    /** The notice under the selector: which day this is when it is not today, that the gathering has not begun, or that it is over. */
    fun notice(choice: DayChoice): DayNotice? {
        val toToday = "/arrivi?giorno=${choice.today}"
        return when (choice.phase) {
            Phase.BEFORE -> DayNotice(Alert(AlertVariant.INFO, "La convivenza comincia ${CheckinFormat.longDay(choice.days.first())}: le presenze si segnano da quel giorno."), null, null)
            Phase.AFTER -> DayNotice(Alert(AlertVariant.INFO, "La convivenza è finita. Puoi ancora correggere le presenze, per esempio quando trascrivi il registro di carta."), null, null)
            Phase.DURING -> when {
                choice.selected.isBefore(choice.today) ->
                    DayNotice(Alert(AlertVariant.INFO, "Stai guardando ${CheckinFormat.longDay(choice.selected)}, un giorno passato. Oggi è ${CheckinFormat.longDay(choice.today)}."), "Vai a oggi", toToday)

                choice.selected.isAfter(choice.today) ->
                    DayNotice(
                        Alert(AlertVariant.INFO, "${CheckinFormat.longDayCapital(choice.selected)} non è ancora arrivato: le presenze si segnano da quel giorno. Oggi è ${CheckinFormat.longDay(choice.today)}."),
                        "Vai a oggi", toToday,
                    )

                else -> null
            }
        }
    }

    // ---- The rows --------------------------------------------------------------------------------------------------------------------

    private fun labels(data: ArrivalsData, registration: RegistrationData, day: LocalDate, unexpected: Boolean): List<RowLabel> {
        if (data.withdrawn(registration)) return listOf(RowLabel("Ritirata", "neutral"))
        return buildList {
            if (data.presence(registration, day) != null) add(RowLabel("Presente", "positive", Icon.CHECK))
            if (registration.status == Status.MAYBE) add(RowLabel("In forse", "warning"))
            if (data.receiptDue(registration)) add(RowLabel("Manleva da ricevere", "warning"))
            if (registration.id in data.duplicates) add(RowLabel("Possibile doppione", "warning"))
            val balance = data.balance(registration)
            if (registration.status == Status.CONFIRMED && balance is Balance.ToPay) add(RowLabel("Da pagare ${RegistrationFormat.money(balance.cents)}", "warning"))
            if (unexpected) add(RowLabel("Non previsto", "neutral"))
        }
    }

    /**
     * The row of [registration] for [day]. [markable]: the day can be marked (it is not after today). Only a child who is expected, with the day markable, has the
     * button; the others are links to the card. [note] is what the answer to a touch adds; [focus] is the row of that answer; [asLink] makes it a link whatever the
     * rest says (the registration was withdrawn, or the day is not one of the gathering).
     */
    fun row(data: ArrivalsData, registration: RegistrationData, day: LocalDate, markable: Boolean, note: RowNote? = null, focus: Boolean = false, asLink: Boolean = false): RowView {
        val expected = data.expected(registration, day)
        val present = data.presence(registration, day) != null
        val kind = when {
            asLink || !expected || !markable -> RowKind.LINK
            present -> RowKind.UNMARK
            else -> RowKind.MARK
        }
        val unexpected = !data.withdrawn(registration) && !registration.includes(day)
        val on = "${registration.name}, ${CheckinFormat.longDay(day)}"
        return RowView(
            id = registration.id,
            href = hrefOf(registration.id, day),
            name = registration.name,
            labels = labels(data, registration, day, unexpected),
            kind = kind,
            day = day.toString(),
            markFor = " $on",
            unmarkFor = " la presenza di $on",
            present = present && kind != RowKind.LINK,
            note = note,
            focus = focus,
        )
    }

    // ---- The summary -----------------------------------------------------------------------------------------------------------------

    /** A number of the summary: the zero is a word («Nessuno»), and the words after the number are left out with it unless [unitAtZero]. */
    private fun stat(label: String, value: Int, zero: String, unit: String?, unitAtZero: Boolean = false): StatView =
        StatView(label, if (value == 0) zero else value.toString(), if (value == 0 && !unitAtZero) null else unit, value == 0)

    fun summary(data: ArrivalsData, choice: DayChoice): SummaryView {
        val day = choice.selected
        val expected = data.registrations.filter { data.expected(it, day) }
        val confirmed = expected.filter { it.status == Status.CONFIRMED }
        val present = confirmed.count { data.presence(it, day) != null }
        val unexpectedPresent = data.registrations.count { !data.withdrawn(it) && !it.includes(day) && data.presence(it, day) != null }
        val receipts = expected.count { data.receiptDue(it) }
        val maybe = expected.count { it.status == Status.MAYBE }
        val outOf = when (confirmed.size) {
            0 -> "nessun bambino atteso"
            1 -> "su 1 atteso"
            else -> "su ${confirmed.size} attesi"
        }
        val stats = listOf(
            stat("Presenti", present, "Nessuno", outOf, unitAtZero = true),
            stat("Presenti non previsti", unexpectedPresent, "Nessuno", null),
            stat("Manleve da ricevere", receipts, "Nessuna", "fra gli attesi"),
            stat("In forse", maybe, "Nessuno", "non contati"),
        )
        val perTeacher = data.collected.joinToString(" · ") { "${CheckinFormat.teacher(it.name)} ${RegistrationFormat.money(it.cents)}" }
        val tag = when {
            day == choice.today -> Badge("Oggi", "info")
            day.isBefore(choice.today) -> Badge("Passato", "neutral")
            else -> Badge("Non ancora", "neutral")
        }
        return SummaryView(
            title = CheckinFormat.longDayCapital(day),
            tag = tag,
            stats = stats,
            collected = MoneyLine("Incassato in tutta la convivenza", CheckinFormat.moneyOrNothing(data.totalCollected), perTeacher.ifEmpty { null }),
            owed = MoneyLine("Ancora da incassare", CheckinFormat.moneyOrNothing(data.stillToCollect), "Delle iscrizioni confermate."),
        )
    }

    // ---- The page ---------------------------------------------------------------------------------------------------------------------

    private fun lead(gathering: GatheringInfo): String = "${gathering.title} · ${GatheringFormat.range(gathering.start, gathering.end)}"

    /** The whole page for a gathering with at least one registration or none; [now] is the moment of the request («Aggiornato alle 10:05»). */
    fun page(data: ArrivalsData, choice: DayChoice, now: OffsetDateTime, alert: Alert?): ArrivalsPage {
        val gathering = data.gathering
        val newHref = "/iscrizioni/nuova?convivenza=${gathering.id}&ritorno=arrivi"
        val day = choice.selected
        val refresh = "/arrivi?giorno=$day"
        if (data.registrations.isEmpty()) {
            return ArrivalsPage(ArrivalsState.EMPTY, lead(gathering), alert, days(choice), null, CheckinFormat.time(now), refresh, null, emptyList(), newHref, null, null, menu = menus.link(gathering.id))
        }
        fun rows(of: List<RegistrationData>, markable: Boolean) = of.map { row(data, it, day, markable) }
        val expected = data.registrations.filter { data.expected(it, day) }
        val unexpected = data.registrations.filter { !data.withdrawn(it) && !it.includes(day) }
        val withdrawn = data.registrations.filter { data.withdrawn(it) }
        val groups = buildList {
            if (expected.isNotEmpty()) add(GroupView("Attesi ${CheckinFormat.shortDay(day)}", expected.size, null, rows(expected, choice.markable)))
            if (unexpected.isNotEmpty()) {
                add(
                    GroupView(
                        "Non previsti ${CheckinFormat.shortDay(day)}", unexpected.size,
                        "Hanno l’iscrizione, ma non per questo giorno. Per segnarli apri la scheda.", rows(unexpected, markable = false),
                    ),
                )
            }
            if (withdrawn.isNotEmpty()) add(GroupView("Ritirate", withdrawn.size, null, rows(withdrawn, markable = false)))
        }
        return ArrivalsPage(
            state = ArrivalsState.LIST,
            lead = lead(gathering),
            alert = alert,
            days = days(choice),
            notice = notice(choice),
            updated = CheckinFormat.time(now),
            refreshHref = refresh,
            summary = summary(data, choice),
            groups = groups,
            newHref = newHref,
            draftTitle = null,
            draftHref = null,
            menu = menus.link(gathering.id),
        )
    }

    fun draftPage(draft: GatheringInfo): ArrivalsPage =
        ArrivalsPage(ArrivalsState.DRAFT, draft.title, null, emptyList(), null, "", "/arrivi", null, emptyList(), null, draft.title, "/convivenza/${draft.id}")

    /** Arrivi, or a print, of a gathering whose registrations are gone (a year after the end): nothing to list, and the page says when and why. */
    fun deletedPage(gathering: GatheringInfo): ArrivalsPage =
        ArrivalsPage(ArrivalsState.DELETED, lead(gathering), null, emptyList(), null, "", "/arrivi", null, emptyList(), null, null, null, texts.arrivalsDeleted(gathering.id, gathering.end))

    fun nonePage(): ArrivalsPage = ArrivalsPage(ArrivalsState.NONE, null, null, emptyList(), null, "", "/arrivi", null, emptyList(), null, null, null)
}
