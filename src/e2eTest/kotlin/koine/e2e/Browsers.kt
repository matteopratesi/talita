package koine.e2e

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright

/** One Playwright and one headless Chromium for the whole run. */
internal object Browsers {
    private val playwright: Playwright = Playwright.create()

    val chromium: Browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching { chromium.close() }
                runCatching { playwright.close() }
            },
        )
    }
}
