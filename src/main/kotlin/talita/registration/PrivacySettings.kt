package talita.registration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * Who is the controller of the data and how to write to them (`talita.privacy.*`, in production the environment variables
 * `TALITA_PRIVACY_CONTROLLER_NAME` and `TALITA_PRIVACY_CONTACT`): the short notice at step 2 of the registration and the complete one at
 * `/informativa` say it. The repository holds only the placeholders below; the address that is real comes with TALITA-9 (docs/03).
 *
 * @property controllerName the name that the notices give for the controller
 * @property contact the address to write to, for questions and for the rights of the parents
 */
@ConfigurationProperties("talita.privacy")
data class PrivacySettings(
    @DefaultValue("Titolare di esempio") val controllerName: String,
    @DefaultValue("nome@example.org") val contact: String,
)
