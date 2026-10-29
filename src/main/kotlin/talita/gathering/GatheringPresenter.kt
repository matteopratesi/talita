package talita.gathering

import org.springframework.stereotype.Component
import talita.account.CurrentAccount
import talita.account.PublicUrl
import talita.ui.Alert
import talita.retention.RetentionPolicy
import talita.retention.RetentionTexts
import talita.ui.ConfirmModel
import java.time.LocalDate

/**
 * Turns a gathering into what the pages of the teachers show: the labels of state, the rows, the cards of the detail with their texts
 * (design guide and the design of TALITA-4: every sentence written here is the one of the design). The rules (what is over, what
 * is missing) come from [Gathering] and [Gatherings]: nothing is decided twice.
 */
@Component
class GatheringPresenter(
    private val gatherings: Gatherings,
    private val publicUrl: PublicUrl,
    private val usage: RegistrationUsage,
    private val policy: RetentionPolicy,
    private val texts: RetentionTexts,
    private val roomSummaries: RoomSummaries,
    private val menuPresenter: MenuPresenter,
) {

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
        val roomSummary = roomSummaries.summary(gathering.id)

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
            registrations = registrations(gathering, today),
            retention = if (draft) null else retention(gathering, over, today, roomSummary.rooms),
            rooms = roomsCard(gathering, over, roomSummary),
            menu = menuPresenter.card(gathering.id),
        )
    }

    /**
     * The card «Camere» (TALITA-11, criterion 16): a row that leads to the page, with how many rooms, how many beds and how many children have no room, and never a name. While there
     * are no rooms it says to write them; a gathering of one day with none has no card (a day needs no rooms), and neither has one that is over: after the last day rooms are not
     * written any more, so the card would offer what the page refuses. A draft has no children, and after the end the people of the rooms are deleted: those two say only the house.
     */
    private fun roomsCard(gathering: Gathering, over: Boolean, summary: RoomSummary): RoomsCard? {
        if (summary.rooms == 0 && (gathering.days.size == 1 || over)) return null
        val href = "/convivenza/${gathering.id}/camere"
        if (summary.rooms == 0) return RoomsCard("Scrivi le camere", href, "Nessuna camera per ora.")
        val rooms = if (summary.rooms == 1) "1 camera" else "${summary.rooms} camere"
        val beds = if (summary.beds == 1) "1 posto" else "${summary.beds} posti"
        val free = summary.unassigned?.let { if (it == 0) "Tutti hanno una camera" else if (it == 1) "1 bambino senza camera" else "$it bambini senza camera" }
        return RoomsCard("Vedi le camere", href, listOfNotNull(rooms, beds, free).joinToString(" · "))
    }

    /**
     * The card «Conservazione dei dati» (design TALITA-7, part 3, 1): the verb says «Si cancellano il …» before the date and «Cancellate il …» from the day of the date, whatever
     * the database holds, so the card and the pages say the same thing at every hour. The line «Su carta» is a proposal of the design: it tells whoever holds the paper when to destroy it. The line «Chi dorme
     * dove» is not there once the gathering is over and has no rooms ([rooms] is zero): nobody can write rooms any more, so nothing was written, nothing was deleted and no room stays for the
     * copy of the year after, and the line would say three things that did not happen (the pages of the rooms and the card of the child in Arrivi say the same: criterion 13).
     */
    private fun retention(gathering: Gathering, over: Boolean, today: LocalDate, rooms: Int): RetentionCard {
        val notesDate = GatheringFormat.date(policy.notesDeletionDate(gathering.endDate))
        val registrationsDate = GatheringFormat.date(policy.registrationsDeletionDate(gathering.endDate))
        val notesGone = policy.notesExpired(gathering.endDate, today)
        val registrationsGone = policy.registrationsExpired(gathering.endDate, today)
        val roomsDate = GatheringFormat.date(policy.roomsDeletionDate(gathering.endDate))
        val roomsGone = policy.roomsExpired(gathering.endDate, today)
        val lines = listOfNotNull(
            RetentionLine(
                "Segnalazioni",
                if (notesGone) "Cancellate il $notesDate" else "Si cancellano il $notesDate",
                if (notesGone) {
                    "${texts.capital(texts.notes)} dopo la fine della convivenza."
                } else {
                    "${texts.capital(texts.notes)} dopo la fine della convivenza, perché servono se durante l’evento succede qualcosa."
                },
            ),
            RetentionLine(
                "Chi dorme dove",
                if (roomsGone) "È stato cancellato il $roomsDate" else "Si cancella il $roomsDate",
                if (roomsGone) {
                    "${texts.capital(texts.roomsWhen)}. Le camere restano, senza nomi, per copiarle l’anno dopo."
                } else {
                    "${texts.capital(texts.roomsWhen)}, quando le camere si svuotano: serve durante l’evento, anche se di notte succede qualcosa. Le camere restano, senza nomi, per copiarle l’anno dopo."
                },
            ).takeUnless { over && rooms == 0 },
            RetentionLine(
                "Iscrizioni, manleve e registro arrivi",
                if (registrationsGone) "Cancellate il $registrationsDate" else "Si cancellano il $registrationsDate",
                if (registrationsGone) {
                    "${texts.capital(texts.registrations)} dopo la fine della convivenza, con le presenze, i pagamenti e il registro delle letture."
                } else {
                    "${texts.capital(texts.registrations)} dopo la fine della convivenza: servono come prova dell’affidamento. Vanno via anche le presenze, i pagamenti e il registro delle letture delle segnalazioni."
                },
            ),
            RetentionLine(
                "Su carta",
                "Da distruggere a partire dal $registrationsDate",
                "Vale per le manleve firmate e per il registro stampato. Il foglio per i maestri va distrutto alla fine della convivenza.",
            ),
        )
        return RetentionCard(
            intro = "Talità cancella da sola i dati di questa convivenza, ogni notte. Qui vedi quando.",
            lines = lines,
            note = if (over) null else "Le date seguono la fine della convivenza: se la cambi, cambiano anche queste.",
        )
    }

    /** The card «Iscrizioni» (design C 3): counts only, never names; a draft has no registrations, not even on paper, and says so. */
    private fun registrations(gathering: Gathering, today: LocalDate): RegistrationsCard {
        if (gathering.state == GatheringState.DRAFT) {
            return RegistrationsCard("Le iscrizioni arrivano dopo l’apertura: finché la convivenza è in bozza non ce ne sono, nemmeno da carta.", null)
        }
        // A year after the end the registrations are gone (from the day of their date): the row says so, and leads to the page that says when and why.
        if (policy.registrationsExpired(gathering.endDate, today)) {
            return RegistrationsCard("Cancellate il ${GatheringFormat.date(policy.registrationsDeletionDate(gathering.endDate))}", "/iscrizioni?convivenza=${gathering.id}")
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
