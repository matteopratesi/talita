package talita.ui

/**
 * A dialog of confirmation, or the page that stands in for it without JavaScript: the same words (design guide, 4.8).
 *
 * @property target the block that the answer of the dialog replaces (htmx `hx-target`, as `#maestri-contenuto`); null when the
 * answer is a whole page (the form is sent by the boosted body and the server redirects)
 * @property cancelHref where «Annulla» of the page without JavaScript leads
 * @property danger the action takes something away for good (the notes on health): the button is the one of danger and not the main one (design guide 4.8)
 * @property more other paragraphs of the text, after [text] (the dialogs of the devices say what happens to the others and to this one in a sentence each)
 * @property note a small last paragraph (`text-sm`): what does not change
 * @property confirmFor what the confirmation with the passkey is for, if the action wants one first (`togli`): the form carries `data-conferma` and the script of the passkeys holds the request until the
 * phone has verified the person (TALITA-15)
 */
data class ConfirmModel(
    val id: String,
    val title: String,
    val text: String,
    val confirmLabel: String,
    val busyLabel: String,
    val action: String,
    val field: String,
    val value: String,
    val target: String? = "#maestri-contenuto",
    val cancelHref: String = "/maestri",
    val danger: Boolean = false,
    val more: List<String> = emptyList(),
    val note: String? = null,
    val confirmFor: String? = null,
)
