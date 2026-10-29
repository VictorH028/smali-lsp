package xyz.surendrajat.smalilsp

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.io.File
import java.io.PrintStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * stdout carries the protocol, so everything the server says goes to stderr: WARN and above by
 * default, INFO with `--verbose`; `--log-file` writes the same lines to a file, started afresh
 * each run. LSP4J logs through java.util.logging, which logging.properties points at stderr too.
 * The SLF4J binding is [SmaliLogProvider] below, found through META-INF/services: a logger is a
 * name and a level check, so a start pays nothing for a logging framework (logback was 150 ms
 * of a 200 ms start and 1.7 MB of the jar).
 */
internal fun configureLogging(verbose: Boolean, logFile: String?) {
    SmaliLanguageServer::class.java.getResourceAsStream("/logging.properties")?.use {
        java.util.logging.LogManager.getLogManager().readConfiguration(it)
    }
    ServerLog.threshold = if (verbose) Level.INFO else Level.WARN
    if (logFile != null) {
        ServerLog.file = try {
            PrintStream(File(logFile).outputStream().buffered(), true, Charsets.UTF_8)
        } catch (e: Exception) {
            System.err.println("Cannot write the log file $logFile: $e")
            null
        }
    }
}

/** The threshold and sinks every logger shares. */
internal object ServerLog {
    @Volatile var threshold: Level = Level.WARN
    @Volatile var file: PrintStream? = null
    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun enabled(level: Level): Boolean = level.toInt() >= threshold.toInt()

    fun write(level: Level, logger: String, message: String, throwable: Throwable?) {
        val line = "${LocalTime.now().format(clock)} ${level.name.padEnd(5)} $logger - $message"
        emit(System.err, line, throwable)
        file?.let { emit(it, line, throwable) }
    }

    private fun emit(out: PrintStream, line: String, throwable: Throwable?) {
        synchronized(out) {
            out.println(line)
            throwable?.printStackTrace(out)
        }
    }
}

private class ServerLogger(name: String) : LegacyAbstractLogger() {
    init { this.name = name }
    private val shortName = name.substringAfterLast('.')

    override fun isTraceEnabled(): Boolean = ServerLog.enabled(Level.TRACE)
    override fun isDebugEnabled(): Boolean = ServerLog.enabled(Level.DEBUG)
    override fun isInfoEnabled(): Boolean = ServerLog.enabled(Level.INFO)
    override fun isWarnEnabled(): Boolean = ServerLog.enabled(Level.WARN)
    override fun isErrorEnabled(): Boolean = ServerLog.enabled(Level.ERROR)
    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(level: Level, marker: Marker?, pattern: String?, arguments: Array<Any?>?, throwable: Throwable?) {
        ServerLog.write(level, shortName, MessageFormatter.basicArrayFormat(pattern, arguments) ?: "", throwable)
    }
}

/** The SLF4J provider, registered in META-INF/services/org.slf4j.spi.SLF4JServiceProvider. */
class SmaliLogProvider : SLF4JServiceProvider {
    private val loggers = ConcurrentHashMap<String, Logger>()
    private val markers = BasicMarkerFactory()
    private val mdc = NOPMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = ILoggerFactory { name -> loggers.computeIfAbsent(name) { ServerLogger(it) } }
    override fun getMarkerFactory(): IMarkerFactory = markers
    override fun getMDCAdapter(): MDCAdapter = mdc
    override fun getRequestedApiVersion(): String = "2.0.99"
    override fun initialize() {}
}
