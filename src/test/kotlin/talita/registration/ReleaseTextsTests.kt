package talita.registration

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import talita.TestcontainersConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The texts that a parent reads (the custody, the notices) are written for the development and must be read by a competent person before they go out
 * (`docs/03-privacy.md`, «Prima del rilascio»). Every placeholder that nobody filled is a marker (`Informativa.markers`), and this test, which runs only
 * with `-Prelease` (`./gradlew build -Prelease`), fails while one is still in a text: so a release with texts that are not true does not pass the build.
 * The rest of the time it says that it is waiting, and a second test keeps the list of the markers honest.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ReleaseTextsTests(@Autowired private val mockMvc: MockMvc) {

    @Test
    fun `t5 release the texts of the custody and of the notices have no placeholder that nobody filled`() {
        assumeTrue(System.getProperty("talita.release") == "true", "only with -Prelease: the texts are still waiting for the person who reads them")

        val pages = listOf("/informativa").associateWith { mockMvc.get(it).andReturn().response.contentAsString }
        val found = Informativa.markers.flatMap { marker ->
            buildList {
                if (CustodyText.model.contains(marker)) add("the model of the custody has $marker")
                pages.forEach { (path, html) -> if (html.contains(marker)) add("$path has $marker") }
            }
        }

        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun `t5 release the markers are the ones that the texts of the development carry, and the model of the custody has none`() {
        assertTrue(Informativa.markers.contains(Informativa.PLACEHOLDER_NOTICE))
        assertTrue(Informativa.markers.contains(Informativa.OPEN_ITEM))
        // The notice of the placeholder is in the page today: the release test above would fail on it, and that is what it is for.
        val page = mockMvc.get("/informativa").andReturn().response.contentAsString
        assertTrue(Informativa.markers.any { page.contains(it) })
        assertFalse(Informativa.markers.any { CustodyText.model.contains(it) })
        assertTrue(CustodyText.model.lines().none { it.trimStart().startsWith("[") }, "no line of the model is a bracketed note")
    }
}
