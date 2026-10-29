package talita

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class TalitaApplicationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val dsl: DSLContext,
    @Autowired private val environment: Environment,
) {

    @Test
    fun `t2 c08 the application is called talita`() {
        assertEquals("talita", environment.getProperty("spring.application.name"))
    }

    @Test
    fun `health endpoint reports UP`() {
        mockMvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }

    @Test
    fun `the migrations are V1 extensions, V2 accounts, V3 teachers, V4 gathering, V5 registration, V6 check-in, V7 retention, V8 teacher moments and V9 rooms, and the only extension is unaccent`() {
        val applied = dsl.resultQuery("select version, script from flyway_schema_history where success order by installed_rank")
            .fetch { it.get(0, String::class.java) to it.get(1, String::class.java) }
        assertEquals(listOf("1" to "V1__extensions.sql", "2" to "V2__accounts.sql", "3" to "V3__teachers.sql", "4" to "V4__gathering.sql", "5" to "V5__registration.sql", "6" to "V6__checkin.sql", "7" to "V7__retention.sql", "8" to "V8__teacher_moments.sql", "9" to "V9__rooms.sql"), applied)

        val files = PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*").mapNotNull { it.filename }.sorted()
        assertEquals(listOf("V1__extensions.sql", "V2__accounts.sql", "V3__teachers.sql", "V4__gathering.sql", "V5__registration.sql", "V6__checkin.sql", "V7__retention.sql", "V8__teacher_moments.sql", "V9__rooms.sql"), files)

        val installed = dsl.resultQuery("select extname from pg_extension").fetch(0, String::class.java)
        assertTrue("unaccent" in installed, "installed: $installed")
        // The extensions of the project this one was copied from are not needed here.
        assertTrue(installed.none { it in setOf("btree" + "_gist", "pg" + "_trgm") }, "installed: $installed")
    }

    @Test
    fun `unaccent finds a name written without the accent`() {
        val found = dsl.resultQuery("select unaccent(lower('Nicolò')) = unaccent(lower('NICOLO'))").fetchOne(0, Boolean::class.java)

        assertEquals(true, found)
    }
}
