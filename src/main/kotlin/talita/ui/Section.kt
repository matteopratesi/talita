package talita.ui

/**
 * The sections of the main navigation: only what is in the scope of the product (the gathering,
 * the registrations, the arrivals). The order is the order of the navigation, which follows the
 * work of the teachers: prepare the gathering, collect the registrations, mark the arrivals. The
 * labels and URLs are those approved in the design.
 *
 * A teacher sees every section. A parent sees only those with [visibleToParents]: the home and the
 * registrations (of their own children, with its own texts). The pages of the others answer 403 to a
 * parent (`TeacherPaths`), so the navigation and the rule say the same thing.
 *
 * @property summary the line under the section in the list of the home page of a teacher
 * @property placeholder the sentence of the "in preparation" page of a teacher, until the section exists (none for the gathering, which exists)
 * @property visibleToParents whether the entry is in the navigation of a parent
 * @property parentSummary the line under the section in the home of a parent
 * @property parentPlaceholder the sentence of the "in preparation" page of a parent
 */
enum class Section(
    val label: String,
    val path: String,
    val icon: Icon,
    val visibleToParents: Boolean,
    val summary: String? = null,
    val placeholder: String? = null,
    val parentSummary: String? = null,
    val parentPlaceholder: String? = null,
) {
    HOME("Home", "/", Icon.HOUSE, visibleToParents = true),
    CONVIVENZA(
        "Convivenza", "/convivenza", Icon.CALENDAR_DAYS, visibleToParents = false,
        summary = "Le date, i maestri e le informazioni per i genitori.",
    ),
    ISCRIZIONI(
        "Iscrizioni", "/iscrizioni", Icon.USERS_ROUND, visibleToParents = true,
        summary = "I bambini iscritti, anche quelli dal modulo cartaceo.",
        placeholder = "Qui troverai i bambini iscritti e potrai inserire le iscrizioni arrivate sul modulo cartaceo.",
        parentSummary = "Le iscrizioni dei tuoi figli.",
        parentPlaceholder = "Qui troverai le iscrizioni dei tuoi figli.",
    ),
    ARRIVI(
        "Arrivi", "/arrivi", Icon.CLIPBOARD_CHECK, visibleToParents = false,
        summary = "Chi è arrivato e le liste da stampare.",
        placeholder = "Qui potrai segnare chi è arrivato e stampare le liste.",
    ),
}
