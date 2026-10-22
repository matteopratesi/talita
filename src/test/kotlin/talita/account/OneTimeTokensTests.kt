package talita.account

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest
import org.springframework.security.authentication.ott.JdbcOneTimeTokenService
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken
import org.springframework.security.authentication.ott.OneTimeTokenService
import talita.Addresses
import talita.MutableClock
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.ONE_TIME_TOKENS
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The links of access in the table `one_time_tokens` of V2 (criterion 14): made, used once, expired,
 * cleaned up. The service is that of Spring Security (JDBC), on Postgres: the table is ours because the
 * script of the library uses a type that Postgres does not have.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class OneTimeTokensTests(
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: MutableClock,
) {

    private fun rows(value: String) = dsl.fetchCount(ONE_TIME_TOKENS, ONE_TIME_TOKENS.TOKEN_VALUE.eq(value))

    private fun consume(value: String) = tokens.consume(OneTimeTokenAuthenticationToken(value))

    @Test
    fun `t3 c14 the service is the JDBC one, and a link is a row with the address and the expiry`() {
        assertTrue(tokens is JdbcOneTimeTokenService, "the links are not in the database")
        val address = Addresses.unique("riga")

        val token = tokens.generate(GenerateOneTimeTokenRequest(address, Duration.ofMinutes(15)))

        assertEquals(36, token.tokenValue.length)
        val row = dsl.selectFrom(ONE_TIME_TOKENS).where(ONE_TIME_TOKENS.TOKEN_VALUE.eq(token.tokenValue)).fetchSingle()
        assertEquals(address, row.username)
        assertEquals(token.expiresAt.epochSecond, row.expiresAt!!.toInstant().epochSecond)
        assertEquals(clock.instant().plus(Duration.ofMinutes(15)).epochSecond / 10, token.expiresAt.epochSecond / 10)
    }

    @Test
    fun `t3 c14 a link is used once and leaves the table at once`() {
        val token = tokens.generate(GenerateOneTimeTokenRequest(Addresses.unique("una-volta"), Duration.ofMinutes(15)))

        val first = consume(token.tokenValue)

        assertNotNull(first)
        assertEquals(token.username, first.username)
        assertEquals(0, rows(token.tokenValue), "a used link is still in the table")
        assertNull(consume(token.tokenValue))
    }

    @Test
    fun `t3 c14 a link that has expired is not worth anything and goes out when somebody tries it`() {
        val token = tokens.generate(GenerateOneTimeTokenRequest(Addresses.unique("scaduto"), Duration.ofMinutes(15)))

        clock.advance(Duration.ofMinutes(16))

        assertNull(consume(token.tokenValue))
        assertEquals(0, rows(token.tokenValue))
    }

    @Test
    fun `t3 c14 the cleanup removes the links that expired and leaves the others`() {
        val live = tokens.generate(GenerateOneTimeTokenRequest(Addresses.unique("vivo"), Duration.ofHours(1)))
        // Born already expired: the cleanup of the library counts from the real time.
        val dead = tokens.generate(GenerateOneTimeTokenRequest(Addresses.unique("morto"), Duration.ofMinutes(-5)))
        assertEquals(1, rows(dead.tokenValue))

        (tokens as JdbcOneTimeTokenService).cleanupExpiredTokens()

        assertEquals(0, rows(dead.tokenValue), "an expired link stayed in the table")
        assertEquals(1, rows(live.tokenValue))
    }

    @Test
    fun `t3 c14 an address of 254 characters fits in the table of the links`() {
        val long = "a".repeat(64) + "." + "b".repeat(64) + "." + "c".repeat(64) + "@" + "d".repeat(43) + ".example.org"
        assertTrue(long.length in 200..254, "${long.length}")

        val token = tokens.generate(GenerateOneTimeTokenRequest(long, Duration.ofMinutes(15)))

        assertEquals(long, consume(token.tokenValue)?.username)
    }
}
