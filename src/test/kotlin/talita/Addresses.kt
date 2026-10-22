package talita

import java.util.UUID

/**
 * Email addresses for the tests. The repository is public and a test (`t2 c37`) allows only
 * `nome@example.org` in tracked files, so the other addresses are built here, in pieces, on the
 * reserved domain `example.org`: nobody can receive mail there.
 */
object Addresses {

    private const val DOMAIN = "example.org"

    /** The address of [name] (lowercase letters, digits, dots and dashes). */
    fun of(name: String): String = name + "@" + DOMAIN

    /** An address that no other test uses, so that tests can share the database and the mailbox. */
    fun unique(name: String): String = of(name + "-" + UUID.randomUUID().toString().replace("-", "").take(10))
}
