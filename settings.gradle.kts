plugins {
    // Auto-provisions the JDK toolchain declared in build.gradle.kts when not installed locally
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "smali-lsp"
