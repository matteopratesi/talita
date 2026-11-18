package talita.api

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import talita.ClockConfiguration
import talita.gathering.GatheringFormat
import talita.ui.Alert
import talita.ui.AlertVariant.INFO
import talita.ui.AlertVariant.SUCCESS
import talita.ui.ConfirmModel
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the card «Token per le API» shows: nothing yet, a token that works, its last day, a token that is past its day, or the one that was just made (shown once). */
enum class TokenState { NONE, ACTIVE, LAST_DAY, EXPIRED, CREATED }

/**
 * The block `#token-contenuto` of the card «Token per le API» (TALITA-21, design norms 1-7), which every action of the card replaces as a whole: the notice, what the token is for, the token or the way to
 * make one, the dialogs. [focus] is whether the notice takes the focus (`data-fuoco`): the one that comes out of order with another block of the page (`hx-swap-oob`) does not, so that the focus stays one.
 * The value of the token ([line]) is in the page only in the answer that makes it (`CREATED`): as text to select, never in an address or in an attribute.
 */
data class TokenBlock(
    val alert: Alert?,
    val focus: Boolean,
    val state: TokenState,
    /** `Token creato il 18 settembre 2027`. */
    val title: String?,
    val meta: String?,
    val badge: String?,
    val badgeTone: String?,
    val id: Long?,
    /** The account has a passkey: «Crea un token» asks the phone first (`data-conferma="token"`) and says so. */
    val confirmFirst: Boolean,
    val revoke: ConfirmModel?,
    val renew: ConfirmModel?,
    /** `Authorization: Bearer talita_…`, only for `CREATED`. */
    val line: String? = null,
    val command: String? = null,
    val until: String? = null,
    val revokeAria: String? = null,
) {
    /** Nothing of the line, which holds the token, can end up in a log through the model. */
    override fun toString(): String = "TokenBlock(state=$state, id=$id)"
}

/** A line of the register of the requests as the card says it, with what it means in a sentence under it. */
data class TokenLogLine(val title: String, val note: String)

/** The block `#token-registro`: the last 20 requests, outside `#token-contenuto`, so that the actions do not draw it again. */
data class TokenLog(val lines: List<TokenLogLine>)

/** The card «Token per le API» of the profile of a teacher and its register: every sentence is the one of the design (TALITA-21). Nothing here writes a value of a token in the log. */
@Component
class ApiTokenPresenter(
    private val tokens: ApiTokens,
    private val requests: ApiRequests,
    /** The name of the file that holds the line of the token on the computer (`dev.header`; `prod.header` for the production): from the configuration, never written in a template. */
    @Value("\${talita.api.header-file:dev.header}") val headerFile: String,
) {

    private fun date(day: java.time.LocalDate): String = GatheringFormat.date(day)

    /** The block for the token that [accountId] has now (or none), with the notice that the action left. */
    fun block(accountId: Long, confirmFirst: Boolean, alert: Alert? = null, focus: Boolean = true): TokenBlock {
        val row = tokens.of(accountId) ?: return none(alert, focus, confirmFirst)
        val created = date(row.createdOn)
        val last = tokens.lastDay(row)
        val title = "Token creato il $created"
        val renew = renewDialog(confirmFirst)
        return when {
            tokens.expired(row) -> TokenBlock(
                alert, focus, TokenState.EXPIRED, title, "È scaduto il ${date(last)}: il programma non può più usarlo.", "Scaduto", "neutral", row.id, confirmFirst, null, null,
            )
            tokens.today() == last -> TokenBlock(
                alert, focus, TokenState.LAST_DAY, title, "Vale fino a oggi. Da domani smette di funzionare: se ti serve ancora, crea un token nuovo.", "Ultimo giorno", "warning", row.id, confirmFirst,
                revokeDialog(row.id), renew, revokeAria = "Revoca il token creato il $created",
            )
            else -> TokenBlock(
                alert, focus, TokenState.ACTIVE, title, "Vale fino al ${date(last)}. Poi smette di funzionare da solo.", "Attivo", "positive", row.id, confirmFirst,
                revokeDialog(row.id), renew, revokeAria = "Revoca il token creato il $created",
            )
        }
    }

    private fun none(alert: Alert?, focus: Boolean, confirmFirst: Boolean) =
        TokenBlock(alert, focus, TokenState.NONE, null, null, null, null, null, confirmFirst, null, null)

    /** The block that shows the token that was just made: the line to save, the command that saves it, and «Fatto». */
    fun created(token: NewApiToken, confirmFirst: Boolean): TokenBlock = TokenBlock(
        alert = Alert(SUCCESS, "Copialo adesso: dopo questa schermata non si vede più.", lead = "Token creato."),
        focus = true,
        state = TokenState.CREATED,
        title = null, meta = null, badge = null, badgeTone = null,
        id = token.row.id,
        confirmFirst = confirmFirst,
        revoke = null, renew = null,
        line = "Authorization: Bearer ${token.value}",
        command = "umask 077; mkdir -p ~/.config/talita && rm -f ~/.config/talita/$headerFile && cat > ~/.config/talita/$headerFile",
        until = date(tokens.lastDay(token.row)),
    )

    private fun revokeDialog(id: Long) = ConfirmModel(
        id = "revoca",
        title = "Revocare il token?",
        text = "Il programma non potrà più fare modifiche con questo token. Quello che ha già fatto resta.",
        confirmLabel = "Revoca il token",
        busyLabel = "Revoca…",
        action = "/profilo/token/revoca",
        field = "token",
        value = id.toString(),
        target = BLOCK,
        cancelHref = "/profilo#token",
        more = listOf("Se ti serve ancora, crei un token nuovo."),
    )

    private fun renewDialog(confirmFirst: Boolean) = ConfirmModel(
        id = "nuovo",
        title = "Creare un token nuovo?",
        text = "Il token di adesso smette di funzionare subito. Il programma potrà lavorare di nuovo solo dopo che avrai salvato quello nuovo nel file sul tuo computer.",
        confirmLabel = "Crea il token nuovo",
        busyLabel = "Creazione…",
        action = "/profilo/token",
        field = "azione",
        value = "nuovo",
        target = BLOCK,
        cancelHref = "/profilo#token",
        more = if (confirmFirst) listOf("Per crearlo, il telefono ti chiede l’impronta, il volto o il codice di sblocco.") else emptyList(),
        confirmFor = if (confirmFirst) "token" else null,
    )

    /** The dialog of the card, as a page without JavaScript shows it. */
    fun dialogFor(accountId: Long, confirmFirst: Boolean, name: String, value: String?): ConfirmModel? = when (name) {
        "revoca" -> block(accountId, confirmFirst).revoke?.takeIf { it.value == value?.trim() }
        "nuovo" -> block(accountId, confirmFirst).renew
        else -> null
    }

    // ---- The register -----------------------------------------------------------------------------------------------------------------------

    private val moment = DateTimeFormatter.ofPattern("d MMMM 'alle' HH:mm", Locale.ITALIAN)

    fun log(): TokenLog = TokenLog(
        requests.last(20).map { row ->
            val at = row.at.atZoneSameInstant(ClockConfiguration.ZONE)
            TokenLogLine("${moment.format(at)} · ${row.method} ${ApiRequests.label(row.route)} · ${row.status}", sentence(row.status))
        },
    )

    private fun sentence(status: Int): String = when (status) {
        200, 201 -> "Andata a buon fine."
        422 -> "Rifiutata: i dati non sono validi."
        409 -> "Rifiutata: adesso non si può fare."
        400, 404, 405, 413, 415 -> "Richiesta non valida."
        429 -> "Troppe richieste."
        500 -> "Errore di Talità."
        else -> "Richiesta non valida."
    }

    // ---- The notices ------------------------------------------------------------------------------------------------------------------------

    fun revoked(): Alert = Alert(
        SUCCESS, "Il programma non può più fare modifiche con quel token. Quello che ha già fatto resta. Se ti serve ancora, crea un token nuovo.", lead = "Token revocato.",
    )

    fun alreadyRevoked(): Alert = Alert(INFO, "Qui sotto trovi quello di adesso.", lead = "Quel token era già stato revocato o sostituito.")

    /** A form of the card without a token arrived when there is one (another tab made it): nothing was replaced. */
    fun alreadyThere(): Alert = Alert(INFO, "Qui sotto trovi quello di adesso. Per sostituirlo tocca «Crea un token nuovo».", lead = "C’è già un token.")

    /** The confirmation with the passkey is missing (409): the page was drawn before, or the ten minutes passed between the tap and the request. */
    fun needsConfirmation(): Alert = Alert(
        INFO, "Per creare un token il telefono ti chiede l’impronta, il volto o il codice di sblocco. Tocca di nuovo «Crea un token».", lead = "Serve la conferma.",
    )

    /** The same without JavaScript: there is nobody to ask the phone. */
    fun needsConfirmationWithoutScript(): Alert = Alert(
        INFO,
        "Per creare un token devi confermare con una passkey, e la conferma vuole JavaScript, che in questo browser è spento o non funziona. Prova con un altro browser, o scrivi al titolare.",
        lead = "Serve la conferma.",
    )

    /** The token that «Esci da tutti gli altri dispositivi» took away with the devices. */
    fun revokedWithDevices(): Alert = Alert(INFO, "Sei uscito da tutti gli altri dispositivi e il token è andato con loro. Se ti serve ancora, creane uno nuovo.", lead = "Token revocato.")

    companion object {
        /** The block that the actions replace. */
        const val BLOCK = "#token-contenuto"
    }
}
