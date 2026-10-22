package talita.e2e

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import talita.TestcontainersConfiguration
import talita.mail.Mailbox

/** The field of an address on the access page. */
internal fun Page.addressField() = getByLabel("Indirizzo email")

internal fun Page.buttonNamed(name: String) = getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(name))

internal fun Page.linkNamed(name: String) = getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName(name))

/**
 * Enters as a person who has never been inside, from the page of access, with the mail that Mailpit
 * receives: the address, the link in the mail, the tap on «Entra». The page is where the app leads the
 * person next (the home, or «Accetta l'invito» if the address has an invitation); the test says which.
 */
internal fun E2e.enterWithTheMail(address: String, on: Page = page) {
    on.navigate(url("/accedi"))
    on.waitForSettledContent()
    on.addressField().fill(address)
    touch(on.buttonNamed("Ricevi il link"))
    assertThat(on.locator("h1")).hasText("Controlla la posta")

    val link = Mailbox.waitForMail(address).links().single()
    on.navigate(url(link.removePrefix(TestcontainersConfiguration.PUBLIC_URL)))
    on.waitForSettledContent()
    touch(on.buttonNamed("Entra"))
}
