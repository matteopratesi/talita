package talita.account

import java.util.Locale

/**
 * An email address as Talità accepts it: written without spaces at the sides, in lowercase, at most
 * 254 characters, with letters, digits and the usual signs of ASCII (accented letters are not
 * accepted), a local part, an `@` and a domain with at least one dot.
 *
 * This is the only way to get an address from what a person typed ([parse]): the account, the link
 * and the invitations all use the normalized form, so an address written with capitals and with spaces at
 * the sides is the same person as the one written in lowercase.
 */
class EmailAddress private constructor(val value: String) {

    override fun equals(other: Any?): Boolean = other is EmailAddress && other.value == value

    override fun hashCode(): Int = value.hashCode()

    /** Not the address: it must not end up in a log by chance. */
    override fun toString(): String = "EmailAddress"

    companion object {
        /** The longest address that email allows, and the size of the columns that hold it. */
        const val MAX_LENGTH = 254

        private const val LOCAL_CHARACTERS = "a-z0-9!#$%&'*+/=?^_`{|}~-"

        private val SHAPE = Regex(
            "^[$LOCAL_CHARACTERS]+(?:\\.[$LOCAL_CHARACTERS]+)*" +
                "@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$",
        )

        private const val MAX_LOCAL_LENGTH = 64

        /** The address, or null when [raw] is empty, too long or does not look like an address. */
        fun parse(raw: String?): EmailAddress? {
            val normalized = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (normalized.isEmpty() || normalized.length > MAX_LENGTH) return null
            if (normalized.substringBefore('@').length > MAX_LOCAL_LENGTH) return null
            return if (SHAPE.matches(normalized)) EmailAddress(normalized) else null
        }
    }
}
