package talita.teacher

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import talita.account.CurrentAccount
import talita.account.EmailAddress
import talita.account.isHtmx
import talita.account.seeOther
import talita.gathering.Custodianship
import talita.gathering.GatheringRef
import talita.ui.Alert
import talita.ui.AlertVariant.DANGER
import talita.ui.AlertVariant.SUCCESS
import talita.ui.AlertVariant.WARNING
import talita.ui.ConfirmModel
import talita.ui.Flash
import talita.ui.LONG_DATE
import talita.ui.Section
import talita.ui.ShellFactory

/**
 * What stops a teacher from being taken away while they are a custodian of a gathering that is not over (criterion 40): the sentence,
 * and the text button that leads to the custodians of that gathering. [ariaLabel] names the gathering when there are two buttons.
 */
data class CustodyLine(val sentence: String, val label: String, val ariaLabel: String?, val href: String)

/** One teacher in the list: name and surname (the address is never shown), and what can be done with them. */
data class TeacherLine(val name: String, val initials: String, val you: Boolean, val removal: ConfirmModel?, val note: String?, val custody: List<CustodyLine> = emptyList())

/** One invitation in the list: the address, when it expires (or expired), and the dialog to withdraw it. */
data class InvitationLine(val email: String, val expired: Boolean, val date: String, val withdrawal: ConfirmModel)

/** Model of the block of «Maestri» that is replaced as a whole: what is in the field, its error, the notice, the two lists. */
data class MaestriPage(
    val email: String,
    val emailError: String?,
    val alert: Alert?,
    val validityDays: Long,
    val teachers: List<TeacherLine>,
    val invitations: List<InvitationLine>,
)

/**
 * «Maestri» (`/maestri`): the teachers (by name, never by address), the invitations that wait, and the form to
 * invite. Only for teachers (see `TeacherPaths`): a parent, or a person with an invitation they have not
 * accepted, gets 403 on every address here, also on a request made by hand.
 *
 * Every action answers with the block `#maestri-contenuto` as a whole (htmx replaces it, the scroll stays),
 * with a notice in it, or with the page if it is not htmx; nothing carries an address or an id of a person in
 * the URL: the forms send them in the body.
 */
@Controller
class MaestriController(
    private val shells: ShellFactory,
    private val teachers: Teachers,
    private val invitations: Invitations,
    private val invite: InviteTeacher,
) {

    @GetMapping("/maestri")
    fun page(account: CurrentAccount, request: HttpServletRequest): ModelAndView =
        render(account, request, email = "", emailError = null, alert = Flash.take(request), status = HttpStatus.OK)

    @PostMapping("/maestri/invita")
    fun invite(account: CurrentAccount, @RequestParam(defaultValue = "") email: String, request: HttpServletRequest): ModelAndView {
        val typed = email.take(MAX_ECHO)
        val address = EmailAddress.parse(email)
            ?: return render(account, request, typed, EMAIL_ERROR, null, HttpStatus.UNPROCESSABLE_ENTITY)
        return when (invite.invite(address)) {
            is InviteResult.Sent -> done(account, request, Alert(SUCCESS, "Invito mandato."))
            InviteResult.AlreadyTeacher -> render(account, request, typed, "È già tra i maestri.", null, HttpStatus.UNPROCESSABLE_ENTITY)
            InviteResult.DailyLimit -> render(account, request, typed, null, Alert(WARNING, DAILY_LIMIT), HttpStatus.TOO_MANY_REQUESTS)
            InviteResult.NotSent -> render(account, request, typed, null, Alert(DANGER, NOT_SENT), HttpStatus.UNPROCESSABLE_ENTITY)
        }
    }

    /** «Manda di nuovo»: the same mail, with a new expiry (one invitation per address). */
    @PostMapping("/maestri/inviti/rimanda")
    fun resend(account: CurrentAccount, @RequestParam(defaultValue = "") email: String, request: HttpServletRequest): ModelAndView {
        val address = EmailAddress.parse(email) ?: return render(account, request, "", null, null, HttpStatus.OK)
        return when (invite.invite(address)) {
            is InviteResult.Sent -> done(account, request, Alert(SUCCESS, "Invito mandato."))
            // The person became a teacher while the page was open: nothing to send, and the block says how things are.
            InviteResult.AlreadyTeacher -> done(account, request, Alert(SUCCESS, "Questa persona è già tra i maestri."))
            InviteResult.DailyLimit -> render(account, request, "", null, Alert(WARNING, DAILY_LIMIT), HttpStatus.TOO_MANY_REQUESTS)
            InviteResult.NotSent -> render(account, request, "", null, Alert(DANGER, NOT_SENT), HttpStatus.UNPROCESSABLE_ENTITY)
        }
    }

    /** «Ritira»: the invitation is gone and whoever enters with the address enters as a parent. Said the same if it was gone already. */
    @PostMapping("/maestri/inviti/ritira")
    fun withdraw(account: CurrentAccount, @RequestParam(defaultValue = "") email: String, request: HttpServletRequest): ModelAndView {
        invitations.withdraw(email.trim().lowercase())
        return done(account, request, Alert(SUCCESS, "Invito ritirato."))
    }

    /**
     * «Togli dai maestri»: the person loses the role and the name is deleted (the account stays, as a parent), but
     * never the last teacher. A teacher who takes themself away goes to the home as a parent.
     */
    @PostMapping("/maestri/togli")
    fun remove(account: CurrentAccount, @RequestParam(name = "maestro", defaultValue = "") target: String, request: HttpServletRequest): Any {
        val id = target.toLongOrNull() ?: return done(account, request, Alert(SUCCESS, "Questa persona non è più tra i maestri."))
        return when (val result = teachers.remove(id)) {
            is RemovalResult.Removed ->
                if (id == account.id) {
                    Flash.put(request, Alert(SUCCESS, "Non sei più tra i maestri."))
                    if (request.isHtmx()) ResponseEntity.ok().header("HX-Redirect", "/").build<Void>() else seeOther("/")
                } else {
                    done(account, request, Alert(SUCCESS, "${result.name.full} non è più tra i maestri."))
                }

            RemovalResult.NotATeacher -> done(account, request, Alert(SUCCESS, "Questa persona non è più tra i maestri."))

            RemovalResult.LastTeacher ->
                render(account, request, "", null, Alert(WARNING, "Serve almeno un maestro. Per togliere questa persona, invita prima un altro maestro."), HttpStatus.UNPROCESSABLE_ENTITY)

            // A request made by hand, or two teachers at the same moment: nothing changes, and the block says why (409).
            is RemovalResult.IsCustodian -> {
                val of = titles(result.gatherings.map { it.title }, "di ")
                val text = if (id == account.id) {
                    "Non puoi toglierti: sei affidatario $of. Togliti prima dagli affidatari."
                } else {
                    "Non puoi togliere ${result.name.full}: è affidatario $of. Toglilo prima dagli affidatari."
                }
                render(account, request, "", null, Alert(WARNING, text), HttpStatus.CONFLICT)
            }
        }
    }

    /**
     * A form without JavaScript that comes back with an error leaves the page at the address of its action. Reloading it asks
     * for that address with a GET, and gets the page of «Maestri» instead of a refusal.
     */
    @GetMapping(
        "/maestri/invita", "/maestri/inviti/rimanda", "/maestri/inviti/ritira", "/maestri/togli",
        "/maestri/togli/conferma", "/maestri/inviti/ritira/conferma",
    )
    fun reloaded(): ModelAndView = seeOther("/maestri")

    // ---- Without JavaScript: the page that stands in for the dialog -------------------------------------------------------

    @PostMapping("/maestri/togli/conferma")
    fun confirmRemoval(account: CurrentAccount, @RequestParam(name = "maestro", defaultValue = "") target: String): ModelAndView {
        val line = teachers.all().firstOrNull { it.accountId.toString() == target.trim() } ?: return seeOther("/maestri")
        val custody = teachers.custodianships()[line.accountId] ?: Custodianship.NONE
        // The one who is a custodian of a gathering that is not over is not taken away: there is nothing to confirm.
        if (custody.unfinished.isNotEmpty()) return seeOther("/maestri")
        return confirmPage(removalDialog(0, line, line.accountId == account.id, custody.finished))
    }

    @PostMapping("/maestri/inviti/ritira/conferma")
    fun confirmWithdrawal(@RequestParam(defaultValue = "") email: String): ModelAndView {
        val invitation = invitations.all().firstOrNull { it.email == email.trim().lowercase() } ?: return seeOther("/maestri")
        return confirmPage(withdrawalDialog(0, invitation.email))
    }

    private fun confirmPage(model: ConfirmModel): ModelAndView =
        ModelAndView("pages/confirmAction", mapOf("shell" to shells.shell(page = model.title, current = Section.CONVIVENZA), "page" to model))

    // ---- The block -------------------------------------------------------------------------------------------------------------

    /** An action that went well: the block with the notice (htmx), or back to the page with the notice (a form without JavaScript). */
    private fun done(account: CurrentAccount, request: HttpServletRequest, alert: Alert): ModelAndView =
        if (request.isHtmx()) {
            render(account, request, "", null, alert, HttpStatus.OK)
        } else {
            Flash.put(request, alert)
            seeOther("/maestri")
        }

    private fun render(
        account: CurrentAccount,
        request: HttpServletRequest,
        email: String,
        emailError: String?,
        alert: Alert?,
        status: HttpStatus,
    ): ModelAndView {
        val people = teachers.all()
        val custodies = teachers.custodianships()
        val lines = people.mapIndexed { index, row ->
            val you = row.accountId == account.id
            val custody = custodies[row.accountId] ?: Custodianship.NONE
            // A custodian of a gathering that is not over is not taken away: the sentence and the way to the custodians are where «Togli» would be.
            val blocked = people.size > 1 && custody.unfinished.isNotEmpty()
            TeacherLine(
                name = row.name.full,
                initials = (row.name.first.take(1) + row.name.last.take(1)).uppercase(),
                you = you,
                removal = if (people.size > 1 && !blocked) removalDialog(index, row, you, custody.finished) else null,
                // The only teacher cannot take themself away: the page says why, where the button would be.
                note = if (people.size == 1) "Serve almeno un maestro: per toglierti, invita prima un altro maestro." else null,
                custody = if (blocked) custody.unfinished.map { custodyLine(it, you, custody.unfinished.size > 1) } else emptyList(),
            )
        }
        val waiting = invitations.all().mapIndexed { index, invitation ->
            val expired = invitations.isExpired(invitation)
            val date = LONG_DATE.format(invitation.expiresAt)
            InvitationLine(invitation.email, expired, if (expired) "Scadeva il $date" else "Scade il $date", withdrawalDialog(index, invitation.email))
        }
        val model = MaestriPage(email, emailError, alert, invitations.validity.toDays(), lines, waiting)

        // An action of htmx on the block gets the block only; anything else (a link, a form without JavaScript) gets the page.
        val fragment = request.isHtmx() && request.getHeader("HX-Boosted") != "true" && request.method == "POST"
        return if (fragment) {
            ModelAndView("pages/maestriBlock", mapOf("page" to model)).also { it.status = status }
        } else {
            ModelAndView("pages/maestri", mapOf("shell" to shells.shell(page = "Maestri", current = Section.CONVIVENZA), "page" to model))
                .also { it.status = status }
        }
    }

    private fun custodyLine(gathering: GatheringRef, you: Boolean, several: Boolean): CustodyLine = CustodyLine(
        sentence = if (you) {
            "Sei affidatario di «${gathering.title}»: per toglierti, prima togliti dagli affidatari."
        } else {
            "Affidatario di «${gathering.title}»: prima va tolto dagli affidatari."
        },
        label = if (you) "Togliti dagli affidatari" else "Togli dagli affidatari",
        ariaLabel = if (several) (if (you) "Togliti dagli affidatari di «${gathering.title}»" else "Togli dagli affidatari di «${gathering.title}»") else null,
        href = "/convivenza/${gathering.id}/affidatari",
    )

    /** «di «A»», «di «A» e di «B»», «di «A», di «B» e di «C»»: the titles of the gatherings in a sentence. [prefix] repeats «di » before every title. */
    private fun titles(titles: List<String>, prefix: String): String {
        val quoted = titles.mapIndexed { index, title -> (if (index == 0) "" else prefix) + "«$title»" }
        return if (quoted.size == 1) prefix + quoted.single() else prefix + quoted.dropLast(1).joinToString(", ") + " e " + quoted.last()
    }

    /** The line that the dialog adds when the teacher is also a custodian of gatherings that are over: their name goes from those too. */
    private fun finishedLine(finished: List<GatheringRef>, you: Boolean): String {
        if (finished.isEmpty()) return ""
        val owner = if (you) "Il tuo" else "Il suo"
        return " $owner nome sparisce anche dagli affidatari ${titles(finished.map { it.title }, "di ")}, " +
            if (finished.size == 1) "che è finita." else "che sono finite."
    }

    private fun removalDialog(index: Int, row: TeacherRow, you: Boolean, finished: List<GatheringRef> = emptyList()): ConfirmModel {
        val name = row.name.full
        val extra = finishedLine(finished, you)
        return if (you) {
            ConfirmModel(
                id = "togli-$index",
                title = "Toglierti dai maestri?",
                text = "Non potrai più gestire la convivenza né vedere i bambini e le segnalazioni. Il tuo nome viene cancellato dall’elenco dei maestri. Un altro maestro potrà invitarti di nuovo.$extra",
                confirmLabel = "Toglimi dai maestri",
                busyLabel = "Rimozione…",
                action = "/maestri/togli",
                field = "maestro",
                value = row.accountId.toString(),
            )
        } else {
            ConfirmModel(
                id = "togli-$index",
                title = "Togliere $name dai maestri?",
                text = "$name non potrà più gestire la convivenza né vedere i bambini e le segnalazioni. Il suo nome viene cancellato dall’elenco dei maestri. Potrà ancora entrare in Talità come genitore.$extra",
                confirmLabel = "Togli dai maestri",
                busyLabel = "Rimozione…",
                action = "/maestri/togli",
                field = "maestro",
                value = row.accountId.toString(),
            )
        }
    }

    private fun withdrawalDialog(index: Int, email: String) = ConfirmModel(
        id = "ritira-$index",
        title = "Ritirare l’invito?",
        text = "L’invito a $email non varrà più. La persona potrà entrare in Talità, ma non come maestro.",
        confirmLabel = "Ritira l’invito",
        busyLabel = "Ritiro…",
        action = "/maestri/inviti/ritira",
        field = "email",
        value = email,
    )

    private companion object {
        const val MAX_ECHO = 1000
        const val EMAIL_ERROR = "Scrivi un indirizzo email completo, per esempio nome@example.org."
        const val NOT_SENT = "Non siamo riusciti a mandare la mail. Riprova tra qualche minuto."
        const val DAILY_LIMIT = "Oggi Talità ha già mandato tante mail e non può mandarne altre. Riprova domani. Se è urgente, avvisa chi organizza la convivenza."
    }
}
