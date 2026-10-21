package talita.ui

/**
 * The sections of the main navigation: only what is in the scope of the product (no preparation
 * shifts, no priests). The order is the order of the navigation. The labels and URLs are those
 * approved in the design; the Centro may change them after the first call (docs/05, question 11).
 *
 * @property summary the line under the section in the list of the home page
 * @property placeholder the sentence of the "in preparation" page, until the section exists
 */
enum class Section(
    val label: String,
    val path: String,
    val icon: Icon,
    val summary: String? = null,
    val placeholder: String? = null,
) {
    HOME("Home", "/", Icon.HOUSE),
    COMUNITA(
        "Comunità", "/comunita", Icon.USERS_ROUND,
        summary = "L’elenco delle comunità, con i fratelli e lo storico.",
        placeholder = "Qui troverai l’elenco delle comunità, con i fratelli e lo storico di ciascuna.",
    ),
    PERSONE(
        "Persone", "/persone", Icon.CONTACT_ROUND,
        summary = "Cerca una persona e apri la sua scheda.",
        placeholder = "Qui potrai cercare una persona e aprire la sua scheda.",
    ),
    CONVIVENZE(
        "Convivenze", "/convivenze", Icon.CALENDAR_DAYS,
        summary = "Le convivenze, con le iscrizioni e le quote.",
        placeholder = "Qui troverai le convivenze, con le iscrizioni e le quote.",
    ),
}
