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

// Regenerates src/main/resources/android-public.txt (android.R names) from a decoded framework:
//   apktool d --no-src -o fw ~/Library/apktool/framework/1.apk
//   ./gradlew generateAndroidPublic -PframeworkPublicXml=fw/res/values/public.xml -PframeworkLabel="framework-res.apk bundled with apktool 3.0.2 (Android 16)"
tasks.register("generateAndroidPublic") {
    group = "build setup"
    description = "Writes src/main/resources/android-public.txt from a framework public.xml"
    val input = providers.gradleProperty("frameworkPublicXml")
    val label = providers.gradleProperty("frameworkLabel").orElse("the Android framework")
    val output = layout.projectDirectory.file("src/main/resources/android-public.txt")
    doLast {
        val xml = File(input.orNull ?: throw GradleException("pass -PframeworkPublicXml=<decoded framework>/res/values/public.xml"))
        val entry = Regex("""<public type="([a-z]+)" name="([^"\s]+)" id="0x([0-9a-f]{8})"""")
        val entries = xml.readLines().mapNotNull { line -> entry.find(line)?.destructured?.let { (type, name, id) -> Triple(id.toLong(16).toInt(), type, name) } }.sortedBy { it.first }
        if (entries.isEmpty() || entries.any { it.first ushr 24 != 0x01 }) throw GradleException("$xml is not a framework public.xml")
        val out = StringBuilder()
        out.append("# Android framework public resource ids (package 0x01): ${entries.size} entries from the public.xml of the\n")
        out.append("# ${label.get()}; AOSP, Apache License 2.0.\n")
        out.append("# Regenerate with ./gradlew generateAndroidPublic (see build.gradle.kts). Sections: [type-hex type]; lines: entry-hex name.\n")
        var section = -1
        for ((id, type, name) in entries) {
            val typeId = (id ushr 16) and 0xff
            if (typeId != section) { out.append("[%02x %s]\n".format(typeId, type)); section = typeId }
            out.append("%04x %s\n".format(id and 0xffff, name))
        }
        output.asFile.writeText(out.toString())
    }
}
