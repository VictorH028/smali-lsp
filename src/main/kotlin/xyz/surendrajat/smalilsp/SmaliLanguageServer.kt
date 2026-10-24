package xyz.surendrajat.smalilsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.*
import org.slf4j.LoggerFactory
import xyz.surendrajat.smalilsp.index.ResourceTable
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import xyz.surendrajat.smalilsp.providers.CallHierarchyProvider
import xyz.surendrajat.smalilsp.providers.CodeLensProvider
import xyz.surendrajat.smalilsp.providers.SemanticTokensProvider
import xyz.surendrajat.smalilsp.providers.CompletionProvider
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.providers.RenameProvider
import xyz.surendrajat.smalilsp.providers.TypeHierarchyProvider
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

internal fun startLsp() {
    val server = SmaliLanguageServer()
    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, System.out)

    val client = launcher.remoteProxy
    server.connect(client)

    // Returns when the client closes the connection. A client that follows the protocol sends
    // exit first and never gets here; one that died must not leave the server running.
    launcher.startListening().get()
    exitProcess(server.exitCode)
}

/**
 * Smali Language Server implementation.
 */
class SmaliLanguageServer : LanguageServer {
    
    private val logger = LoggerFactory.getLogger(SmaliLanguageServer::class.java)
    private lateinit var client: LanguageClient
    
    // Core components
    private val index = WorkspaceIndex()
    private val writes = WriteQueue()
    private val scanner = WorkspaceScanner(index)
    private val definitionProvider = DefinitionProvider(index)
    private val hoverProvider = HoverProvider(index)
    private val referenceProvider = ReferenceProvider(index)
    private val callHierarchyProvider = CallHierarchyProvider(index)
    private val typeHierarchyProvider = TypeHierarchyProvider(index)
    private val codeLensProvider = CodeLensProvider(index)
    private val completionProvider = CompletionProvider(index)
    private val renameProvider = RenameProvider(index)

    // Text document service
    private val textDocumentService = SmaliTextDocumentService(
        index,
        definitionProvider,
        hoverProvider,
        referenceProvider,
        callHierarchyProvider,
        typeHierarchyProvider,
        codeLensProvider,
        completionProvider,
        renameProvider,
        writes
    )
    
    // Workspace service
    private val workspaceService = SmaliWorkspaceService(index, writes)
    
    /**
     * Connect to client.
     */
    fun connect(client: LanguageClient) {
        this.client = client
        textDocumentService.connect(client)
        workspaceService.onFoldersAdded = { folders ->
            CompletableFuture.runAsync {
                try {
                    indexWorkspace(folders)
                } catch (e: Throwable) {
                    logger.error("Failed to index added workspace folders", e)
                } finally {
                    textDocumentService.refreshDiagnosticsForOpenFiles()
                }
            }
        }
    }

    /** Blocks until every notification received so far has been applied. For tests. */
    fun awaitWrites() = textDocumentService.awaitWrites()
    
    private var pendingWorkspaceFolders: List<WorkspaceFolder> = emptyList()
    private var clientSupportsProgress = false
    private var clientSupportsWatcherRegistration = false

    /** Completes once the initial workspace indexing has finished (or was skipped/failed). */
    val indexingDone: CompletableFuture<Void> = CompletableFuture()

    @Volatile
    private var shutdownRequested = false

    /**
     * Initialize server.
     * Client sends workspace folders and capabilities.
     * Indexing is deferred to initialized() so the client can receive progress notifications.
     */
    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        logger.info("Initializing Smali Language Server")
        logger.info("Client: ${params.clientInfo?.name} ${params.clientInfo?.version}")
        logger.info("Workspace folders: ${params.workspaceFolders?.size ?: 0}")

        // Clients that do not support workspace folders send rootUri (or the older rootPath).
        pendingWorkspaceFolders = params.workspaceFolders
            ?: params.rootUri?.let { listOf(WorkspaceFolder(it, "root")) }
            ?: params.rootPath?.let { listOf(WorkspaceFolder(File(it).toURI().toString(), "root")) }
            ?: emptyList()
        clientSupportsProgress = params.capabilities?.window?.workDoneProgress == true
        clientSupportsWatcherRegistration = params.capabilities?.workspace?.didChangeWatchedFiles?.dynamicRegistration == true

        return CompletableFuture.completedFuture(createInitializeResult())
    }
    
    /**
     * Index all workspace folders.
     */
    private fun indexWorkspace(folders: List<WorkspaceFolder>) = runBlocking {
        logger.info("Indexing ${folders.size} workspace folder(s)...")

        val progressToken = Either.forLeft<String, Int>("smali-indexing-${UUID.randomUUID()}")

        // Progress is a courtesy: only for clients that advertise it, and never a reason to
        // skip indexing — a client that rejects the create request just gets no progress bar.
        val progress = clientSupportsProgress && try {
            // A client that advertised progress but never answers must not hold up indexing
            client.createProgress(WorkDoneProgressCreateParams().apply { token = progressToken }).get(5, TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            logger.warn("Client did not accept progress creation, indexing without progress: ${e.message}")
            false
        }
        if (progress) {
            client.notifyProgress(ProgressParams(progressToken, Either.forLeft(
                WorkDoneProgressBegin().apply {
                    title = "Indexing Smali"
                    percentage = 0
                    cancellable = false
                }
            )))
        }

        try {
            for (folder in folders) {
                try {
                    val uri = folder.uri
                    val dir = File(java.net.URI(uri))

                    if (dir.exists() && dir.isDirectory) {
                        logger.info("Scanning: ${dir.absolutePath}")

                        val startTime = System.currentTimeMillis()
                        val lastReportedPercent = java.util.concurrent.atomic.AtomicInteger(0)
                        val result = scanner.scanDirectory(dir) { processed, total ->
                            // Reported from the scanner's worker threads
                            val percent = if (total > 0) processed * 100 / total else 0
                            if (lastReportedPercent.getAndAccumulate(percent, ::maxOf) < percent || processed == total) {
                                logger.info("Indexing: $processed/$total ($percent%)")
                                if (progress) {
                                    client.notifyProgress(ProgressParams(progressToken, Either.forLeft(
                                        WorkDoneProgressReport().apply {
                                            message = "$processed / $total files"
                                            percentage = percent
                                        }
                                    )))
                                }
                            }
                        }

                        val resources = index.resources.load(ResourceTable.publicXml(dir))
                        val duration = System.currentTimeMillis() - startTime
                        val stats = index.getStats()

                        logger.info("Indexed ${result.filesSucceeded} files in ${duration}ms")
                        if (resources > 0) logger.info("  Resources: $resources ids from res/values/public.xml")
                        logger.info("  Rate: ${result.filesPerSecond} files/sec")
                        logger.info("  Classes: ${stats.classes}, Methods: ${stats.methods}, Fields: ${stats.fields}, Strings: ${stats.strings}")

                        if (result.filesFailed > 0) {
                            logger.warn("  Failed: ${result.filesFailed} files")
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Failed to index folder: ${folder.uri}", e)
                }
            }
        } finally {
            // Always end progress — a dropped End leaves the client's status bar
            // stuck on an indefinite "Indexing…" spinner until the server restarts.
            val stats = index.getStats()
            if (progress) {
                client.notifyProgress(ProgressParams(progressToken, Either.forLeft(
                    WorkDoneProgressEnd().apply {
                        message = "Indexed ${stats.classes} classes, ${stats.methods} methods, ${stats.fields} fields"
                    }
                )))
            }
        }
    }
    
    /**
     * Create initialize result with server capabilities.
     */
    private fun createInitializeResult(): InitializeResult {
        val capabilities = ServerCapabilities()
        
        // Text document sync
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
        
        // Goto definition
        capabilities.setDefinitionProvider(true)
        
        // Hover
        capabilities.setHoverProvider(true)
        
        // Find references
        capabilities.setReferencesProvider(true)
        
        // Document symbols
        capabilities.setDocumentSymbolProvider(true)
        
        // Workspace symbols
        capabilities.setWorkspaceSymbolProvider(true)

        // Call hierarchy
        capabilities.setCallHierarchyProvider(true)

        // Type hierarchy
        capabilities.setTypeHierarchyProvider(true)

        // Code lens
        capabilities.setCodeLensProvider(CodeLensOptions(true))

        // Completion
        capabilities.setCompletionProvider(CompletionOptions(false, listOf("L", ">", "/")))

        // Rename (with prepare support)
        capabilities.setRenameProvider(RenameOptions(true))

        // Implementations, occurrences in the file, foldable regions
        capabilities.setImplementationProvider(true)
        capabilities.setDocumentHighlightProvider(true)
        capabilities.setFoldingRangeProvider(true)
        capabilities.setInlayHintProvider(true)
        capabilities.setCodeActionProvider(CodeActionOptions(listOf(CodeActionKind.QuickFix)))
        capabilities.setDocumentFormattingProvider(true)
        capabilities.setDocumentRangeFormattingProvider(true)
        // Semantic tokens: full, delta and range, for editors without a smali grammar
        capabilities.setSemanticTokensProvider(SemanticTokensWithRegistrationOptions(SemanticTokensProvider.legend, SemanticTokensServerFull(true), true))

        // Folders added or removed during the session are indexed or dropped
        capabilities.workspace = WorkspaceServerCapabilities().apply {
            workspaceFolders = WorkspaceFoldersOptions().apply {
                supported = true
                setChangeNotifications(Either.forRight(true))
            }
        }

        val versionInfo = loadVersionInfo()
        val serverInfo = ServerInfo("Smali Language Server", versionInfo.version)
        return InitializeResult(capabilities, serverInfo)
    }
    
    /**
     * Called after initialize response. Client is now ready to receive notifications.
     * Trigger workspace indexing here so we can send progress updates.
     */
    override fun initialized(params: InitializedParams) {
        logger.info("Server initialized, starting workspace indexing...")
        registerFileWatcher()
        if (pendingWorkspaceFolders.isNotEmpty()) {
            CompletableFuture.runAsync {
                try {
                    indexWorkspace(pendingWorkspaceFolders)
                    index.trimUsages()
                    JvmTuning.compactAfterScan()
                } catch (e: Throwable) {
                    logger.error("Failed to index workspace", e)
                } finally {
                    // Whatever went wrong, semantic diagnostics must not stay suppressed for the session
                    textDocumentService.indexingComplete = true
                    textDocumentService.refreshDiagnosticsForOpenFiles()
                    indexingDone.complete(null)
                }
            }
        } else {
            textDocumentService.indexingComplete = true
            indexingDone.complete(null)
        }
    }
    
    /**
     * Ask the client to report create/change/delete of .smali files on disk (apktool
     * regenerating a project, deletions outside the editor). Most clients only send
     * workspace/didChangeWatchedFiles for watchers the server registered.
     */
    private fun registerFileWatcher() {
        if (!clientSupportsWatcherRegistration) return
        val options = DidChangeWatchedFilesRegistrationOptions(listOf(
            FileSystemWatcher(Either.forLeft("**/*.smali")),
            FileSystemWatcher(Either.forLeft("**/res/values/public.xml"))
        ))
        val registration = Registration("smali-file-watcher", "workspace/didChangeWatchedFiles", options)
        try {
            client.registerCapability(RegistrationParams(listOf(registration))).exceptionally { e ->
                logger.warn("Client rejected file watcher registration: ${e.message}")
                null
            }
        } catch (e: Exception) {
            // An in-process client may throw instead of returning a failed future; never let that
            // stop initialization.
            logger.warn("Client does not accept capability registration: ${e.message}")
        }
    }

    /**
     * Shutdown server.
     */
    override fun shutdown(): CompletableFuture<Any> {
        logger.info("Shutting down server")
        shutdownRequested = true
        writes.close()
        return CompletableFuture.completedFuture(null)
    }
    
    /** 0 after a shutdown request and 1 otherwise, as the protocol asks, so an editor can tell a clean stop from a server that died mid-session. */
    val exitCode: Int get() = if (shutdownRequested) 0 else 1

    override fun exit() {
        logger.info("Server exiting")
        exitProcess(exitCode)
    }
    
    override fun getTextDocumentService(): TextDocumentService {
        return textDocumentService
    }
    
    override fun getWorkspaceService(): WorkspaceService {
        return workspaceService
    }
}
