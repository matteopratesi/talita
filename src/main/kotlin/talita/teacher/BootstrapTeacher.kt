package talita.teacher

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import talita.account.EmailAddress

/**
 * The first teacher. When the application starts and there is no teacher, it makes an invitation for the
 * address of `TALITA_BOOTSTRAP_TEACHER` (`talita.bootstrap.teacher`): no mail goes out, the person
 * enters from `/accedi` with that address and accepts the invitation by writing their name, like any teacher.
 *
 * With at least one teacher the variable does nothing: it is not a way to be a teacher, only a way to start.
 * Without the variable and without teachers the application starts anyway and the log says that nobody
 * can be invited. The invitation lasts as every other (7 days); at the next start, while there is no
 * teacher, it is renewed. The log never has the address.
 */
@Component
class BootstrapTeacher(
    private val settings: BootstrapSettings,
    private val teachers: Teachers,
    private val invitations: Invitations,
) {

    private val log = LoggerFactory.getLogger(BootstrapTeacher::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun inviteTheFirstTeacher() {
        if (teachers.count() > 0) return

        val configured = settings.teacher.trim()
        if (configured.isEmpty()) {
            log.warn("There is no teacher and TALITA_BOOTSTRAP_TEACHER is not set: nobody can be invited until it is.")
            return
        }
        val address = EmailAddress.parse(configured)
        if (address == null) {
            log.warn("There is no teacher and TALITA_BOOTSTRAP_TEACHER (talita.bootstrap.teacher) is not an email address: nobody was invited.")
            return
        }
        invitations.invite(address)
        log.info("There is no teacher: the address of TALITA_BOOTSTRAP_TEACHER is invited (no mail sent). It enters from /accedi and accepts.")
    }
}
