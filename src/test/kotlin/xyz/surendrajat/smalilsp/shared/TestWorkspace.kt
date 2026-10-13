package xyz.surendrajat.smalilsp.shared

import java.io.File
import java.nio.file.Files
import org.eclipse.lsp4j.Position

/**
 * A directory of smali files for the in-process server tests: a temporary one, deleted on cleanup.
 */
class TestWorkspace(val baseDir: File) {
    
    init {
        require(baseDir.exists()) { "Base directory does not exist: ${baseDir.absolutePath}" }
        require(baseDir.isDirectory) { "Base directory is not a directory: ${baseDir.absolutePath}" }
    }
    
    /**
     * Get root URI for LSP initialize
     */
    val rootUri: String
        get() = baseDir.toPath().toUri().toString()
    
    /**
     * Get file URI for a relative path
     */
    fun getFileUri(relativePath: String): String {
        return File(baseDir, relativePath).toPath().toUri().toString()
    }
    
    /**
     * Read file content
     */
    fun readFile(relativePath: String): String {
        val file = File(baseDir, relativePath)
        require(file.exists()) { "File does not exist: ${file.absolutePath}" }
        return file.readText()
    }
    
    /**
     * Write file content (creates parent directories)
     */
    fun writeFile(relativePath: String, content: String) {
        val file = File(baseDir, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
    
    companion object {
        
        
        /**
         * Create temporary workspace with single file content (defaults to Test.smali)
         */
        fun createTemp(content: String): TestWorkspace {
            return createTemp(mapOf("Test.smali" to content))
        }
        
        /**
         * Create temporary workspace with given files
         */
        fun createTemp(files: Map<String, String>): TestWorkspace {
            val tempDir = Files.createTempDirectory("smali-lsp-test").toFile()
            tempDir.deleteOnExit()
            
            val workspace = TestWorkspace(tempDir)
            files.forEach { (path, content) ->
                workspace.writeFile(path, content)
            }
            
            return workspace
        }
        
    }
    
    /**
     * Clean up workspace (for temp workspaces)
     */
    fun cleanup() {
        if (baseDir.name.startsWith("smali-lsp-test")) {
            baseDir.deleteRecursively()
        }
    }
}
