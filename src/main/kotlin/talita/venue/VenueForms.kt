package talita.venue

import jakarta.servlet.http.HttpServletRequest
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import talita.checkin.ArrivalForms
import talita.checkin.VenueFormRef
import talita.gathering.GatheringChecks
import talita.gathering.Limits
import talita.gathering.PublicText
import talita.gathering.TextCheck
import talita.gathering.isForeignKeyViolation
import talita.gathering.isUniqueViolation
import talita.jooq.tables.references.VENUE
import talita.jooq.tables.references.VENUE_FORM
import talita.ui.Alert
import talita.ui.ConfirmModel

// ---- The words ---------------------------------------------------------------------------------------------------------------------

/** The sentences of the forms of a venue (design TALITA-20, part 3, guide 6 «Strutture»): written once, here, and read by the pages and by the tests. */
object VenueFormTexts {
    const val NEW_TITLE = "Nuovo modulo"
    const val CHANGE_TITLE = "Modifica il modulo"
    const val SAVED = "Modulo salvato."
    const val REMOVED = "Modulo tolto."
    const val ALREADY_REMOVED = "Il modulo era già stato tolto."
    const val LEAD = "Documenti che la struttura chiede. Talità li stampa da Arrivi, con i bambini presenti. Li vedono solo i maestri."
    const val EMPTY_TITLE = "Nessun modulo."
    const val EMPTY_TEXT = "Se la struttura chiede un registro dei presenti, scrivilo qui: lo stampi da Arrivi."
    const val NAME_HELP = "Come lo chiama la struttura, per esempio «Registro per la tassa di soggiorno». Compare in alto sul foglio che stampi. Scrivi il nome del documento e non di una persona, senza numeri di telefono né email."
    const val CHILDREN_LEGEND = "Colonne per i bambini"
    const val CHILDREN_HELP = "Cognome e nome dei bambini ci sono sempre. Aggiungi solo quello che la struttura chiede."
    const val TEACHERS_LEGEND = "Colonne per i maestri"
    const val TEACHERS_HELP = "Cognome e nome degli affidatari ci sono sempre. Documento e codice fiscale li scrivi sul foglio quando stampi: Talità non li riceve e non li salva, vanno solo sulla carta."
    const val AGE_LABEL = "Età al primo giorno"
    const val AGE_HELP = "Gli anni che ha il bambino il primo giorno della convivenza. Non la data di nascita."
    const val DAYS_LABEL = "Giorni di presenza"
    const val DAYS_HELP = "Una colonna per ogni giorno: «presente», «solo pranzo» o vuota."
    const val DOCUMENT_LABEL = "Documento d’identità"
    const val TAX_CODE_LABEL = "Codice fiscale"
    const val TEACHER_FIELD_HELP = "Un campo per maestro nella pagina di stampa. Quello che scrivi non si salva."

    /** `1 modulo`, `2 moduli`. */
    fun count(forms: Int): String = if (forms == 1) "1 modulo" else "$forms moduli"

    /** What goes with a venue that is taken away: the rooms and the forms, «Si cancellano anche le sue 2 camere e il suo modulo.»; null when it has neither. */
    fun hangsFrom(rooms: Int, forms: Int): String? {
        val parts = listOfNotNull(
            when (rooms) {
                0 -> null
                1 -> "la sua camera"
                else -> "le sue $rooms camere"
            },
            when (forms) {
                0 -> null
                1 -> "il suo modulo"
                else -> "i suoi $forms moduli"
            },
        )
        if (parts.isEmpty()) return null
        return (if (rooms + forms == 1) "Si cancella anche " else "Si cancellano anche ") + parts.joinToString(" e ") + "."
    }
}

// ---- The checks --------------------------------------------------------------------------------------------------------------------

/**
 * The name of a form of a venue (TALITA-20, part 3, criterion 20): from 1 to 60 characters on one line, with the controls of the public texts (no email, no telephone, no IBAN, no first and last name of a person
 * that the application knows), but with its own last sentence: the name is not public, the parents never read it, it is on the sheet that a teacher prints, so the error says «scrivi solo il nome del
 * documento». A name that the venue has already is not checked here: it is the database that knows ([VenueForms]).
 */
object VenueFormChecks {
    const val NAME_MISSING = "Scrivi il nome del modulo."
    const val NAME_TAKEN = "C’è già un modulo con questo nome."

    fun name(raw: String?): TextCheck =
        GatheringChecks.publicLine(raw, Limits.FORM_NAME, NAME_MISSING, PublicText.DOCUMENT_CLOSING) { "Il nome può avere al massimo ${Limits.FORM_NAME} caratteri: ne hai scritti $it." }
}

// ---- The service -------------------------------------------------------------------------------------------------------------------

/** What came of saving a form. */
sealed interface FormSave {
    data class Saved(val form: VenueFormRef) : FormSave

    /** The name is the one of another form of the venue (whatever the capitals and the spaces at the sides). */
    data object NameTaken : FormSave

    /** No such venue, or no such form in it: another teacher took it away a moment before. */
    data object NotFound : FormSave
}

/** What came of taking a form away. */
sealed interface FormRemoval {
    data class Removed(val name: String) : FormRemoval

    /** The form is not there: it was taken away a moment ago (the answer is the state as it is). */
    data object AlreadyGone : FormRemoval

    /** The form never was in this venue (404). */
    data object NotFound : FormRemoval
}

/** The four choices of a form as the page sends them: `true` is a box that is ticked. */
data class FormColumns(val age: Boolean, val days: Boolean, val teacherDocument: Boolean, val teacherTaxCode: Boolean)

/**
 * The forms of the venues (TALITA-20, part 3): what the pages of the venue write and what Arrivi reads through [ArrivalForms]. **A form is a name and four choices, never a value**: what a teacher writes in the
 * fields of the sheet is not here and does not reach the server.
 *
 * - **Every write is in a transaction that locks the venue for sharing**: a venue that is being taken away (it locks itself for update) is not found, and one that is found is not taken away until the write ends.
 * - **The name is unique in the venue** whatever the capitals and the spaces at the sides (the index of the table): two teachers who write the same name at the same moment leave one form and an answer for the
 *   second, never an error.
 * - **Nothing here writes a name in the log**: this class does not log at all.
 */
@Component
class VenueForms(private val dsl: DSLContext) : ArrivalForms {

    private fun ref(row: Record) = VenueFormRef(
        row[VENUE_FORM.ID]!!, row[VENUE_FORM.NAME]!!, row[VENUE_FORM.WITH_AGE]!!, row[VENUE_FORM.WITH_DAYS]!!, row[VENUE_FORM.WITH_TEACHER_DOCUMENT]!!, row[VENUE_FORM.WITH_TEACHER_TAX_CODE]!!,
    )

    private val columns = listOf(VENUE_FORM.ID, VENUE_FORM.NAME, VENUE_FORM.WITH_AGE, VENUE_FORM.WITH_DAYS, VENUE_FORM.WITH_TEACHER_DOCUMENT, VENUE_FORM.WITH_TEACHER_TAX_CODE)

    override fun ofVenue(venueId: Long): List<VenueFormRef> =
        dsl.select(columns).from(VENUE_FORM).where(VENUE_FORM.VENUE_ID.eq(venueId)).orderBy(DSL.lower(DSL.trim(VENUE_FORM.NAME)), VENUE_FORM.ID).fetch { ref(it) }

    override fun find(venueId: Long, id: Long): VenueFormRef? =
        dsl.select(columns).from(VENUE_FORM).where(VENUE_FORM.ID.eq(id)).and(VENUE_FORM.VENUE_ID.eq(venueId)).fetchOne { ref(it) }

    private fun venueShared(tx: DSLContext, venueId: Long): Boolean = tx.select(VENUE.ID).from(VENUE).where(VENUE.ID.eq(venueId)).forShare().fetchOne() != null

    /** A new form of the venue, with a [name] that [VenueFormChecks] has already checked. */
    fun create(venueId: Long, name: String, choices: FormColumns): FormSave = guarded {
        dsl.transactionResult { configuration ->
            val tx = DSL.using(configuration)
            if (!venueShared(tx, venueId)) return@transactionResult FormSave.NotFound
            val id = tx.insertInto(VENUE_FORM).set(VENUE_FORM.VENUE_ID, venueId).set(VENUE_FORM.NAME, name).set(VENUE_FORM.WITH_AGE, choices.age).set(VENUE_FORM.WITH_DAYS, choices.days)
                .set(VENUE_FORM.WITH_TEACHER_DOCUMENT, choices.teacherDocument).set(VENUE_FORM.WITH_TEACHER_TAX_CODE, choices.teacherTaxCode)
                .onConflictDoNothing().returningResult(VENUE_FORM.ID).fetchOne()?.value1() ?: return@transactionResult FormSave.NameTaken
            FormSave.Saved(VenueFormRef(id, name, choices.age, choices.days, choices.teacherDocument, choices.teacherTaxCode))
        }
    }

    /** The form [formId] of the venue has a new name and new choices. */
    fun update(venueId: Long, formId: Long, name: String, choices: FormColumns): FormSave = guarded {
        dsl.transactionResult { configuration ->
            val tx = DSL.using(configuration)
            tx.select(VENUE_FORM.ID).from(VENUE_FORM).where(VENUE_FORM.ID.eq(formId)).and(VENUE_FORM.VENUE_ID.eq(venueId)).forUpdate().fetchOne() ?: return@transactionResult FormSave.NotFound
            val taken = tx.fetchExists(VENUE_FORM, VENUE_FORM.VENUE_ID.eq(venueId).and(VENUE_FORM.ID.ne(formId)).and(DSL.lower(DSL.trim(VENUE_FORM.NAME)).eq(name.trim().lowercase())))
            if (taken) return@transactionResult FormSave.NameTaken
            tx.update(VENUE_FORM).set(VENUE_FORM.NAME, name).set(VENUE_FORM.WITH_AGE, choices.age).set(VENUE_FORM.WITH_DAYS, choices.days)
                .set(VENUE_FORM.WITH_TEACHER_DOCUMENT, choices.teacherDocument).set(VENUE_FORM.WITH_TEACHER_TAX_CODE, choices.teacherTaxCode).where(VENUE_FORM.ID.eq(formId)).execute()
            FormSave.Saved(VenueFormRef(formId, name, choices.age, choices.days, choices.teacherDocument, choices.teacherTaxCode))
        }
    }

    /** Two teachers who write the same name at the same moment: the unique index says no to the second, and that is the same as the name being taken. */
    private fun guarded(save: () -> FormSave): FormSave = try {
        save()
    } catch (e: RuntimeException) {
        when {
            e.isUniqueViolation() -> FormSave.NameTaken
            e.isForeignKeyViolation() -> FormSave.NotFound
            else -> throw e
        }
    }

    /**
     * Takes the form away. A form that is not in the venue is [FormRemoval.AlreadyGone] if it was there a moment ago (the ids of an identity column are never used again, so an id that the column has already given
     * and that is nowhere was taken away), and [FormRemoval.NotFound] if it never was (404). Nothing hangs from a form: a sheet that was printed stays with who has it.
     */
    fun remove(venueId: Long, formId: Long): FormRemoval = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        val name = tx.select(VENUE_FORM.NAME).from(VENUE_FORM).where(VENUE_FORM.ID.eq(formId)).and(VENUE_FORM.VENUE_ID.eq(venueId)).forUpdate().fetchOne(VENUE_FORM.NAME)
            ?: return@transactionResult if (wasHere(tx, formId)) FormRemoval.AlreadyGone else FormRemoval.NotFound
        tx.deleteFrom(VENUE_FORM).where(VENUE_FORM.ID.eq(formId)).execute()
        FormRemoval.Removed(name)
    }

    private fun wasHere(tx: DSLContext, formId: Long): Boolean {
        if (tx.fetchExists(VENUE_FORM, VENUE_FORM.ID.eq(formId))) return false
        val issued = tx.fetchOne("select case when is_called then last_value else 0 end from venue_form_id_seq")?.get(0, Long::class.java) ?: 0L
        return formId <= issued
    }
}

// ---- The view models ---------------------------------------------------------------------------------------------------------------

/** A form in the list of the venue: its name and what the sheet has, and what «Togli» does (a dialog: the sheets that were printed stay with who has them). */
data class VenueFormRow(val id: Long, val name: String, val columns: String, val editHref: String, val removeHref: String, val removeDialog: ConfirmModel)

/** The block `#moduli-struttura` of the page of a venue, which «Togli» answers with, whole: the forms, the notice of the action at the top and the way to write a new one. */
data class VenueFormsBlock(val venueId: Long, val rows: List<VenueFormRow>, val alert: Alert?, val announcement: String?, val newHref: String)

/** The page of the form of a form, new or to change: the name as it was written, the four boxes as they were ticked, the error under the name and the way back. */
data class VenueFormPage(
    val venueId: Long,
    val title: String,
    val lead: String,
    val name: String,
    val choices: FormColumns,
    val nameError: String?,
    val alert: Alert?,
    val action: String,
)

/** The notice of an action on a form that waits for the next page (a request without htmx): left in the session, read once, in a key of its own. */
object FormFlash {

    private const val KEY = "talita.flash.moduli"

    fun put(request: HttpServletRequest, alert: Alert) {
        request.getSession(true).setAttribute(KEY, alert)
    }

    fun take(request: HttpServletRequest): Alert? {
        val session = request.getSession(false) ?: return null
        val alert = session.getAttribute(KEY) as? Alert
        if (alert != null) session.removeAttribute(KEY)
        return alert
    }
}

/** Turns the forms of a venue into what its pages show: every sentence written here is the one of the design. */
@Component
class VenueFormsPresenter(private val forms: VenueForms) {

    private fun href(venueId: Long, tail: String = ""): String = "/strutture/$venueId$tail"

    /** The dialog «Togliere «{modulo}»?», or the page that stands in for it without JavaScript (the same words). */
    fun removalDialog(venueId: Long, form: VenueFormRef): ConfirmModel = ConfirmModel(
        id = "togli-modulo-${form.id}",
        title = "Togliere «${form.name}»?",
        text = "Il modulo sparisce dalla struttura e non si stampa più da Arrivi. I fogli già stampati restano a chi li ha. Potrai riscriverlo quando vuoi.",
        confirmLabel = "Togli il modulo",
        busyLabel = "Tolgo…",
        action = href(venueId, "/moduli/${form.id}/togli"),
        field = "modulo",
        value = form.id.toString(),
        target = "#moduli-struttura",
        cancelHref = href(venueId, "#moduli"),
        danger = false,
    )

    fun block(venueId: Long, alert: Alert? = null): VenueFormsBlock {
        val rows = forms.ofVenue(venueId).map {
            VenueFormRow(it.id, it.name, it.columns(), href(venueId, "/moduli/${it.id}/modifica"), href(venueId, "/moduli/${it.id}/togli"), removalDialog(venueId, it))
        }
        return VenueFormsBlock(venueId, rows, alert, alert?.text, href(venueId, "/moduli/nuovo"))
    }

    fun newPage(
        venueId: Long,
        venueName: String,
        name: String = "",
        choices: FormColumns = FormColumns(false, false, false, false),
        nameError: String? = null,
        alert: Alert? = null,
    ): VenueFormPage = VenueFormPage(venueId, VenueFormTexts.NEW_TITLE, venueName, name, choices, nameError, alert, href(venueId, "/moduli/nuovo"))

    fun changePage(
        venueId: Long,
        venueName: String,
        form: VenueFormRef,
        name: String? = null,
        choices: FormColumns? = null,
        nameError: String? = null,
        alert: Alert? = null,
    ): VenueFormPage = VenueFormPage(
        venueId, VenueFormTexts.CHANGE_TITLE, venueName, name ?: form.name, choices ?: FormColumns(form.age, form.days, form.teacherDocument, form.teacherTaxCode), nameError, alert,
        href(venueId, "/moduli/${form.id}/modifica"),
    )
}
