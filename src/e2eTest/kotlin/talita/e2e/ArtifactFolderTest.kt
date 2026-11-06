package talita.e2e

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The folder where a failed e2e test leaves its trace and its screenshot: the name of a test is a sentence of 250 characters and more, and a folder name of more than 255 bytes is refused by the file system. When that
 * happened (the CI of TALITA-20, `ModuleQaE2eTest`) the test failed with `FileSystemException` and the reason of its failure was lost. No browser here: it is the name and nothing else.
 */
class ArtifactFolderTest {

    private val longName = "t20 c25 the sheets that Chromium prints are as many as the caption says, with all the children once, the header on every sheet and the teachers under the last, " +
        "at the sizes where the table of the teachers moves to a sheet of its own and where it does not"

    @Test
    fun `the folder of a test with a very long name fits the limit of the file system, in every part`() {
        val folder = artifactFolder("ModuleQaE2eTest", longName, "desktop")
        assertTrue(longName.length > 200, "the name of the example is long")
        for (part in folder.split('/')) assertTrue(part.toByteArray().size <= 255, "«$part» has ${part.toByteArray().size} bytes")
        assertTrue(folder.startsWith("ModuleQaE2eTest/t20_c25_the_sheets_that_Chromium_prints"), folder)
        assertTrue(folder.endsWith("-desktop"), folder)
        assertTrue(Regex("[A-Za-z0-9._/-]+").matches(folder), "only characters that a path takes: $folder")
    }

    @Test
    fun `two long names that start in the same way, and the two profiles of a test, have each a folder of their own`() {
        val first = artifactFolder("ModuleQaE2eTest", "$longName, first", "desktop")
        val second = artifactFolder("ModuleQaE2eTest", "$longName, second", "desktop")
        assertNotEquals(first, second)
        assertNotEquals(artifactFolder("ModuleQaE2eTest", longName, "phone"), artifactFolder("ModuleQaE2eTest", longName, "desktop"))
        assertEquals(first, artifactFolder("ModuleQaE2eTest", "$longName, first", "desktop"), "the same test has always the same folder")
    }

    @Test
    fun `the folder of a short name is the name, a code and the profile`() {
        val folder = artifactFolder("PageBasicsE2eTest", "t2 c1 the page opens", "phone")
        assertTrue(Regex("PageBasicsE2eTest/t2_c1_the_page_opens-[0-9a-f]{8}-phone").matches(folder), folder)
    }
}
