import java.time.Instant
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

group = "xyz.surendrajat"
version = "1.5.0"

repositories {
    mavenCentral()
}

dependencies {
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
}

// Keep never-loaded artifacts off the runtime classpath, so the shadow jar stays lean
// without fragile path-based excludes: io.ktor:* are the MCP SDK's HTTP/SSE transports
// (dragging in kotlin-reflect, jansi, and typesafe-config through ktor-server-core). This
// server is stdio-only; the ktor code paths are never loaded.
// Tests keep the full classpath (this only touches runtimeClasspath).
configurations.runtimeClasspath {
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
        // The build is warning-free; keep it that way
        allWarningsAsErrors.set(true)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(targetJavaVersion)
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

// Live testing in APKLab: copies the fat jar to ~/.apklab/smali-lsp-dev.jar (or -PapklabDir=<dir>).
// Point `apklab.smaliLspPath` at that file once; reload the VS Code window after each install.
tasks.register<Copy>("installApklab") {
    group = "distribution"
    description = "Copies the shadow jar to ~/.apklab/smali-lsp-dev.jar for APKLab to launch"
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into(providers.gradleProperty("apklabDir").orElse(providers.systemProperty("user.home").map { "$it/.apklab" }))
    rename { "smali-lsp-dev.jar" }
}
