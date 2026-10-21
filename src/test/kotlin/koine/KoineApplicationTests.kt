package koine

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class KoineApplicationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val dsl: DSLContext,
) {

    @Test
    fun `health endpoint reports UP`() {
        mockMvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }

    @Test
    fun `migrations install the required extensions`() {
        val installed = dsl.resultQuery("select extname from pg_extension").fetch(0, String::class.java)

        assertTrue(installed.containsAll(listOf("btree_gist", "pg_trgm", "unaccent")), "installed: $installed")
    }
}
