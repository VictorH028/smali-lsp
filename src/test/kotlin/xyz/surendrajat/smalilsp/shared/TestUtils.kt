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

    /** The decompiled apps under `test-data/`, by name; empty when none is checked out. */
    fun fixtureDirs(): List<File> =
        File(getProjectRoot(), "test-data").listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
}
