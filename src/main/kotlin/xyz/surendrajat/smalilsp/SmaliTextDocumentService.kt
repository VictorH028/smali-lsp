package xyz.surendrajat.smalilsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
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
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.providers.RenameProvider
import xyz.surendrajat.smalilsp.providers.TypeHierarchyProvider
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

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
    private val renameProvider: RenameProvider
) : TextDocumentService {

    private val logger = LoggerFactory.getLogger(SmaliTextDocumentService::class.java)
    private val parser = SmaliParser()
    private val diagnosticProvider = DiagnosticProvider(parser, index)
    private var client: LanguageClient? = null

    /** Open file URIs, so diagnostics can be refreshed once workspace indexing completes. */
    private val openFiles = ConcurrentHashMap.newKeySet<String>()

    /** Semantic diagnostics are suppressed until workspace indexing has completed. */
    @Volatile
    var indexingComplete: Boolean = false

    fun connect(client: LanguageClient) {
        this.client = client
    }

    /**
     * Answers a request on the common pool. A failing provider is logged and answered with
     * [fallback], so one bad request never takes the session down; a [ResponseErrorException]
     * thrown by [compute] or [fallback] reaches the client as that error.
     */
    private fun <T> request(name: String, context: String?, fallback: () -> T, compute: (CancelChecker) -> T): CompletableFuture<T> {
        logger.debug("{}: {}", name, context)
        return CompletableFutures.computeAsync { checker ->
            try {
                compute(checker)
            } catch (e: ResponseErrorException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Error in $name${context?.let { ": $it" } ?: ""}", e)
                fallback()
            }
        }
    }

    private val TextDocumentPositionParams.at: String
        get() = "${textDocument.uri} at ${position.line}:${position.character}"

    private fun responseError(code: ResponseErrorCode, message: String) =
        ResponseErrorException(ResponseError(code, message, null))

    /** Re-publish diagnostics for every open file, clearing warnings that were only stale during indexing. */
    fun refreshDiagnosticsForOpenFiles() {
        logger.info("Refreshing diagnostics for ${openFiles.size} open file(s)")
        for (uri in openFiles) {
            try {
                val content = index.getDocumentContent(uri) ?: continue
                publishDiagnostics(uri, diagnosticProvider.computeDiagnostics(uri, content))
            } catch (e: Exception) {
                logger.error("Error refreshing diagnostics for $uri", e)
            }
        }
    }

    private fun publishDiagnostics(uri: String, diagnostics: List<Diagnostic>) {
        client?.publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics))
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = params.textDocument.uri
        val content = params.textDocument.text

        logger.debug("didOpen: $uri")
        openFiles.add(uri)
        index.setDocumentContent(uri, content)

        try {
            val parseResult = parser.parseWithErrors(uri, content)

            // Index unless the workspace scan already did
            val existing = index.findFileByUri(uri)
            if (existing == null) {
                parseResult.smaliFile?.let { smaliFile ->
                    index.indexFile(smaliFile)
                    logger.debug("Indexed: $uri (${smaliFile.classDefinition.name})")
                } ?: logger.warn("Failed to parse: $uri")
            } else {
                logger.debug("Already indexed: $uri")
            }

            val diagnostics = if (indexingComplete) {
                diagnosticProvider.computeDiagnosticsFromParseResult(uri, parseResult)
            } else {
                diagnosticProvider.computeSyntaxDiagnosticsFromParseResult(parseResult)
            }
            publishDiagnostics(uri, diagnostics)
        } catch (e: Exception) {
            logger.error("Error processing didOpen for $uri", e)
        }
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = params.textDocument.uri
        val changes = params.contentChanges

        if (changes.isEmpty()) return

        // Full document sync (TextDocumentSyncKind.Full)
        val content = changes[0].text

        logger.debug("didChange: $uri")
        index.setDocumentContent(uri, content)

        try {
            val parseResult = parser.parseWithErrors(uri, content)
            parseResult.smaliFile?.let { smaliFile ->
                index.indexFile(smaliFile)
                logger.debug("Re-indexed: $uri")
            } ?: logger.warn("Failed to re-parse: $uri")

            // Semantic diagnostics stay suppressed during indexing, as in didOpen; otherwise
            // one keystroke mid-indexing floods the file with undefined-class warnings.
            val diagnostics = if (indexingComplete) {
                diagnosticProvider.computeDiagnosticsFromParseResult(uri, parseResult)
            } else {
                diagnosticProvider.computeSyntaxDiagnosticsFromParseResult(parseResult)
            }
            publishDiagnostics(uri, diagnostics)
        } catch (e: Exception) {
            logger.error("Error processing didChange for $uri", e)
        }
    }

    /** The file stays indexed: definitions and references from other files must keep resolving into it. */
    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        openFiles.remove(uri)
        index.removeDocumentContent(uri)
        logger.debug("didClose: $uri (keeping in index)")
        publishDiagnostics(uri, emptyList())
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        logger.debug("didSave: ${params.textDocument.uri}")
    }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> =
        request("definition", params.at, { Either.forLeft(mutableListOf()) }) {
            Either.forLeft(definitionProvider.findDefinition(params.textDocument.uri, params.position).toMutableList())
        }

    override fun hover(params: HoverParams): CompletableFuture<Hover?> =
        request("hover", params.at, { null }) {
            hoverProvider.provideHover(params.textDocument.uri, params.position)
        }

    override fun references(params: ReferenceParams): CompletableFuture<MutableList<out Location>> =
        request("references", params.at, { mutableListOf() }) {
            referenceProvider.findReferences(params.textDocument.uri, params.position, params.context.isIncludeDeclaration).toMutableList()
        }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<MutableList<Either<SymbolInformation, DocumentSymbol>>> =
        request("documentSymbol", params.textDocument.uri, { mutableListOf() }) {
            val file = index.findFileByUri(params.textDocument.uri) ?: return@request mutableListOf()

            val classSymbol = DocumentSymbol(
                file.classDefinition.name,
                SymbolKind.Class,
                file.classDefinition.range,
                file.classDefinition.range
            )
            classSymbol.children = mutableListOf()
            file.methods.forEach { method ->
                classSymbol.children.add(DocumentSymbol("${method.name}${method.descriptor}", SymbolKind.Method, method.range, method.range))
            }
            file.fields.forEach { field ->
                classSymbol.children.add(DocumentSymbol("${field.name}: ${field.type}", SymbolKind.Field, field.range, field.range))
            }
            mutableListOf(Either.forRight(classSymbol))
        }

    override fun prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture<MutableList<CallHierarchyItem>> =
        request("prepareCallHierarchy", params.at, { mutableListOf() }) {
            callHierarchyProvider.prepare(params.textDocument.uri, params.position).toMutableList()
        }

    override fun callHierarchyIncomingCalls(params: CallHierarchyIncomingCallsParams): CompletableFuture<MutableList<CallHierarchyIncomingCall>> =
        request("callHierarchyIncomingCalls", params.item.name, { mutableListOf() }) {
            callHierarchyProvider.incomingCalls(params.item).toMutableList()
        }

    override fun callHierarchyOutgoingCalls(params: CallHierarchyOutgoingCallsParams): CompletableFuture<MutableList<CallHierarchyOutgoingCall>> =
        request("callHierarchyOutgoingCalls", params.item.name, { mutableListOf() }) {
            callHierarchyProvider.outgoingCalls(params.item).toMutableList()
        }

    override fun prepareTypeHierarchy(params: TypeHierarchyPrepareParams): CompletableFuture<MutableList<TypeHierarchyItem>> =
        request("prepareTypeHierarchy", params.at, { mutableListOf() }) {
            typeHierarchyProvider.prepare(params.textDocument.uri, params.position).toMutableList()
        }

    override fun typeHierarchySupertypes(params: TypeHierarchySupertypesParams): CompletableFuture<MutableList<TypeHierarchyItem>> =
        request("typeHierarchySupertypes", params.item.name, { mutableListOf() }) {
            typeHierarchyProvider.supertypes(params.item).toMutableList()
        }

    override fun typeHierarchySubtypes(params: TypeHierarchySubtypesParams): CompletableFuture<MutableList<TypeHierarchyItem>> =
        request("typeHierarchySubtypes", params.item.name, { mutableListOf() }) {
            typeHierarchyProvider.subtypes(params.item).toMutableList()
        }

    override fun codeLens(params: CodeLensParams): CompletableFuture<MutableList<out CodeLens>> =
        request("codeLens", params.textDocument.uri, { mutableListOf() }) {
            codeLensProvider.provideCodeLenses(params.textDocument.uri).toMutableList()
        }

    override fun resolveCodeLens(params: CodeLens): CompletableFuture<CodeLens> =
        request("resolveCodeLens", null, { params }) {
            codeLensProvider.resolveCodeLens(params)
        }

    override fun completion(params: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> =
        request("completion", params.at, { Either.forRight(CompletionList(false, emptyList())) }) {
            val uri = params.textDocument.uri
            val lineText = index.getLineContent(uri, params.position.line) ?: ""
            Either.forRight(completionProvider.provideCompletions(uri, params.position, lineText))
        }

    override fun prepareRename(params: PrepareRenameParams): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> =
        request("prepareRename", params.at, { throw responseError(ResponseErrorCode.InternalError, "Rename preparation failed") }) {
            val result = renameProvider.prepareRename(params.textDocument.uri, params.position)
                ?: throw responseError(ResponseErrorCode.InvalidRequest, "Cannot rename this symbol")
            if (result.isLeft) Either3.forFirst(result.left) else Either3.forSecond(result.right)
        }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit?> =
        request("rename", "${params.at} -> ${params.newName}", { null }) {
            renameProvider.rename(params.textDocument.uri, params.position, params.newName)
        }
}
