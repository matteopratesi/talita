package talita.account

import org.jooq.DSLContext
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import talita.Addresses
import talita.CapturesOutput
import talita.PostgresConfiguration
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.ONE_TIME_TOKENS
import talita.mail.Mailbox
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the person sees, and what the log keeps, when the link cannot be sent (criteria 5 and 15). */
private fun MockBrowser.askForLink(address: String): MvcResult = submit("/accedi", "/accedi", mapOf("email" to address))

private const val SEND_FAILED = "Non siamo riusciti a mandare il link. Riprova tra qualche minuto. Se il problema continua, avvisa chi organizza la convivenza."

/** The mail server is down: nobody listens on the port. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["spring.mail.port=1"])
@AutoConfigureMockMvc
class LoginMailDownTests(@Autowired private val mockMvc: MockMvc, @Autowired private val dsl: DSLContext) {

    @Test
    @CapturesOutput
    fun `t3 c15 with the mail server down the page stays filled in with a notice, no link is left, and the log has neither the address nor the link`(output: CapturedOutput) {
        val address = Addresses.unique("guasto")
        val before = dsl.fetchCount(ONE_TIME_TOKENS)

        // Many tries in a row: a mail that does not leave does not use up the three links of the address.
        repeat(5) {
            val result = MockBrowser(mockMvc).askForLink(address)

            assertEquals(422, result.response.status)
            val html = result.response.contentAsString
            assertTrue(html.contains(SEND_FAILED), html)
            assertTrue(html.contains("""role="alert""""))
            assertTrue(html.contains("""value="$address""""))
        }

        assertEquals(before, dsl.fetchCount(ONE_TIME_TOKENS), "a link that nobody has was left in the table")
        assertTrue(Mailbox.sentTo(address).isEmpty())
        assertTrue(output.all.contains("Mail not sent: "), "the log does not say that the mail did not leave")
        assertFalse(output.all.contains(address), "the log has the address")
        assertFalse(output.all.contains("/accedi/conferma"), "the log has a link")
    }
}

/** The address of the site is missing: no link can be made. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["talita.public-url="])
@AutoConfigureMockMvc
class LoginWithoutPublicUrlTests(@Autowired private val mockMvc: MockMvc, @Autowired private val dsl: DSLContext) {

    @Test
    @CapturesOutput
    fun `t3 c15 without talita public-url no mail with a link leaves, the person is told, and the log says why`(output: CapturedOutput) {
        val address = Addresses.unique("senza-indirizzo")
        val before = dsl.fetchCount(ONE_TIME_TOKENS)

        val result = MockBrowser(mockMvc).askForLink(address)

        assertEquals(422, result.response.status)
        assertTrue(result.response.contentAsString.contains(SEND_FAILED))
        assertEquals(before, dsl.fetchCount(ONE_TIME_TOKENS))
        assertTrue(Mailbox.sentTo(address).isEmpty(), "a mail left without a public address")
        assertTrue(output.all.contains("talita.public-url is not set"))
        assertFalse(output.all.contains(address))
    }
}

/** The mail server accepts the connection and says nothing: the sending fails in time and the person is told (criterion 5). */
@Import(PostgresConfiguration::class)
@SpringBootTest(properties = [PostgresConfiguration.PRIVACY_CONTROLLER, PostgresConfiguration.PRIVACY_CONTACT])
@AutoConfigureMockMvc
class LoginSlowMailTests(@Autowired private val mockMvc: MockMvc) {

    @Test
    fun `t3 c05 with a server that accepts the connection and keeps quiet the person sees the notice in less than 15 seconds`() {
        val started = System.nanoTime()
        val result = assertTimeoutPreemptively(
            Duration.ofSeconds(30),
            ThrowingSupplier { MockBrowser(mockMvc).askForLink(Addresses.unique("lento")) },
        )
        val seconds = Duration.ofNanos(System.nanoTime() - started).toMillis() / 1000.0

        assertEquals(422, result.response.status)
        assertTrue(result.response.contentAsString.contains(SEND_FAILED))
        assertTrue(seconds < 15, "the page answered after $seconds seconds: htmx gives up after 20")
    }

    companion object {
        private val silent = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        private val accepted = CopyOnWriteArrayList<Socket>()

        init {
            thread(isDaemon = true) {
                try {
                    while (true) accepted += silent.accept()
                } catch (_: IOException) {
                    // closed with the test run
                }
            }
        }

        @JvmStatic
        @DynamicPropertySource
        fun mail(registry: DynamicPropertyRegistry) {
            registry.add("spring.mail.host") { "127.0.0.1" }
            registry.add("spring.mail.port") { silent.localPort }
            registry.add("spring.mail.properties.mail.smtp.starttls.enable") { "false" }
            registry.add("spring.mail.properties.mail.smtp.starttls.required") { "false" }
            registry.add("talita.mail.from") { Addresses.of("talita") }
            registry.add("talita.public-url") { TestcontainersConfiguration.PUBLIC_URL }
        }
    }
}
