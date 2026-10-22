package talita.ui

/** The "Profilo" pill in the header: the letters in the circle, and whether the page on screen is the profile. */
data class ProfileLink(val initials: String, val current: Boolean)

/**
 * What the layout needs on every page. Templates read it through its getters.
 *
 * A person who is inside has the [profile] in the header and the [navigation]; one who is not (the
 * login pages, the error pages for someone without access) has neither: only the mark, in a narrow
 * column ([isSignedIn] tells the layout which one to draw).
 */
class Shell(
    /** Text of the browser tab: `Convivenza · Talità`, or just `Talità` for the home. */
    val title: String,
    val assets: Assets,
    val navigation: List<NavItem>,
    val profile: ProfileLink? = null,
) {
    val isSignedIn: Boolean get() = profile != null

    /** Where "Vai alla home" of the error pages leads: the home, or for a person without access the access page. */
    val homeHref: String get() = if (isSignedIn) "/" else "/accedi"

    val homeLabel: String get() = if (isSignedIn) "Vai alla home" else "Vai all’accesso"
}
