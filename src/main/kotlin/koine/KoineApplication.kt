package koine

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulithic

// `ui` (layout, navigation, error pages) is shared: every module may use it, it depends on none.
@Modulithic(sharedModules = ["ui"])
@SpringBootApplication
class KoineApplication

fun main(args: Array<String>) {
    runApplication<KoineApplication>(*args)
}
