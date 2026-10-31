package talita

import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.OutputCaptureExtension

/**
 * Captures the output of **this test only** and gives it to the parameter `output: CapturedOutput` (TALITA-16, criterion 4).
 *
 * `@ExtendWith(OutputCaptureExtension::class)` on a class opens two captures, one for the class and one for each test, and `output.all` (and `out`, `err`) joins them: the lines of
 * the start of the context, of the tests before and of a thread that a failed test has left running would be in the log of a test that did not write them. A test that counts the
 * lines (t7 c15, t11 c20, t7 c28) would fail for lines that are not its own, and a positive check ("the log says X") could pass on a line written before. On a method there is
 * only the capture of the test: it begins before the `@BeforeEach` methods and ends after the test.
 *
 * The tests that read the log of the **start** of the application (`NoMailTests`, `BootstrapStartupTests`) put the extension on the class, on purpose, and say why.
 * `RepositoryRulesTests` allows no other class.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(OutputCaptureExtension::class)
annotation class CapturesOutput
