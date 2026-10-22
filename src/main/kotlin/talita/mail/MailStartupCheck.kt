package talita.mail

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component

/**
 * The application starts without mail (a jar with no profile and no `spring.mail.host`, like the
 * one of the smoke test), but says so once at the start: otherwise the first person who asks for a
 * link would be the one to find out.
 */
@Component
class MailStartupCheck(private val sender: JavaMailSender?, private val settings: MailSettings) {

    private val log = LoggerFactory.getLogger(MailStartupCheck::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun warnIfMailIsNotConfigured() {
        if (sender == null) {
            log.warn("Mail is not configured (spring.mail.host is not set): login links and invitations cannot be sent.")
        } else if (settings.from.isBlank()) {
            log.warn("Mail has no sender address (talita.mail.from is not set): login links and invitations cannot be sent.")
        }
    }
}
