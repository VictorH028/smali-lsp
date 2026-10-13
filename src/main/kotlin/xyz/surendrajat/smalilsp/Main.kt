package xyz.surendrajat.smalilsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import xyz.surendrajat.smalilsp.providers.CallHierarchyProvider
import xyz.surendrajat.smalilsp.providers.CodeLensProvider
import xyz.surendrajat.smalilsp.providers.CompletionProvider
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.providers.RenameProvider
import xyz.surendrajat.smalilsp.providers.TypeHierarchyProvider
import xyz.surendrajat.smalilsp.cli.McpMode
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.FileAppender
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking

/**
 * Main entry point for Smali Language Server.
 * 
 * Architecture:
 * - Communicates via LSP protocol over stdio (default mode)
 * - OR runs as MCP server for AI agent integration (keeps index in memory, <10ms queries)
 * - Indexes workspace on initialization
 * - Provides goto definition, hover, find references, diagnostics
 *
 * Usage:
 *   java -jar smali-lsp-<version>.jar lsp  (LSP mode - IDE integration)
 *   java -jar smali-lsp-<version>.jar mcp  (MCP server - AI agent integration)
 */
data class VersionInfo(val version: String, val commit: String, val buildTime: String)

internal fun loadVersionInfo(): VersionInfo {
    val props = java.util.Properties()
    val stream = SmaliLanguageServer::class.java.getResourceAsStream("/version.properties")
    if (stream != null) {
        stream.use { props.load(it) }
    }

    val version = props.getProperty("version", "unknown")
    val commit = props.getProperty("commit", "unknown")
    val buildTime = props.getProperty("buildTime", "unknown")
    return VersionInfo(version, commit, buildTime)
}

fun main(args: Array<String>) {
    val verbose = args.contains("--verbose")
    val command = args.firstOrNull { it != "--verbose" } ?: "help"

    if (verbose) {
        enableFileLogging()
    }

    when (command) {
        "lsp" -> startLsp()
        "mcp" -> McpMode().run()
        "--version", "-v" -> {
            val info = loadVersionInfo()
            println("smali-lsp v${info.version}+${info.commit} (built ${info.buildTime})")
        }
        "help", "--help", "-h" -> printUsage()
        else -> {
            System.err.println("Unknown command: $command")
            printUsage()
            System.exit(1)
        }
    }
}

/**
 * Enable file logging (logback + JUL) when --verbose is passed.
 * By default, logging is OFF to avoid creating log files in CWD.
 */
private fun enableFileLogging() {
    // Configure Logback programmatically
    val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext

    val encoder = PatternLayoutEncoder()
    encoder.context = loggerContext
    encoder.pattern = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
    encoder.start()

    val fileAppender = FileAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
    fileAppender.context = loggerContext
    fileAppender.name = "FILE"
    fileAppender.file = "smali-lsp.log"
    fileAppender.encoder = encoder
    fileAppender.start()

    val rootLogger = loggerContext.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
    rootLogger.level = Level.INFO
    rootLogger.addAppender(fileAppender)

    // Configure JUL for LSP4J
    val julHandler = java.util.logging.FileHandler("smali-lsp-jul.log", 1_000_000, 1, true)
    julHandler.formatter = java.util.logging.SimpleFormatter()
    val julRoot = java.util.logging.Logger.getLogger("")
    julRoot.addHandler(julHandler)
    julRoot.level = java.util.logging.Level.WARNING
}

private fun printUsage() {
    val info = loadVersionInfo()
    println("""
        smali-lsp v${info.version} — Language Server & MCP server for Smali

        Usage: java -jar smali-lsp-<version>.jar <command> [options]

        Commands:
          lsp          Start LSP server over stdio (for IDE integration)
          mcp          Start MCP server over stdio (for AI agent integration)

        Options:
          --verbose    Enable file logging (smali-lsp.log in current directory)
          --version    Show version info
          --help       Show this help message
    """.trimIndent())
}

private fun startLsp() {
    // Load JUL properties to suppress default ConsoleHandler (would corrupt LSP stdio)
    val julStream = SmaliLanguageServer::class.java.getResourceAsStream("/logging.properties")
    if (julStream != null) {
        julStream.use { java.util.logging.LogManager.getLogManager().readConfiguration(it) }
    }

    val server = SmaliLanguageServer()
    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, System.out)

    val client = launcher.remoteProxy
    server.connect(client)

    // Start listening - blocks until connection closes
    launcher.startListening()
}

/**
 * Smali Language Server implementation.
 */
class SmaliLanguageServer : LanguageServer {
    
    private val logger = LoggerFactory.getLogger(SmaliLanguageServer::class.java)
    private lateinit var client: LanguageClient
    
    // Core components
    private val index = WorkspaceIndex()
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
        renameProvider
    )
    
    // Workspace service
    private val workspaceService = SmaliWorkspaceService(index)
    
    /**
     * Connect to client.
     */
    fun connect(client: LanguageClient) {
        this.client = client
        textDocumentService.connect(client)
    }
    
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
            client.createProgress(WorkDoneProgressCreateParams().apply { token = progressToken }).get()
            true
        } catch (e: Exception) {
            logger.warn("Client rejected progress creation, indexing without progress: ${e.message}")
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
                        var lastReportedPercent = 0
                        val result = scanner.scanDirectory(dir) { processed, total ->
                            val percent = if (total > 0) processed * 100 / total else 0
                            if (percent > lastReportedPercent || processed == total) {
                                lastReportedPercent = percent
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

                        val duration = System.currentTimeMillis() - startTime
                        val stats = index.getStats()

                        logger.info("Indexed ${result.filesSucceeded} files in ${duration}ms")
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
                    textDocumentService.indexingComplete = true
                    textDocumentService.refreshDiagnosticsForOpenFiles()
                } catch (e: Exception) {
                    logger.error("Failed to index workspace", e)
                    // Mark indexing complete even on failure so diagnostics aren't permanently suppressed
                    textDocumentService.indexingComplete = true
                } finally {
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
        val options = DidChangeWatchedFilesRegistrationOptions(listOf(FileSystemWatcher(Either.forLeft("**/*.smali"))))
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
        return CompletableFuture.completedFuture(null)
    }
    
    /**
     * Exit server. The spec asks for status 0 after a shutdown request and 1 otherwise,
     * so an editor can tell a clean stop from a server that died mid-session.
     */
    override fun exit() {
        logger.info("Server exiting")
        System.exit(if (shutdownRequested) 0 else 1)
    }
    
    override fun getTextDocumentService(): TextDocumentService {
        return textDocumentService
    }
    
    override fun getWorkspaceService(): WorkspaceService {
        return workspaceService
    }
}
