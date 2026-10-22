package talita.ui

import org.springframework.stereotype.Component
import talita.account.CurrentAccount

/** One entry of the main navigation, as the template draws it. */
data class NavItem(val label: String, val href: String, val icon: Icon, val current: Boolean)

/**
 * The main navigation. The list is built on every request, not written in the template, and it depends
 * on the role of the person as the database says it now: a teacher has the four entries, a parent only
 * Home and Iscrizioni. A person who is both has the navigation of the teacher.
 */
@Component
class Navigation {

    /**
     * @param current the section the page belongs to; none on the error pages and in the profile
     * @param account who is making the request; a teacher sees every section, anyone else those of a parent
     */
    fun items(current: Section?, account: CurrentAccount?): List<NavItem> =
        Section.entries
            .filter { account?.isTeacher == true || it.visibleToParents }
            .map { NavItem(label = it.label, href = it.path, icon = it.icon, current = it == current) }
}
