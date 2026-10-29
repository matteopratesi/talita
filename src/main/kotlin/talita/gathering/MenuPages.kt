package talita.gathering

import org.springframework.stereotype.Component
import talita.ui.ConfirmModel
import talita.ui.Icon
import java.time.LocalDate

/**
 * The menu file as the pages say it (design TALITA-12, part 1): the icon of its kind, the name (the text of whoever wrote it: it is shown as text and wraps), and
 * `PDF · 240 KB · caricato il 3 settembre alle 21:40`. Never the content.
 */
data class MenuFileView(val name: String, val icon: Icon, val type: String, val size: String, val moment: String) {
    /** `PDF · 240 KB · caricato il 3 settembre alle 21:40`. */
    val meta: String get() = "$type · $size · caricato il $moment"

    /** `PDF · 240 KB`: the line of Arrivi. */
    val short: String get() = "$type · $size"
}

/**
 * The card «Menù» of the detail of the gathering (criterion 1): without a file, a sentence and the way to upload it; with it, the file and what can be done with
 * it. [dialog] is the dialog of «Togli» (only when there is a file to take away).
 */
data class MenuCard(
    val file: MenuFileView?,
    val menuHref: String,
    val downloadHref: String,
    val removeHref: String,
    val dialog: ConfirmModel?,
)

/**
 * The page of the menu (`/convivenza/{id}/menu`): one page for two cases, which change only in the words (design 2). [current] is the menu that is there, shown
 * above the form when the page is about replacing it. [error] is under the field and is the only way the page says that something is wrong.
 */
data class MenuPage(
    val id: Long,
    val heading: String,
    val gatheringTitle: String,
    val current: MenuFileView?,
    val button: String,
    val busyButton: String,
    val error: String?,
) {
    val action: String get() = "/convivenza/$id/menu"
    val cancelHref: String get() = "/convivenza/$id"
    val help: String get() = MenuTexts.HELP
    val warning: String get() = MenuTexts.DATA_WARNING
    val slowNote: String get() = MenuTexts.SLOW_NOTE
    val replaces: String get() = MenuTexts.REPLACES
}

/** The row of Arrivi that leads to the file (criterion 9): there is only when the gathering has a menu. */
data class MenuLink(val href: String, val meta: String)

/**
 * Says the menu in words: the one place of the texts of the card, the page, the dialog and the errors (design TALITA-12, parts 1 to 4), so that the limit of
 * size in the help and in the error is the constant of [MenuFile] and never a number written twice.
 */
@Component
class MenuPresenter(private val menus: GatheringMenus, private val gatherings: Gatherings) {

    private fun view(summary: MenuSummary, today: LocalDate): MenuFileView = MenuFileView(
        name = summary.fileName,
        icon = if (summary.type == MenuType.PDF) Icon.FILE_TEXT else Icon.IMAGE,
        type = summary.type.label,
        size = MenuFile.sizeLabel(summary.sizeBytes.toLong()),
        moment = MenuFile.momentLabel(summary.uploadedAt, today),
    )

    /** The card of the detail: reads the summary of the menu (name, kind, size, date) and never its content. */
    fun card(gatheringId: Long): MenuCard {
        val file = menus.summary(gatheringId)?.let { view(it, gatherings.today()) }
        return MenuCard(
            file = file,
            menuHref = "/convivenza/$gatheringId/menu",
            downloadHref = "/convivenza/$gatheringId/menu/file",
            removeHref = "/convivenza/$gatheringId/menu/togli",
            dialog = if (file == null) null else removeDialog(gatheringId),
        )
    }

    /** The page of the menu, with [error] under the field if the last attempt was refused. */
    fun page(gathering: Gathering, error: String?): MenuPage {
        val current = menus.summary(gathering.id)?.let { view(it, gatherings.today()) }
        return if (current == null) {
            MenuPage(gathering.id, "Carica il menù", gathering.title, null, "Carica il menù", "Caricamento…", error)
        } else {
            MenuPage(gathering.id, "Sostituisci il menù", gathering.title, current, "Sostituisci il menù", "Sostituzione…", error)
        }
    }

    /** The row of Arrivi: `Scarica il file · PDF · 240 KB`, or null if the gathering has no menu. */
    fun link(gatheringId: Long): MenuLink? =
        menus.summary(gatheringId)?.let { MenuLink("/convivenza/$gatheringId/menu/file", "Scarica il file · " + view(it, gatherings.today()).short) }

    /**
     * «Togliere il menù?» (criterion 6): the dialog of the card, or the page that stands in for it without JavaScript, the same words. It does not say the name of the
     * file: it is the same whether the menu was already taken away or replaced meanwhile, and the page of confirmation reads nothing. The button is the one of danger:
     * the file is deleted and does not come back (it can be uploaded again).
     */
    fun removeDialog(gatheringId: Long): ConfirmModel = ConfirmModel(
        id = "dialogo-togli-menu",
        title = "Togliere il menù?",
        text = "Il file del menù viene cancellato da Talità e i maestri non potranno più scaricarlo. Chi l’ha già scaricato ne ha una copia sul telefono. Potrai caricarne un altro quando vuoi.",
        confirmLabel = "Togli il menù",
        busyLabel = "Rimozione…",
        action = "/convivenza/$gatheringId/menu/togli",
        field = "from",
        value = "detail",
        target = null,
        cancelHref = "/convivenza/$gatheringId",
        danger = true,
    )
}

/** The words of the menu that more than one place says: the errors under the field, the notices of the page and the confirmations (design TALITA-12). */
object MenuTexts {
    const val CHOOSE_A_FILE = "Scegli il file del menù."
    const val NOT_A_MENU = "Questo file non è un PDF, un JPEG o un PNG."
    val TOO_BIG: String = "Il file è troppo grande: al massimo ${MenuFile.LIMIT_LABEL}."

    /** The help under the field. */
    val HELP: String = "Un PDF, oppure una foto JPEG o PNG del foglio. Al massimo ${MenuFile.LIMIT_LABEL}. Lo vedono solo i maestri."

    /** The notice above the field: the only defence of the app against what the file holds, which it does not read (the help is not enough). */
    const val DATA_WARNING = "Nel file niente nomi, allergie o altri dati dei bambini: le allergie stanno nelle segnalazioni."

    const val SLOW_NOTE = "Con poco campo può volerci un po’. Se il caricamento si interrompe non si salva niente: scegli di nuovo il file e riprova."

    const val REPLACES = "Il nuovo file prende il suo posto: il menù resta uno solo."

    const val UPLOADED = "Menù caricato."
    const val REMOVED = "Menù tolto."
}
