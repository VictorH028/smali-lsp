package xyz.surendrajat.smalilsp

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.WorkspaceService
import org.slf4j.LoggerFactory
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.WorkspaceSymbolProvider
import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Workspace Service for Smali Language Server.
 * 
 * Handles workspace-level events:
 * - Configuration changes
 * - File system watchers
 * - Workspace symbol search
 * 
 * Provides:
 * - workspace/symbol: Search for symbols across the entire workspace
 */
class SmaliWorkspaceService(
    private val index: WorkspaceIndex,
    private val writes: WriteQueue = WriteQueue(),
    private val parser: SmaliParser = SmaliParser(index.strings)
) : WorkspaceService {

    private val logger = LoggerFactory.getLogger(SmaliWorkspaceService::class.java)
    private val symbolProvider = WorkspaceSymbolProvider(index)

    /** Blocks until every notification received so far has been applied. For tests. */
    fun awaitWrites(timeoutSeconds: Long = 30) {
        writes.barrier().get(timeoutSeconds, TimeUnit.SECONDS)
    }
    
    /**
     * Configuration changed.
     */
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        logger.debug("didChangeConfiguration: ${params.settings}")
        // No configuration options yet
    }
    
    /**
     * Watched files changed on disk.
     *
     * Open files are kept in sync by didChange, but a file that was deleted,
     * created, or edited externally (e.g. by apktool regenerating a project)
     * will not trigger didChange — without handling this, go-to-definition
     * navigates to ghost URIs that no longer exist, and references to new
     * classes fail to resolve until the user opens them manually. Files that
     * are open in the editor are left to the editor.
     */
    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        if (params.changes.isEmpty()) return
        logger.debug("didChangeWatchedFiles: ${params.changes.size} change(s)")

        // Only the last event per file matters; a regenerated project can send thousands at once
        val latest = LinkedHashMap<String, FileChangeType>()
        for (change in params.changes) latest[change.uri] = change.type

        writes.submit("didChangeWatchedFiles") {
            for ((uri, type) in latest) {
                try {
                    applyWatchedChange(uri, type)
                } catch (e: Exception) {
                    logger.warn("Error handling watched file change for $uri: ${e.message}")
                }
            }
        }
    }

    private fun applyWatchedChange(uri: String, type: FileChangeType) {
        // An open buffer is the truth for its file: the editor reports its own changes through
        // didChange, and didClose brings the disk version back.
        if (index.isOpen(uri)) return
        when (type) {
            FileChangeType.Deleted -> {
                if (index.removeFile(uri)) logger.debug("Removed from index (deleted): $uri")
            }
            FileChangeType.Created, FileChangeType.Changed -> {
                val file = File(URI(uri))
                if (!file.exists() || !file.isFile) return
                val smaliFile = parser.parse(uri, file.readText(Charsets.UTF_8))
                if (smaliFile != null) {
                    index.indexFile(smaliFile)
                    logger.debug("Re-indexed from disk ($type): $uri")
                } else {
                    logger.warn("Failed to parse watched file: $uri")
                }
            }
        }
    }
    
    /**
     * Search workspace for symbols: case-insensitive, fuzzy, classes, methods and fields, the
     * best 500 matches. The editor sends one request per keystroke and cancels the previous
     * one, which stops its scan.
     */
    override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> =
        writes.request("workspace/symbol", params.query, { Either.forLeft(emptyList()) }) { checker ->
            Either.forLeft(symbolProvider.search(params.query, checker))
        }
}
