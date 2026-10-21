package xyz.surendrajat.smalilsp.shared

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * In-process LanguageClient that keeps the diagnostics and capability registrations the server sends.
 */
class TestLSPClient : LanguageClient {

    /** The last diagnostics published for each URI. */
    private val receivedDiagnostics = ConcurrentHashMap<String, List<Diagnostic>>()

    override fun publishDiagnostics(params: PublishDiagnosticsParams) {
        receivedDiagnostics[params.uri] = params.diagnostics
    }

    override fun logMessage(params: MessageParams) {}

    override fun showMessage(params: MessageParams) {}

    override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> = CompletableFuture.completedFuture(null)

    override fun telemetryEvent(obj: Any) {}

    override fun createProgress(params: WorkDoneProgressCreateParams): CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    override fun notifyProgress(params: ProgressParams) {}

    // Capabilities the server registers dynamically (e.g. the .smali file watcher)
    val registrations = mutableListOf<RegistrationParams>()

    override fun registerCapability(params: RegistrationParams): CompletableFuture<Void> {
        registrations.add(params)
        return CompletableFuture.completedFuture(null)
    }
    
    // ===== Helper methods for tests =====
    
    /**
     * Get diagnostics for a URI (returns empty list if none)
     */
    /** Forgets every published diagnostic, so a later publish can be told from an earlier one. */
    fun clearAll() = receivedDiagnostics.clear()

    fun getDiagnostics(uri: String): List<Diagnostic> = 
        receivedDiagnostics[uri] ?: emptyList()
    
    /**
     * Wait for diagnostics to be published for a URI.
     * Throws TimeoutException if diagnostics don't arrive in time.
     */
    fun waitForDiagnostics(uri: String, timeoutMs: Long = 5000): List<Diagnostic> {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            receivedDiagnostics[uri]?.let { return it }
            Thread.sleep(50)
        }
        throw TimeoutException("No diagnostics received for $uri within ${timeoutMs}ms")
    }
    
    /**
     * Wait for diagnostics to be cleared (empty list published)
     */
    fun waitForDiagnosticsCleared(uri: String, timeoutMs: Long = 5000) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val diags = receivedDiagnostics[uri]
            if (diags != null && diags.isEmpty()) return
            Thread.sleep(50)
        }
        throw TimeoutException("Diagnostics not cleared for $uri within ${timeoutMs}ms")
    }
}
