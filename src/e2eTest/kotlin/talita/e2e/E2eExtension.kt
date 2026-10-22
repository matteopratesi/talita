package talita.e2e

import com.microsoft.playwright.Page
import com.microsoft.playwright.Tracing
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.junit.jupiter.api.extension.TestTemplateInvocationContext
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider
import org.junit.jupiter.api.TestTemplate
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream

/**
 * An e2e test: it runs once for each profile (phone and desktop by default) and receives an [E2e]
 * with a fresh page. The Playwright life cycle is managed here, by hand: `@UsePlaywright` is
 * experimental and tied to JUnit 5.
 *
 * The page is already inside the app (the real flow of the link, see [E2e.signIn]) unless the test is
 * about what someone without access sees: then `signedIn = false`. A teacher, unless `teacher = false`: a
 * parent has two entries in the navigation instead of four. Whatever the test does, it fails if
 * the console of a page complains of the Content-Security-Policy.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@TestTemplate
@ExtendWith(E2eExtension::class)
annotation class E2eTest(val profiles: Array<Profile> = [Profile.PHONE, Profile.DESKTOP], val signedIn: Boolean = true, val teacher: Boolean = true)

class E2eExtension : TestTemplateInvocationContextProvider {

    override fun supportsTestTemplate(context: ExtensionContext): Boolean =
        context.testMethod.map { it.isAnnotationPresent(E2eTest::class.java) }.orElse(false)

    override fun provideTestTemplateInvocationContexts(context: ExtensionContext): Stream<TestTemplateInvocationContext> =
        Stream.of(*context.requiredTestMethod.getAnnotation(E2eTest::class.java).profiles)
            .map<TestTemplateInvocationContext> {
                context.requiredTestMethod.getAnnotation(E2eTest::class.java).let { test -> ProfileInvocation(context.requiredTestMethod.name, it, test.signedIn, test.teacher) }
            }
}

private class ProfileInvocation(private val test: String, private val profile: Profile, private val signedIn: Boolean, private val teacher: Boolean) : TestTemplateInvocationContext {
    override fun getDisplayName(invocationIndex: Int): String = "$test [${profile.label}]"

    override fun getAdditionalExtensions() = listOf(PageLifecycle(profile, signedIn, teacher))
}

/** Opens the page before the test; on failure it saves the trace and a screenshot of the page. */
private class PageLifecycle(private val profile: Profile, private val signedIn: Boolean, private val teacher: Boolean) : BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private val namespace = ExtensionContext.Namespace.create(PageLifecycle::class.java)

    override fun beforeEach(context: ExtensionContext) {
        val application = SpringExtension.getApplicationContext(context)
        val port = application.environment.getRequiredProperty("local.server.port")
        val browserContext = Browsers.chromium.newContext(profile.contextOptions())
        browserContext.tracing().start(Tracing.StartOptions().setScreenshots(true).setSnapshots(true))
        val page = browserContext.newPage()
        page.setDefaultTimeout(10_000.0)
        val e2e = E2e(page, browserContext, profile, "http://localhost:$port", application)
        e2e.watchCsp(page)
        e2e.teacher = teacher
        context.getStore(namespace).put("e2e", e2e)
        if (signedIn) e2e.signIn(teacher = teacher)
    }

    override fun afterEach(context: ExtensionContext) {
        val e2e = context.getStore(namespace).remove("e2e", E2e::class.java) ?: return
        // A violation of the Content-Security-Policy is a failure of the test, whatever the test was about.
        val violations = e2e.cspViolations.toList()
        val failed = context.executionException.isPresent || violations.isNotEmpty()
        try {
            if (failed) {
                val dir = artifactsDir(context)
                Files.createDirectories(dir)
                runCatching { e2e.page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("screenshot.png")).setFullPage(true)) }
                e2e.browserContext.tracing().stop(Tracing.StopOptions().setPath(dir.resolve("trace.zip")))
            } else {
                e2e.browserContext.tracing().stop()
            }
        } finally {
            e2e.close()
        }
        check(violations.isEmpty()) { "The browser reports violations of the Content-Security-Policy: $violations" }
    }

    override fun supportsParameter(parameter: ParameterContext, context: ExtensionContext): Boolean =
        parameter.parameter.type == E2e::class.java

    override fun resolveParameter(parameter: ParameterContext, context: ExtensionContext): Any =
        checkNotNull(context.getStore(namespace).get("e2e", E2e::class.java))

    private fun artifactsDir(context: ExtensionContext): Path {
        val root = Path.of(System.getProperty("e2e.artifacts", "build/e2e-artifacts"))
        val test = (context.requiredTestClass.simpleName + "/" + context.requiredTestMethod.name + "-" + profile.label)
            .replace(Regex("[^A-Za-z0-9._/-]"), "_")
        return root.resolve(test)
    }
}
