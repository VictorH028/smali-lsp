package xyz.surendrajat.smalilsp.indexer

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import xyz.surendrajat.smalilsp.util.SourceFiles
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.SimpleFileVisitor
import java.nio.file.Path
import java.nio.file.FileVisitResult
import java.io.IOException
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
        val content = SourceFiles.read(file)
        
        val smaliFile = parser.parse(uri, content)
            ?: throw IllegalStateException("Parse returned null for $uri")
        index.indexFile(smaliFile, keepBody = false)
    }
    
    /**
     * Recursively find all .smali files in a directory.
     */
    private fun findSmaliFiles(directory: File): List<File> {
        if (!directory.exists() || !directory.isDirectory) {
            return emptyList()
        }
        
        // One stat per entry, from the walk's attributes. Links below the root are not followed:
        // a symlinked directory would recurse until the path is too long or index its files
        // twice. A root that is itself a link is entered, and its files keep the root's spelling,
        // which is the one the client opens them by.
        val root = directory.toPath()
        val walkRoot = if (Files.isSymbolicLink(root)) root.toRealPath() else root
        val found = ArrayList<File>()
        Files.walkFileTree(walkRoot, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file.fileName.toString().endsWith(".smali") && (attrs.isRegularFile || (attrs.isSymbolicLink && Files.isRegularFile(file)))) {
                    found.add((if (walkRoot === root) file else root.resolve(walkRoot.relativize(file))).toFile())
                }
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return found
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


