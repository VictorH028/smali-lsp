package xyz.surendrajat.smalilsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.slf4j.LoggerFactory
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.CallHierarchyProvider
import xyz.surendrajat.smalilsp.providers.CodeLensProvider
import xyz.surendrajat.smalilsp.providers.CompletionProvider
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.DiagnosticProvider
import xyz.surendrajat.smalilsp.providers.DocumentSymbolProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.providers.RenameProvider
import xyz.surendrajat.smalilsp.providers.TypeHierarchyProvider
import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The textDocument side of the server: keeps open buffers and the index in step
 * (didOpen/didChange re-parse and re-index, didClose keeps the file indexed so cross-file
 * navigation into it still works) and answers the language requests by delegating to the
 * providers.
 */
class SmaliTextDocumentService(
    private val index: WorkspaceIndex,
    private val definitionProvider: DefinitionProvider,
    private val hoverProvider: HoverProvider,
    private val referenceProvider: ReferenceProvider,
    private val callHierarchyProvider: CallHierarchyProvider,
    private val typeHierarchyProvider: TypeHierarchyProvider,
    private val codeLensProvider: CodeLensProvider,
    private val completionProvider: CompletionProvider,
    private val renameProvider: RenameProvider,
    private val writes: WriteQueue = WriteQueue()
) : TextDocumentService {

    private val logger = LoggerFactory.getLogger(SmaliTextDocumentService::class.java)
    private val parser = SmaliParser(index.strings)
    private val diagnosticProvider = DiagnosticProvider(parser, index)
    private val documentSymbolProvider = DocumentSymbolProvider()
    private var client: LanguageClient? = null

    /** Open file URIs, so diagnostics can be refreshed once workspace indexing completes. */
    private val openFiles = ConcurrentHashMap.newKeySet<String>()

    /** Semantic diagnostics are suppressed until workspace indexing has completed. */
    @Volatile
    var indexingComplete: Boolean = false

    fun connect(client: LanguageClient) {
        this.client = client
    }

    /** Blocks until every notification received so far has been applied. For tests. */
    fun awaitWrites(timeoutSeconds: Long = 30) {
        writes.barrier().get(timeoutSeconds, TimeUnit.SECONDS)
    }

    private val TextDocumentPositionParams.at: String
        get() = "${textDocument.uri} at ${position.line}:${position.character}"

    private fun responseError(code: ResponseErrorCode, message: String) =
        ResponseErrorException(ResponseError(code, message, null))

    /** Re-publish diagnostics for every open file, clearing warnings that were only stale during indexing. */
    fun refreshDiagnosticsForOpenFiles() {
        logger.info("Refreshing diagnostics for ${openFiles.size} open file(s)")
        writes.submit("refreshDiagnostics") {
            for (uri in openFiles) {
                try {
                    val content = index.getDocumentContent(uri) ?: continue
                    publishDiagnostics(uri, diagnosticProvider.computeDiagnostics(uri, content))
                } catch (e: Exception) {
                    logger.error("Error refreshing diagnostics for $uri", e)
                }
            }
        }
    }

    private fun publishDiagnostics(uri: String, diagnostics: List<Diagnostic>) {
        client?.publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics))
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = params.textDocument.uri
        val text = params.textDocument.text
        logger.debug("didOpen: $uri")
        openFiles.add(uri)
        // The scan may have indexed the file from disk, but the editor's text is what the user
        // sees (a restored unsaved buffer, a file changed since the scan): index it again.
        writes.submit("didOpen $uri") { update(uri, text, reindex = true) }
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        // Full document sync (TextDocumentSyncKind.Full)
        val content = params.contentChanges.firstOrNull()?.text ?: return
        val uri = params.textDocument.uri
        logger.debug("didChange: $uri")
        writes.submit("didChange $uri") { update(uri, content, reindex = true) }
    }

    /**
     * Stores the buffer, re-parses, indexes when [reindex] and publishes diagnostics. Semantic
     * diagnostics wait for workspace indexing to complete; otherwise one keystroke mid-indexing
     * floods the file with undefined-class warnings that vanish once indexing is done.
     */
    private fun update(uri: String, content: String, reindex: Boolean) {
        index.setDocumentContent(uri, content)
        try {
            val parseResult = parser.parseWithErrors(uri, content)
            val smaliFile = parseResult.smaliFile
            if (smaliFile == null) {
                logger.warn("Failed to parse: $uri")
            } else if (reindex) {
                index.indexFile(smaliFile)
            }
            val diagnostics = if (indexingComplete) {
                diagnosticProvider.computeDiagnosticsFromParseResult(uri, parseResult)
            } else {
                diagnosticProvider.computeSyntaxDiagnosticsFromParseResult(parseResult)
            }
            publishDiagnostics(uri, diagnostics)
        } catch (e: Exception) {
            logger.error("Error updating $uri", e)
        }
    }

    /**
     * The file stays indexed, as it is on disk: definitions and references from other files must
     * keep resolving into it, and edits discarded with the buffer must not linger in the index.
     */
    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        openFiles.remove(uri)
        logger.debug("didClose: $uri")
        writes.submit("didClose $uri") {
            val buffer = index.getDocumentContent(uri)
            index.removeDocumentContent(uri)
            publishDiagnostics(uri, emptyList())
            reindexFromDisk(uri, buffer)
        }
    }

    /** Re-reads the closed file from disk unless the buffer already matched it; a file that was never saved leaves the index. */
    private fun reindexFromDisk(uri: String, buffer: String?) {
        val file = try { File(URI(uri)) } catch (_: Exception) { null }
        if (file == null || !file.isFile) {
            if (index.removeFile(uri)) logger.debug("Removed from index (closed without a file on disk): $uri")
            return
        }
        val text = file.readText(Charsets.UTF_8)
        if (text == buffer) return
        val parsed = parser.parse(uri, text)
        if (parsed != null) index.indexFile(parsed) else index.removeFile(uri)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        logger.debug("didSave: ${params.textDocument.uri}")
    }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> =
        writes.request("definition", params.at, { Either.forLeft(mutableListOf()) }) {
            Either.forLeft(definitionProvider.findDefinition(params.textDocument.uri, params.position).toMutableList())
        }

    override fun hover(params: HoverParams): CompletableFuture<Hover?> =
        writes.request("hover", params.at, { null }) {
            hoverProvider.provideHover(params.textDocument.uri, params.position)
        }

    override fun references(params: ReferenceParams): CompletableFuture<MutableList<out Location>> =
        writes.request("references", params.at, { mutableListOf() }) { checker ->
            referenceProvider.findReferences(params.textDocument.uri, params.position, params.context.isIncludeDeclaration, checker).toMutableList()
        }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<MutableList<Either<SymbolInformation, DocumentSymbol>>> =
        writes.request("documentSymbol", params.textDocument.uri, { mutableListOf() }) {
            val file = index.findFileByUri(params.textDocument.uri) ?: return@request mutableListOf()
            documentSymbolProvider.provideDocumentSymbols(file).map { Either.forRight<SymbolInformation, DocumentSymbol>(it) }.toMutableList()
        }

    override fun prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture<MutableList<CallHierarchyItem>> =
        writes.request("prepareCallHierarchy", params.at, { mutableListOf() }) {
            callHierarchyProvider.prepare(params.textDocument.uri, params.position).toMutableList()
        }

    override fun callHierarchyIncomingCalls(params: CallHierarchyIncomingCallsParams): CompletableFuture<MutableList<CallHierarchyIncomingCall>> =
        writes.request("callHierarchyIncomingCalls", params.item.name, { mutableListOf() }) {
            callHierarchyProvider.incomingCalls(params.item).toMutableList()
        }

    override fun callHierarchyOutgoingCalls(params: CallHierarchyOutgoingCallsParams): CompletableFuture<MutableList<CallHierarchyOutgoingCall>> =
        writes.request("callHierarchyOutgoingCalls", params.item.name, { mutableListOf() }) {
            callHierarchyProvider.outgoingCalls(params.item).toMutableList()
        }

    override fun prepareTypeHierarchy(params: TypeHierarchyPrepareParams): CompletableFuture<MutableList<TypeHierarchyItem>> =
        writes.request("prepareTypeHierarchy", params.at, { mutableListOf() }) {
            typeHierarchyProvider.prepare(params.textDocument.uri, params.position).toMutableList()
        }

    override fun typeHierarchySupertypes(params: TypeHierarchySupertypesParams): CompletableFuture<MutableList<TypeHierarchyItem>> =
        writes.request("typeHierarchySupertypes", params.item.name, { mutableListOf() }) {
            typeHierarchyProvider.supertypes(params.item).toMutableList()
        }

    override fun typeHierarchySubtypes(params: TypeHierarchySubtypesParams): CompletableFuture<MutableList<TypeHierarchyItem>> =
        writes.request("typeHierarchySubtypes", params.item.name, { mutableListOf() }) {
            typeHierarchyProvider.subtypes(params.item).toMutableList()
        }

    override fun codeLens(params: CodeLensParams): CompletableFuture<MutableList<out CodeLens>> =
        writes.request("codeLens", params.textDocument.uri, { mutableListOf() }) {
            codeLensProvider.provideCodeLenses(params.textDocument.uri).toMutableList()
        }

    override fun resolveCodeLens(params: CodeLens): CompletableFuture<CodeLens> =
        writes.request("resolveCodeLens", null, { params }) {
            codeLensProvider.resolveCodeLens(params)
        }

    override fun completion(params: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> =
        writes.request("completion", params.at, { Either.forRight(CompletionList(false, emptyList())) }) {
            val uri = params.textDocument.uri
            val lineText = index.getLineContent(uri, params.position.line) ?: ""
            Either.forRight(completionProvider.provideCompletions(uri, params.position, lineText))
        }

    override fun prepareRename(params: PrepareRenameParams): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> =
        writes.request("prepareRename", params.at, { throw responseError(ResponseErrorCode.InternalError, "Rename preparation failed") }) {
            val result = renameProvider.prepareRename(params.textDocument.uri, params.position)
                ?: throw responseError(ResponseErrorCode.InvalidRequest, "Cannot rename this symbol")
            if (result.isLeft) Either3.forFirst(result.left) else Either3.forSecond(result.right)
        }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit?> =
        writes.request("rename", "${params.at} -> ${params.newName}", { null }) {
            renameProvider.rename(params.textDocument.uri, params.position, params.newName)
        }
}
