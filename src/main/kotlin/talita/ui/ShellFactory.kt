package talita.ui

import org.springframework.stereotype.Component

/** Builds the [Shell] of a page. */
@Component
class ShellFactory(private val assets: Assets, private val navigation: Navigation) {

    /**
     * @param page name of the page for the title of the tab (`Convivenza` gives `Convivenza · Talità`);
     * null for the home, whose title is just `Talità`
     * @param current the section the page belongs to; null on the error pages
     */
    fun shell(page: String?, current: Section?): Shell = Shell(
        title = if (page == null) "Talità" else "$page · Talità",
        assets = assets,
        navigation = navigation.items(current),
    )
}
