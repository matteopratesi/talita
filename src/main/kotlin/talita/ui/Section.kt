package talita.ui

/**
 * The sections of the main navigation: only what is in the scope of the product (the gathering,
 * the registrations, the arrivals). The order is the order of the navigation, which follows the
 * work of the teachers: prepare the gathering, collect the registrations, mark the arrivals. The
 * labels and URLs are those approved in the design.
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
    CONVIVENZA(
        "Convivenza", "/convivenza", Icon.CALENDAR_DAYS,
        summary = "Le date, i maestri e le informazioni per i genitori.",
        placeholder = "Qui troverai le date, i maestri e la pagina con le informazioni per i genitori.",
    ),
    ISCRIZIONI(
        "Iscrizioni", "/iscrizioni", Icon.USERS_ROUND,
        summary = "I bambini iscritti, anche quelli dal modulo cartaceo.",
        placeholder = "Qui troverai i bambini iscritti e potrai inserire le iscrizioni arrivate sul modulo cartaceo.",
    ),
    ARRIVI(
        "Arrivi", "/arrivi", Icon.CLIPBOARD_CHECK,
        summary = "Chi è arrivato e le liste da stampare.",
        placeholder = "Qui potrai segnare chi è arrivato e stampare le liste.",
    ),
}
