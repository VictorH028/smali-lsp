package xyz.surendrajat.smalilsp.unit

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import xyz.surendrajat.smalilsp.ServerLog
import xyz.surendrajat.smalilsp.SmaliLogProvider
import xyz.surendrajat.smalilsp.configureLogging
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The server's own SLF4J binding: the threshold, the log file and the line format. */
class LoggingTest {

    @Test
    fun `the binding is the server's own, warnings pass at the default level and info only when verbose`() {
        assertTrue(LoggerFactory.getILoggerFactory().javaClass.name.startsWith(SmaliLogProvider::class.java.packageName), LoggerFactory.getILoggerFactory().javaClass.name)
        val logger = LoggerFactory.getLogger("xyz.surendrajat.smalilsp.Probe")
        try {
            ServerLog.threshold = Level.WARN
            assertTrue(logger.isWarnEnabled && !logger.isInfoEnabled && !logger.isDebugEnabled)
            ServerLog.threshold = Level.INFO
            assertTrue(logger.isInfoEnabled && !logger.isDebugEnabled)
        } finally {
            ServerLog.threshold = Level.WARN
        }
    }

    @Test
    fun `the log file starts afresh and holds the formatted lines with their stack traces`(@TempDir dir: Path) {
        val file = dir.resolve("smali.log").toFile()
        file.writeText("from an earlier run\n")
        val logger = LoggerFactory.getLogger("xyz.surendrajat.smalilsp.Probe")
        try {
            configureLogging(verbose = true, logFile = file.path)
            logger.debug("not at this level")
            logger.info("indexed {} files in {} ms", 3, 7)
            logger.warn("something odd", IllegalStateException("why"))
        } finally {
            ServerLog.file?.close()
            ServerLog.file = null
            ServerLog.threshold = Level.WARN
        }
        val lines = file.readLines()
        assertTrue(lines.none { "earlier run" in it }, "the file is truncated: $lines")
        assertTrue(Regex("""\d\d:\d\d:\d\d\.\d\d\d INFO  Probe - indexed 3 files in 7 ms""").matches(lines[0]), lines[0])
        assertTrue(lines[1].endsWith("WARN  Probe - something odd"), lines[1])
        assertEquals("java.lang.IllegalStateException: why", lines[2])
        assertTrue(lines.none { "not at this level" in it })
    }
}
