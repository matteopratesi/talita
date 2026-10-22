package talita

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.annotation.MergedAnnotations
import org.springframework.core.env.MapPropertySource
import org.springframework.test.context.ContextConfigurationAttributes
import org.springframework.test.context.ContextCustomizer
import org.springframework.test.context.ContextCustomizerFactory
import org.springframework.test.context.MergedContextConfiguration
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import talita.mail.MailpitContainer

/** Only the database: for the tests of an application that starts without a mail server. */
@TestConfiguration(proxyBeanMethods = false)
class PostgresConfiguration {

    // Same major version as compose.yaml, the jOOQ code generation and the server.
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:18"))
}

/**
 * What every test of the whole application needs: Postgres, a clock that a test can move forward
 * and, through [MailpitContextCustomizerFactory], a Mailpit that catches the mail and the public
 * address of the site. No test uses a real mail server.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(PostgresConfiguration::class)
class TestcontainersConfiguration {

    @Bean
    @Primary
    fun testClock(): MutableClock = MutableClock(ClockConfiguration.ZONE)

    companion object {
        /** The address that the links of the mails start with (never the Host of a request). */
        const val PUBLIC_URL = "https://talita.example.org"
    }
}

/**
 * Gives the mail properties to every application context that imports [TestcontainersConfiguration].
 *
 * It has to be a customizer of the context and not a bean (`DynamicPropertyRegistrar`): Spring Boot
 * sets up the mail sender only if `spring.mail.host` exists while the configuration classes are read,
 * which is before any bean is created. Registered in `META-INF/spring.factories` of the tests.
 */
class MailpitContextCustomizerFactory : ContextCustomizerFactory {

    override fun createContextCustomizer(testClass: Class<*>, configAttributes: List<ContextConfigurationAttributes>): ContextCustomizer? {
        val imported = MergedAnnotations.from(testClass, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
            .stream(Import::class.java)
            .anyMatch { TestcontainersConfiguration::class.java in it.getClassArray("value") }
        return if (imported) MailpitContextCustomizer else null
    }
}

private object MailpitContextCustomizer : ContextCustomizer {

    override fun customizeContext(context: ConfigurableApplicationContext, mergedConfig: MergedContextConfiguration) {
        val properties = mapOf<String, Any>(
            "spring.mail.host" to MailpitContainer.host,
            "spring.mail.port" to MailpitContainer.smtpPort,
            // The Mailpit of the tests has no certificate, so no STARTTLS (as in the dev profile).
            "spring.mail.properties.mail.smtp.starttls.enable" to "false",
            "spring.mail.properties.mail.smtp.starttls.required" to "false",
            "talita.mail.from" to Addresses.of("talita"),
            "talita.public-url" to TestcontainersConfiguration.PUBLIC_URL,
        )
        context.environment.propertySources.addFirst(MapPropertySource("mailpit", properties))
    }
}
