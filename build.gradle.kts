import java.time.Instant
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    id("antlr")
    id("jacoco")
    alias(libs.plugins.jmh)
}

group = "xyz.surendrajat"
version = "1.5.0"

repositories {
    mavenCentral()
}

dependencies {
    // ANTLR for parsing
    antlr(libs.antlr.tool)
    implementation(libs.antlr.runtime)

    // LSP4J for language server
    implementation(libs.lsp4j)

    // Kotlin coroutines for concurrency
    implementation(libs.kotlinx.coroutines.core)

    // JSON for CLI mode
    implementation(libs.gson)

    // MCP (Model Context Protocol) server
    implementation(libs.mcp.kotlin.sdk.server)

    // Logging
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.junit.jupiter)
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    // Gradle 9 no longer injects the JUnit platform launcher — must be declared explicitly
    testRuntimeOnly(libs.junit.platform.launcher)

    // JMH Benchmarking
    jmh(libs.jmh.core)
    jmh(libs.jmh.generator.annprocess)
}

// Compile and test on a modern JDK (auto-provisioned via foojay resolver in settings)…
val toolchainJdkVersion = 24
// …while emitting Java 17 bytecode so the distributed jar keeps running on any Java 17+ runtime
val targetJavaVersion = 17

kotlin {
    jvmToolchain(toolchainJdkVersion)
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(targetJavaVersion)
}

// Configure ANTLR
tasks.generateGrammarSource {
    maxHeapSize = "128m"
    arguments = arguments + listOf("-visitor", "-long-messages", "-package", "xyz.surendrajat.smalilsp.parser.generated")
    outputDirectory = file("build/generated-src/antlr/main/xyz/surendrajat/smalilsp/parser/generated")
}

// Make Kotlin compilation depend on ANTLR generation
tasks.compileKotlin {
    dependsOn(tasks.generateGrammarSource)
}

tasks.compileTestKotlin {
    dependsOn(tasks.generateTestGrammarSource)
}

// Fix JMH task dependencies
tasks.named("compileJmhKotlin") {
    dependsOn(tasks.named("generateJmhGrammarSource"))
}

// me.champeau.jmh 0.7.3 predates shadow 9: it feeds the benchmark runtime
// classpath to ShadowJar via from(), which shadow 9 copies as nested .jar
// files instead of merging (shadow 8 unpacked them). The resulting jar had
// no org.openjdk.jmh.Main. Merge the configuration properly and drop the
// nested jar copies.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("jmhJar") {
    configurations.set(listOf(project.configurations.getByName("jmhRuntimeClasspath")))
    exclude("*.jar")
}

tasks.test {
    useJUnitPlatform()

    // Show test output
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }

    // Each test worker needs enough heap for large APK tests (indexing 4K+ files)
    maxHeapSize = "1g"

    // Limit parallelism: each worker holds a parsed APK in memory.
    // Too many forks OOM; too few wastes time. 4 is a safe default.
    maxParallelForks = minOf(4, Runtime.getRuntime().availableProcessors())

    // Fail on first test failure
    failFast = true
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Generate version metadata resource from project version + git commit
val versionPropertiesDir = layout.buildDirectory.dir("generated-resources/version")
val versionPropertiesFile = versionPropertiesDir.map { it.file("version.properties") }
val gitCommitProvider = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

tasks.register("generateVersionProperties") {
    inputs.property("projectVersion", project.version.toString())
    inputs.property("gitCommit", gitCommitProvider)
    outputs.file(versionPropertiesFile)
    doLast {
        val commitHash = try {
            gitCommitProvider.get()
        } catch (_: Exception) {
            "unknown"
        }

        val buildTime = Instant.now().toString()
        val file = versionPropertiesFile.get().asFile

        file.parentFile.mkdirs()
        file.writeText("""version=${project.version}
commit=$commitHash
buildTime=$buildTime
""")
    }
}

tasks.processResources {
    dependsOn("generateVersionProperties")
    from(versionPropertiesDir)
}

// The plain jar (classes-only, no deps) is useless — only the shadow fat jar is distributed.
tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("smali-lsp")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())

    manifest {
        attributes["Main-Class"] = "xyz.surendrajat.smalilsp.MainKt"
    }

    // --- Exclude build-time-only ANTLR4 tool classes ---
    // antlr(libs.antlr.tool) leaks into runtimeClasspath via the ANTLR plugin.
    // Only antlr4-runtime (org/antlr/v4/runtime/**) is needed at runtime.
    // The tool packages below are used only during grammar code generation at build time.
    exclude("org/antlr/v4/Tool*.class")  // top-level Tool class
    exclude("org/antlr/v4/tool/**")
    exclude("org/antlr/v4/codegen/**")
    exclude("org/antlr/v4/analysis/**")
    exclude("org/antlr/v4/automata/**")
    exclude("org/antlr/v4/gui/**")
    exclude("org/antlr/v4/semantics/**")
    exclude("org/antlr/v4/parse/**")
    exclude("org/antlr/v4/misc/**")     // tool misc (runtime misc lives under org/antlr/v4/runtime/misc)

    // ANTLR3 runtime — transitive dep of the full antlr4 tool, not used at runtime
    exclude("org/antlr/runtime/**")

    // StringTemplate 4 — only used by ANTLR4 code generation, not at runtime
    exclude("org/stringtemplate/**")

    // Tree layout — only for ANTLR4 GUI visualization
    exclude("org/abego/**")

    // ICU4J — only used by ANTLR4 tool for unicode property analysis during grammar compilation
    exclude("com/ibm/**")

    // --- Exclude Ktor HTTP/WebSocket transport ---
    // The MCP Kotlin SDK includes Ktor for HTTP+SSE transport.
    // We only use StdioServerTransport. Ktor classes are never loaded on the stdio path
    // (class loading is lazy; KtorServerKt extension functions are never called).
    exclude("io/ktor/**")
    exclude("com/typesafe/**")  // Typesafe Config, pulled in by Ktor
    exclude("META-INF/ktor-*.kotlin_module")

    // Ktor-only transitive artifacts — nothing on the stdio path references them:
    // kotlin-reflect (~8.7MB of classes, ktor-server-core's config machinery) and
    // jansi (ktor's dev-mode terminal colors). kotlin.reflect base interfaces (KClass
    // etc.) live in kotlin-stdlib and stay bundled; only the heavyweight
    // kotlin-reflect implementation artifact is dropped.
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:kotlin-reflect:.*"))
        exclude(dependency("org.fusesource.jansi:jansi:.*"))
    }
}
