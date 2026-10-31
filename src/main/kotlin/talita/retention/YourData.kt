package talita.retention

import org.springframework.stereotype.Component
import talita.account.CurrentAccount
import talita.gathering.GatheringFormat
import talita.registration.RegistrationFormat
import talita.ui.Alert
import talita.ui.AlertVariant

/**
 * A section of the text of a dialog that deletes data: the heading («Cancelliamo subito», «Resta»), a short list, and a sentence in bold (the «Non resta niente.» that
 * takes the place of the list). Written by [YourDataPresenter]; drawn by `profile/erasureText.jte`.
 */
data class DialogSection(val title: String?, val items: List<String>, val strong: String? = null)

/**
 * A dialog that deletes data (the guide, 4.8, with the rule of TALITA-7): what is deleted at once, what stays with its date and its reason or «Non resta niente.», the
 * copies of safety, and «Non si può annullare.». The same model is the dialog of the profile and the page that stands in for it without JavaScript.
 *
 * @property id the id of the `<dialog>`: a number and a word, never a name
 * @property action where the confirmation is sent
 */
data class ErasureDialog(
    val id: String,
    val title: String,
    val sections: List<DialogSection>,
    val small: String,
    val strong: String,
    val confirmLabel: String,
    val busyLabel: String,
    val action: String,
    val cancelHref: String = "/profilo",
)

/** A thing to do before: a sentence, and the text button that leads to where it is done (none when the teachers have to do it). [ariaLabel] names the child when the label alone is not enough. */
data class BlockLine(val sentence: String, val label: String?, val ariaLabel: String?, val href: String?)

/** One child in «I tuoi figli»: the name that shows (the first name; both when two children have the same), the circle with the letters, and the action or why there is none. */
data class DataChild(
    val id: Long,
    val shownName: String,
    val fullName: String,
    val initials: String,
    val buttonLabel: String,
    val ariaLabel: String,
    val dialog: ErasureDialog?,
    val blocks: List<BlockLine>,
    val confirmHref: String,
)

/** «Il tuo account»: the sentence, the dialog and the button, or the list of what to do first. */
data class DataAccount(val text: String, val dialog: ErasureDialog?, val blocks: List<BlockLine>, val confirmHref: String)

/** The card «I tuoi dati» of the profile (criteria 16, 18, 23, 24): the introduction, the children, the account. */
data class YourData(val intro: String, val children: List<DataChild>, val account: DataAccount)

/**
 * Builds what the profile shows about the data of a person, and the dialogs and notices of the deletions (design TALITA-7, parts 1 and 2). Every sentence is the one of
 * the design; the facts come from the plan of [ErasureService] (the one that the deletion itself follows), and the dates and the numbers from [RetentionPolicy] and
 * [RetentionTexts]. Titles of gatherings and names are text, never HTML, and appear only in the page of the person they belong to.
 */
@Component
class YourDataPresenter(private val erasures: ErasureService, private val texts: RetentionTexts) {

    private fun date(plan: RegistrationFate): String = GatheringFormat.date(checkNotNull(plan.keptUntil) { "a registration that stays has its date" })

    private fun quoted(title: String): String = "«$title»"

    // ---- The card of the profile -------------------------------------------------------------------------------------------------

    /** The name that shows for [child] among [children]: the first name, or both if another child of the account has the same first name. */
    private fun shownName(child: OwnChild, children: List<OwnChild>): String =
        if (children.count { it.firstName.trim().equals(child.firstName.trim(), ignoreCase = true) } > 1) "${child.firstName} ${child.lastName}" else child.firstName

    /** The name that shows for the child [childId] of [accountId], or null if it is not one of theirs. */
    fun shownName(accountId: Long, childId: Long): String? {
        val children = erasures.childrenOf(accountId)
        return children.firstOrNull { it.id == childId }?.let { shownName(it, children) }
    }

    fun forProfile(account: CurrentAccount): YourData {
        val children = erasures.childrenOf(account.id)
        val shown = children.map { child -> child to shownName(child, children) }
        val childRows = shown.map { (child, name) ->
            val plan = erasures.planChild(account.id, child.id)
            DataChild(
                id = child.id,
                shownName = name,
                fullName = "${child.firstName} ${child.lastName}",
                initials = RegistrationFormat.initials(child.firstName, child.lastName),
                buttonLabel = "Cancella i dati di $name",
                ariaLabel = "Cancella i dati di ${child.firstName} ${child.lastName}",
                dialog = plan?.takeUnless { it.blocked }?.let { childDialog(it, name) },
                blocks = plan?.blockers?.let { childBlocks(it, name) }.orEmpty(),
                confirmHref = "/profilo/figli/${child.id}/cancella",
            )
        }
        val plan = erasures.planAccount(account.id)
        val dataAccount = if (plan == null) {
            DataAccount("", null, emptyList(), "/profilo/account/cancella")
        } else {
            DataAccount(
                text = accountText(account.isTeacher),
                dialog = if (plan.blocked) null else accountDialog(plan),
                blocks = if (plan.blocked) accountBlocks(plan) else emptyList(),
                confirmHref = "/profilo/account/cancella",
            )
        }
        return YourData(intro(children.isNotEmpty(), account.isTeacher), childRows, dataAccount)
    }

    private fun intro(withChildren: Boolean, teacher: Boolean): String = when {
        withChildren -> "Teniamo nome, cognome e data di nascita dei tuoi figli per precompilare l’iscrizione dell’anno dopo. Li cancelli quando vuoi."
        teacher -> "Qui puoi cancellare il tuo account."
        else -> "Non ci sono figli collegati al tuo account. Quando iscrivi un bambino, i suoi dati compaiono qui e li puoi cancellare quando vuoi."
    }

    private fun accountText(teacher: Boolean): String =
        if (teacher) {
            "Cancelliamo il tuo indirizzo email e il tuo nome. Non sarai più tra i maestri: non gestirai più la convivenza né vedrai i bambini. " +
                "Per rientrare ti basta chiedere un nuovo link: troverai un account vuoto."
        } else {
            "Cancelliamo il tuo indirizzo email e quello che è a tuo nome. Per rientrare ti basta chiedere un nuovo link: troverai un account vuoto."
        }

    // ---- What blocks (criteria 17, 24) ---------------------------------------------------------------------------------------------

    /** The sentences that take the place of the button of a child: one for each gathering that is not finished (design part 1, 3). */
    fun childBlocks(blockers: List<Blocker>, name: String): List<BlockLine> = blockers.map { blocker ->
        if (blocker.parentCanWithdraw) {
            BlockLine(
                "Per cancellare i dati di $name, ritira prima la sua iscrizione alla ${quoted(blocker.gatheringTitle)}.",
                "Vai alle iscrizioni", "Vai alle iscrizioni, per ritirare quella di ${blocker.childName}", "/iscrizioni",
            )
        } else {
            BlockLine("Le iscrizioni sono chiuse. Per cancellare i dati di $name, chiedi ai maestri di ritirare la sua iscrizione alla ${quoted(blocker.gatheringTitle)}.", null, null, null)
        }
    }

    /** The list under «Per cancellare il tuo account, prima:», in the order of the design: the children, the last teacher, the custodianships. */
    fun accountBlocks(plan: AccountPlan): List<BlockLine> = buildList {
        for (blocker in plan.blockers) {
            if (blocker.detached) {
                // The child is not among «I tuoi figli» and «Iscrizioni» only shows the registration: a teacher put it back, and only the teachers can withdraw it.
                add(
                    BlockLine(
                        "chiedi ai maestri di ritirare l’iscrizione di ${blocker.childFirstName} alla ${quoted(blocker.gatheringTitle)}: un maestro l’ha rimessa dopo che avevi cancellato i dati di ${blocker.childFirstName}.",
                        null, null, null,
                    ),
                )
            } else if (blocker.parentCanWithdraw) {
                add(
                    BlockLine(
                        "ritira l’iscrizione di ${blocker.childFirstName} alla ${quoted(blocker.gatheringTitle)}.",
                        "Vai alle iscrizioni", "Vai alle iscrizioni, per ritirare quella di ${blocker.childName}", "/iscrizioni",
                    ),
                )
            } else {
                add(BlockLine("chiedi ai maestri di ritirare l’iscrizione di ${blocker.childFirstName} alla ${quoted(blocker.gatheringTitle)}: le iscrizioni sono chiuse.", null, null, null))
            }
        }
        if (plan.teacherBlock.lastTeacher) add(BlockLine("invita un altro maestro: ora sei l’unico.", "Vai a Maestri", null, "/maestri"))
        val several = plan.teacherBlock.custodies.size > 1
        for (gathering in plan.teacherBlock.custodies) {
            add(
                BlockLine(
                    "togliti dagli affidatari della ${quoted(gathering.title)}.", "Togliti dagli affidatari",
                    if (several) "Togliti dagli affidatari della ${quoted(gathering.title)}" else null, "/convivenza/${gathering.id}/affidatari",
                ),
            )
        }
    }

    // ---- The dialogs (criteria 18, 23) ---------------------------------------------------------------------------------------------

    private fun stays(fate: RegistrationFate, name: String, own: Boolean): String {
        val withdrawn = if (fate.withdrawn) " ritirata" else ""
        val address = if (own) " e il tuo indirizzo email scritto lì dentro" else ""
        // «più», not another «e»: the list already has one, and the presences and the payments are «presenze e pagamenti».
        val marked = if (fate.marked) ", più le presenze e i pagamenti segnati dai maestri" else ""
        val reason = texts.keptAs(fate.reason ?: KeepReason.PROOF)
        val whatItHolds = if (own) "il suo nome, la data di nascita, l’affidamento che hai accettato$address$marked" else "il suo nome, la data di nascita e l’affidamento che hai accettato$marked"
        return "L’iscrizione$withdrawn di $name alla ${quoted(fate.gatheringTitle)}, con $whatItHolds: resta fino al ${date(fate)}, $reason."
    }

    private fun small(): String = "Anche le copie di sicurezza, che sono cifrate, perdono questi dati entro ${texts.backups}."

    /** The dialog of a child that can be deleted now (design part 1, 4); [name] is the first name, or both when two children have the same one. */
    fun childDialog(plan: ChildPlan, name: String): ErasureDialog {
        val now = buildList {
            if (plan.childGoes) add("Il nome, il cognome e la data di nascita di ${plan.name}.")
            else add("$name dalla tua lista di figli: non comparirà più quando iscrivi un bambino.")
            for (fate in plan.going) add("L’iscrizione ritirata di ${fate.childName} alla ${quoted(fate.gatheringTitle)}, con i giorni scelti e il telefono.")
            if (plan.hasNotes) add("Le segnalazioni su allergie, intolleranze e farmaci di $name e il consenso che hai dato.")
        }
        val rest = if (plan.kept.isEmpty()) {
            DialogSection(null, emptyList(), "Non resta niente.")
        } else {
            DialogSection("Resta", plan.kept.map { stays(it, it.childName, own = false) })
        }
        return ErasureDialog(
            id = "cancella-figlio-${plan.childId}",
            title = "Cancellare i dati di $name?",
            sections = listOf(DialogSection("Cancelliamo subito", now), rest),
            small = small(),
            strong = "Non si può annullare.",
            confirmLabel = "Cancella i dati di $name",
            busyLabel = "Cancellazione…",
            action = "/profilo/figli/${plan.childId}/cancella",
        )
    }

    private fun join(parts: List<String>): String = if (parts.size == 1) parts.single() else parts.dropLast(1).joinToString(", ") + " e " + parts.last()

    /** The dialog of the account (design part 2, 3). */
    fun accountDialog(plan: AccountPlan): ErasureDialog {
        val now = buildList {
            add(if (plan.addressStays) "Il tuo accesso a Talità." else "Il tuo indirizzo email e il tuo accesso a Talità.")
            // What a person has done to enter without a link goes at once, and the dialog says it only if there is something (TALITA-15, criterion 29): on how many phones, and that a passkey stays in the phone.
            if (plan.devices == 1) add("Il dispositivo collegato: da lì esci da Talità.")
            if (plan.devices > 1) add("I dispositivi collegati: da tutti i telefoni e i computer esci da Talità.")
            if (plan.passkeys == 1) add("La passkey: resta nel telefono, ma non fa più entrare.")
            if (plan.passkeys > 1) add("Le passkey: restano nei telefoni, ma non fanno più entrare.")
            if (plan.isTeacher) add("Il tuo nome e cognome dall’elenco dei maestri: non gestirai più la convivenza né vedrai i bambini.")
            if (plan.hasInvitations) add("Gli inviti mandati a questo indirizzo.")
            for (child in plan.children) {
                if (child.childGoes) {
                    val parts = buildList {
                        add("il nome")
                        add("la data di nascita")
                        if (child.going.size == 1) add("l’iscrizione ritirata") else if (child.going.size > 1) add("le iscrizioni ritirate")
                        if (child.hasNotes) add("le segnalazioni")
                    }
                    add("I dati di ${child.name}: ${join(parts)}.")
                } else if (child.hasNotes) {
                    add("Le segnalazioni di ${child.name} e il consenso che hai dato.")
                }
            }
        }
        val rest = buildList {
            plan.kept.forEach { add(stays(it, it.childName, own = true)) }
            plan.readingsUntil?.let {
                add(
                    "Il tuo indirizzo email, senza nome: resta fino al ${GatheringFormat.date(it)}. Nel registro delle letture c’è scritto che hai letto le segnalazioni di alcuni " +
                        "bambini, e i genitori hanno diritto di sapere chi le ha lette.",
                )
            }
            plan.markedUntil?.let {
                add(
                    "Le presenze, i pagamenti e le iscrizioni dal modulo cartaceo che hai segnato: restano fino al ${GatheringFormat.date(it)}, senza il tuo nome. " +
                        "Si leggerà «un maestro che non è più tra i maestri».",
                )
            }
        }
        return ErasureDialog(
            id = "cancella-account",
            title = "Cancellare il tuo account?",
            sections = listOf(DialogSection("Cancelliamo subito", now), if (rest.isEmpty()) DialogSection(null, emptyList(), "Non resta niente.") else DialogSection("Resta", rest)),
            small = small(),
            strong = "Non si può annullare.",
            confirmLabel = "Cancella il mio account",
            busyLabel = "Cancellazione…",
            action = "/profilo/account/cancella",
        )
    }

    // ---- The notices after the request (criteria 19, 22) -----------------------------------------------------------------------------

    /** «Dati di Luca cancellati.» with what stays, until when and why (design part 1, 5). [name] is the one that the button showed. */
    fun childDone(done: ChildErasure.Done, name: String): Alert {
        val lead = "Dati di $name cancellati."
        if (done.kept.isEmpty()) return Alert(AlertVariant.SUCCESS, "Non resta niente.", lead)
        val reason = when {
            done.kept.all { it.reason == KeepReason.REGISTER } -> texts.keptAs(KeepReason.REGISTER)
            done.kept.all { it.reason == KeepReason.PROOF } -> texts.keptAs(KeepReason.PROOF)
            else -> "come prova dell’affidamento o come registro della convivenza"
        }
        val text = if (done.kept.size == 1) {
            val fate = done.kept.single()
            val withdrawn = if (fate.withdrawn) "ritirata " else ""
            "Resta fino al ${date(fate)} l’iscrizione $withdrawn" + "di $name alla ${quoted(fate.gatheringTitle)}, $reason. La trovi in «Iscrizioni»."
        } else {
            val list = join(done.kept.map { "alla ${quoted(it.gatheringTitle)} (fino al ${date(it)})" })
            "Restano le iscrizioni di $name $list, $reason. Le trovi in «Iscrizioni»."
        }
        return Alert(AlertVariant.SUCCESS, text, lead, "Vai alle iscrizioni", "/iscrizioni")
    }

    /** The notice when the same request had been made already: information, never an error. */
    fun childAlreadyDone(): Alert = Alert(AlertVariant.INFO, "Questi dati erano già stati cancellati.")

    /** What the 409 says at the top of the profile when a request is refused. */
    fun notDeleted(): Alert = Alert(AlertVariant.WARNING, "Non è stato cancellato niente. Le cose da fare prima sono scritte qui sotto.")

    /** What `/accedi` says after an account was deleted (once, and nothing about what stays: the dialog said it). */
    fun accountDeleted(): Alert = talita.account.ACCOUNT_DELETED_NOTICE
}
