package talita.checkin

import org.springframework.stereotype.Component
import talita.gathering.GatheringFormat
import talita.gathering.GatheringInfo
import talita.registration.DayKind
import talita.registration.RegistrationData
import talita.registration.RegistrationFormat
import talita.registration.Status
import talita.ui.Icon
import talita.ui.SelectOption
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime

/** What the three prints have in common over the document: the title, the gathering with its dates, and when it was printed (the hour of the server at the request). */
data class PrintHead(val title: String, val lead: String, val printedLine: String)

/** The page that says there is nothing to print, with the way out (design part 3, states). */
data class PrintEmpty(val icon: Icon, val title: String, val text: String, val href: String, val label: String, val buttonIcon: Icon)

// ---- The register (criterion 23) ------------------------------------------------------------------------------------------------

/** A day over a column of the register: `V 17` for the eyes and `Ven 17` for the screen reader. */
data class RegisterDay(val short: String, val spoken: String)

/** A day of a row: the letter (`G` whole day, `P` lunch only, `–` not foreseen; none on a blank row) and the box, with an X when the presence is marked in Talità. */
data class RegisterCell(val letter: String?, val present: Boolean)

/** A row of the register; [blank] is one of the five at the end, for a child who is not on the list. [manleva] is null for the box that is still to be filled. */
data class RegisterRow(
    val name: String?,
    val cells: List<RegisterCell>,
    val due: String?,
    val paid: String?,
    val manleva: String?,
    val notes: String?,
    val blank: Boolean,
)

/** The four lines under the register. «Incassato a mano» has no number: it is for the pen. */
data class RegisterTotals(val confirmed: String, val maybe: String, val collected: String)

/** One sheet of the register: its caption, its rows and, on the last one, the totals (a sheet of the trailer alone has no rows and the totals). */
data class RegisterBlock(val caption: String, val rows: List<RegisterRow>, val totals: RegisterTotals?)

data class RegisterPage(
    val head: PrintHead,
    val legend: String,
    val days: List<RegisterDay>,
    /** More than four days: the columns of the days are narrower. */
    val squeezed: Boolean,
    val blocks: List<RegisterBlock>,
    val footer: String,
    val empty: PrintEmpty?,
)

// ---- The list for the venue (criterion 25) -----------------------------------------------------------------------------------------

/** A line of the list: surname and name, and nothing else. */
data class NameRow(val last: String, val first: String)

/** One sheet of names; [blank] rows are added under the names, for the adults who are written by hand. [newSheet] starts a sheet of paper before it. */
data class NameBlock(val caption: String?, val rows: List<NameRow>, val blank: Int = 0, val newSheet: Boolean = false)

data class VenuePage(
    val head: PrintHead,
    val options: List<SelectOption>,
    val childrenTitle: String,
    val children: List<NameBlock>,
    val teachersTitle: String,
    val teachers: NameBlock,
    val footer: String,
    val empty: PrintEmpty?,
)

// ---- The sheet for the teachers (criterion 26) ---------------------------------------------------------------------------------------

data class SheetRow(val name: String, val phone: String)

/** One sheet of the sheet for the teachers; the sentence at the end is on the last one. */
data class SheetBlock(val caption: String, val rows: List<SheetRow>, val closing: Boolean)

data class SheetPage(val head: PrintHead, val notice: String, val blocks: List<SheetBlock>, val closing: String, val empty: PrintEmpty?)

/**
 * The words of the three prints, and how their rows are divided into sheets of paper. What is written here is in the design (part 3); nothing is a contact, a note on
 * health or a free text, and nothing is saved.
 */
@Component
class PrintPresenter {

    private fun head(title: String, data: ArrivalsData, now: OffsetDateTime): PrintHead =
        PrintHead(title, "${data.gathering.title} · ${GatheringFormat.range(data.gathering.start, data.gathering.end)}", "Stampato il ${CheckinFormat.momentWithYear(now)}.")

    private fun emptyRegister(): PrintEmpty = PrintEmpty(
        Icon.CLIPBOARD_LIST, "Nessuna iscrizione da stampare",
        "Il registro ha una riga per ogni iscrizione non ritirata. Quando i genitori iscrivono i bambini, lo trovi qui.", "/arrivi", "Torna agli arrivi", Icon.CHEVRON_LEFT,
    )

    private fun caption(title: String, page: Int, count: Int): String = "$title · pagina $page di $count"

    private fun active(data: ArrivalsData): List<RegistrationData> = data.registrations.filterNot { data.withdrawn(it) }

    // ---- The register -------------------------------------------------------------------------------------------------------------

    private fun letter(day: LocalDate): String = when (day.dayOfWeek) {
        DayOfWeek.MONDAY -> "L"
        DayOfWeek.TUESDAY -> "Ma"
        DayOfWeek.WEDNESDAY -> "Me"
        DayOfWeek.THURSDAY -> "G"
        DayOfWeek.FRIDAY -> "V"
        DayOfWeek.SATURDAY -> "S"
        DayOfWeek.SUNDAY -> "D"
    }

    private fun dayLetter(registration: RegistrationData, day: LocalDate): String = when {
        registration.presence.allDays -> "G"
        registration.presence.days[day] == DayKind.FULL -> "G"
        registration.presence.days[day] == DayKind.LUNCH -> "P"
        else -> "–"
    }

    private fun registerRow(data: ArrivalsData, registration: RegistrationData): RegisterRow {
        val days = data.gathering.days
        val due = data.due(registration)
        val paid = data.paid(registration)
        val notes = buildList {
            if (registration.status == Status.MAYBE) add("In forse")
            if (registration.onPaper) add("Dal cartaceo")
            if (data.duplicates[registration.id].orEmpty().isNotEmpty()) add("Possibile doppione")
            days.filter { data.present(registration, it) && !registration.includes(it) }.forEach { add("${CheckinFormat.weekdayName(it)} non previsto") }
        }
        return RegisterRow(
            name = registration.name,
            cells = days.map { RegisterCell(dayLetter(registration, it), data.present(registration, it)) },
            due = when (due) {
                null -> "Da definire"
                0 -> "Gratis"
                else -> RegistrationFormat.money(due)
            },
            paid = if (paid == 0) null else RegistrationFormat.money(paid),
            manleva = when {
                !registration.onPaper -> "Online"
                registration.paperFormReceived -> "Cartacea"
                else -> null
            },
            notes = notes.joinToString(" · "),
            blank = false,
        )
    }

    private fun blankRow(days: Int): RegisterRow = RegisterRow(null, List(days) { RegisterCell(null, false) }, null, null, null, null, blank = true)

    /** The register, or the page that says there is nothing to print when no registration is left that is not withdrawn. */
    fun register(data: ArrivalsData, now: OffsetDateTime): RegisterPage {
        val head = head("Registro arrivi", data, now)
        val legend = LEGEND
        val footer = "Il registro si conserva fra i documenti della convivenza. Non ha recapiti né segnalazioni."
        val days = data.gathering.days.map { RegisterDay("${letter(it)} ${CheckinFormat.dayNumber(it)}", "${CheckinFormat.weekdayAbbreviation(it)} ${CheckinFormat.dayNumber(it)}") }
        val rows = active(data)
        if (rows.isEmpty()) return RegisterPage(head, legend, days, days.size > 4, emptyList(), footer, emptyRegister())
        val sheets = Pagination.split(
            rows.map { registerRow(data, it) } + List(BLANK_ROWS) { blankRow(days.size) },
            Pagination.register(days.size), Pagination.REGISTER_RESERVE,
        )
        val totals = RegisterTotals(
            confirmed = CheckinFormat.moneyOrNothing(rows.filter { it.status == Status.CONFIRMED }.sumOf { data.due(it) ?: 0 }),
            maybe = CheckinFormat.moneyOrNothing(rows.filter { it.status == Status.MAYBE }.sumOf { data.due(it) ?: 0 }),
            collected = CheckinFormat.moneyOrNothing(data.totalCollected),
        )
        val blocks = sheets.pages.mapIndexed { index, page ->
            RegisterBlock(caption("Registro arrivi", index + 1, sheets.count), page, totals.takeIf { !sheets.trailerAlone && index == sheets.pages.lastIndex })
        } + if (sheets.trailerAlone) listOf(RegisterBlock(caption("Registro arrivi", sheets.count, sheets.count), emptyList(), totals)) else emptyList()
        return RegisterPage(head, legend, days, days.size > 4, blocks, footer, null)
    }

    // ---- The list for the venue ---------------------------------------------------------------------------------------------------

    /**
     * The list for the venue: the children (whose registration is confirmed, or, with a [day], those who are present on that day and whose registration is not
     * withdrawn) and the teachers in custody, with surname and name and nothing else.
     */
    fun venue(data: ArrivalsData, day: LocalDate?, now: OffsetDateTime): VenuePage {
        val head = head("Elenco per la struttura", data, now)
        val options = listOf(SelectOption("", "Tutte le iscrizioni confermate", day == null)) +
            data.gathering.days.map { SelectOption(it.toString(), "Presenti ${CheckinFormat.longDay(it)}", it == day) }
        val children = if (day == null) {
            data.registrations.filter { it.status == Status.CONFIRMED }
        } else {
            data.registrations.filter { data.present(it, day) }
        }
        val title = if (day == null) "Bambini con l’iscrizione confermata" else "Bambini presenti ${CheckinFormat.longDay(day)}"
        val teachers = data.gathering.custodians.map { NameRow(it.last, it.first) }
        val teachersBlock = NameBlock(null, teachers, blank = BLANK_TEACHERS)
        val footer = "Gli adulti in più si scrivono a mano nelle righe vuote."
        if (children.isEmpty()) {
            val empty = if (day == null) {
                PrintEmpty(
                    Icon.LIST, "Nessuna iscrizione confermata",
                    "L’elenco ha una riga per ogni iscrizione confermata. Quando i genitori iscrivono i bambini, lo trovi qui.", "/arrivi", "Torna agli arrivi", Icon.CHEVRON_LEFT,
                )
            } else if (day.isAfter(data.today)) {
                // A day that has not come has no presents yet, whatever the arrival wrote in advance (TALITA-19, criterion 8).
                PrintEmpty(
                    Icon.LIST, "Nessun bambino presente ${CheckinFormat.longDay(day)}",
                    "${CheckinFormat.longDayCapital(day)} non è ancora arrivato: i bambini si contano presenti da quel giorno. Per scegliere un altro giorno apri l’elenco delle iscrizioni confermate.",
                    "/arrivi/elenco", "Vedi le iscrizioni confermate", Icon.LIST,
                )
            } else {
                PrintEmpty(
                    Icon.LIST, "Nessun bambino presente ${CheckinFormat.longDay(day)}",
                    "L’elenco dei presenti si riempie man mano che segni le presenze. Puoi scegliere un altro giorno o tutte le iscrizioni confermate.",
                    "/arrivi/elenco", "Vedi le iscrizioni confermate", Icon.LIST,
                )
            }
            return VenuePage(head, options, title, emptyList(), "Maestri", teachersBlock, footer, empty)
        }
        val sheets = Pagination.split(children.map { NameRow(it.lastName, it.firstName) }, Pagination.VENUE, Pagination.venueReserve(teachers.size))
        val blocks = sheets.pages.mapIndexed { index, page -> NameBlock(caption("Elenco per la struttura", index + 1, sheets.count), page) }
        val teachersWhole = if (sheets.trailerAlone) teachersBlock.copy(caption = caption("Elenco per la struttura", sheets.count, sheets.count), newSheet = true) else teachersBlock
        return VenuePage(head, options, "$title · ${children.size}", blocks, "Maestri · ${teachers.size}", teachersWhole, footer, null)
    }

    // ---- The sheet for the teachers -----------------------------------------------------------------------------------------------

    fun sheet(data: ArrivalsData, now: OffsetDateTime): SheetPage {
        val head = head("Foglio per i maestri", data, now)
        val notice = "Questo foglio contiene i recapiti dei genitori. Tienilo fra i maestri e distruggilo alla fine della convivenza."
        val closing = "Alla fine della convivenza questo foglio va distrutto."
        val rows = active(data)
        if (rows.isEmpty()) return SheetPage(head, notice, emptyList(), closing, emptyRegister())
        val sheets = Pagination.split(rows.map { SheetRow(it.name, it.phone) }, Pagination.TEACHER_SHEET, Pagination.TEACHER_SHEET_RESERVE)
        val blocks = sheets.pages.mapIndexed { index, page ->
            SheetBlock(caption("Foglio per i maestri", index + 1, sheets.count), page, closing = !sheets.trailerAlone && index == sheets.pages.lastIndex)
        } + if (sheets.trailerAlone) listOf(SheetBlock(caption("Foglio per i maestri", sheets.count, sheets.count), emptyList(), closing = true)) else emptyList()
        return SheetPage(head, notice, blocks, closing, null)
    }

    /** The day of a request of the venue list: a day of the gathering, or null for the one that is not (the page does not exist then). */
    fun dayOf(gathering: GatheringInfo, typed: String?): LocalDate? = parseDay(typed)?.takeIf { it in gathering.days }

    companion object {
        private const val BLANK_ROWS = 5
        private const val BLANK_TEACHERS = 3

        /**
         * The legend of the register (design of TALITA-19, 2 D): the letters of the days, what the X is, and the rule of the arrival that carries on to the days after it, with the
         * limit that the paper register has too (whoever goes away early keeps the X until somebody takes it away).
         */
        const val LEGEND = "G giornata · P solo pranzo · – giorno non previsto. La X nella casella è una presenza segnata in Talità: un arrivo vale anche per i giorni dopo a cui il " +
            "bambino è iscritto, ma la X compare solo dal loro giorno. Chi va via prima resta con la X finché non lo togli in Talità: controlla con la conta della mattina."
    }
}
