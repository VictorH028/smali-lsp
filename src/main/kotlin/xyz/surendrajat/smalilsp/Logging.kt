package xyz.surendrajat.smalilsp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.FileAppender
import org.slf4j.LoggerFactory

/**
 * Warnings and errors go to stderr (logback.xml); stdout is the protocol channel. `--verbose`
 * lowers the threshold to INFO, `--log-file` additionally writes everything at that level
 * to the given file. LSP4J logs through java.util.logging, which logging.properties points
 * at stderr as well.
 */
internal fun configureLogging(verbose: Boolean, logFile: String?) {
    SmaliLanguageServer::class.java.getResourceAsStream("/logging.properties")?.use {
        java.util.logging.LogManager.getLogManager().readConfiguration(it)
    }

    val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    val rootLogger = loggerContext.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
    if (verbose) rootLogger.level = Level.INFO

    if (logFile != null) {
        val encoder = PatternLayoutEncoder().apply {
            context = loggerContext
            pattern = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
            start()
        }
        val fileAppender = FileAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
            context = loggerContext
            name = "FILE"
            file = logFile
            this.encoder = encoder
            start()
        }
        rootLogger.addAppender(fileAppender)
    }
}
