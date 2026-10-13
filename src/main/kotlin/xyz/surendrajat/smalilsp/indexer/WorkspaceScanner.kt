package xyz.surendrajat.smalilsp.indexer

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds every `.smali` file under a directory and indexes it: one coroutine worker per core
 * reads, parses and indexes files from a bounded channel, so a 20 K-file app takes a few seconds
 * and a 76 K-file app under ten. One bad file is counted and skipped, not fatal.
 */
class WorkspaceScanner(
    private val index: WorkspaceIndex,
    private val parser: SmaliParser = SmaliParser(index.strings)
) {
    private val logger = LoggerFactory.getLogger(WorkspaceScanner::class.java)
    
    /** Indexes every `.smali` file under [directory]; [progressCallback] gets (processed, total) every thousand files and at the end. */
    suspend fun scanDirectory(
        directory: File,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): ScanResult {
        val startTime = System.currentTimeMillis()
        
        // Find all .smali files
        logger.info("Scanning directory: ${directory.absolutePath}")
        val smaliFiles = findSmaliFiles(directory)
        val totalFiles = smaliFiles.size
        logger.info("Found $totalFiles .smali files")
        
        if (totalFiles == 0) {
            return ScanResult(
                filesProcessed = 0,
                filesSucceeded = 0,
                filesFailed = 0,
                durationMs = System.currentTimeMillis() - startTime
            )
        }
        
        // A bounded channel and a fixed worker pool: one coroutine per file would mean 100 K live
        // Deferred objects on a large app; the pool gives the same throughput with O(workers).
        val succeeded = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val processed = AtomicInteger(0)
        val workerCount = maxOf(2, Runtime.getRuntime().availableProcessors())
        // Buffer one batch ahead per worker so workers are never starved.
        val channel = Channel<File>(capacity = workerCount * 4)

        coroutineScope {
            // Producer: feed files into the channel
            launch {
                smaliFiles.forEach { channel.send(it) }
                channel.close()
            }

            // Workers: drain the channel, one coroutine per CPU core
            repeat(workerCount) {
                launch(Dispatchers.Default) {
                    for (file in channel) {
                        try {
                            processFile(file)
                            succeeded.incrementAndGet()
                        } catch (e: OutOfMemoryError) {
                            throw e
                        } catch (e: Throwable) {
                            // Including a StackOverflowError from a pathological file: one bad file must not end the scan
                            logger.warn("Failed to process ${file.absolutePath}: $e")
                            failed.incrementAndGet()
                        } finally {
                            val current = processed.incrementAndGet()
                            if (current % 1000 == 0 || current == totalFiles) {
                                progressCallback?.invoke(current, totalFiles)
                            }
                        }
                    }
                }
            }
        }
        
        val duration = System.currentTimeMillis() - startTime
        val result = ScanResult(
            filesProcessed = totalFiles,
            filesSucceeded = succeeded.get(),
            filesFailed = failed.get(),
            durationMs = duration
        )
        
        logger.info("Scan complete: ${result.filesSucceeded} succeeded, ${result.filesFailed} failed in ${duration}ms")
        logger.info("Index stats: ${index.getStats()}")
        
        return result
    }
    
    /**
     * Process a single file: parse and index.
     */
    private fun processFile(file: File) {
        val uri = file.toPath().toUri().toString()  // file:///…, the form LSP clients send
        val content = file.readText(Charsets.UTF_8)
        
        val smaliFile = parser.parse(uri, content)
            ?: throw IllegalStateException("Parse returned null for $uri")
        index.indexFile(smaliFile)
    }
    
    /**
     * Recursively find all .smali files in a directory.
     */
    private fun findSmaliFiles(directory: File): List<File> {
        if (!directory.exists() || !directory.isDirectory) {
            return emptyList()
        }
        
        // Symlinked directories are skipped: a link back up the tree would otherwise recurse
        // until the path is too long, and a link elsewhere would index its files twice
        return directory.walkTopDown()
            .onEnter { it == directory || !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && it.extension == "smali" }
            .toList()
    }
}

/**
 * Result of scanning a workspace.
 */
data class ScanResult(
    val filesProcessed: Int,
    val filesSucceeded: Int,
    val filesFailed: Int,
    val durationMs: Long
) {
    val filesPerSecond: Double
        get() = if (durationMs > 0) (filesProcessed * 1000.0) / durationMs else 0.0
}


