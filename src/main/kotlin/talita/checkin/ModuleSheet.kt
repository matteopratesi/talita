package talita.checkin

import org.springframework.stereotype.Component
import talita.gathering.GatheringFormat
import talita.registration.DayKind
import talita.registration.RegistrationData
import talita.registration.RegistrationFormat
import talita.ui.Icon
import java.time.LocalDate
import java.time.OffsetDateTime

// ---- The sheet of a form of the venue (TALITA-20, part 3, criteria 20-27 and 29-31) -----------------------------------------------------------------

/** A day over a column: `Ven 17` for the eyes and `Venerdì 17 settembre` for the screen reader. */
data class ModuleDay(val short: String, val spoken: String)

/** A child of the sheet: surname and name, the age if the form asks for it, and a cell for each day (`presente`, `solo pranzo` or nothing) if it asks for the days. Never a date of birth, a contact or a note. */
data class ModuleChildRow(val last: String, val first: String, val age: String?, val cells: List<String>)

/** One sheet of paper of the children, with its own table and its caption («Registro per la tassa di soggiorno · pagina 1 di 2»). */
data class ModuleChildBlock(val caption: String, val rows: List<ModuleChildRow>)

/** A row of the table of the teachers: a custodian (surname and name) or a blank row, numbered from 1, for an adult who is written by hand. */
data class ModuleTeacherRow(val last: String?, val first: String?, val blank: Int?) {

    /** The name of a field of the row for a screen reader: «Documento d’identità di Marta Bianchi», or «Codice fiscale, riga vuota 2». */
    fun label(column: String): String = if (blank == null) "$column di $first $last" else "$column, riga vuota $blank"
}

/** The table of the teachers, with its caption; [newSheet] starts a sheet of paper before it (it did not fit under the children). */
data class ModuleTeacherBlock(val caption: String, val rows: List<ModuleTeacherRow>, val newSheet: Boolean)

/**
 * The columns of the sheet. [days] are those of the table of the children; [teacherDays] those that are left in the table of the teachers (none when the form does not ask for the days, or when the
 * fields of the teachers leave no room for them: see [ModuleLayout]).
 */
data class ModuleColumns(val age: Boolean, val days: List<ModuleDay>, val teacherDays: List<ModuleDay>, val document: Boolean, val taxCode: Boolean) {
    val fields: Boolean get() = document || taxCode
}

data class ModulePage(
    val head: PrintHead,
    val help: String,
    /** Under the title: «Struttura: Casa del bosco. Stampato il 18 settembre 2027 alle 9:12.» */
    val printed: String,
    /** «Età in anni al primo giorno della convivenza, il 17 settembre 2027.» when the form asks for the age. */
    val ageLine: String?,
    val childrenTitle: String,
    val legend: String,
    val columns: ModuleColumns,
    /** More than four days: the columns of the days are narrower. */
    val squeezed: Boolean,
    val children: List<ModuleChildBlock>,
    val teachersTitle: String,
    val teachers: ModuleTeacherBlock,
    /** The notice over the table of the teachers (it is never printed): what is written in the fields is not saved. Null when the form has no fields. */
    val notice: List<String>?,
    val footer: String,
    val empty: PrintEmpty?,
)

/** What the table of the teachers can hold on an A4 sheet in portrait (the measures of the print, in millimetres): the name keeps at least 27 mm, and the days go when the rest does not fit. */
object ModuleLayout {
    private const val SHEET = 174
    private const val NAME_MIN = 27
    private const val AGE = 12
    private const val FIELD = 36

    /** The width of a column of a day: narrower with more than four days (the columns of the register do the same). */
    fun dayWidth(days: Int): Int = if (days > 4) 16 else 21

    /** The days stay in the table of the teachers when, with the age and the fields, they leave the name its 27 mm: with one field up to six days, with two up to three; with none always. */
    fun daysFit(age: Boolean, fields: Int, days: Int): Boolean = (if (age) AGE else 0) + fields * FIELD + days * dayWidth(days) <= SHEET - NAME_MIN
}

/**
 * The sheet of a form of the venue (TALITA-20, part 3): the children who are present on at least one day (the rule of TALITA-19, [ArrivalsData.present]), by surname and name, with the columns that the form
 * has, and the table of the teachers in custody and of the blank rows, with a field for each column of the teachers. Nothing here reads a date of birth (only the age on the first day, if the form asks for
 * it), a contact, a note on health, a payment or a room, and nothing is saved: the fields of the teachers are not in the model, they are in the page and never leave it.
 */
@Component
class ModulePresenter {

    private fun caption(name: String, page: Int, count: Int): String = "$name · pagina $page di $count"

    private fun days(data: ArrivalsData): List<ModuleDay> =
        data.gathering.days.map { ModuleDay("${CheckinFormat.weekdayAbbreviation(it)} ${CheckinFormat.dayNumber(it)}", "${CheckinFormat.weekdayName(it)} ${CheckinFormat.dayAndMonth(it)}") }

    private fun cell(data: ArrivalsData, registration: RegistrationData, day: LocalDate): String = when {
        !data.present(registration, day) -> ""
        !registration.presence.allDays && registration.presence.days[day] == DayKind.LUNCH -> "solo pranzo"
        else -> "presente"
    }

    private fun present(data: ArrivalsData): List<RegistrationData> = data.registrations.filter { child -> data.gathering.days.any { data.present(child, it) } }

    /** What is said over the table: what the sheet has, and where the fields of the teachers are (the sentence changes with the columns). */
    fun help(form: VenueFormRef): String {
        val extras = listOfNotNull("l’età".takeIf { form.age }, "i giorni di presenza".takeIf { form.days })
        val children = if (extras.isEmpty()) {
            "Il foglio ha solo il nome e il cognome dei bambini presenti e dei maestri affidatari."
        } else {
            "Il foglio ha il nome e il cognome dei bambini presenti e dei maestri affidatari, ${extras.joinToString(" e ")}, come chiede la struttura."
        }
        val teachers = when {
            form.teacherDocument && form.teacherTaxCode -> " Per i maestri ci sono anche il documento d’identità e il codice fiscale: li scrivi nella tabella dei maestri, prima di stampare o di salvare il PDF."
            form.teacherDocument -> " Per i maestri c’è anche il documento d’identità: lo scrivi nella tabella dei maestri, prima di stampare o di salvare il PDF."
            form.teacherTaxCode -> " Per i maestri c’è anche il codice fiscale: lo scrivi nella tabella dei maestri, prima di stampare o di salvare il PDF."
            else -> ""
        }
        return "$children$teachers Stampa su A4, in verticale."
    }

    fun page(data: ArrivalsData, form: VenueFormRef, now: OffsetDateTime): ModulePage {
        val gathering = data.gathering
        val head = PrintHead(form.name, "${gathering.title} · ${GatheringFormat.range(gathering.start, gathering.end)}", "Stampato il ${CheckinFormat.momentWithYear(now)}.")
        val printed = (gathering.venueName?.let { "Struttura: $it. " }.orEmpty()) + head.printedLine
        val ageLine = if (form.age) "Età in anni al primo giorno della convivenza, il ${GatheringFormat.date(gathering.start)}." else null
        val allDays = days(data)
        val columns = ModuleColumns(
            age = form.age,
            days = if (form.days) allDays else emptyList(),
            teacherDays = if (form.days && ModuleLayout.daysFit(form.age, form.fields, allDays.size)) allDays else emptyList(),
            document = form.teacherDocument,
            taxCode = form.teacherTaxCode,
        )
        val children = present(data)
        val teachers = gathering.custodians.map { ModuleTeacherRow(it.last, it.first, null) }
        val blankRows = List(BLANK_TEACHERS) { ModuleTeacherRow(null, null, it + 1) }
        val legend = "Ci sono i bambini segnati presenti almeno un giorno, fino al momento della stampa." + if (form.days) " Un giorno vuoto è senza presenza, oppure non ancora arrivato." else ""
        val notice = if (form.teacherColumns) listOf("Quello che scrivi qui non si salva in Talità.", "Finisce solo nel foglio o nel PDF che salvi tu. Se esci dalla pagina o la ricarichi, sparisce.") else null
        val footer = "Gli adulti in più si scrivono a mano nelle righe vuote."
        val squeezed = allDays.size > 4
        if (children.isEmpty()) {
            val empty = PrintEmpty(
                Icon.LIST, "Nessun bambino presente",
                "Il modulo ha i bambini che i maestri hanno segnato presenti almeno un giorno, e per ora non ce n’è nessuno. Segna gli arrivi, oppure stampa l’elenco di chi è iscritto.",
                "/arrivi/elenco", "Apri l’elenco per la struttura", Icon.LIST,
            )
            return ModulePage(head, help(form), printed, ageLine, "Bambini presenti", legend, columns, squeezed, emptyList(), "Maestri", ModuleTeacherBlock("Maestri", teachers + blankRows, false), notice, footer, empty)
        }
        val sheets = Pagination.split(
            children.map { child ->
                ModuleChildRow(child.lastName, child.firstName, if (form.age) RegistrationFormat.age(child.birthDate, gathering.start).toString() else null, if (form.days) gathering.days.map { cell(data, child, it) } else emptyList())
            },
            Pagination.module(allDays.size), Pagination.venueReserve(teachers.size),
        )
        val blocks = sheets.pages.mapIndexed { index, rows -> ModuleChildBlock(caption(form.name, index + 1, sheets.count), rows) }
        val teacherBlock = if (sheets.trailerAlone) {
            ModuleTeacherBlock(caption(form.name, sheets.count, sheets.count), teachers + blankRows, newSheet = true)
        } else {
            ModuleTeacherBlock("Maestri", teachers + blankRows, newSheet = false)
        }
        return ModulePage(head, help(form), printed, ageLine, "Bambini presenti · ${children.size}", legend, columns, squeezed, blocks, "Maestri · ${teachers.size}", teacherBlock, notice, footer, null)
    }

    private companion object {
        const val BLANK_TEACHERS = 3
    }
}
