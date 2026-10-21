package xyz.surendrajat.smalilsp

import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * The one thread that mutates the index and publishes diagnostics.
 *
 * Notifications (didOpen, didChange, didClose, watched-file changes) hand their work here and
 * return at once, so the LSP4J reader thread never parses and a burst of watched-file events
 * cannot stall message dispatch. Requests ([request]) take the [barrier] of the writes queued
 * before them and wait for it, so a request always sees every edit the editor sent ahead of it.
 */
class WriteQueue : AutoCloseable {
    private val logger = LoggerFactory.getLogger(WriteQueue::class.java)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "smali-lsp-writer").apply { isDaemon = true } }
    private val lock = Any()
    private var tail: CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    /** Runs [work] after everything queued before it. A failure is logged and never holds up later work. */
    fun submit(name: String, work: () -> Unit): CompletableFuture<Void> = synchronized(lock) {
        JvmTuning.noteActivity()
        if (executor.isShutdown) return tail
        val next = tail.handleAsync<Void>({ _, _ ->
            try {
                work()
            } catch (e: Exception) {
                logger.error("$name failed", e)
            }
            null
        }, executor)
        tail = next
        next
    }

    /** Completes once every write queued so far has run. */
    fun barrier(): CompletableFuture<Void> = synchronized(lock) { tail }

    /**
     * Answers a request on the common pool once the writes queued before it have been applied.
     * A failing [compute] is logged and answered with [fallback], so one bad request never takes
     * the session down; a [ResponseErrorException] thrown by [compute] or [fallback] reaches the
     * client as that error; a cancelled request ($/cancelRequest) stays cancelled, and long scans
     * check the [CancelChecker] so a superseded request stops early.
     */
    fun <T> request(name: String, context: () -> String?, fallback: () -> T, compute: (CancelChecker) -> T): CompletableFuture<T> {
        if (logger.isDebugEnabled) logger.debug("{}: {}", name, context())
        JvmTuning.noteActivity()
        val barrier = barrier()
        return CompletableFutures.computeAsync { checker ->
            try {
                barrier.join()
                compute(checker)
            } catch (e: ResponseErrorException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Error in $name${context()?.let { ": $it" } ?: ""}", e)
                fallback()
            }
        }
    }

    override fun close() {
        executor.shutdown()
    }
}
