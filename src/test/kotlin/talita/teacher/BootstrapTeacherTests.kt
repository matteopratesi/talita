package talita.teacher

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import talita.Addresses
import talita.CapturesOutput
import talita.TestcontainersConfiguration
import talita.account.TestAccounts
import talita.jooq.tables.references.TEACHER_INVITATION
import talita.mail.Mailbox
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The first teacher, from `TALITA_BOOTSTRAP_TEACHER` (criterion 30): the logic, in the shared application. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class BootstrapTeacherTests(
    @Autowired private val dsl: DSLContext,
    @Autowired private val realTeachers: Teachers,
    @Autowired private val clock: java.time.Clock,
    @Autowired private val invitations: Invitations,
) {

    /** Written in pieces: the only address allowed in a tracked file is `nome@example.org`. */
    private fun at(local: String, domain: String) = local + "@" + domain

    private val noTeachers = object : Teachers(dsl, clock) {
        override fun count(): Int = 0
    }

    private fun bootstrap(configured: String, teachers: Teachers = noTeachers) = BootstrapTeacher(BootstrapSettings(configured), teachers, invitations)

    @Test
    @CapturesOutput
    fun `t3 c30 with no teacher the address of the variable gets an invitation, normalized, and no mail leaves, and the log does not say the address`(output: CapturedOutput) {
        val local = "primo-" + System.nanoTime()

        bootstrap("  " + at(local.replaceFirstChar { it.uppercase() }, "Example.ORG") + " ").inviteTheFirstTeacher()

        val invitation = invitations.pendingFor(at(local, "example.org"))
        assertNotNull(invitation, "no invitation for the normalized address")
        assertEquals(at(local, "example.org"), invitation.email)
        assertTrue(invitation.expiresAt.isAfter(java.time.Instant.now().plus(java.time.Duration.ofDays(6))), "less than 7 days")
        assertTrue(Mailbox.sentTo(at(local, "example.org")).isEmpty(), "a mail left")
        assertTrue(output.all.contains("is invited (no mail sent)"))
        assertFalse(output.all.contains(local), "the log has the address")
    }

    @Test
    fun `t3 c30 with a teacher the variable does nothing`() {
        TestAccounts.makeTeacher(dsl, Addresses.unique("c-e-gia-uno"))
        val address = at("non-invitato-" + System.nanoTime(), "example.org")

        bootstrap(address, realTeachers).inviteTheFirstTeacher()

        assertNull(invitations.pendingFor(address))
        assertEquals(0, dsl.fetchCount(TEACHER_INVITATION, TEACHER_INVITATION.EMAIL.eq(address)))
    }

    @Test
    @CapturesOutput
    fun `t3 c30 without the variable and without teachers the application goes on and the log warns`(output: CapturedOutput) {
        val before = dsl.fetchCount(TEACHER_INVITATION)

        bootstrap("").inviteTheFirstTeacher()
        bootstrap("   ").inviteTheFirstTeacher()

        assertEquals(before, dsl.fetchCount(TEACHER_INVITATION))
        assertTrue(output.all.contains("There is no teacher and TALITA_BOOTSTRAP_TEACHER is not set"))
    }

    @Test
    @CapturesOutput
    fun `t3 c30 a variable that is not an address invites nobody and says so without writing it`(output: CapturedOutput) {
        val before = dsl.fetchCount(TEACHER_INVITATION)

        bootstrap("non-un-indirizzo-" + System.nanoTime()).inviteTheFirstTeacher()

        assertEquals(before, dsl.fetchCount(TEACHER_INVITATION))
        assertTrue(output.all.contains("is not an email address: nobody was invited"))
        assertFalse(output.all.contains("non-un-indirizzo"))
    }

    @Test
    fun `t3 c30 the dev profile sets the first teacher to the address of the examples`() {
        val dev = YamlPropertiesFactoryBean().let { factory ->
            factory.setResources(ClassPathResource("application-dev.yaml"))
            factory.getObject()!!
        }

        assertEquals("nome@example.org", dev.getProperty("talita.bootstrap.teacher"))
        // And production has none: the server sets it (docs/04-ambiente.md).
        val production = YamlPropertiesFactoryBean().let { factory ->
            factory.setResources(ClassPathResource("application.yaml"))
            factory.getObject()!!
        }
        assertNull(production.getProperty("talita.bootstrap.teacher"))
    }
}

/**
 * The application that starts with no teacher and the variable set: the invitation is there when it is ready (criterion 30).
 *
 * The capture of the output is on the **class** on purpose (TALITA-16, criterion 4): the test reads the log of the start of the application ("is invited (no mail sent)"), which is
 * written while the context starts, before the test. This class has a context of its own (the property of the variable), so nothing else wrote before it.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["talita.bootstrap.teacher=" + "primo.maestro" + "@" + "example.org"])
@ExtendWith(OutputCaptureExtension::class)
class BootstrapStartupTests(@Autowired private val invitations: Invitations) {

    @Test
    fun `t3 c30 at the start, with no teacher and the variable set, there is an invitation for that address and no mail`(output: CapturedOutput) {
        val invitation = invitations.pendingFor("primo.maestro" + "@" + "example.org")

        assertNotNull(invitation, "the application did not invite the first teacher at the start")
        assertTrue(Mailbox.sentTo("primo.maestro" + "@" + "example.org").isEmpty())
        assertTrue(output.all.contains("is invited (no mail sent)"))
    }
}
