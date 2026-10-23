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
import talita.account.seeOther
import talita.gathering.Badge
import talita.gathering.GatheringFormat
import talita.gathering.GatheringInfo
import talita.gathering.GatheringReader
import talita.gathering.GatheringState
import talita.ui.Alert
import talita.ui.AlertVariant
import talita.ui.AlertVariant.DANGER
import talita.ui.AlertVariant.INFO
import talita.ui.AlertVariant.SUCCESS
import talita.ui.AlertVariant.WARNING
import talita.ui.ConfirmModel
import talita.ui.Flash
import talita.ui.Icon
import talita.ui.Section
import talita.ui.ShellFactory

/**
 * **The filter «only his own»** (criterion 16): the one place that says whether a registration is the one of the person who asks. Every address of the
 * parent that has the id of a registration (reading, change, withdrawal, notes; GET and POST) goes through [require], and a registration of another
 * person, a teacher who is not the parent and an id that does not exist all get the same answer: the page of an id that does not exist, and nothing
 * changes. A test tries every address with the parent, with another parent, with a teacher and with nobody.
 */
@Component
class OwnRegistrations(private val registrations: Registrations) {

    /** The registration [id] if it belongs to [account]; null for anything else. */
    fun find(account: CurrentAccount, id: Long): RegistrationData? = registrations.find(id)?.takeIf { it.accountId == account.id }

    /** The registration or the answer 404 of an id that does not exist. */
    fun require(account: CurrentAccount, id: Long): RegistrationData = find(account, id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    /**
     * The same, except for the withdrawal: a teacher withdraws any registration (criterion 26), with the pages of the teachers; whoever else, only their own.
     * It is the only address with an id that two roles share, and the only place that says so.
     */
    fun requireOrTeacher(account: CurrentAccount, id: Long): RegistrationData =
        if (account.isTeacher) registrations.find(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND) else require(account, id)
}

/** A button of the card of a registration: the text, the address (and the page of confirmation without JavaScript), the icon and the dialog that it opens. */
data class CardAction(val label: String, val ariaLabel: String, val href: String, val icon: Icon, val opens: String?)

/** The card of a registration of the parent (criterion 11): everything the parent knows of it, read as it is. */
data class RegistrationCard(
    val id: Long,
    val name: String,
    val status: String,
    val statusTone: String,
    val withdrawn: Boolean,
    val presence: String,
    val quota: String,
    val phone: String,
    val health: String,
    val accepted: String?,
    val actions: List<CardAction>,
)

/** The registrations of one gathering of the parent: the heading, what the situation is, the cards, the total, and the way to register another child. */
data class MineSection(
    val id: Long,
    val title: String,
    val dates: String,
    val badges: List<Badge>,
    val notice: Alert?,
    val cards: List<RegistrationCard>,
    val total: String?,
    val anotherHref: String?,
    val dialogs: List<ConfirmModel>,
    /** Non-withdrawn registrations, and the sum of their fees: what the home says (`2 bambini · 95 €`). */
    val count: Int,
    val sum: Int,
)

data class MinePage(val alert: Alert?, val sections: List<MineSection>)

/** What the page of a modification shows: the data of the child, the phone, and what surrounds them. */
data class EditPage(
    val id: Long,
    val title: String,
    val lead: String,
    val notice: Alert,
    val alert: Alert?,
    val phone: String,
    val phoneError: String?,
    val child: ChildView,
    val offer: OfferView,
    val formAction: String,
    val quotaAction: String,
    val submit: String,
    val busy: String,
)

/**
 * What the page of the notes on health of a registration shows: the parent's («Segnalazioni») and the one where a teacher corrects the notes of a paper
 * registration («Correggi le segnalazioni»). [togli] is the dialog that takes them away (the parent only); [intro] the paragraphs of the parent;
 * [warning] the notice that this page shows the notes, so a reading was written.
 */
data class HealthPage(
    val id: Long,
    val title: String,
    val lead: String,
    val firstName: String,
    val child: ChildView,
    val consentAt: String?,
    val exists: Boolean,
    val closedNotice: Boolean,
    val alert: Alert?,
    val togli: ConfirmModel?,
    val formAction: String,
    val cancelHref: String,
    val paper: Boolean = false,
    val intro: Boolean = true,
    val warning: String? = null,
)

/** Builds what the pages of the parent show of the registrations they have made. The sentences are those of the design (B of TALITA-5). */
@Component
class MinePresenter(
    private val registrations: Registrations,
    private val gatherings: GatheringReader,
    private val healthNotes: HealthNotes,
    private val presenter: RegistrationPresenter,
) {

    private fun quotaOf(gathering: GatheringInfo, registration: RegistrationData): Int? = Quota.due(presenter.offer(gathering), registration.presence)

    private fun badges(gathering: GatheringInfo): List<Badge> = buildList {
        add(
            when (gathering.state) {
                GatheringState.OPEN -> Badge("Iscrizioni aperte", "info")
                else -> Badge("Iscrizioni chiuse")
            },
        )
        if (gathering.over) add(Badge("Finita"))
    }

    fun withdrawDialog(registration: RegistrationData, gathering: GatheringInfo) = ConfirmModel(
        id = "ritira-${registration.id}",
        title = "Ritirare l’iscrizione?",
        text = "L’iscrizione di ${registration.name} alla ${gathering.title} risulterà ritirata: la quota non è dovuta e i giorni non contano più. " +
            "Le segnalazioni, se ci sono, vengono cancellate subito. Resta fra le tue iscrizioni con lo stato «Ritirata»: finché le iscrizioni sono aperte " +
            "puoi rimetterla «Confermata» o «In forse».",
        confirmLabel = "Ritira l’iscrizione",
        busyLabel = "Ritiro…",
        action = "/iscrizioni/${registration.id}/ritira",
        field = "from",
        value = registration.status.code,
        target = null,
        cancelHref = "/iscrizioni",
    )

    fun revokeDialog(registration: RegistrationData) = ConfirmModel(
        id = "togli-${registration.id}",
        title = "Togliere le segnalazioni?",
        text = "Cancelliamo subito le segnalazioni su allergie, intolleranze e farmaci di ${registration.name} e il tuo consenso: i maestri non potranno più leggerle. Puoi scriverle di nuovo quando vuoi.",
        confirmLabel = "Togli le segnalazioni",
        busyLabel = "Rimozione…",
        action = "/iscrizioni/${registration.id}/segnalazioni/togli",
        field = "from",
        value = "health",
        target = null,
        cancelHref = "/iscrizioni",
        danger = true,
    )

    /** The sections of the page «Iscrizioni» of [accountId], the gathering that starts last first (criterion 11). */
    fun sections(accountId: Long): List<MineSection> {
        val owned = registrations.ownedBy(accountId)
        val withHealth = healthNotes.existing(owned.map { it.id })
        return owned.groupBy { it.gatheringId }.mapNotNull { (gatheringId, items) -> gatherings.byId(gatheringId)?.let { it to items } }
            .sortedWith(compareByDescending<Pair<GatheringInfo, List<RegistrationData>>> { it.first.start }.thenByDescending { it.first.id })
            .map { (gathering, items) -> section(gathering, items, withHealth) }
    }

    private fun section(gathering: GatheringInfo, items: List<RegistrationData>, withHealth: Set<Long>): MineSection {
        val canChange = Rules.parentCanChange(gathering)
        val canChangeHealth = Rules.parentCanChangeHealth(gathering)
        val dialogs = mutableListOf<ConfirmModel>()
        val cards = items.map { registration ->
            val withdrawn = registration.status == Status.WITHDRAWN
            val hasHealth = registration.id in withHealth
            val actions = buildList {
                if (canChange && !withdrawn) {
                    add(CardAction("Modifica", "Modifica l’iscrizione di ${registration.name}", "/iscrizioni/${registration.id}/modifica", Icon.PENCIL, null))
                }
                if (canChangeHealth && !withdrawn) {
                    add(CardAction("Segnalazioni", "Segnalazioni di ${registration.name}", "/iscrizioni/${registration.id}/segnalazioni", Icon.HEART_PULSE, null))
                }
                if (canChange && !withdrawn) {
                    add(CardAction("Ritira l’iscrizione", "Ritira l’iscrizione di ${registration.name}", "/iscrizioni/${registration.id}/ritira", Icon.CIRCLE_X, "ritira-${registration.id}"))
                    dialogs += withdrawDialog(registration, gathering)
                }
                if (canChange && withdrawn) {
                    add(CardAction("Rimetti l’iscrizione", "Rimetti l’iscrizione di ${registration.name}", "/iscrizioni/${registration.id}/modifica", Icon.ROTATE_CCW, null))
                }
                if (gathering.over && hasHealth) {
                    add(CardAction("Togli le segnalazioni", "Togli le segnalazioni di ${registration.name}", "/iscrizioni/${registration.id}/segnalazioni/togli", Icon.TRASH_2, "togli-${registration.id}"))
                    dialogs += revokeDialog(registration)
                }
            }
            val due = quotaOf(gathering, registration)
            RegistrationCard(
                id = registration.id,
                name = registration.name,
                status = registration.status.label,
                statusTone = when (registration.status) {
                    Status.CONFIRMED -> "positive"
                    Status.MAYBE -> "warning"
                    Status.WITHDRAWN -> "neutral"
                },
                withdrawn = withdrawn,
                presence = RegistrationFormat.presence(registration.presence, gathering.days),
                quota = if (withdrawn) "Non dovuta" else due?.let(RegistrationFormat::money) ?: "Da definire",
                phone = registration.phone,
                health = if (hasHealth) "Scritte" else "Nessuna",
                accepted = registration.custodyAcceptedAt?.let { "Affidamento accettato il ${RegistrationFormat.moment(it)}" },
                actions = actions,
            )
        }
        val active = items.filter { it.status != Status.WITHDRAWN }
        val sum = active.sumOf { quotaOf(gathering, it) ?: 0 }
        val notice = when {
            gathering.over -> Alert(INFO, "La convivenza è finita.")
            !canChange -> Alert(WARNING, "Le iscrizioni sono chiuse. Per cambiare un’iscrizione o iscrivere un altro bambino, chiedi ai maestri.")
            else -> null
        }
        return MineSection(
            id = gathering.id,
            title = gathering.title,
            dates = GatheringFormat.range(gathering.start, gathering.end),
            badges = badges(gathering),
            notice = notice,
            cards = cards,
            total = if (active.isEmpty()) null else RegistrationFormat.money(sum),
            anotherHref = if (canChange) "/info/${gathering.code}/iscrizione" else null,
            dialogs = dialogs,
            count = active.size,
            sum = sum,
        )
    }

    /** The detail of a section for the home: `2 bambini · 95 €`, `1 bambino`, `Nessuna iscrizione attiva`; `Gratuita` when nothing is due. */
    fun summary(section: MineSection): String = when (section.count) {
        0 -> "Nessuna iscrizione attiva"
        else -> (if (section.count == 1) "1 bambino" else "${section.count} bambini") + " · " + RegistrationFormat.money(section.sum)
    }
}

/**
 * The registrations of the parent (criteria 11-16, 19): `/iscrizioni`, the change, the withdrawal and the notes on health. A card shows everything,
 * and every action has its page (or its dialog): a change ends with a change of page and a notice, nothing is replaced by itself. A registration that
 * is not the person's is the page of an id that does not exist ([OwnRegistrations]); closed registrations, or a gathering that is over, only look.
 *
 * A teacher has the same address `/iscrizioni`, and sees the list of the teachers (`TeacherRegistrationsController`).
 */
@Controller
class MineController(
    private val shells: ShellFactory,
    private val own: OwnRegistrations,
    private val registrations: Registrations,
    private val gatherings: GatheringReader,
    private val healthNotes: HealthNotes,
    private val presenter: RegistrationPresenter,
    private val mine: MinePresenter,
    private val teacherPages: TeacherRegistrationsController,
) {

    // ---- The list ---------------------------------------------------------------------------------------------------------------

    @GetMapping("/iscrizioni")
    fun list(account: CurrentAccount, request: HttpServletRequest, @RequestParam(name = "convivenza", required = false) gathering: Long?): ModelAndView =
        if (account.isTeacher) teacherPages.list(account, request, gathering) else listPage(account, Flash.take(request), HttpStatus.OK)

    private fun listPage(account: CurrentAccount, alert: Alert?, status: HttpStatus): ModelAndView =
        ModelAndView(
            "registration/mine",
            mapOf("shell" to shells.shell(page = "Iscrizioni", current = Section.ISCRIZIONI), "page" to MinePage(alert, mine.sections(account.id))),
        ).also { it.status = status }

    /** A change that cannot be made now (criterion 15): the same page, a notice in front, nothing changed. */
    private fun notAllowed(account: CurrentAccount, result: ChangeResult.NotAllowed, what: String): ModelAndView =
        listPage(account, Alert(WARNING, if (result.over) "La convivenza è finita: non è stato cambiato niente." else "Le iscrizioni si sono chiuse mentre stavi cambiando $what: non è stato cambiato niente. Per cambiarla, chiedi ai maestri."), HttpStatus.CONFLICT)

    private fun back(request: HttpServletRequest, text: String, variant: AlertVariant = SUCCESS): ModelAndView {
        Flash.put(request, Alert(variant, text))
        return seeOther("/iscrizioni")
    }

    // ---- Modification and «Rimetti» -----------------------------------------------------------------------------------------------

    private fun editPage(registration: RegistrationData, gathering: GatheringInfo, form: FormInput, check: FormCheck?, alert: Alert?, status: HttpStatus = HttpStatus.OK): ModelAndView {
        val restoring = registration.status == Status.WITHDRAWN
        val offer = presenter.offer(gathering)
        val accepted = RegistrationFormat.day(checkNotNull(registration.custodyAcceptedAt) { "a registration of a parent has its acceptance" })
        val notice = Alert(
            INFO,
            if (restoring) {
                "L’iscrizione è ritirata. Scegli «Confermata» o «In forse» e tocca «Rimetti l’iscrizione»: l’affidamento che hai accettato il $accepted resta valido. Le segnalazioni si cancellano con il ritiro: se servono, scrivile di nuovo da «Segnalazioni»."
            } else {
                "L’affidamento che hai accettato il $accepted non cambia. Per allergie, intolleranze e farmaci usa «Segnalazioni» nella scheda dell’iscrizione."
            },
        )
        val page = EditPage(
            id = registration.id,
            title = if (restoring) "Rimetti l’iscrizione" else "Modifica l’iscrizione",
            lead = "${registration.name} · ${gathering.title}",
            notice = notice,
            alert = alert,
            phone = form.phone,
            phoneError = check?.phoneError,
            child = presenter.childView(0, 1, form.children.first(), check?.children?.firstOrNull(), offer, withHealth = false),
            offer = presenter.offerView(offer),
            formAction = "/iscrizioni/${registration.id}/modifica",
            quotaAction = "/iscrizioni/${registration.id}/modifica/quota",
            submit = if (restoring) "Rimetti l’iscrizione" else "Salva le modifiche",
            busy = if (restoring) "Ripristino…" else "Salvataggio…",
        )
        return ModelAndView("registration/edit", mapOf("shell" to shells.shell(page = page.title, current = Section.ISCRIZIONI), "page" to page)).also { it.status = status }
    }

    private fun inputOf(registration: RegistrationData, gathering: GatheringInfo): FormInput {
        val days = registration.presence.days.mapKeys { Quota.key(it.key) }.mapValues { it.value.code }
        val oneDay = gathering.days.size == 1
        return FormInput(
            registration.phone,
            listOf(
                ChildInput(
                    firstName = registration.firstName,
                    lastName = registration.lastName,
                    birthDay = registration.birthDate.dayOfMonth.toString(),
                    birthMonth = registration.birthDate.monthValue.toString(),
                    birthYear = registration.birthDate.year.toString(),
                    presence = if (registration.presence.allDays) "all_days" else if (oneDay) "lunch" else "days",
                    days = days,
                    status = if (registration.status == Status.WITHDRAWN) Status.CONFIRMED.code else registration.status.code,
                ),
            ),
        )
    }

    @GetMapping("/iscrizioni/{id:[0-9]{1,18}}/modifica")
    fun edit(account: CurrentAccount, @PathVariable id: Long): ModelAndView {
        val registration = own.require(account, id)
        val gathering = gatherings.byId(registration.gatheringId)!!
        if (!Rules.parentCanChange(gathering)) return notAllowed(account, ChangeResult.NotAllowed(gathering.over), "l’iscrizione")
        return editPage(registration, gathering, inputOf(registration, gathering), null, null)
    }

    @PostMapping("/iscrizioni/{id:[0-9]{1,18}}/modifica")
    fun saveEdit(account: CurrentAccount, @PathVariable id: Long, @RequestParam params: Map<String, String>, request: HttpServletRequest): ModelAndView {
        val registration = own.require(account, id)
        val gathering = gatherings.byId(registration.gatheringId)!!
        if (!Rules.parentCanChange(gathering)) return notAllowed(account, ChangeResult.NotAllowed(gathering.over), "l’iscrizione")
        val form = FormParser.parse(params).let { it.copy(children = it.children.take(1)) }
        val check = registrations.markTaken(RegistrationChecks.check(form, presenter.offer(gathering), gathering.start, gatherings.today(), withHealth = false), account.id, gathering.id, except = id)
        val errors = if (check.errorCount > 1) Alert(DANGER, "Controlla i ${check.errorCount} campi segnati in rosso.") else null
        if (!check.valid) return editPage(registration, gathering, form, check, errors, HttpStatus.UNPROCESSABLE_ENTITY)
        return when (val result = registrations.update(id, account.id, check.phone, check.children.single())) {
            ChangeResult.Done, ChangeResult.AlreadyDone -> back(request, if (registration.status == Status.WITHDRAWN) "Iscrizione rimessa." else "Iscrizione salvata.")
            ChangeResult.NotFound, ChangeResult.IsWithdrawn -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            is ChangeResult.NotAllowed -> notAllowed(account, result, "l’iscrizione")
            is ChangeResult.Taken -> editPage(
                registration, gathering, form,
                check.copy(children = check.children.map { it.copy(errors = it.errors + ("firstName" to "${it.name} ${Registrations.TAKEN}"), taken = true) }), null, HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
    }

    /** The quota of the block while the days change, as a piece, like the one of the first step. */
    @PostMapping("/iscrizioni/{id:[0-9]{1,18}}/modifica/quota")
    fun editQuota(account: CurrentAccount, @PathVariable id: Long, @RequestParam params: Map<String, String>): ModelAndView {
        val registration = own.require(account, id)
        val offer = presenter.offer(gatherings.byId(registration.gatheringId)!!)
        val input = FormParser.parse(params).children.first()
        return ModelAndView("registration/quotaFragment", mapOf("lines" to listOf(QuotaLine(0, presenter.quota(offer, input)?.let(RegistrationFormat::money))), "total" to null))
    }

    // ---- Withdrawal -------------------------------------------------------------------------------------------------------------------

    /** The page that stands in for the dialog «Ritirare l’iscrizione?» without JavaScript (same words). */
    @GetMapping("/iscrizioni/{id:[0-9]{1,18}}/ritira")
    fun confirmWithdrawal(account: CurrentAccount, @PathVariable id: Long): ModelAndView {
        val registration = own.requireOrTeacher(account, id)
        val gathering = gatherings.byId(registration.gatheringId)!!
        if (registration.status == Status.WITHDRAWN) return seeOther(if (account.isTeacher) "/iscrizioni/$id" else "/iscrizioni")
        if (account.isTeacher) {
            val model = teacherPages.withdrawDialog(registration)
            return ModelAndView("pages/confirmAction", mapOf("shell" to shells.shell(page = model.title, current = Section.ISCRIZIONI), "page" to model))
        }
        if (!Rules.parentCanChange(gathering)) return notAllowed(account, ChangeResult.NotAllowed(gathering.over), "l’iscrizione")
        val model = mine.withdrawDialog(registration, gathering)
        return ModelAndView("pages/confirmAction", mapOf("shell" to shells.shell(page = model.title, current = Section.ISCRIZIONI), "page" to model))
    }

    @PostMapping("/iscrizioni/{id:[0-9]{1,18}}/ritira")
    fun withdraw(account: CurrentAccount, @PathVariable id: Long, request: HttpServletRequest): ModelAndView {
        own.requireOrTeacher(account, id)
        // A teacher withdraws any registration and goes back to its detail; a parent only their own, and goes back to the list.
        return when (val result = registrations.withdraw(id, account.id.takeUnless { account.isTeacher })) {
            ChangeResult.Done, ChangeResult.AlreadyDone -> if (account.isTeacher) teacherPages.withdrawn(request, id) else back(request, "Iscrizione ritirata.")
            ChangeResult.NotFound, ChangeResult.IsWithdrawn, is ChangeResult.Taken -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            is ChangeResult.NotAllowed -> notAllowed(account, result, "l’iscrizione")
        }
    }

    // ---- The notes on health -------------------------------------------------------------------------------------------------------

    private fun healthPage(registration: RegistrationData, gathering: GatheringInfo, input: ChildInput, errors: Map<String, String>, alert: Alert?, status: HttpStatus = HttpStatus.OK): ModelAndView {
        val note = healthNotes.readForOwner(registration.id)
        val view = presenter.childView(0, 1, input, ChildCheck(input, errors, "", "", null, null, null, null, null), presenter.offer(gathering))
        val page = HealthPage(
            id = registration.id,
            title = "Segnalazioni",
            lead = "${registration.name} · ${gathering.title}",
            firstName = registration.firstName,
            child = view,
            consentAt = note?.let { "Consenso dato il ${RegistrationFormat.moment(it.consentAt)}." },
            exists = note != null,
            closedNotice = !Rules.parentCanChange(gathering),
            alert = alert,
            togli = mine.revokeDialog(registration),
            formAction = "/iscrizioni/${registration.id}/segnalazioni",
            cancelHref = "/iscrizioni",
        )
        return ModelAndView("registration/health", mapOf("shell" to shells.shell(page = "Segnalazioni", current = Section.ISCRIZIONI), "page" to page)).also { it.status = status }
    }

    @GetMapping("/iscrizioni/{id:[0-9]{1,18}}/segnalazioni")
    fun health(account: CurrentAccount, @PathVariable id: Long, request: HttpServletRequest): ModelAndView {
        val registration = own.require(account, id)
        if (registration.status == Status.WITHDRAWN) return back(request, "L’iscrizione è ritirata: non ci sono segnalazioni da cambiare.", WARNING)
        val gathering = gatherings.byId(registration.gatheringId)!!
        if (!Rules.parentCanChangeHealth(gathering)) return notAllowed(account, ChangeResult.NotAllowed(gathering.over), "le segnalazioni")
        val note = healthNotes.readForOwner(id)
        val input = ChildInput(allergies = note?.notes?.allergies.orEmpty(), intolerances = note?.notes?.intolerances.orEmpty(), medications = note?.notes?.medications.orEmpty(), healthConsent = note != null)
        return healthPage(registration, gathering, input, emptyMap(), null)
    }

    @PostMapping("/iscrizioni/{id:[0-9]{1,18}}/segnalazioni")
    fun saveHealth(account: CurrentAccount, @PathVariable id: Long, @RequestParam params: Map<String, String>, request: HttpServletRequest): ModelAndView {
        val registration = own.require(account, id)
        if (registration.status == Status.WITHDRAWN) return back(request, "L’iscrizione è ritirata: non ci sono segnalazioni da cambiare.", WARNING)
        val gathering = gatherings.byId(registration.gatheringId)!!
        if (!Rules.parentCanChangeHealth(gathering)) return notAllowed(account, ChangeResult.NotAllowed(gathering.over), "le segnalazioni")
        val input = ChildInput(
            allergies = params["allergies"].orEmpty().take(5000), intolerances = params["intolerances"].orEmpty().take(5000),
            medications = params["medications"].orEmpty().take(5000), healthConsent = params["healthConsent"] == "true",
        )
        val (notes, errors) = RegistrationChecks.notes(input.allergies, input.intolerances, input.medications, input.healthConsent)
        if (errors.isNotEmpty()) {
            val many = if (errors.size > 1) Alert(DANGER, "Controlla i ${errors.size} campi segnati in rosso.") else null
            return healthPage(registration, gathering, input, errors, many, HttpStatus.UNPROCESSABLE_ENTITY)
        }
        return when (val result = registrations.saveHealth(id, account.id, notes)) {
            ChangeResult.Done, ChangeResult.AlreadyDone -> back(request, if (notes == null) "Segnalazioni tolte." else "Segnalazioni salvate.")
            ChangeResult.IsWithdrawn -> back(request, "L’iscrizione è ritirata: non ci sono segnalazioni da cambiare.", WARNING)
            ChangeResult.NotFound, is ChangeResult.Taken -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            is ChangeResult.NotAllowed -> notAllowed(account, result, "le segnalazioni")
        }
    }

    /** The page that stands in for the dialog «Togliere le segnalazioni?» without JavaScript. */
    @GetMapping("/iscrizioni/{id:[0-9]{1,18}}/segnalazioni/togli")
    fun confirmRevoke(account: CurrentAccount, @PathVariable id: Long): ModelAndView {
        val model = mine.revokeDialog(own.require(account, id))
        return ModelAndView("pages/confirmAction", mapOf("shell" to shells.shell(page = model.title, current = Section.ISCRIZIONI), "page" to model))
    }

    @PostMapping("/iscrizioni/{id:[0-9]{1,18}}/segnalazioni/togli")
    fun revoke(account: CurrentAccount, @PathVariable id: Long, request: HttpServletRequest): ModelAndView {
        own.require(account, id)
        return when (registrations.revokeHealth(id, account.id)) {
            ChangeResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            else -> back(request, "Segnalazioni tolte.")
        }
    }
}
