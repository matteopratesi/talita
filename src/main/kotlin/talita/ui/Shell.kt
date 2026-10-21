package talita.ui

/** What the layout needs on every page. Templates read it through its getters. */
class Shell(
    /** Text of the browser tab: `Convivenza · Talità`, or just `Talità` for the home. */
    val title: String,
    val assets: Assets,
    val navigation: List<NavItem>,
)
