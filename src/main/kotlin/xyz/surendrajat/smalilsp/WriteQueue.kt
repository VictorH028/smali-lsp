package xyz.surendrajat.smalilsp

import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * The one thread that mutates the index and publishes diagnostics.
 *
 * Notifications (didOpen, didChange, didClose, watched-file changes) hand their work here and
 * return at once, so the LSP4J reader thread never parses and a burst of watched-file events
 * cannot stall message dispatch. Requests take the [barrier] of the writes queued before them
 * and wait for it, so a request always sees every edit the editor sent ahead of it.
 */
class WriteQueue : AutoCloseable {
    private val logger = LoggerFactory.getLogger(WriteQueue::class.java)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "smali-lsp-writer").apply { isDaemon = true } }
    private val lock = Any()
    private var tail: CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    /** Runs [work] after everything queued before it. A failure is logged and never holds up later work. */
    fun submit(name: String, work: () -> Unit): CompletableFuture<Void> = synchronized(lock) {
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

    override fun close() {
        executor.shutdown()
    }
}
