package talita.ui

import org.springframework.stereotype.Component

/** One entry of the main navigation, as the template draws it. */
data class NavItem(val label: String, val href: String, val icon: Icon, val current: Boolean)

/**
 * The main navigation. The list is built on every request, not written in the template: login
 * (TALITA-3) will filter it by role (teacher or parent).
 */
@Component
class Navigation {

    /** @param current the section the page belongs to; none on the error pages */
    fun items(current: Section?): List<NavItem> =
        Section.entries.map { NavItem(label = it.label, href = it.path, icon = it.icon, current = it == current) }
}
