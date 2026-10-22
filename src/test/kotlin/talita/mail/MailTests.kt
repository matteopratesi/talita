package talita.mail

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import talita.Addresses
import talita.MutableClock
import talita.TestcontainersConfiguration
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The mail of Talità (criteria 1, 2, 5 and 6): sent to a Mailpit, plain text, with the headers of Mailjet. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class MailTests(
    @Autowired private val mailer: Mailer,
    @Autowired private val mailSender: JavaMailSender,
    @Autowired private val settings: MailSettings,
    @Autowired private val clock: MutableClock,
    @Autowired private val environment: Environment,
    @Value("\${spring.mail.host}") private val mailHost: String,
) {

    private fun read(path: String): String = Files.readString(Path.of(path))

    /** The mail that the design gives for trying the mail (comment «Design · parte A»). */
    private fun testMail(to: String) = OutgoingMail(
        to = to,
        subject = "Prova di posta da Talità",
        text = "Ciao,\n\nquesta è una mail di prova. Se la leggi, la posta di Talità funziona.\n\nTalità\n",
    )

    @Test
    fun `t3 c01 compose starts Mailpit with an exact version, on 127_0_0_1 only, SMTP 1026 and web 8026, without a relay`() {
        val compose = read("compose.yaml")

        assertTrue(compose.contains("image: ${MailpitContainer.IMAGE}"), "compose.yaml and the tests must use the same Mailpit")
        assertTrue(Regex("""image: axllent/mailpit:v\d+\.\d+\.\d+\s""").containsMatchIn(compose), "an exact version, never latest")
        // Every published port, all on localhost. 1025 and 8025 belong to another project on the same
        // PC, like 5432 and 8080, so they are never the port of the host.
        val ports = compose.lines().map { it.trim() }
            .filter { it.startsWith("- \"") && it.endsWith("\"") }
            .map { it.removePrefix("- \"").removeSuffix("\"") }
        assertEquals(listOf("127.0.0.1:5433:5432", "127.0.0.1:1026:1025", "127.0.0.1:8026:8025"), ports)
        // Nothing leaves Mailpit: no relay to a real server.
        assertTrue(!compose.contains("MP_SMTP_RELAY"), "Mailpit must not forward the mail")
    }

    @Test
    fun `t3 c01 the dev profile sends the mail to the Mailpit of compose`() {
        val dev = yaml("application-dev.yaml")

        assertEquals("127.0.0.1", dev["spring.mail.host"])
        assertEquals("1026", dev["spring.mail.port"].toString())
        assertEquals("false", dev["spring.mail.properties.mail.smtp.starttls.enable"].toString())
        assertEquals("false", dev["spring.mail.properties.mail.smtp.starttls.required"].toString())
    }

    @Test
    fun `t3 c02 a mail sent by the application is received by the Mailpit of the tests, which tells recipient, subject and text`() {
        val to = Addresses.unique("mail")

        assertEquals(MailResult.SENT, mailer.send(testMail(to)))

        val received = Mailbox.waitForMail(to)
        assertEquals(listOf(to), received.to)
        assertEquals("Prova di posta da Talità", received.subject)
        assertTrue(received.text.contains("questa è una mail di prova. Se la leggi, la posta di Talità funziona."), received.text)
        // The mail went to the container, not to a real server.
        assertEquals(MailpitContainer.host, mailHost)
        assertEquals(MailpitContainer.smtpPort, environment.getRequiredProperty("spring.mail.port").toInt())
    }

    @Test
    fun `t3 c05 a server that accepts the connection and says nothing makes the sending fail in less than 15 seconds`() {
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { silent ->
            val accepted = CopyOnWriteArrayList<Socket>()
            thread(isDaemon = true) {
                try {
                    while (true) accepted += silent.accept()
                } catch (_: IOException) {
                    // the server socket was closed: the test is over
                }
            }
            // The sender of the application, with the same properties (the timeouts), towards the silent server.
            val towardsSilent = JavaMailSenderImpl().apply {
                host = InetAddress.getLoopbackAddress().hostAddress
                port = silent.localPort
                javaMailProperties = (mailSender as JavaMailSenderImpl).javaMailProperties
            }
            val slowMailer = Mailer(towardsSilent, settings, MailQuota(settings, clock))

            val started = System.nanoTime()
            val result = assertTimeoutPreemptively(Duration.ofSeconds(25), ThrowingSupplier { slowMailer.send(testMail(Addresses.unique("lento"))) })
            val seconds = Duration.ofNanos(System.nanoTime() - started).toMillis() / 1000.0

            assertEquals(MailResult.FAILED, result)
            assertTrue(seconds < 15, "the sending failed after $seconds seconds: htmx gives up after 20, the person would see the wrong message")
            accepted.forEach { it.close() }
        }
    }

    @Test
    fun `t3 c06 a mail is from Talita, plain text only, with the headers that switch off the tracking at Mailjet`() {
        val to = Addresses.unique("formato")
        // An ampersand and an apostrophe stay as they are: the text does not go through the HTML escape.
        val text = "Ciao,\n\ntocca questo link: https://talita.example.org/accedi/conferma?token=a&b=c\n\nL’invito vale 7 giorni & non serve altro. Dell'altro no.\n\nTalità\n"

        assertEquals(MailResult.SENT, mailer.send(OutgoingMail(to, "Oggetto di prova", text)))

        val received = Mailbox.waitForMail(to)
        assertEquals("Talità", received.fromName)
        assertEquals(settings.from, received.fromAddress)
        assertEquals(Addresses.of("talita"), received.fromAddress)
        assertEquals("", received.html, "no HTML part")
        assertTrue(received.text.contains("https://talita.example.org/accedi/conferma?token=a&b=c"), received.text)
        assertTrue(received.text.contains("L’invito vale 7 giorni & non serve altro. Dell'altro no."), received.text)
        assertTrue(!received.text.contains("&amp;") && !received.text.contains("&#"), "the text was escaped like HTML")
        assertEquals(listOf("0"), received.headers["x-mailjet-trackopen"])
        assertEquals(listOf("0"), received.headers["x-mailjet-trackclick"])
        val contentType = received.headers.getValue("content-type").single().lowercase()
        assertTrue(contentType.startsWith("text/plain") && contentType.contains("utf-8"), "content type: $contentType")
    }

    private fun yaml(resource: String): Map<String, Any> {
        val factory = org.springframework.beans.factory.config.YamlPropertiesFactoryBean()
        factory.setResources(org.springframework.core.io.ClassPathResource(resource))
        return factory.getObject()!!.entries.associate { it.key.toString() to it.value }
    }
}
