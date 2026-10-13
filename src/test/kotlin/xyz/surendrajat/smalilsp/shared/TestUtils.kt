package xyz.surendrajat.smalilsp.shared

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

/**
 * Test utility functions for portable test data access.
 */
object TestUtils {
    /**
     * Get the project root directory (where build.gradle.kts is located).
     * Works regardless of working directory.
     */
    fun getProjectRoot(): File {
        // Start from this class file location and walk up to find project root
        val classFile = File(TestUtils::class.java.protectionDomain.codeSource.location.path)
        var current: File? = classFile

        // Walk up until we find build.gradle.kts
        while (current != null && current.exists()) {
            if (File(current, "build.gradle.kts").exists()) {
                return current
            }
            current = current.parentFile
        }
        
        // Fallback: use system property if available
        System.getProperty("user.dir")?.let { return File(it) }
        
        error("Could not find project root. Please run tests from project directory.")
    }

    /**
     * Get a specific APK decompiled directory.
     * Returns null if the APK or test-data directory doesn't exist (allows tests to skip gracefully).
     */
    fun getApk(name: String): File? {
        val testDataDir = File(getProjectRoot(), "test-data")
        if (!testDataDir.exists()) return null
        val apkDir = File(testDataDir, name)
        return if (apkDir.exists() && apkDir.isDirectory) apkDir else null
    }
}
