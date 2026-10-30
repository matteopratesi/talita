package talita.ui

import org.springframework.stereotype.Component
import talita.account.CurrentAccounts
import talita.account.PasskeySupport

/**
 * Builds the [Shell] of a page. The person who is inside is not passed in: the factory asks
 * [CurrentAccounts], so no controller can forget it (a person without access gets the shell without
 * navigation and without profile, and the navigation of a parent and that of a teacher differ).
 */
@Component
class ShellFactory(
    private val assets: Assets,
    private val navigation: Navigation,
    private val accounts: CurrentAccounts,
    private val passkeys: PasskeySupport,
) {

    /**
     * @param page name of the page for the title of the tab (`Convivenza` gives `Convivenza · Talità`);
     * null for the home, whose title is just `Talità`
     * @param current the section the page belongs to; null on the error pages
     * @param onProfile the page is the profile: the "Profilo" pill in the header is the current one
     * @param noindex the page asks not to be indexed (`noindex` in its head)
     * @param wide the page has a table that is printed and takes more width than the others
     */
    fun shell(page: String?, current: Section?, onProfile: Boolean = false, noindex: Boolean = false, wide: Boolean = false): Shell {
        val account = accounts.current()
        return Shell(
            title = if (page == null) "Talità" else "$page · Talità",
            assets = assets,
            navigation = if (account == null) emptyList() else navigation.items(current, account),
            profile = account?.let { ProfileLink(it.initials, onProfile) },
            noindex = noindex,
            wide = wide,
            passkeys = passkeys.enabled,
        )
    }

    /** The shell of the public pages (no navigation, no profile, no script): [page] is the title of the tab, `{page} · Talità`. */
    fun publicShell(page: String, share: Share?): PublicShell = PublicShell("$page · Talità", assets, share)
}
