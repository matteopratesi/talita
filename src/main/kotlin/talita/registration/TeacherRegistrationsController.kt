package talita.registration

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import talita.account.CurrentAccount
import talita.account.Gate
import talita.account.GateMode
import talita.account.NotesGate
import talita.account.PasskeyConfirmations
import talita.account.isHtmxFragment
import talita.account.seeOther
import talita.gathering.Badge
import talita.gathering.GatheringFormat
import talita.gathering.GatheringInfo
import talita.gathering.GatheringReader
import talita.gathering.GatheringState
import talita.retention.DeletedState
import talita.retention.NotesDeleted
import talita.retention.RetentionPolicy
import talita.retention.RetentionTexts
import talita.ui.Alert
import talita.ui.AlertVariant.DANGER
import talita.ui.AlertVariant.INFO
import talita.ui.AlertVariant.SUCCESS
import talita.ui.AlertVariant.WARNING
import talita.ui.ConfirmModel
import talita.ui.Flash
import talita.ui.Section
import talita.ui.ShellFactory
import java.time.LocalDate

/** The value of `ritorno` that says the paper form was opened from the arrivals: a word, no data. */
private const val ARRIVALS = "arrivi"

/** A row of the list of the teachers (criterion 20): the whole row leads to the detail. Nothing here is about health. */
data class TeacherRow(val href: String, val initials: String, val name: String, val status: Badge, val manleva: Badge?, val duplicate: Boolean, val line: String)

/** What the page «Iscrizioni» of the teachers is about: a list, a gathering with no registration, a draft, or no gathering at all. */
enum class TeacherListKind { LIST, EMPTY, DRAFT, NONE, DELETED }

data class TeacherListPage(
    val kind: TeacherListKind,
    val alert: Alert?,
    val lead: String?,
    val newHref: String?,
    val summary: List<Pair<String, String>>,
    val rows: List<TeacherRow>,
    val draftTitle: String?,
    val draftHref: String?,
    /** The registrations of the gathering are gone (a year after the end): the empty state that says when and why. */
    val deleted: DeletedState? = null,
)

/** Another registration of the same child, for «Apri l'altra iscrizione». */
data class OtherRegistration(val href: String, val ariaLabel: String)

/** What the card «Segnalazioni» shows: the button alone, or, after a request, the notes with the reading that was written for it. */
data class NotesView(
    val id: Long,
    val shown: Boolean,
    val readAt: String?,
    val rows: List<Pair<String, String>>,
    val consent: String?,
    /** A paper registration with notes: they can be corrected from a page that shows them (and writes a reading). */
    val canCorrect: Boolean,
    /** The notes are past their date: the card says so, with no button (the same for every registration, it does not say whether there were any). */
    val deleted: NotesDeleted? = null,
    /** What the card says about the confirmation with the passkey (TALITA-15): that the phone will ask, until when it holds, the notice that it is needed. */
    val gate: NotesGate = NotesGate.NONE,
)

data class ReadingView(val name: String, val at: String, val mine: Boolean)

/** The detail of a registration for the teachers (criteria 21, 24, 25; design C 4-6): the order is child, registration, notes, custody, readings. */
data class TeacherDetailPage(
    val id: Long,
    val name: String,
    val lead: String,
    val badges: List<Badge>,
    val duplicates: List<OtherRegistration>,
    val birth: String,
    val age: String,
    val presence: String,
    val quota: String,
    val phone: String,
    val telephone: String,
    val withdrawn: Boolean,
    val onPaper: Boolean,
    val accepted: String?,
    val acceptedBy: String?,
    val version: String?,
    val acceptedText: String?,
    val paperReceived: Boolean,
    val enteredBy: String?,
    val notes: NotesView,
    val readings: List<ReadingView>,
    val alert: Alert?,
    val withdrawDialog: ConfirmModel,
    /** The card of the same child in the arrivals (TALITA-6), when this registration is of the gathering that the arrivals are about; else none. */
    val arrivalsHref: String? = null,
)

/** The form of a paper registration and of a correction: the data of the child, the phone, the paper form, and what surrounds them (design C 7, 8). */
data class PaperPage(
    val title: String,
    val lead: String,
    val intro: String?,
    val notice: Alert?,
    val alert: Alert?,
    val phone: String,
    val phoneError: String?,
    val child: ChildView,
    val offer: OfferView,
    val received: Boolean,
    val formAction: String,
    val quotaAction: String,
    val submit: String,
    val busy: String,
    val cancelHref: String,
    val gatheringId: Long,
    /** A paper form is being typed in: the notes are in the block. Otherwise a correction: they have a page of their own. */
    val inserting: Boolean,
    val paper: Boolean,
    /** For a correction of a paper registration: whether it has notes (the link says «Aggiungi» or the notice says to show them first). */
    val hasNotes: Boolean,
    val id: Long?,
    /** The form was opened from the arrivals («Inserisci un’iscrizione» there): once saved it leads to the card of the new registration in the arrivals, and «Annulla» to the arrivals. */
    val fromArrivals: Boolean = false,
    /** The notes of this gathering are past their date (a month after the end): the form has no fields for them, and this says why (TALITA-7, design part 3, 5). */
    val notesDeleted: String? = null,
    /** The same, for a correction: the link «Aggiungi le segnalazioni» is not there any more. */
    val notesGone: Boolean = false,
) {
    /** What a request for the quota carries: the days of the block, the gathering when the form is for a new registration, and nothing else. */
    val quotaFields: String get() = offer.quotaFields(listOf(child.index), if (inserting) listOf("convivenza") else emptyList())
}

/**
 * The registrations as the teachers see them (criteria 20-28): the list of a gathering with the counts, the detail with the notes that are shown only on
 * request, the paper forms, the corrections, and who read what. One class says the words; the rules are those of [Rules] and of [Registrations].
 */
@Component
class TeacherPresenter(
    private val registrations: Registrations,
    private val healthNotes: HealthNotes,
    private val presenter: RegistrationPresenter,
    private val policy: RetentionPolicy,
    private val texts: RetentionTexts,
    private val clock: java.time.Clock,
) {

    private fun quotaOf(gathering: GatheringInfo, registration: RegistrationData): Int? = Quota.due(presenter.offer(gathering), registration.presence)

    /** The label of the manleva (none for a withdrawn registration): the same words in the list, in the detail and in the card of the arrivals. */
    fun manleva(registration: RegistrationData): Badge? = when {
        registration.status == Status.WITHDRAWN -> null
        !registration.onPaper -> Badge("Accettata online", "positive")
        registration.paperFormReceived -> Badge("Cartacea ricevuta", "positive")
        else -> Badge("Da ricevere", "warning")
    }

    /** The label of the state of a registration. */
    fun statusBadge(registration: RegistrationData): Badge = Badge(
        registration.status.label,
        when (registration.status) {
            Status.CONFIRMED -> "positive"
            Status.MAYBE -> "warning"
            Status.WITHDRAWN -> "neutral"
        },
    )

    /** The list of [gathering]: the counts, the money, the rows by surname and name (criteria 20, 24). */
    fun list(gathering: GatheringInfo, alert: Alert?): TeacherListPage {
        val items = registrations.ofGathering(gathering.id)
        val duplicates = registrations.duplicates(gathering.id)
        val counts = items.groupingBy { it.status }.eachCount()
        val confirmed = items.filter { it.status == Status.CONFIRMED }.sumOf { quotaOf(gathering, it) ?: 0 }
        val maybe = items.filter { it.status == Status.MAYBE }.sumOf { quotaOf(gathering, it) ?: 0 }
        fun count(status: Status): String = (counts[status] ?: 0).let { if (it == 0) "Nessuna" else it.toString() }
        val rows = items.map { registration ->
            val withdrawn = registration.status == Status.WITHDRAWN
            val quota = if (withdrawn) "Non dovuta" else quotaOf(gathering, registration)?.let(RegistrationFormat::money) ?: "Da definire"
            TeacherRow(
                href = "/iscrizioni/${registration.id}",
                initials = RegistrationFormat.initials(registration.firstName, registration.lastName),
                name = registration.name,
                status = statusBadge(registration),
                manleva = manleva(registration),
                duplicate = registration.id in duplicates,
                line = listOf(RegistrationFormat.ageText(registration.birthDate, gathering.start), RegistrationFormat.presenceShort(registration.presence), quota).joinToString(" · "),
            )
        }
        return TeacherListPage(
            kind = if (items.isEmpty()) TeacherListKind.EMPTY else TeacherListKind.LIST,
            alert = alert,
            lead = "${gathering.title} · ${GatheringFormat.range(gathering.start, gathering.end)}",
            newHref = "/iscrizioni/nuova?convivenza=${gathering.id}",
            summary = listOf(
                "Confermate" to count(Status.CONFIRMED),
                "In forse" to count(Status.MAYBE),
                "Ritirate" to count(Status.WITHDRAWN),
                "Quota delle confermate" to RegistrationFormat.money(confirmed),
                "Quota di chi è in forse" to RegistrationFormat.money(maybe),
            ),
            rows = rows,
            draftTitle = null,
            draftHref = null,
        )
    }

    fun withdrawDialog(registration: RegistrationData) = ConfirmModel(
        id = "ritira",
        title = "Ritirare l’iscrizione?",
        text = "L’iscrizione di ${registration.name} risulterà ritirata: la quota non è più dovuta e i giorni non contano più. Le segnalazioni, se ci sono, vengono cancellate subito. " +
            "Resta nell’elenco con lo stato «Ritirata» e puoi rimetterla quando vuoi.",
        confirmLabel = "Ritira l’iscrizione",
        busyLabel = "Ritiro…",
        action = "/iscrizioni/${registration.id}/ritira",
        field = "from",
        value = registration.status.code,
        target = null,
        cancelHref = "/iscrizioni/${registration.id}",
    )

    private fun readings(id: Long, me: Long): List<ReadingView> =
        healthNotes.readings(id).map { ReadingView(it.teacher ?: "Un maestro che non è più tra i maestri", RegistrationFormat.moment(it.at), it.teacherAccountId == me) }

    /**
     * The notes of the card: [read] has them (after a request that wrote a reading), null for the card as it is before. From the day of their date the card says that they were
     * deleted, whatever [read] is: the date and not the rows decides (criterion 30).
     */
    fun notes(registration: RegistrationData, read: NotesRead?, gathering: GatheringInfo, gate: NotesGate = NotesGate.NONE): NotesView {
        val note = read?.note
        val rows = buildList {
            note?.notes?.allergies?.let { add("Allergie" to it) }
            note?.notes?.intolerances?.let { add("Intolleranze" to it) }
            note?.notes?.medications?.let { add("Farmaci" to it) }
        }
        return NotesView(
            id = registration.id,
            shown = read != null,
            readAt = read?.let { "Lettura registrata a tuo nome: ${RegistrationFormat.moment(it.at)}." },
            rows = rows,
            consent = note?.let { if (it.onPaper) "Il genitore ha dato il consenso sul modulo cartaceo." else "Consenso dato dal genitore il ${RegistrationFormat.moment(it.consentAt)}." },
            canCorrect = registration.onPaper && note != null && registration.status != Status.WITHDRAWN,
            deleted = if (policy.notesExpired(gathering.end, LocalDate.now(clock))) texts.notesDeleted(gathering.end) else null,
            // The notes that are shown have no confirmation to ask for; the invitation is for the arrivals and the profile, not for this card.
            gate = if (read != null || gate.invite) NotesGate.NONE else gate,
        )
    }

    fun detail(account: CurrentAccount, registration: RegistrationData, gathering: GatheringInfo, read: NotesRead?, alert: Alert?, arrivalsHref: String? = null, gate: NotesGate = NotesGate.NONE): TeacherDetailPage {
        val duplicates = registrations.duplicates(gathering.id)[registration.id].orEmpty().mapNotNull { registrations.find(it) }
        val withdrawn = registration.status == Status.WITHDRAWN
        val due = quotaOf(gathering, registration)
        return TeacherDetailPage(
            id = registration.id,
            name = registration.name,
            lead = "${gathering.title} · ${GatheringFormat.range(gathering.start, gathering.end)}",
            badges = listOfNotNull(statusBadge(registration), manleva(registration), Badge("Possibile doppione", "warning").takeIf { duplicates.isNotEmpty() }),
            duplicates = duplicates.map { OtherRegistration("/iscrizioni/${it.id}", "Apri l’altra iscrizione, fatta il ${RegistrationFormat.day(it.createdAt)}") },
            birth = RegistrationFormat.birth(registration.birthDate),
            age = RegistrationFormat.ageText(registration.birthDate, gathering.start) + " il primo giorno",
            presence = RegistrationFormat.presence(registration.presence, gathering.days),
            quota = if (withdrawn) "Non dovuta" else due?.let(RegistrationFormat::money) ?: "Da definire",
            phone = registration.phone,
            telephone = RegistrationFormat.telephone(registration.phone),
            withdrawn = withdrawn,
            onPaper = registration.onPaper,
            accepted = registration.custodyAcceptedAt?.let { "Accettato online il ${RegistrationFormat.moment(it)}" },
            acceptedBy = registration.custodyEmail,
            version = registration.custodyVersion,
            acceptedText = registration.custodyText,
            paperReceived = registration.paperFormReceived,
            enteredBy = registration.enteredBy?.let { registrations.teacherName(it) ?: "un maestro che non è più tra i maestri" },
            notes = notes(registration, read, gathering, gate),
            readings = readings(registration.id, account.id),
            alert = alert,
            withdrawDialog = withdrawDialog(registration),
            arrivalsHref = arrivalsHref,
        )
    }
}

/**
 * The registrations for the teachers (criteria 20-28, 29): everything under `/iscrizioni` that only a teacher opens (`TeacherPaths` answers 403 to a
 * parent, whatever the address, before a request gets here). The list is `/iscrizioni`, which is also the page of the parent: [MineController] gives it
 * to this class when the person is a teacher.
 *
 * - **The notes on health are never in a page that a teacher opens: they come only with «Mostra le segnalazioni»**, a POST that writes the reading in
 *   the same transaction that gives the answer; if it cannot be written, nothing is shown (a 500, never the notes).
 * - **Typing in a paper form and correcting** are two forms: the paper form has the signed form that arrived and the consent on paper; a correction
 *   never touches what a parent accepted online, nor the notes of an online registration.
 * - **A draft has no registrations**, not even on paper (404).
 */
@Controller
class TeacherRegistrationsController(
    private val shells: ShellFactory,
    private val registrations: Registrations,
    private val gatherings: GatheringReader,
    private val healthNotes: HealthNotes,
    private val presenter: RegistrationPresenter,
    private val teacher: TeacherPresenter,
    private val policy: RetentionPolicy,
    private val texts: RetentionTexts,
    private val confirmations: PasskeyConfirmations,
) {

    private fun notFound(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND)

    /** What the card of the notes says about the confirmation, for this person now. */
    private fun gateOf(account: CurrentAccount, request: HttpServletRequest): NotesGate = NotesGate.of(confirmations.view(account, request.getSession(false)))

    /** The dialog of the withdrawal in the words of the teachers (the page of confirmation without JavaScript shows the same). */
    fun withdrawDialog(registration: RegistrationData): ConfirmModel = teacher.withdrawDialog(registration)

    /** After a withdrawal by a teacher: the detail, with the notice. */
    fun withdrawn(request: HttpServletRequest, id: Long): ModelAndView = back(request, id, "Iscrizione ritirata.")

    private fun page(template: String, title: String, model: Any, extra: Map<String, Any?> = emptyMap(), status: HttpStatus = HttpStatus.OK): ModelAndView =
        ModelAndView(template, mapOf("shell" to shells.shell(page = title, current = Section.ISCRIZIONI), "page" to model) + extra).also { it.status = status }

    private fun registration(id: Long): RegistrationData = registrations.find(id) ?: notFound()

    private fun gatheringOf(registration: RegistrationData): GatheringInfo = checkNotNull(gatherings.byId(registration.gatheringId))

    private fun back(request: HttpServletRequest, id: Long, text: String, variant: talita.ui.AlertVariant = SUCCESS): ModelAndView {
        Flash.put(request, Alert(variant, text))
        return seeOther("/iscrizioni/$id")
    }

    // ---- The list (criteria 20, 24) --------------------------------------------------------------------------------------------------

    /** The list of the gathering that starts last and is not a draft, or [gatheringId]; with only a draft, a notice; with nothing, the empty state. */
    fun list(account: CurrentAccount, request: HttpServletRequest, gatheringId: Long?): ModelAndView {
        val alert = Flash.take(request)
        val gathering = if (gatheringId != null) gatherings.byId(gatheringId) ?: notFound() else gatherings.latestNotDraft()
        // A year after the end the registrations are gone, from the day of their date: the page says when and why, and leads to the gathering.
        if (gathering != null && gathering.state != GatheringState.DRAFT && policy.registrationsExpired(gathering.end, gatherings.today())) {
            val model = TeacherListPage(
                TeacherListKind.DELETED, alert, "${gathering.title} · ${GatheringFormat.range(gathering.start, gathering.end)}", null, emptyList(), emptyList(), null, null,
                texts.registrationsDeleted(gathering.id, gathering.end),
            )
            return page("registration/teacherList", "Iscrizioni", model)
        }
        if (gathering != null && gathering.state != GatheringState.DRAFT) return page("registration/teacherList", "Iscrizioni", teacher.list(gathering, alert))
        val draft = gathering ?: gatherings.current()
        if (draft != null) {
            val model = TeacherListPage(
                TeacherListKind.DRAFT, alert, null, null, emptyList(), emptyList(), draft.title, "/convivenza/${draft.id}",
            )
            return page("registration/teacherList", "Iscrizioni", model)
        }
        return page("registration/teacherList", "Iscrizioni", TeacherListPage(TeacherListKind.NONE, alert, null, null, emptyList(), emptyList(), null, null))
    }

    // ---- The detail and the notes (criteria 21, 22) ---------------------------------------------------------------------------------------

    @GetMapping("/iscrizioni/{id:[0-9]{1,18}}")
    fun detail(account: CurrentAccount, @PathVariable id: Long, request: HttpServletRequest): ModelAndView {
        val registration = registration(id)
        return page("registration/teacherDetail", registration.name, teacher.detail(account, registration, gatheringOf(registration), null, Flash.take(request), arrivalsHref(registration), gateOf(account, request)))
    }

    /** The card of this registration in the arrivals, if it is of the gathering that the arrivals are about (the registrations of an older one have no card there). */
    private fun arrivalsHref(registration: RegistrationData): String? = if (registration.gatheringId == gatherings.latestNotDraft()?.id) "/arrivi/${registration.id}" else null

    /**
     * «Mostra le segnalazioni»: the reading is written and the notes are read in one transaction, and only then is anything shown. With htmx the answer is
     * the card of the notes and, out of band, the card of the readings; without JavaScript, the whole page. Never cached.
     */
    @PostMapping("/iscrizioni/{id:[0-9]{1,18}}/mostra-segnalazioni")
    fun showNotes(account: CurrentAccount, @PathVariable id: Long, request: HttpServletRequest): ModelAndView {
        val registration = registration(id)
        val gathering = gatheringOf(registration)
        // The check comes first, and `readForTeacher` wants its proof: without a confirmation that holds, a teacher with a passkey reads nothing and writes no reading (TALITA-15, criterion 39).
        val proof = when (val gate = confirmations.gate(account, request.getSession(false))) {
            is Gate.Granted -> gate.proof

            Gate.Needed -> {
                // 409, like the other refusals that depend on the state of things: the card is replaced with the notice (htmx), or the whole page says it (without JavaScript).
                val needs = NotesGate(if (request.isHtmxFragment()) GateMode.SERVE else GateMode.SERVE_NO_JS)
                val refused = teacher.detail(account, registration, gathering, null, null, arrivalsHref(registration), needs)
                return if (request.isHtmxFragment()) {
                    ModelAndView("registration/notesCard", mapOf("page" to refused, "oob" to false)).also { it.status = HttpStatus.CONFLICT }
                } else {
                    page("registration/teacherDetail", registration.name, refused, status = HttpStatus.CONFLICT)
                }
            }
        }
        val read = healthNotes.readForTeacher(id, proof)
        val detail = teacher.detail(account, registration, gathering, read, null, arrivalsHref(registration))
        return if (request.isHtmxFragment()) {
            ModelAndView("registration/notesCard", mapOf("page" to detail, "oob" to true))
        } else {
            page("registration/teacherDetail", registration.name, detail)
        }
    }

    // ---- The manleva (criterion 21) --------------------------------------------------------------------------------------------------

    @PostMapping("/iscrizioni/{id:[0-9]{1,18}}/manleva")
    fun markPaper(@PathVariable id: Long, @RequestParam(name = "received", defaultValue = "true") received: Boolean, request: HttpServletRequest): ModelAndView {
        registration(id)
        return when (registrations.markPaper(id, received)) {
            ChangeResult.Done, ChangeResult.AlreadyDone -> back(request, id, if (received) "Manleva segnata come ricevuta." else "Manleva segnata come da ricevere.")
            else -> notFound()
        }
    }

    // ---- A paper form (criteria 25, 29) ----------------------------------------------------------------------------------------------

    private fun openGathering(id: Long?): GatheringInfo {
        val gathering = (if (id != null) gatherings.byId(id) else gatherings.latestNotDraft()) ?: notFound()
        if (!Rules.teacherCanChange(gathering)) notFound()
        // A year after the end the registrations are gone: there is nothing to type in, and the page of «Iscrizioni» says when and why (TALITA-7, design part 3, 5).
        if (policy.registrationsExpired(gathering.end, gatherings.today())) notFound()
        return gathering
    }

    /** The notes of this gathering are past their date: they would be deleted the next night, so they are not written any more. */
    private fun notesGone(gathering: GatheringInfo): Boolean = policy.notesExpired(gathering.end, gatherings.today())

    private fun notesDeletedSentence(gathering: GatheringInfo): String = "Le segnalazioni di questa convivenza sono state cancellate il ${GatheringFormat.date(policy.notesDeletionDate(gathering.end))}: non si possono più scrivere."

    private fun notesDeletedRedirect(request: HttpServletRequest, id: Long, gathering: GatheringInfo): ModelAndView = back(
        request, id,
        "Le segnalazioni sono state cancellate il ${GatheringFormat.date(policy.notesDeletionDate(gathering.end))}: non si possono più scrivere né correggere.", WARNING,
    )

    private fun paperPage(gathering: GatheringInfo, form: FormInput, check: FormCheck?, received: Boolean, alert: Alert?, status: HttpStatus = HttpStatus.OK, fromArrivals: Boolean = false): ModelAndView {
        val offer = presenter.offer(gathering)
        val gone = notesGone(gathering)
        val notice = if (!Rules.parentCanChange(gathering)) Alert(INFO, "Le iscrizioni sono chiuse: i genitori non possono più iscriversi, ma tu puoi inserire un modulo di carta.") else null
        val model = PaperPage(
            title = "Inserisci un’iscrizione",
            lead = "${gathering.title} · ${GatheringFormat.range(gathering.start, gathering.end)}",
            intro = "Per chi ha compilato il modulo di carta. Scrivi i dati come sono sul modulo, un bambino alla volta. L’iscrizione risulta inserita da te.",
            notice = notice,
            alert = alert,
            phone = form.phone,
            phoneError = check?.phoneError,
            child = presenter.childView(0, 1, form.children.first(), check?.children?.firstOrNull(), offer, withHealth = !gone),
            offer = presenter.offerView(offer),
            received = received,
            formAction = "/iscrizioni/nuova",
            quotaAction = "/iscrizioni/nuova/quota",
            submit = "Inserisci l’iscrizione",
            busy = "Inserimento…",
            cancelHref = if (fromArrivals) "/arrivi" else "/iscrizioni?convivenza=${gathering.id}",
            gatheringId = gathering.id,
            inserting = true,
            paper = true,
            hasNotes = false,
            id = null,
            fromArrivals = fromArrivals,
            notesDeleted = if (gone) notesDeletedSentence(gathering) else null,
        )
        return page("registration/paper", model.title, model, status = status)
    }

    @GetMapping("/iscrizioni/nuova")
    fun newPaper(@RequestParam(name = "convivenza", required = false) gatheringId: Long?, @RequestParam(name = "ritorno", required = false) back: String?): ModelAndView {
        val gathering = openGathering(gatheringId)
        return paperPage(gathering, FormInput("", listOf(ChildInput())), null, false, null, fromArrivals = back == ARRIVALS)
    }

    @PostMapping("/iscrizioni/nuova")
    fun insertPaper(account: CurrentAccount, @RequestParam params: Map<String, String>, request: HttpServletRequest): ModelAndView {
        val gathering = openGathering(params["convivenza"]?.trim()?.toLongOrNull())
        val form = FormParser.parse(params).let { it.copy(children = it.children.take(1)) }
        val received = params["paperReceived"] == "true"
        val fromArrivals = params["ritorno"] == ARRIVALS
        val check = RegistrationChecks.check(form, presenter.offer(gathering), gathering.start, gatherings.today(), withHealth = !notesGone(gathering), paper = true)
        if (!check.valid) {
            val errors = if (check.errorCount > 1) Alert(DANGER, "Controlla i ${check.errorCount} campi segnati in rosso.") else null
            return paperPage(gathering, form, check, received, errors, HttpStatus.UNPROCESSABLE_ENTITY, fromArrivals)
        }
        return when (val result = registrations.insertPaper(account.id, gathering.id, check.phone, check.children.single(), received)) {
            // Opened from the arrivals, it leads back to them: to the card of the new registration there, with the same notice.
            is Registrations.PaperResult.Created -> if (fromArrivals) {
                Flash.put(request, Alert(SUCCESS, "Iscrizione inserita."))
                seeOther("/arrivi/${result.id}")
            } else {
                back(request, result.id, "Iscrizione inserita.")
            }
            is Registrations.PaperResult.NotAllowed, Registrations.PaperResult.NotFound -> notFound()
        }
    }

    /** The quota of the paper form while the days change. */
    @PostMapping("/iscrizioni/nuova/quota")
    fun paperQuota(@RequestParam params: Map<String, String>): ModelAndView {
        val gathering = openGathering(params["convivenza"]?.trim()?.toLongOrNull())
        val input = FormParser.parse(params).children.first()
        return ModelAndView("registration/quotaFragment", mapOf("lines" to listOf(QuotaLine(0, presenter.quota(presenter.offer(gathering), input)?.let(RegistrationFormat::money))), "total" to null))
    }

    // ---- A correction (criteria 26, 14) ------------------------------------------------------------------------------------------------

    private fun correctPage(registration: RegistrationData, gathering: GatheringInfo, form: FormInput, check: FormCheck?, received: Boolean, alert: Alert?, status: HttpStatus = HttpStatus.OK): ModelAndView {
        val restoring = registration.status == Status.WITHDRAWN
        val offer = presenter.offer(gathering)
        val hasNotes = healthNotes.existing(listOf(registration.id)).isNotEmpty()
        val title = if (restoring) "Rimetti l’iscrizione" else "Correggi l’iscrizione"
        val model = PaperPage(
            title = title,
            lead = "${registration.name} · ${gathering.title}",
            intro = null,
            notice = null,
            alert = alert,
            phone = form.phone,
            phoneError = check?.phoneError,
            child = presenter.childView(0, 1, form.children.first(), check?.children?.firstOrNull(), offer, withHealth = false),
            offer = presenter.offerView(offer),
            received = received,
            formAction = "/iscrizioni/${registration.id}/correggi",
            quotaAction = "/iscrizioni/${registration.id}/correggi/quota",
            submit = if (restoring) "Rimetti l’iscrizione" else "Salva le correzioni",
            busy = if (restoring) "Ripristino…" else "Salvataggio…",
            cancelHref = "/iscrizioni/${registration.id}",
            gatheringId = gathering.id,
            inserting = false,
            paper = registration.onPaper,
            hasNotes = hasNotes,
            id = registration.id,
            notesGone = notesGone(gathering),
        )
        return page("registration/paper", title, model, status = status)
    }

    private fun inputOf(registration: RegistrationData, gathering: GatheringInfo): FormInput = FormInput(
        registration.phone,
        listOf(
            ChildInput(
                firstName = registration.firstName,
                lastName = registration.lastName,
                birthDay = registration.birthDate.dayOfMonth.toString(),
                birthMonth = registration.birthDate.monthValue.toString(),
                birthYear = registration.birthDate.year.toString(),
                presence = if (registration.presence.allDays) "all_days" else if (gathering.days.size == 1) "lunch" else "days",
                days = registration.presence.days.mapKeys { Quota.key(it.key) }.mapValues { it.value.code },
                status = if (registration.status == Status.WITHDRAWN) Status.CONFIRMED.code else registration.status.code,
            ),
        ),
    )

    @GetMapping("/iscrizioni/{id:[0-9]{1,18}}/correggi")
    fun correct(@PathVariable id: Long): ModelAndView {
        val registration = registration(id)
        val gathering = gatheringOf(registration)
        if (!Rules.teacherCanChange(gathering)) notFound()
        return correctPage(registration, gathering, inputOf(registration, gathering), null, registration.paperFormReceived, null)
    }

    @PostMapping("/iscrizioni/{id:[0-9]{1,18}}/correggi")
    fun saveCorrection(account: CurrentAccount, @PathVariable id: Long, @RequestParam params: Map<String, String>, request: HttpServletRequest): ModelAndView {
        val registration = registration(id)
        val gathering = gatheringOf(registration)
        if (!Rules.teacherCanChange(gathering)) notFound()
        val form = FormParser.parse(params).let { it.copy(children = it.children.take(1)) }
        val received = params["paperReceived"] == "true"
        val check = RegistrationChecks.check(form, presenter.offer(gathering), gathering.start, gatherings.today(), withHealth = false)
        if (!check.valid) {
            val errors = if (check.errorCount > 1) Alert(DANGER, "Controlla i ${check.errorCount} campi segnati in rosso.") else null
            return correctPage(registration, gathering, form, check, received, errors, HttpStatus.UNPROCESSABLE_ENTITY)
        }
        return when (val result = registrations.update(id, null, check.phone, check.children.single(), received.takeIf { registration.onPaper })) {
            ChangeResult.Done, ChangeResult.AlreadyDone -> back(request, id, if (registration.status == Status.WITHDRAWN) "Iscrizione rimessa." else "Iscrizione salvata.")
            ChangeResult.NotFound, ChangeResult.IsWithdrawn, is ChangeResult.NotAllowed -> notFound()
            is ChangeResult.Taken -> correctPage(
                registration, gathering, form,
                check.copy(children = check.children.map { it.copy(errors = it.errors + ("firstName" to "${result.name} ${Registrations.TAKEN}"), taken = true) }), received, null, HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
    }

    @PostMapping("/iscrizioni/{id:[0-9]{1,18}}/correggi/quota")
    fun correctQuota(@PathVariable id: Long, @RequestParam params: Map<String, String>): ModelAndView {
        val offer = presenter.offer(gatheringOf(registration(id)))
        val input = FormParser.parse(params).children.first()
        return ModelAndView("registration/quotaFragment", mapOf("lines" to listOf(QuotaLine(0, presenter.quota(offer, input)?.let(RegistrationFormat::money))), "total" to null))
    }

    // ---- The notes of a paper registration (criteria 22, 26) --------------------------------------------------------------------------------

    private fun healthPage(registration: RegistrationData, gathering: GatheringInfo, input: ChildInput, errors: Map<String, String>, alert: Alert?, status: HttpStatus = HttpStatus.OK, confirm: NotesGate? = null): ModelAndView {
        val view = presenter.childView(0, 1, input, ChildCheck(input, errors, "", "", null, null, null, null, null), presenter.offer(gathering))
        val model = HealthPage(
            id = registration.id,
            title = "Correggi le segnalazioni",
            lead = "${registration.name} · ${gathering.title}",
            firstName = registration.firstName,
            child = view,
            consentAt = null,
            exists = false,
            closedNotice = false,
            alert = alert,
            togli = null,
            formAction = "/iscrizioni/${registration.id}/correggi-segnalazioni",
            cancelHref = "/iscrizioni/${registration.id}",
            paper = true,
            intro = false,
            warning = if (confirm == null && healthNotes.existing(listOf(registration.id)).isNotEmpty()) "Questa pagina mostra le segnalazioni: la lettura è stata registrata a tuo nome." else null,
            confirm = confirm,
        )
        return page("registration/health", "Correggi le segnalazioni", model, status = status)
    }

    /** Shows the notes of a paper registration to correct them: the page shows them, so the reading is written first; with no notes there is nothing to read. */
    @GetMapping("/iscrizioni/{id:[0-9]{1,18}}/correggi-segnalazioni")
    fun correctNotes(account: CurrentAccount, @PathVariable id: Long, request: HttpServletRequest): ModelAndView {
        val registration = registration(id)
        if (!registration.onPaper) notFound()
        val gathering = gatheringOf(registration)
        if (!Rules.teacherCanChange(gathering) || registration.status == Status.WITHDRAWN) notFound()
        if (notesGone(gathering)) return notesDeletedRedirect(request, id, gathering)
        val exists = healthNotes.existing(listOf(id)).isNotEmpty()
        // The page shows the notes, so reading them is the same act as opening them from the detail: a teacher with a passkey confirms first, and until then the page has no fields, reads nothing and
        // registers nothing (TALITA-15, criterion 39). A paper registration with no notes has nothing to confirm.
        val proof = if (exists) {
            when (val gate = confirmations.gate(account, request.getSession(false))) {
                is Gate.Granted -> gate.proof
                Gate.Needed -> return healthPage(registration, gathering, ChildInput(), emptyMap(), null, confirm = NotesGate(GateMode.CONFIRM))
            }
        } else {
            null
        }
        val note = if (proof != null) healthNotes.readForTeacher(id, proof).note else null
        val input = ChildInput(allergies = note?.notes?.allergies.orEmpty(), intolerances = note?.notes?.intolerances.orEmpty(), medications = note?.notes?.medications.orEmpty(), healthConsent = note != null)
        return healthPage(registration, gathering, input, emptyMap(), null)
    }

    @PostMapping("/iscrizioni/{id:[0-9]{1,18}}/correggi-segnalazioni")
    fun saveNotes(account: CurrentAccount, @PathVariable id: Long, @RequestParam params: Map<String, String>, request: HttpServletRequest): ModelAndView {
        val registration = registration(id)
        if (!registration.onPaper) notFound()
        val gathering = gatheringOf(registration)
        if (notesGone(gathering)) return notesDeletedRedirect(request, id, gathering)
        val input = ChildInput(
            allergies = params["allergies"].orEmpty().take(5000), intolerances = params["intolerances"].orEmpty().take(5000),
            medications = params["medications"].orEmpty().take(5000), healthConsent = params["healthConsent"] == "true",
        )
        val (notes, errors) = RegistrationChecks.notes(input.allergies, input.intolerances, input.medications, input.healthConsent, paper = true)
        if (errors.isNotEmpty()) {
            val many = if (errors.size > 1) Alert(DANGER, "Controlla i ${errors.size} campi segnati in rosso.") else null
            return healthPage(registration, gathering, input, errors, many, HttpStatus.UNPROCESSABLE_ENTITY)
        }
        return when (registrations.saveHealthOnPaper(id, account.id, notes)) {
            ChangeResult.Done, ChangeResult.AlreadyDone -> back(request, id, if (notes == null) "Segnalazioni tolte." else "Segnalazioni salvate.")
            ChangeResult.IsWithdrawn -> back(request, id, "L’iscrizione è ritirata: non ci sono segnalazioni da cambiare.", WARNING)
            else -> notFound()
        }
    }
}
