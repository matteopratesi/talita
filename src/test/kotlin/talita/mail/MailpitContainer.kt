package talita.mail

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * The Mailpit that catches the mail in the tests (unit and e2e), the same version as the one of
 * `compose.yaml`. One container for the whole test run: every application context sends to it, and
 * the tests tell their mails apart by the address of the recipient (see [Mailbox]). No test talks to
 * a real mail server.
 */
object MailpitContainer {

    /** Image with an exact tag: never `latest`. A test checks that `compose.yaml` uses the same. */
    const val IMAGE = "axllent/mailpit:v1.31.4"

    private const val SMTP_PORT = 1025
    private const val HTTP_PORT = 8025

    private val container: GenericContainer<*> = GenericContainer(DockerImageName.parse(IMAGE))
        .withExposedPorts(SMTP_PORT, HTTP_PORT)
        // Mailpit would otherwise ask GitHub whether there is a newer version...
        .withEnv("MP_DISABLE_VERSION_CHECK", "true")
        // ...and look up in the DNS the name of whoever sends the mail, which costs five seconds for every mail
        // where the network does not answer for the address of the Docker bridge.
        .withEnv("MP_SMTP_DISABLE_RDNS", "true")
        .waitingFor(Wait.forHttp("/readyz").forPort(HTTP_PORT).withStartupTimeout(Duration.ofSeconds(60)))
        .also { it.start() }

    val host: String get() = container.host

    val smtpPort: Int get() = container.getMappedPort(SMTP_PORT)

    val httpPort: Int get() = container.getMappedPort(HTTP_PORT)
}
