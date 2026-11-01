package talita

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.system.CapturedOutput
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The capture of the output of one test (TALITA-16, criterion 4): the log of a test has only what was written while the test ran. The lines are synthetic markers.
 * A line written outside any test (in `@BeforeAll`, as the start of a context does) is not there, and neither is the line of the other test of this class, whichever runs first.
 */
class CapturesOutputTests {

    companion object {
        private const val BEFORE_ALL = "t16-marker-written-before-all"

        @BeforeAll
        @JvmStatic
        fun writeOutsideTheTests() {
            println(BEFORE_ALL)
            System.err.println("$BEFORE_ALL-err")
        }
    }

    @Test
    @CapturesOutput
    fun `t16 c04 the log of a test has the line of the test and not the one written before all the tests, nor the one of another test`(output: CapturedOutput) {
        println("t16-marker-of-the-first-test")
        System.err.println("t16-marker-of-the-first-test-err")

        assertTrue(output.all.contains("t16-marker-of-the-first-test\n"), output.all)
        assertTrue(output.out.contains("t16-marker-of-the-first-test"), output.out)
        assertTrue(output.err.contains("t16-marker-of-the-first-test-err"), output.err)
        assertFalse(output.all.contains(BEFORE_ALL), "the line of @BeforeAll is in the log of the test: ${output.all}")
        assertFalse(output.all.contains("t16-marker-of-the-second-test"), "the line of the other test is in the log: ${output.all}")
    }

    @Test
    @CapturesOutput
    fun `t16 c04 the same for the second test, and the line is there only once`(output: CapturedOutput) {
        println("t16-marker-of-the-second-test")

        assertTrue(output.all.contains("t16-marker-of-the-second-test"), output.all)
        assertTrue(Regex("t16-marker-of-the-second-test").findAll(output.all).count() == 1, "the line is joined with a copy of itself: ${output.all}")
        assertFalse(output.all.contains(BEFORE_ALL), "the line of @BeforeAll is in the log of the test: ${output.all}")
        assertFalse(output.all.contains("t16-marker-of-the-first-test"), "the line of the other test is in the log: ${output.all}")
    }
}
