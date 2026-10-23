package talita.ui

/**
 * A dialog of confirmation, or the page that stands in for it without JavaScript: the same words (design guide, 4.8).
 *
 * @property target the block that the answer of the dialog replaces (htmx `hx-target`, as `#maestri-contenuto`); null when the
 * answer is a whole page (the form is sent by the boosted body and the server redirects)
 * @property cancelHref where «Annulla» of the page without JavaScript leads
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
)
