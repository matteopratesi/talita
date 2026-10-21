package koine.e2e

import com.microsoft.playwright.Browser

/** The two ways every e2e test looks at the app: a phone and a desktop. */
enum class Profile(val label: String) {
    /** 360x800, touch, mobile: the way most people use Koinè. */
    PHONE("phone"),

    /** 1280x800 with mouse and keyboard. */
    DESKTOP("desktop"),
    ;

    val isPhone: Boolean get() = this == PHONE

    fun contextOptions(): Browser.NewContextOptions = when (this) {
        PHONE -> Browser.NewContextOptions()
            .setViewportSize(360, 800)
            .setIsMobile(true)
            .setHasTouch(true)
            .setDeviceScaleFactor(2.0)

        DESKTOP -> Browser.NewContextOptions()
            .setViewportSize(1280, 800)
    }.setLocale("it-IT").setTimezoneId("Europe/Rome")
}
