package xyz.surendrajat.smalilsp

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams
import org.eclipse.lsp4j.WorkspaceFolder
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

/** Workspace-level notifications (folders, watched files, configuration) and the workspace symbol search. */
class SmaliWorkspaceService(
    private val index: WorkspaceIndex,
    private val writes: WriteQueue = WriteQueue(),
    private val parser: SmaliParser = SmaliParser(index.strings)
) : WorkspaceService {

    private val logger = LoggerFactory.getLogger(SmaliWorkspaceService::class.java)
    private val symbolProvider = WorkspaceSymbolProvider(index)

    /** Receives the folders the client adds, so the server can index them; the server sets it. */
    var onFoldersAdded: (List<WorkspaceFolder>) -> Unit = {}

    /** A closed folder's files and resources leave the index; an opened folder is indexed like the initial ones. */
    override fun didChangeWorkspaceFolders(params: DidChangeWorkspaceFoldersParams) {
        val removed = params.event.removed
        val added = params.event.added
        logger.info("Workspace folders changed: ${added.size} added, ${removed.size} removed")
        if (removed.isNotEmpty()) {
            writes.submit("didChangeWorkspaceFolders") {
                for (folder in removed) {
                    logger.info("Removed ${index.removeFilesUnder(folder.uri)} file(s) under ${folder.uri}")
                    try { index.resources.removeUnder(File(URI(folder.uri))) } catch (_: Exception) { }
                }
            }
        }
        if (added.isNotEmpty()) onFoldersAdded(added)
    }

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
    
    /** Watched files changed on disk: apktool regenerating a project, an external edit, a delete. Files open in the editor are left to didChange. */
    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        if (params.changes.isEmpty()) return
        logger.debug("didChangeWatchedFiles: {} change(s)", params.changes.size)

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
        if (uri.endsWith("/res/values/public.xml")) {
            // apktool rewrote the resource table (or it went away with the project): read it again
            val count = index.resources.load(File(URI(uri)))
            logger.debug("Resource table {}: {} ids", type, count)
            return
        }
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
        writes.request("workspace/symbol", { params.query }, { Either.forLeft(emptyList()) }) { checker ->
            Either.forLeft(symbolProvider.search(params.query, checker))
        }
}
