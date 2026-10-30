package talita.mail

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * Settings of the mail that Talità sends (`talita.mail.*`, in production the environment variables
 * `TALITA_MAIL_FROM`...). The SMTP server itself is configured with the standard `spring.mail.*`.
 *
 * @property from address of the sender, in production on the domain of Talità. Empty means "not
 * configured": the application starts, but no mail leaves.
 * @property name name shown next to the address
 * @property dailyLimit most mails sent in any 24 hours, links and invitations together; with [reminderDailyLimit] under the
 * free quota of the mail provider (200 a day for the whole account)
 * @property reminderDailyLimit most reminders sent in any 24 hours (TALITA-13), apart from [dailyLimit]: a reminder never takes the place of a link
 */
@ConfigurationProperties("talita.mail")
data class MailSettings(
    @DefaultValue("") val from: String,
    @DefaultValue("Talità") val name: String,
    @DefaultValue("150") val dailyLimit: Int,
    @DefaultValue("40") val reminderDailyLimit: Int = 40,
)
