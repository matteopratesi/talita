package talita.venue

import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * A database of its own for the tests of a migration with data (TALITA-20, criteria 11 and 19): the migrations are applied up to a version, the test writes the rows that a database of before
 * would hold (synthetic, with plain SQL: the generated classes of jOOQ are those of the last schema), and then the migration that is tried runs on them. Nothing is shared with the database of the
 * other tests, so the schema is never half done under their feet. The container is the same major version as the real one (`postgres:18`).
 */
internal class MigrationDatabase(private val postgres: PostgreSQLContainer) {

    /** The migrations of the application up to [version] (a number, `"12"`), the ones that are there already are not applied again. */
    fun migrateTo(version: String) {
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).locations("classpath:db/migration").target(version).load().migrate()
    }

    /** Runs [block] with a connection to the database, and closes it. */
    fun <T> dsl(block: (DSLContext) -> T): T = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { block(DSL.using(it)) }

    companion object {
        /** Starts a database, gives it to [test] and stops it, whatever the test does. */
        fun <T> use(test: (MigrationDatabase) -> T): T = PostgreSQLContainer(DockerImageName.parse("postgres:18")).use { postgres ->
            postgres.start()
            test(MigrationDatabase(postgres))
        }
    }
}
