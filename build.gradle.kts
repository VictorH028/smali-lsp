import java.time.Instant
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    id("antlr")
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

// Keep build-time-only and never-loaded artifacts off the runtime classpath, so the
// shadow jar stays lean without fragile path-based excludes:
//  - org.antlr:antlr4 is the grammar *tool* (codegen, StringTemplate, ICU, ANTLR3);
//    the antlr plugin leaks it onto the runtime classpath. Only antlr4-runtime —
//    declared directly above — is needed at runtime.
//  - io.ktor:* are the MCP SDK's HTTP/SSE transports (dragging in kotlin-reflect,
//    jansi, and typesafe-config through ktor-server-core). This server is
//    stdio-only; the ktor code paths are never loaded.
// Tests keep the full classpath (this only touches runtimeClasspath).
configurations.runtimeClasspath {
    exclude(group = "org.antlr", module = "antlr4")
    exclude(group = "io.ktor")
}

// Build on whatever JDK launches Gradle — any Java 17+ works (Gradle 9's own floor;
// no pinned toolchain, no forced downloads). The artifact is kept Java-17 compatible
// regardless of the build JDK:
//  - bytecode: jvmTarget/release below emit class-file 61
//  - API surface: release (javac) and -Xjdk-release (kotlinc) compile against the
//    Java 17 API, so code using newer JDK APIs fails the build instead of crashing
//    on a real Java 17 runtime. CI additionally runs the tests on a JDK 17 leg.
val targetJavaVersion = 17

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
        freeCompilerArgs.add("-Xjdk-release=$targetJavaVersion")
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

// Generate version metadata resource from project version + git commit
val versionPropertiesDir = layout.buildDirectory.dir("generated-resources/version")
val versionPropertiesFile = versionPropertiesDir.map { it.file("version.properties") }
val gitCommitProvider = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

tasks.register("generateVersionProperties") {
    // Task-local copies: the doLast closure must capture plain values/Providers,
    // not `project` or top-level script properties (which drag the whole script
    // object in and break the configuration cache).
    val projectVersion = project.version.toString()
    val commitProvider = gitCommitProvider
    val outputFile = versionPropertiesFile
    inputs.property("projectVersion", projectVersion)
    inputs.property("gitCommit", commitProvider)
    outputs.file(outputFile)
    doLast {
        val commitHash = try {
            commitProvider.get()
        } catch (_: Exception) {
            "unknown"
        }

        val buildTime = Instant.now().toString()
        val file = outputFile.get().asFile

        file.parentFile.mkdirs()
        file.writeText("""version=$projectVersion
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

// Jar contents are governed by the runtimeClasspath excludes above — no path lists needed.
tasks.shadowJar {
    archiveBaseName.set("smali-lsp")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())

    manifest {
        attributes["Main-Class"] = "xyz.surendrajat.smalilsp.MainKt"
    }
}
