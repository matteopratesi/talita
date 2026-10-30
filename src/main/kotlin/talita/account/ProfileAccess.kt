package talita.account

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.jooq.DSLContext
import org.springframework.http.ResponseEntity
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import talita.gathering.GatheringFormat
import talita.retention.RetentionPolicy
import talita.retention.RetentionTexts
import talita.ui.Alert
import talita.ui.AlertVariant.INFO
import talita.ui.AlertVariant.SUCCESS
import talita.ui.ConfirmModel
import talita.ui.Flash
import talita.ui.ShellFactory

/** A row of «Dispositivi collegati»: a phone or a computer where the person touched «Ricordami su questo telefono». Never a name, a browser, a place or a last use. */
data class DeviceLine(
    /** `Collegato dal 3 settembre 2027`. */
    val title: String,
    /** `Rientri senza link fino al 2 ottobre 2027.`, and for the device the person is on, «Per uscire da qui, tocca «Esci».». */
    val meta: String,
    /** The device that the person looks from: the label «Questo dispositivo» and no button (there is «Esci» above, which does the same). */
    val current: Boolean,
    /** The dialog of «Togli», null for the current device. */
    val removal: ConfirmModel?,
    /** The name of the button for a screen reader: it starts with the visible word and says which one (`Togli il dispositivo collegato dal 30 agosto 2027`). */
    val ariaLabel: String?,
)

/**
 * The block «Dispositivi collegati» of the profile (TALITA-15, criteria 14-18), which every action of the profile replaces as a whole (`#dispositivi-contenuto`): the notice, the list with its
 * dialogs, «Esci da tutti gli altri dispositivi» with its own. [full] is the sentence under a list of ten rows.
 */
data class DevicesBlock(
    val alert: Alert?,
    val intro: String,
    val lines: List<DeviceLine>,
    val full: String?,
    val exitOthers: ConfirmModel,
)

/**
 * A notice that an action of the profile leaves for the page that a form without JavaScript is sent back to, one for each block (`dispositivi`, `passkey`): the block shows it at the top, with the
 * focus, once. In memory, in the session; it carries no data. With htmx the block is the answer and the notice is in it.
 */
internal object AccessFlash {
    private fun key(area: String) = "talita.accessFlash.$area"

    fun put(request: HttpServletRequest, area: String, alert: Alert) {
        request.getSession(true).setAttribute(key(area), alert)
    }

    fun take(request: HttpServletRequest, area: String): Alert? {
        val session = request.getSession(false) ?: return null
        val alert = session.getAttribute(key(area)) as? Alert
        if (alert != null) session.removeAttribute(key(area))
        return alert
    }
}

/**
 * What the profile says about how the person enters (TALITA-15): the devices where they touched «Ricordami su questo telefono». The words are those of the design (parts 2 and 3); the dates come
 * from [RetentionPolicy] and are the ones of the role of the person **now**, so a parent who becomes a teacher sees the rows shorten. Nothing here writes a date or a number in the log.
 */
@Component
class AccessPresenter(
    private val devices: RememberedDevices,
    private val policy: RetentionPolicy,
    private val remembered: DeviceRememberMeServices,
    private val origins: SessionOrigins,
    private val passkeys: Passkeys,
    private val support: PasskeySupport,
) {

    /** The device that this request is on: the one that it came in with (a re-entry) or whose cookie it carries, or the one that its session was born from. */
    fun currentDevice(request: HttpServletRequest): Long? =
        (request.getAttribute(DeviceRememberMeServices.DEVICE) as? Long) ?: remembered.presentedRow(request)?.id ?: origins.of(request.getSession(false))?.deviceId

    private fun date(day: java.time.LocalDate): String = GatheringFormat.date(day)

    /** `per 30 giorni` or `per 7 giorni` for the role of the person. */
    private fun days(teacher: Boolean): String = policy.devices(teacher).days.toString()

    private val orLink: String get() = if (support.enabled) "un nuovo link, o una passkey" else "un nuovo link"

    fun devices(account: CurrentAccount, request: HttpServletRequest, alert: Alert?): DevicesBlock {
        val current = currentDevice(request)
        // A row that is past its day does not enter any more even if the job has not deleted it yet: it is not shown and not counted.
        val rows = devices.ofAccount(account.id).filterNot { devices.expired(it, account.isTeacher) }
            .sortedWith(compareByDescending<DeviceRow> { it.id == current }.thenByDescending { it.createdOn }.thenByDescending { it.id })
        val lines = rows.mapIndexed { index, row ->
            val since = date(row.createdOn)
            val until = date(policy.deviceLastDay(row.createdOn, account.isTeacher))
            val isCurrent = row.id == current
            DeviceLine(
                title = "Collegato dal $since",
                meta = "Rientri senza link fino al $until." + if (isCurrent) " Per uscire da qui, tocca «Esci»." else "",
                current = isCurrent,
                removal = if (isCurrent) null else removal(index + 1, row, since),
                ariaLabel = if (isCurrent) null else "Togli il dispositivo collegato dal $since",
            )
        }
        val intro = if (lines.isEmpty()) {
            "Nessun dispositivo collegato. Quando entri con un link, puoi spuntare «Ricordami su questo telefono»: da lì rientri senza chiedere un nuovo link, per ${days(account.isTeacher)} giorni."
        } else {
            "Telefoni e computer dove hai scelto «Ricordami su questo telefono»: da lì rientri senza chiedere un link, per ${days(account.isTeacher)} giorni dal giorno della scelta."
        }
        return DevicesBlock(
            alert = alert,
            intro = intro,
            lines = lines,
            full = if (lines.size >= RememberedDevices.MAX_PER_ACCOUNT) "Hai ${RememberedDevices.MAX_PER_ACCOUNT} dispositivi collegati, il massimo. Se ne colleghi un altro, esce quello collegato da più tempo." else null,
            exitOthers = exitOthers(passkeys.hasAny(account.id)),
        )
    }

    private fun removal(position: Int, row: DeviceRow, since: String) = ConfirmModel(
        id = "togli-dispositivo-$position",
        title = "Togliere il dispositivo collegato dal $since?",
        text = "Quel telefono o computer esce da Talità. Per rientrare servirà $orLink.",
        confirmLabel = "Togli il dispositivo",
        busyLabel = "Rimozione…",
        action = "/profilo/dispositivi/togli",
        field = "dispositivo",
        value = row.id.toString(),
        target = BLOCK,
        cancelHref = "/profilo",
        more = listOf("Gli altri dispositivi restano collegati."),
    )

    /** The dialog of «Esci da tutti gli altri dispositivi». [withPasskeys]: the account has passkeys, which this does not touch and the dialog sends to their list. */
    private fun exitOthers(withPasskeys: Boolean) = ConfirmModel(
        id = "esci-altri",
        title = "Uscire da tutti gli altri dispositivi?",
        text = "Tutti gli altri telefoni e computer escono da Talità, anche quelli dove hai scelto «Ricordami su questo telefono». Per rientrare servirà $orLink.",
        confirmLabel = "Esci da tutti gli altri dispositivi",
        busyLabel = "Uscita…",
        action = "/profilo/dispositivi/esci-altri",
        field = "azione",
        value = "esci-altri",
        target = BLOCK,
        cancelHref = "/profilo",
        more = listOf("Questo dispositivo resta dentro."),
        note = if (withPasskeys) "Le passkey non cambiano. Se ce n’è una di un telefono perso, o che non è tua, toglila dalla scheda «Passkey», qui sotto." else null,
    )

    // ---- The notices ------------------------------------------------------------------------------------------------------------------------

    fun removed(): Alert = Alert(SUCCESS, "", lead = "Dispositivo tolto.")

    fun alreadyRemoved(): Alert = Alert(INFO, "Questo dispositivo era già stato tolto.")

    /** After «Esci da tutti gli altri dispositivi»: this one stays inside, and the person is told to look at the passkeys if there are any. */
    fun othersExited(withPasskeys: Boolean): Alert =
        if (withPasskeys) {
            Alert(SUCCESS, "Questo resta dentro. Controlla le passkey: se ce n’è una che non è tua, o di un telefono che non hai più, toglila.", "Gli altri dispositivi sono usciti.", "Vai alle passkey", "/profilo#passkey")
        } else {
            Alert(SUCCESS, "Questo resta dentro.", "Gli altri dispositivi sono usciti.")
        }

    companion object {
        /** The block that the actions replace. */
        const val BLOCK = "#dispositivi-contenuto"
    }
}

/** What «Esci da tutti gli altri dispositivi» does, in one transaction: the rows of the others go and the number of the sessions of the account grows. */
@Component
class OtherDevices(private val dsl: DSLContext, private val devices: RememberedDevices, private val accounts: Accounts) {

    /** Closes every session and every device of [accountId] but the device [keep] (the one of the person who asks), and returns the new number of the sessions. */
    fun closeAllBut(accountId: Long, keep: Long?): Int = dsl.transactionResult { configuration ->
        val tx = org.jooq.impl.DSL.using(configuration)
        devices.deleteOthers(tx, accountId, keep)
        accounts.raiseSessionGeneration(tx, accountId)
    }
}

/**
 * The actions of «Dispositivi collegati» (TALITA-15, criteria 14-18): `POST /profilo/dispositivi/togli`, `POST /profilo/dispositivi/esci-altri` and the pages that stand in for their dialogs without
 * JavaScript (`…/conferma`). Open to every account, and each person acts only on their own: the number of a device of someone else, or of one that is not there, is answered as one that is gone, and
 * changes nothing (nobody sees the devices of another, not even a teacher). The id of the device is in the body of the form, never in an address.
 *
 * - **htmx**: the answer is the block `#dispositivi-contenuto`, with the notice and the focus on it; nothing else of the page changes. **Without JavaScript** the form leaves the notice for the profile
 *   ([AccessFlash]) and goes back to it.
 * - **A double tap** or a page that was left open: the second answer shows the state of now, with an information notice, never an error.
 * - **«Togli» on the device the person is on** is «Esci»: the row goes, the session ends and the person goes to the access page.
 */
@Controller
class DevicesController(
    private val shells: ShellFactory,
    private val presenter: AccessPresenter,
    private val devices: RememberedDevices,
    private val others: OtherDevices,
    private val origins: SessionOrigins,
    private val remembered: DeviceRememberMeServices,
    private val passkeys: Passkeys,
    private val texts: SessionTexts,
) {

    /** The block, as the answer of an action: htmx gets it, a form without JavaScript is sent back to the profile with the notice. */
    private fun answer(account: CurrentAccount, request: HttpServletRequest, alert: Alert): Any {
        if (request.isHtmxFragment()) return ModelAndView("profile/devicesBlock", mapOf("block" to presenter.devices(account, request, alert)))
        AccessFlash.put(request, "dispositivi", alert)
        return seeOther("/profilo#dispositivi-contenuto")
    }

    @PostMapping("/profilo/dispositivi/togli")
    fun remove(account: CurrentAccount, @RequestParam(name = "dispositivo", defaultValue = "") target: String, request: HttpServletRequest, response: HttpServletResponse): Any {
        val id = target.trim().toLongOrNull() ?: return answer(account, request, presenter.alreadyRemoved())
        if (id == presenter.currentDevice(request)) return signOut(request, response, id, account)
        return answer(account, request, if (devices.deleteOwn(account.id, id)) presenter.removed() else presenter.alreadyRemoved())
    }

    /** The device that the person is on, taken away, is «Esci»: the row and the cookie go, the session ends, and the access page says so, once. */
    private fun signOut(request: HttpServletRequest, response: HttpServletResponse, id: Long, account: CurrentAccount): Any {
        devices.deleteOwn(account.id, id)
        remembered.forget(request, response)
        SecurityContextLogoutHandler().logout(request, response, null)
        Flash.put(request, texts.signedOut)
        return if (request.isHtmx()) ResponseEntity.ok().header("HX-Redirect", LoginEntryPoint.LOGIN_PATH).build<Void>() else seeOther(LoginEntryPoint.LOGIN_PATH)
    }

    @PostMapping("/profilo/dispositivi/esci-altri")
    fun exitOthers(account: CurrentAccount, request: HttpServletRequest): Any {
        val generation = others.closeAllBut(account.id, presenter.currentDevice(request))
        // This session stays inside: it adopts the new number, the others (which have the old one) end at their next request.
        origins.adopt(request.getSession(true), generation)
        return answer(account, request, presenter.othersExited(passkeys.hasAny(account.id)))
    }

    // ---- Without JavaScript: the page that stands in for the dialog ------------------------------------------------------------------------

    @PostMapping("/profilo/dispositivi/togli/conferma")
    fun confirmRemoval(account: CurrentAccount, @RequestParam(name = "dispositivo", defaultValue = "") target: String, request: HttpServletRequest): ModelAndView {
        val block = presenter.devices(account, request, null)
        val line = block.lines.firstOrNull { it.removal?.value == target.trim() } ?: return seeOther("/profilo")
        return confirmPage(checkNotNull(line.removal))
    }

    @PostMapping("/profilo/dispositivi/esci-altri/conferma")
    fun confirmExitOthers(account: CurrentAccount, request: HttpServletRequest): ModelAndView = confirmPage(presenter.devices(account, request, null).exitOthers)

    private fun confirmPage(model: ConfirmModel): ModelAndView =
        ModelAndView("pages/confirmAction", mapOf("shell" to shells.shell(page = model.title, current = null, onProfile = true), "page" to model.copy(target = null)))

    /** A form without JavaScript that came back leaves the page at the address of its action: reloading it asks with a GET, and gets the profile. */
    @GetMapping("/profilo/dispositivi/togli", "/profilo/dispositivi/togli/conferma", "/profilo/dispositivi/esci-altri", "/profilo/dispositivi/esci-altri/conferma")
    fun reloaded(): ModelAndView = seeOther("/profilo")
}

/** A row of the card «Passkey»: the day it was added, whether the phone says it is synchronized, and «Togli» with its dialog. Never a name, a model of phone or a last use. */
data class PasskeyLine(
    /** `Aggiunta il 3 settembre 2027`. */
    val title: String,
    /** «Sincronizzata» or «Solo su un dispositivo»: the one thing that tells two passkeys apart. */
    val label: String,
    val synced: Boolean,
    /** What the label means, in a sentence. */
    val meta: String,
    val removal: ConfirmModel,
    val ariaLabel: String,
)

/**
 * The card «Passkey» of the profile (TALITA-15, criteria 19, 20, 25, 28, 45, 46), whose block `#passkey-contenuto` every action replaces as a whole: the notice, the explanation, the rows with their
 * dialogs, the button to add one (or the sentence that says that the most are there), and what to do if the phone changes or is lost.
 *
 * @property confirmFirst a teacher who has a passkey confirms with it before «Togli» and «Aggiungi»: the button and the dialog carry `data-conferma`, and the sentences say it
 * @property invite a teacher with no passkey is invited to add one (their notes on health would then ask for the fingerprint, the face or the code)
 * @property teacher the person is a teacher: the card has a line more on how long «Ricordami» lasts, and the words about the titolare
 */
data class PasskeysBlock(
    val alert: Alert?,
    val teacher: Boolean,
    val lines: List<PasskeyLine>,
    val full: Boolean,
    val confirmFirst: Boolean,
    val invite: Boolean,
    val rememberLine: String?,
) {
    /** A teacher who has a passkey: the words of the card say how their passkeys protect the notes on health (the confirmation, and what to do if the only one is lost). */
    val protects: Boolean get() = teacher && lines.isNotEmpty()
}

/**
 * What the profile says about the passkeys, and the notices of its actions (design TALITA-15, parts 2 and 4). The words are the ones of the design; the rows come from [Passkeys] and carry the day
 * and whether the phone synchronizes the passkey, which is all that is kept.
 */
@Component
class PasskeyPresenter(private val passkeys: Passkeys, private val retention: RetentionTexts, private val support: PasskeySupport) {

    /** The block, or null when there are no passkeys on this site (there is no public address): then the card is not in the page. */
    fun block(account: CurrentAccount, alert: Alert?, confirmNeeded: Boolean): PasskeysBlock? {
        if (!support.enabled) return null
        val rows = passkeys.ofAccount(account.id)
        val lines = rows.mapIndexed { index, row ->
            val day = GatheringFormat.date(row.createdOn)
            PasskeyLine(
                title = "Aggiunta il $day",
                label = if (row.synced) "Sincronizzata" else "Solo su un dispositivo",
                synced = row.synced,
                meta = if (row.synced) "Si ritrova anche sugli altri dispositivi del tuo account Apple o Google." else "Resta solo sul dispositivo dove l’hai aggiunta: se lo cambi, non ti segue.",
                removal = removal(index + 1, row, day, account.isTeacher && confirmNeeded),
                ariaLabel = "Togli la passkey aggiunta il $day",
            )
        }
        return PasskeysBlock(
            alert = alert,
            teacher = account.isTeacher,
            lines = lines,
            full = lines.size >= Passkeys.MAX_PER_ACCOUNT,
            confirmFirst = account.isTeacher && confirmNeeded,
            invite = account.isTeacher && lines.isEmpty(),
            rememberLine = if (account.isTeacher) "Per i maestri «Ricordami su questo telefono» dura ${retention.devicesTeacherNumber} giorni: con una passkey non serve chiedere un link nemmeno dopo." else null,
        )
    }

    private fun removal(position: Int, row: PasskeyRow, day: String, confirmFirst: Boolean) = ConfirmModel(
        id = "togli-passkey-$position",
        title = "Togliere la passkey aggiunta il $day?",
        text = "Con questa passkey non potrai più entrare in Talità. Nel telefono la passkey resta, ma non serve più a niente: se vuoi, puoi cancellarla da lì.",
        confirmLabel = "Togli la passkey",
        busyLabel = "Rimozione…",
        action = "/profilo/passkey/togli",
        field = "passkey",
        value = row.reference,
        target = BLOCK,
        cancelHref = "/profilo",
        more = listOfNotNull("Per entrare ti resta il link, e puoi aggiungere una passkey nuova quando vuoi.", if (confirmFirst) "Per toglierla, il telefono ti chiede prima l’impronta, il volto o il codice di sblocco." else null),
        confirmFor = if (confirmFirst) "togli" else null,
    )

    // ---- The notices ------------------------------------------------------------------------------------------------------------------------

    /** After a registration: where to use the passkey, and for a teacher what changes for the notes on health (the first passkey is not a confirmation: the next opening asks for one). */
    fun added(teacher: Boolean): Alert = Alert(
        SUCCESS,
        "Dalla pagina «Accedi» puoi entrare con «Entra con una passkey», senza aspettare la mail." +
            if (teacher) " Da ora, prima di aprire le segnalazioni dei bambini il telefono ti chiede l’impronta, il volto o il codice di sblocco." else "",
        "Passkey aggiunta.",
    )

    fun removed(): Alert = Alert(SUCCESS, "Nel telefono resta, ma non fa più entrare.", "Passkey tolta.")

    fun alreadyRemoved(): Alert = Alert(INFO, "Questa passkey era già stata tolta.")

    /** 409 on «Togli»: a teacher with a passkey and no confirmation that holds (the page was drawn before, or the ten minutes passed between the tap and the request). */
    fun removalNeedsConfirmation(): Alert = Alert(INFO, "Per togliere una passkey il telefono ti chiede l’impronta, il volto o il codice di sblocco. Tocca di nuovo «Togli».", "Serve la conferma.")

    /** The same without JavaScript: there is nobody to ask the phone, so the words say that the confirmation wants it, and that there is another way. */
    fun needsConfirmationWithoutScript(): Alert = Alert(
        INFO,
        "Per togliere o aggiungere una passkey devi confermare con una passkey, e la conferma vuole JavaScript, che in questo browser è spento o non funziona. Prova con un altro browser, o scrivi al titolare.",
        "Serve la conferma.",
    )

    companion object {
        /** The block that the actions replace. */
        const val BLOCK = "#passkey-contenuto"
    }
}

/**
 * The actions of the card «Passkey» (TALITA-15, criteria 20, 25, 26): the block asked again after a registration (`GET /profilo/passkey`), «Togli» (`POST /profilo/passkey/togli`) and the page that
 * stands in for its dialog without JavaScript. The registration itself is made by the filters of Spring's WebAuthn (`POST /webauthn/register/options` and `/webauthn/register`), and the removal
 * of Spring (`DELETE /webauthn/register/{id}`) is closed. Open to every account, and each person acts only on their own passkeys: the reference of a passkey that is not theirs, or is not there, is
 * answered as one that is gone, and changes nothing. The reference is in the body of the form, never in an address.
 */
@Controller
class PasskeysController(
    private val shells: ShellFactory,
    private val presenter: PasskeyPresenter,
    private val passkeys: Passkeys,
    private val confirmations: PasskeyConfirmations,
) {

    private fun block(account: CurrentAccount, request: HttpServletRequest, alert: Alert?, status: org.springframework.http.HttpStatus = org.springframework.http.HttpStatus.OK): ModelAndView {
        val block = presenter.block(account, alert, confirmations.needed(account, request.getSession(false))) ?: return seeOther("/profilo")
        return ModelAndView("profile/passkeysBlock", mapOf("block" to block)).also { it.status = status }
    }

    /** The block after a registration, with the notice that the registration left (htmx), or the profile (a page asked for by hand). */
    @GetMapping("/profilo/passkey")
    fun reload(account: CurrentAccount, request: HttpServletRequest): ModelAndView =
        if (request.isHtmxFragment()) block(account, request, AccessFlash.take(request, "passkey")) else seeOther("/profilo#passkey")

    @PostMapping("/profilo/passkey/togli")
    fun remove(account: CurrentAccount, @RequestParam(name = "passkey", defaultValue = "") target: String, request: HttpServletRequest): ModelAndView {
        if (confirmations.needed(account, request.getSession(false))) return servesConfirmation(account, request)
        val alert = if (passkeys.deleteOwn(account.id, target.trim())) presenter.removed() else presenter.alreadyRemoved()
        if (request.isHtmxFragment()) return block(account, request, alert)
        AccessFlash.put(request, "passkey", alert)
        return seeOther("/profilo#passkey")
    }

    /** A teacher who has a passkey and no confirmation that is still valid: nothing is removed, and the block says to confirm (409). Without JavaScript the whole profile says that it wants it. */
    private fun servesConfirmation(account: CurrentAccount, request: HttpServletRequest): ModelAndView {
        if (request.isHtmxFragment()) return block(account, request, presenter.removalNeedsConfirmation(), org.springframework.http.HttpStatus.CONFLICT)
        AccessFlash.put(request, "passkey", presenter.needsConfirmationWithoutScript())
        return seeOther("/profilo#passkey")
    }

    /** Without JavaScript: the page with the words of the dialog. A passkey that is not the person's, or is not there, leads back to the profile. */
    @PostMapping("/profilo/passkey/togli/conferma")
    fun confirmRemoval(account: CurrentAccount, @RequestParam(name = "passkey", defaultValue = "") target: String, request: HttpServletRequest): ModelAndView {
        val block = presenter.block(account, null, confirmations.needed(account, request.getSession(false))) ?: return seeOther("/profilo")
        val line = block.lines.firstOrNull { it.removal.value == target.trim() } ?: return seeOther("/profilo")
        return ModelAndView("pages/confirmAction", mapOf("shell" to shells.shell(page = line.removal.title, current = null, onProfile = true), "page" to line.removal.copy(target = null)))
    }

    /** A form without JavaScript that came back leaves the page at the address of its action: reloading it asks with a GET, and gets the profile. */
    @GetMapping("/profilo/passkey/togli", "/profilo/passkey/togli/conferma")
    fun reloaded(): ModelAndView = seeOther("/profilo#passkey")
}
