package talita.account

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import talita.gathering.GatheringFormat
import talita.retention.RetentionTexts
import talita.ui.Alert
import talita.ui.AlertVariant.INFO
import talita.ui.AlertVariant.SUCCESS
import talita.ui.ConfirmModel
import talita.ui.ShellFactory

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
