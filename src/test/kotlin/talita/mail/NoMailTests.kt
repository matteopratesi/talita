package talita.mail

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.contributor.HealthContributor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import talita.Addresses
import talita.PostgresConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The application as the smoke test starts it: no profile and no `spring.mail.host`. Only the
 * database is there; no mail server, no sender address, no public address.
 */
@Import(PostgresConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class NoMailTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val context: ApplicationContext,
    @Autowired private val mailer: Mailer,
) {

    @Test
    fun `t3 c03 without a mail server the application starts, the health is UP and the log says that the mail is not configured`(output: CapturedOutput) {
        assertEquals(0, context.getBeansOfType(JavaMailSender::class.java).size, "there is a mail sender without spring.mail.host")

        mockMvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
        assertTrue(output.all.contains("Mail is not configured (spring.mail.host is not set)"), "the log does not warn")
    }

    @Test
    fun `t3 c03 asking for a mail without a server fails without stopping anything, and the log does not name the recipient`(output: CapturedOutput) {
        val to = Addresses.unique("senza-posta")

        assertEquals(MailResult.FAILED, mailer.send(OutgoingMail(to, "Oggetto", "Testo")))

        assertTrue(output.all.contains("Mail not sent: no mail server configured."))
        assertTrue(!output.all.contains(to), "the log carries the address of the recipient")
    }

    @Test
    fun `t3 c04 the mail server is not part of the health of the application`() {
        val contributors = context.getBeansOfType(HealthContributor::class.java).keys
        assertTrue(contributors.none { it.contains("mail", ignoreCase = true) }, "health contributors: $contributors")
    }
}
