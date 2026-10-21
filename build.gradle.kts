import gg.jte.ContentType
import org.flywaydb.core.Flyway
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.Configuration
import org.jooq.meta.jaxb.Database
import org.jooq.meta.jaxb.ForcedType
import org.jooq.meta.jaxb.Generator
import org.jooq.meta.jaxb.Jdbc
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.jooq.meta.jaxb.Target as JooqTarget

buildscript {
    dependencies {
        // Code generation uses the same jOOQ, Flyway, driver and Testcontainers versions as the app.
        // Keep this version equal to the Spring Boot plugin below.
        classpath(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
        classpath("org.jooq:jooq-codegen")
        classpath("org.flywaydb:flyway-database-postgresql")
        classpath("org.postgresql:postgresql")
        classpath("org.testcontainers:testcontainers-postgresql")
    }
}

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    // Same version as the jte-spring-boot-starter-4 dependency below.
    id("gg.jte.gradle") version "3.2.4"
}

group = "koine"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

extra["springModulithVersion"] = "2.1.1"
// The JTE plugin and the starter must have the same version. The plugin version is repeated
// in the plugins block, which cannot read variables.
val jteVersion = "3.2.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jooq:jooq-postgres-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("gg.jte:jte-spring-boot-starter-4:$jteVersion")
    implementation("tools.jackson.module:jackson-module-kotlin")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

// Same major version as compose.yaml and TestcontainersConfiguration.
val postgresImage = "postgres:18"
val migrationsDir = layout.projectDirectory.dir("src/main/resources/db/migration")
val jooqOutputDir = layout.buildDirectory.dir("generated-sources/jooq")

// Applies the migrations to a throwaway Postgres and generates the jOOQ classes from the
// resulting schema, so every query is checked against the real schema at compile time.
// Needs Docker, like the tests.
val generateJooq = tasks.register("generateJooq") {
    group = "build"
    description = "Generates the jOOQ classes from the Flyway migrations."
    inputs.dir(migrationsDir)
    inputs.property("postgresImage", postgresImage)
    outputs.dir(jooqOutputDir)

    doLast {
        val outputDir = jooqOutputDir.get().asFile
        outputDir.deleteRecursively()

        // Flyway and jOOQ look up drivers and plugins through the context class loader.
        val thread = Thread.currentThread()
        val previousClassLoader = thread.contextClassLoader
        thread.contextClassLoader = GenerationTool::class.java.classLoader
        try {
            PostgreSQLContainer(postgresImage).use { postgres ->
                postgres.start()

                Flyway.configure(GenerationTool::class.java.classLoader)
                    .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                    .locations("filesystem:${migrationsDir.asFile.path}")
                    .load()
                    .migrate()

                GenerationTool.generate(
                    Configuration()
                        .withJdbc(
                            Jdbc()
                                .withDriver("org.postgresql.Driver")
                                .withUrl(postgres.jdbcUrl)
                                .withUser(postgres.username)
                                .withPassword(postgres.password),
                        )
                        .withGenerator(
                            Generator()
                                .withName("org.jooq.codegen.KotlinGenerator")
                                .withDatabase(
                                    Database()
                                        .withName("org.jooq.meta.postgres.PostgresDatabase")
                                        .withInputSchema("public")
                                        .withExcludes("flyway_schema_history")
                                        // Skips the hundreds of functions added by the extensions.
                                        .withIncludeRoutines(false)
                                        // Membership periods: daterange as a range of LocalDate.
                                        .withForcedTypes(
                                            ForcedType()
                                                .withIncludeTypes("daterange")
                                                .withUserType("org.jooq.postgres.extensions.types.LocalDateRange")
                                                .withBinding("org.jooq.postgres.extensions.bindings.LocalDateRangeBinding"),
                                        ),
                                )
                                .withTarget(
                                    JooqTarget()
                                        .withPackageName("koine.jooq")
                                        .withDirectory(outputDir.path),
                                ),
                        ),
                )
            }
        } finally {
            thread.contextClassLoader = previousClassLoader
        }
    }
}

// JTE templates in src/main/jte become Java classes at build time (generate, not precompile:
// the classes then go through the normal compilation and end up in the bootJar, see
// https://github.com/casid/jte/issues/438). A template that reads a missing property of its
// Kotlin model fails the build.
jte {
    generate()
    contentType = ContentType.Html
}

// Tailwind CSS standalone binary: no Node, no npm. The version and the SHA-256 of each supported
// platform are written here and nowhere else. The checksum never comes from the download itself
// and is verified before every run of the binary. The values are those of the release assets
// (https://github.com/tailwindlabs/tailwindcss/releases, they match its sha256sums.txt).
// Another platform (for example ARM, if the deploy builds there): add its name and checksum below.
val tailwindVersion = "4.3.3"
val tailwindChecksums = mapOf(
    "linux-x64" to "dc61b3ac6b8c9ca874c0cc4c57b2409791a64c5540404ca5f5367360babc313a",
)
// Outside build/, so it survives `clean`. Under the Gradle caches, which CI caches.
val tailwindCacheDir = gradle.gradleUserHomeDir.resolve("caches/koine-tools/tailwindcss/v$tailwindVersion")

fun tailwindPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val platform = if (os.contains("linux") && (arch == "amd64" || arch == "x86_64")) "linux-x64" else null
    return platform ?: throw GradleException(
        "Tailwind CSS: unsupported platform '$os/$arch'. " +
            "Add its name and SHA-256 to tailwindChecksums in build.gradle.kts.",
    )
}

fun tailwindExecutable(): File = tailwindCacheDir.resolve("tailwindcss-${tailwindPlatform()}")

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// Checks the cached binary against the checksum in this file, downloading it first when missing.
// Declares no outputs on purpose: it runs every time, so a binary altered in the cache is caught
// before anything executes it. With the binary in the cache it does not use the network.
val downloadTailwind = tasks.register("downloadTailwind") {
    group = "build setup"
    description = "Downloads the Tailwind CSS standalone binary into the cache and verifies its SHA-256."

    doLast {
        val platform = tailwindPlatform()
        val expected = tailwindChecksums.getValue(platform)
        val binary = tailwindExecutable()

        if (!binary.exists()) {
            val url = "https://github.com/tailwindlabs/tailwindcss/releases/download/v$tailwindVersion/tailwindcss-$platform"
            logger.lifecycle("Downloading $url")
            val partial = File(binary.path + ".part")
            val failure = try {
                tailwindCacheDir.mkdirs()
                val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                val request = HttpRequest.newBuilder(URI.create(url)).build()
                val status = client.send(request, HttpResponse.BodyHandlers.ofFile(partial.toPath())).statusCode()
                if (status == 200) null else "HTTP $status"
            } catch (e: java.io.IOException) {
                e.message ?: e.javaClass.simpleName
            }
            if (failure != null) {
                partial.delete()
                throw GradleException(
                    "Tailwind CSS: cannot download $url ($failure). " +
                        "The first build needs the network; with the binary in $tailwindCacheDir it does not.",
                )
            }
            val downloaded = sha256(partial)
            if (downloaded != expected) {
                partial.delete()
                throw GradleException(
                    "Tailwind CSS: the file downloaded from $url has SHA-256 $downloaded, expected $expected. " +
                        "It was not run.",
                )
            }
            Files.move(partial.toPath(), binary.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }

        val actual = sha256(binary)
        if (actual != expected) {
            throw GradleException(
                "Tailwind CSS: ${binary.path} has SHA-256 $actual, expected $expected. " +
                    "The file was not run. Delete it to download it again.",
            )
        }
        binary.setExecutable(true)
    }
}

val tailwindInput = layout.projectDirectory.file("src/main/css/app.css")
val tailwindOutput = layout.buildDirectory.file("generated-resources/tailwind/static/css/app.css")

// Builds the CSS that the app serves. The inputs (CSS source, templates, version) and the output
// are declared, so with nothing changed the task is UP-TO-DATE. Classes must appear whole in the
// templates: Tailwind scans the text of src/main/jte (see @source in app.css).
val tailwindCss = tasks.register<Exec>("tailwindCss") {
    group = "build"
    description = "Builds the minified CSS with Tailwind from src/main/css/app.css and the JTE templates."
    dependsOn(downloadTailwind)
    inputs.file(tailwindInput)
    inputs.dir(layout.projectDirectory.dir("src/main/jte"))
    inputs.property("tailwindVersion", tailwindVersion)
    outputs.file(tailwindOutput)

    doFirst {
        val output = tailwindOutput.get().asFile
        output.parentFile.mkdirs()
        commandLine(tailwindExecutable().path, "--input", tailwindInput.asFile.path, "--output", output.path, "--minify")
    }
}

// Development: rebuilds the CSS while it runs, next to `bootRun` (the dev profile serves this file
// straight from build/). `--watch=always` because a plain `--watch` stops when stdin closes.
tasks.register<Exec>("tailwindWatch") {
    group = "application"
    description = "Rebuilds the CSS whenever a template or the CSS source changes. Run it next to bootRun."
    dependsOn(downloadTailwind)

    doFirst {
        val output = tailwindOutput.get().asFile
        output.parentFile.mkdirs()
        commandLine(tailwindExecutable().path, "--input", tailwindInput.asFile.path, "--output", output.path, "--watch=always")
    }
}

tasks.processResources {
    from(tailwindCss) { into("static/css") }
}

kotlin {
    sourceSets.main {
        kotlin.srcDir(generateJooq)
    }
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// End-to-end tests: Playwright drives a real Chromium against the app started on a random port,
// with Postgres from Testcontainers. A separate suite (`./gradlew e2eTest`, sources in
// src/e2eTest), and `check` depends on it, so `./gradlew build` and CI run it too.
// The axe-core integration must have the same version as Playwright (it depends on it).
val playwrightVersion = "1.63.0"
val axeVersion = "4.14.0"

testing {
    suites {
        register<JvmTestSuite>("e2eTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(sourceSets.test.get().output)
                implementation("org.springframework.boot:spring-boot-starter-webmvc-test")
                implementation("org.springframework.boot:spring-boot-testcontainers")
                implementation("org.testcontainers:testcontainers-postgresql")
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
                implementation("com.microsoft.playwright:playwright:$playwrightVersion")
                implementation("com.deque.html.axe-core:playwright:$axeVersion")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(tasks.test)
                    dependsOn("playwrightInstall")
                    // Playwright for Java would download Chromium, Firefox and WebKit on its own;
                    // playwrightInstall has already installed the only one we use.
                    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
                    // Trace and screenshot of every failed test end up here (see E2eExtension).
                    val artifacts = layout.buildDirectory.dir("e2e-artifacts")
                    systemProperty("e2e.artifacts", artifacts.get().asFile.path)
                    doFirst { delete(artifacts) }
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("e2eTest"))
}

// Installs the Chromium that the e2e tests use (into ~/.cache/ms-playwright, nothing to do when
// it is already there). CI adds -PplaywrightWithDeps to install the system libraries too: that
// needs apt, which on the development PC does not work today (docs/04), so there it is left out.
val playwrightCli = configurations.create("playwrightCli")

dependencies {
    playwrightCli("com.microsoft.playwright:playwright:$playwrightVersion")
}

tasks.register<JavaExec>("playwrightInstall") {
    group = "verification"
    description = "Installs the Chromium used by the e2e tests (-PplaywrightWithDeps also installs system libraries)."
    classpath = playwrightCli
    mainClass = "com.microsoft.playwright.CLI"
    args = buildList {
        add("install")
        if (project.hasProperty("playwrightWithDeps")) add("--with-deps")
        add("chromium")
    }
}

// Opens the Playwright trace viewer on the trace of a failed e2e test:
// ./gradlew playwrightShowTrace -Ptrace=build/e2e-artifacts/<class>/<test>-<profile>/trace.zip
tasks.register<JavaExec>("playwrightShowTrace") {
    group = "verification"
    description = "Opens the trace viewer on the file given with -Ptrace=<path to trace.zip>."
    classpath = playwrightCli
    mainClass = "com.microsoft.playwright.CLI"
    args = listOf("show-trace", project.findProperty("trace")?.toString() ?: "")
}

// Development mode (dev profile): JTE reloads the templates from src/main/jte on every request.
tasks.bootRun {
    systemProperty("spring.profiles.active", "dev")
}
