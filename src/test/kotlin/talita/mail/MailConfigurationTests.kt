package talita.mail

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What `application.yaml` says about the mail (criterion 4), read as a file: no context, no profile. */
class MailConfigurationTests {

    private val application: Map<String, String> = YamlPropertiesFactoryBean().let { factory ->
        factory.setResources(ClassPathResource("application.yaml"))
        factory.getObject()!!.entries.associate { it.key.toString() to it.value.toString() }
    }

    @Test
    fun `t3 c04 STARTTLS is on and required, the SMTP timeouts are 10 seconds and the mail is out of the health`() {
        assertEquals("true", application["spring.mail.properties.mail.smtp.starttls.enable"])
        assertEquals("true", application["spring.mail.properties.mail.smtp.starttls.required"])
        for (timeout in listOf("connectiontimeout", "timeout", "writetimeout")) {
            assertEquals("10000", application["spring.mail.properties.mail.smtp.$timeout"], timeout)
        }
        assertEquals("false", application["management.health.mail.enabled"])
    }

    @Test
    fun `t3 c04 application_yaml has no mail host, user, password or sender, and no address of anyone`() {
        for (key in listOf("spring.mail.host", "spring.mail.port", "spring.mail.username", "spring.mail.password", "talita.mail.from", "talita.public-url", "talita.bootstrap.teacher")) {
            assertTrue(key !in application, "application.yaml sets $key")
        }
        val text = Files.readString(Path.of("src/main/resources/application.yaml"))
        assertTrue(!Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""").containsMatchIn(text), "an address in application.yaml")
    }

    @Test
    fun `t3 c04 the variables of production are in docs 04 with an example value`() {
        val docs = Files.readString(Path.of("docs/04-ambiente.md"))

        for (variable in listOf(
            "SPRING_MAIL_HOST", "SPRING_MAIL_PORT", "SPRING_MAIL_USERNAME", "SPRING_MAIL_PASSWORD",
            "TALITA_MAIL_FROM", "TALITA_PUBLIC_URL", "TALITA_BOOTSTRAP_TEACHER",
        )) {
            assertTrue(Regex("""(?m)^\s*$variable=\S+""").containsMatchIn(docs), "docs/04-ambiente.md has no example for $variable")
        }
    }
}
