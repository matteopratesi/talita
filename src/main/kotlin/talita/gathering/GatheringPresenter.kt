package talita.gathering

import org.springframework.stereotype.Component
import talita.account.CurrentAccount
import talita.account.PublicUrl
import talita.ui.Alert
import talita.ui.ConfirmModel
import java.time.LocalDate

/**
 * Turns a gathering into what the pages of the teachers show: the labels of state, the rows, the cards of the detail with their texts
 * (design guide and the design of TALITA-4: every sentence written here is the one of the design). The rules (what is over, what
 * is missing) come from [Gathering] and [Gatherings]: nothing is decided twice.
 */
@Component
class GatheringPresenter(private val gatherings: Gatherings, private val publicUrl: PublicUrl, private val usage: RegistrationUsage) {

    /** The labels of state of a gathering: its state, and «Finita» when it is over (a gathering that is not a draft and whose last day has passed). */
    fun badges(gathering: Gathering, today: LocalDate): List<Badge> = buildList {
        add(
            when (gathering.state) {
                GatheringState.DRAFT -> Badge("Bozza")
                GatheringState.OPEN -> Badge("Iscrizioni aperte", "info")
                GatheringState.CLOSED -> Badge("Iscrizioni chiuse")
            },
        )
        if (gathering.isOver(today)) add(Badge("Finita"))
    }

    fun row(gathering: Gathering, today: LocalDate): GatheringRow =
        GatheringRow("/convivenza/${gathering.id}", gathering.title, badges(gathering, today), GatheringFormat.range(gathering.startDate, gathering.endDate))

    fun section(): SectionPage {
        val today = gatherings.today()
        return SectionPage(gatherings.current()?.let { row(it, today) }, gatherings.closed().map { row(it, today) })
    }

    /** The paragraphs of a text: blank lines separate them (several blank lines are one break), a single line break stays inside the paragraph. */
    fun paragraphs(text: String?): List<String> =
        text?.split(PARAGRAPH_BREAK)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** One thing per line, the empty lines left out. */
    fun lines(text: String?): List<String> = text?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /**
     * The detail of [gathering]. [alert] follows an action (a confirmation or a refusal). [showMissing]: the person tapped «Apri le iscrizioni»
     * and the draft is not complete, so the list of what is missing is the notice (criterion 10).
     */
    fun detail(gathering: Gathering, account: CurrentAccount, alert: Alert?, showMissing: Boolean = false): DetailPage {
        val today = gatherings.today()
        val custodians = gatherings.custodians(gathering.id)
        val over = gathering.isOver(today)
        val draft = gathering.state == GatheringState.DRAFT
        val missing = gatherings.missing(gathering, custodians.size, today)
        val fees = gathering.fees

        return DetailPage(
            id = gathering.id,
            title = gathering.title,
            alert = alert,
            missing = if (showMissing && draft && missing.isNotEmpty()) missing.map { MissingLink(it.text, it.path(gathering.id)) } else null,
            standing = if (gathering.state == GatheringState.OPEN && over) STANDING_OPEN_AND_OVER else null,
            state = stateCard(gathering, over, missing.isEmpty()),
            dialog = dialog(gathering, over, missing.isEmpty()),
            days = GatheringFormat.days(gathering.days),
            place = gathering.place,
            placeNeeded = draft && gathering.place == null,
            fees = listOf(
                FeeLine("Quota intera", fees.fullCents?.let(GatheringFormat::fee), draft && fees.fullCents == null),
                FeeLine("Quota giornaliera", fees.dailyCents?.let(GatheringFormat::fee), false),
                FeeLine("Quota solo pranzo", fees.lunchOnlyCents?.let(GatheringFormat::fee), false),
            ),
            custodians = custodians.map { CustodianLine(it.name, it.accountId == account.id) },
            custodiansNeeded = draft && custodians.isEmpty(),
            infoParagraphs = paragraphs(gathering.infoText),
            whatToBring = lines(gathering.whatToBring),
            link = LinkCard(
                open = !draft,
                address = if (draft) null else publicUrl.of("/info/${gathering.publicCode}"),
                over = over,
                previewHref = "/convivenza/${gathering.id}/anteprima",
                title = gathering.title,
                copyPrimary = !draft && !over,
            ),
            registrations = registrations(gathering),
        )
    }

    /** The card «Iscrizioni» (design C 3): counts only, never names; a draft has no registrations, not even on paper, and says so. */
    private fun registrations(gathering: Gathering): RegistrationsCard {
        if (gathering.state == GatheringState.DRAFT) {
            return RegistrationsCard("Le iscrizioni arrivano dopo l’apertura: finché la convivenza è in bozza non ce ne sono, nemmeno da carta.", null)
        }
        val counts = usage.counts(gathering.id)
        val parts = listOfNotNull(
            counts.confirmed.takeIf { it > 0 }?.let { if (it == 1) "1 confermata" else "$it confermate" },
            counts.maybe.takeIf { it > 0 }?.let { "$it in forse" },
            counts.withdrawn.takeIf { it > 0 }?.let { if (it == 1) "1 ritirata" else "$it ritirate" },
        )
        return RegistrationsCard(parts.joinToString(" · ").ifEmpty { "Nessuna iscrizione" }, "/iscrizioni?convivenza=${gathering.id}")
    }

    /**
     * The card «Stato»: the labels, a sentence, and the one button that makes sense (design 4). The button of a draft that is not complete
     * is a form that sends straight away, and the answer is the list of what is missing. The main button of the page is «Apri le iscrizioni»
     * for a draft, «Copia il link» (card «Link per i genitori») for open or closed registrations, and «Chiudi le iscrizioni» again when the
     * gathering is over and they are still open: the thing to do.
     */
    private fun stateCard(gathering: Gathering, over: Boolean, complete: Boolean): StateCard {
        val id = gathering.id
        val badges = badges(gathering, gatherings.today())
        return when (gathering.state) {
            GatheringState.DRAFT -> StateCard(
                badges, "I genitori non vedono ancora la pagina con le informazioni.", null,
                StateButton("Apri le iscrizioni", primary = true, href = "/convivenza/$id/apri", opens = if (complete) "dialogo-apri" else null, direct = !complete, action = "apri"),
                from = "draft",
            )

            GatheringState.OPEN -> StateCard(
                badges, "I genitori possono iscrivere i figli dal link.", null,
                StateButton("Chiudi le iscrizioni", primary = over, href = "/convivenza/$id/chiudi", opens = "dialogo-chiudi", direct = false, action = "chiudi"),
                from = "open",
            )

            GatheringState.CLOSED -> {
                val closed = "I genitori vedono la pagina, ma non possono iscrivere i figli né cambiare le iscrizioni."
                val other = if (over) null else gatherings.current()
                when {
                    over -> StateCard(badges, "$closed La convivenza è finita: le iscrizioni non si riaprono.", null, null, "closed")
                    other != null -> StateCard(
                        badges, closed,
                        StateNote("Non puoi riaprire le iscrizioni finché", "«${other.title}»", "/convivenza/${other.id}", " non è chiusa."),
                        null, "closed",
                    )
                    else -> StateCard(
                        badges, closed, null,
                        StateButton("Riapri le iscrizioni", primary = false, href = "/convivenza/$id/riapri", opens = "dialogo-riapri", direct = false, action = "riapri"),
                        from = "closed",
                    )
                }
            }
        }
    }

    /** The dialog that the button of the card «Stato» opens, or none (a draft that is not complete has no dialog, and a closed one that cannot reopen neither). */
    private fun dialog(gathering: Gathering, over: Boolean, complete: Boolean): ConfirmModel? = when (gathering.state) {
        GatheringState.DRAFT -> if (complete) confirm(gathering.id, StateAction.OPEN, over) else null
        GatheringState.OPEN -> confirm(gathering.id, StateAction.CLOSE, over)
        GatheringState.CLOSED -> if (!over && gatherings.current() == null) confirm(gathering.id, StateAction.REOPEN, over) else null
    }

    /** The dialog of a step of the state, or the page that stands in for it without JavaScript: the same words (criteria 9, 11, 12). */
    fun confirm(id: Long, action: StateAction, over: Boolean): ConfirmModel = when (action) {
        StateAction.OPEN -> ConfirmModel(
            id = "dialogo-apri",
            title = "Aprire le iscrizioni?",
            text = "Da adesso la pagina con le informazioni è visibile a chi ha il link e i genitori possono iscrivere i figli. Potrai chiudere le iscrizioni quando vuoi, ma la convivenza non torna in bozza.",
            confirmLabel = "Apri le iscrizioni",
            busyLabel = "Apertura…",
            action = "/convivenza/$id/apri",
            field = "from",
            value = "draft",
            target = null,
            cancelHref = "/convivenza/$id",
        )

        StateAction.CLOSE -> ConfirmModel(
            id = "dialogo-chiudi",
            title = "Chiudere le iscrizioni?",
            text = "I genitori non potranno più iscrivere i figli né cambiare le iscrizioni: per farlo dovranno chiedere a un maestro. La pagina con le informazioni resta visibile. " +
                if (over) "Non potrai riaprirle, perché la convivenza è finita." else "Potrai riaprire le iscrizioni finché la convivenza non è finita.",
            confirmLabel = "Chiudi le iscrizioni",
            busyLabel = "Chiusura…",
            action = "/convivenza/$id/chiudi",
            field = "from",
            value = "open",
            target = null,
            cancelHref = "/convivenza/$id",
        )

        StateAction.REOPEN -> ConfirmModel(
            id = "dialogo-riapri",
            title = "Riaprire le iscrizioni?",
            text = "I genitori potranno di nuovo iscrivere i figli e cambiare le iscrizioni. La pagina con le informazioni torna a mostrare il bottone per iscriversi.",
            confirmLabel = "Riapri le iscrizioni",
            busyLabel = "Riapertura…",
            action = "/convivenza/$id/riapri",
            field = "from",
            value = "closed",
            target = null,
            cancelHref = "/convivenza/$id",
        )
    }

    companion object {
        private val PARAGRAPH_BREAK = Regex("""\n[ \t]*\n(?:[ \t]*\n)*""")

        const val STANDING_OPEN_AND_OVER = "La convivenza è finita ma le iscrizioni sono ancora aperte. Chiudile per poter creare la prossima."
    }
}
