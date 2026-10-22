package talita.account

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.mail.Mailbox
import kotlin.test.assertFalse

/** The log at INFO and above has no address, no token and no link, whatever happens (criterion 26). */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class LogsTests(@Autowired private val mockMvc: MockMvc) {

    @Test
    fun `t3 c26 the log has no address, token or link during a link, a login, a link that does not work, an exit and a wrong address`(output: CapturedOutput) {
        val address = Addresses.unique("diario")
        val wrong = "indirizzo-sbagliato-" + System.nanoTime()
        val browser = MockBrowser(mockMvc)

        browser.submit("/accedi", "/accedi", mapOf("email" to wrong))
        browser.submit("/accedi", "/accedi", mapOf("email" to address))
        val token = Mailbox.waitForMail(address).links().single().substringAfter("token=")
        browser.confirm(token)
        browser.get("/profilo")
        browser.submit("/profilo", "/esci", emptyMap())
        // The same link again, an invented one, and a form with a code that expired.
        browser.confirm(token)
        browser.confirm("0b8b3d7e-2222-4333-8444-555566667777")
        browser.post("/accedi", mapOf("email" to address))

        val log = output.all
        for (secret in listOf(address, wrong, token, "token=", "/accedi/conferma?")) {
            assertFalse(log.contains(secret), "the log has '$secret'")
        }
        // Nobody writes the address of the person in the session or in the exceptions either.
        assertFalse(Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\.[A-Za-z]{2,}""").containsMatchIn(log.replace(Addresses.of("talita"), "")), "an address is in the log")
    }
}
