package xyz.surendrajat.smalilsp.cli

import com.google.gson.Gson
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TypeHierarchyItem
import org.slf4j.LoggerFactory
import xyz.surendrajat.smalilsp.index.ResourceTable
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.JvmTuning
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import xyz.surendrajat.smalilsp.loadVersionInfo
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.CallHierarchyProvider
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.DiagnosticProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.providers.TypeHierarchyProvider
import xyz.surendrajat.smalilsp.providers.WorkspaceSymbolProvider
import xyz.surendrajat.smalilsp.util.Types
import java.io.File
import java.net.URI

/**
 * MCP server over stdio: the same index and providers as the LSP, exposed as tools for AI
 * agents. `smali_index` builds a [Session] for a directory; every other tool reads the
 * current session. A re-index drops the old session before scanning, so two indexes of a
 * large app are never in memory at once, and swaps the new one in whole.
 */
class McpMode {

    private val logger = LoggerFactory.getLogger(McpMode::class.java)

    /** JSON-RPC "invalid params": the code the protocol gives an unknown tool. */
    private val INVALID_PARAMS = -32602
    private val gson = Gson()
    private val versionInfo = loadVersionInfo()

    /** Everything that belongs to one indexed directory. */
    private class Session(val directory: String, val index: WorkspaceIndex) {
        val hover = HoverProvider(index)
        val definitions = DefinitionProvider(index)
        val references = ReferenceProvider(index)
        val diagnostics = DiagnosticProvider(SmaliParser(index.strings), index)
        val symbols = WorkspaceSymbolProvider(index)
        val callHierarchy = CallHierarchyProvider(index)
        val typeHierarchy = TypeHierarchyProvider(index)
    }

    @Volatile
    private var session: Session? = null

    /** Tool calls run concurrently; only one index can be under construction at a time. */
    private val indexing = Mutex()

    fun run() {
        // kotlin-logging (transitive via the MCP SDK) prints a banner to STDOUT on first use,
        // which corrupts the protocol stream; it must be off before any SDK class initializes.
        System.setProperty("kotlin-logging.logStartupMessage", "false")

        val server = Server(
            Implementation(name = "smali-lsp", version = versionInfo.version),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)))
        )
        registerTools(server)

        val transport = StdioServerTransport(System.`in`.asSource().buffered(), System.out.asSink().buffered()) {}
        runBlocking {
            val mcpSession = server.createSession(transport)
            answerUnknownToolsWithProtocolError(mcpSession)
            logger.info("MCP server started")
            val done = Job()
            mcpSession.onClose { done.complete() }
            done.join()
        }
    }

    /**
     * The SDK answers a call to a tool that does not exist with a tool result marked as an
     * error, as if the tool had run and failed. The protocol says an unknown tool is a
     * JSON-RPC error (invalid params, -32602), which is what clients check for, so the
     * session's `tools/call` handler is replaced by one that raises it and otherwise runs the
     * registered tool.
     */
    private fun answerUnknownToolsWithProtocolError(mcpSession: ServerSession) {
        mcpSession.setRequestHandler<CallToolRequest>(Method.Defined.ToolsCall) { request, _ ->
            val run = handlers[request.name] ?: throw McpException(INVALID_PARAMS, "Unknown tool: ${request.name}")
            run(request)
        }
    }

    /** The registered tools by name, as [tool] wraps them; the session's `tools/call` dispatches through this map. */
    private val handlers = LinkedHashMap<String, suspend (CallToolRequest) -> CallToolResult>()

    // ======================================================================
    // Tool registration
    // ======================================================================

    private class Arg(val name: String, val description: String, val type: String = "string", val required: Boolean = true)

    /** A problem the agent can act on; its message becomes the tool error. */
    private open class ToolError(message: String) : Exception(message)
    private class MissingArgument(name: String) : ToolError("Missing required argument: '$name'")

    private class Args(private val json: JsonObject?) {
        private fun present(name: String): JsonElement? = json?.get(name)?.takeUnless { it is JsonNull }
        fun string(name: String): String? = present(name)?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw ToolError("'$name' must be a string") }
        fun int(name: String): Int? = present(name)?.let { (it as? JsonPrimitive)?.intOrNull ?: throw ToolError("'$name' must be a number") }
        fun require(name: String): String = string(name) ?: throw MissingArgument(name)
        /** `max_results`, defaulting to [default] and never above [cap], so one answer cannot flood an agent's context. */
        fun maxResults(default: Int, cap: Int = 5000): Int = (int("max_results") ?: default).coerceIn(1, cap)
    }

    /**
     * Declares a tool once: its schema comes from [args], a missing session answers with the
     * standard error unless [needsSession] is false, and a [ToolError] or any other failure
     * becomes a tool error instead of a dead JSON-RPC call.
     */
    private fun Server.tool(
        name: String,
        description: String,
        vararg args: Arg,
        needsSession: Boolean = true,
        handler: suspend (Args, Session?) -> CallToolResult
    ) {
        val schema = ToolSchema(
            properties = buildJsonObject {
                for (arg in args) putJsonObject(arg.name) {
                    put("type", arg.type)
                    put("description", arg.description)
                }
            },
            required = args.filter { it.required }.map { it.name }
        )
        val run: suspend (CallToolRequest) -> CallToolResult = { request ->
            JvmTuning.noteActivity()
            val current = session
            if (needsSession && current == null) {
                toolError("No index loaded. Call smali_index first.")
            } else {
                try {
                    handler(Args(request.arguments), current)
                } catch (e: ToolError) {
                    toolError(e.message ?: name)
                } catch (e: Exception) {
                    logger.error("$name failed", e)
                    toolError("$name failed: ${e.message}")
                }
            }
        }
        handlers[name] = run
        addTool(name = name, description = description, inputSchema = schema) { request -> run(request) }
    }

    private val uriArg = Arg("uri", "File URI (e.g., 'file:///path/to/file.smali')")
    private val positionArgs = arrayOf(
        Arg("uri", "File URI (e.g., 'file:///path/to/file.smali'); with line and character, selects a symbol by position", required = false),
        Arg("line", "Line number (0-indexed)", type = "number", required = false),
        Arg("character", "Character position (0-indexed)", type = "number", required = false),
    )
    private val symbolArgs = arrayOf(
        Arg("class_name", "Fully qualified class name (e.g., 'Lcom/example/Foo;'); selects a symbol by name instead of position", required = false),
        Arg("member_name", "Method or field name within class_name", required = false),
        Arg("descriptor", "Method descriptor (e.g., '(I)V'); required for methods of classes outside the workspace", required = false),
        Arg("field_type", "Field type (e.g., 'I'); required for fields of classes outside the workspace", required = false),
    )

    private fun registerTools(server: Server) {
        server.tool(
            "smali_index",
            "Index smali files in an APKTool-decompiled directory. Call this first before using any other tool. " +
                "Keeps the index in memory for fast subsequent queries (<1s).",
            Arg("directory", "Path to APKTool project directory containing smali files"),
            needsSession = false
        ) { args, _ ->
            val directory = args.require("directory")
            val dir = File(directory)
            if (!dir.exists() || !dir.isDirectory) throw ToolError("Directory not found: $directory")
            if (!indexing.tryLock()) throw ToolError("An index is already being built; wait for it to finish")

            logger.info("Indexing: ${dir.absolutePath}")
            val startTime = System.currentTimeMillis()
            val (index, result) = try {
                // Drop the old session before building the new one: for a large app the two
                // indexes would not fit in the heap together. No System.gc(): forcing a full
                // collection before a long scan only pegs every core with GC threads.
                session = null
                val index = WorkspaceIndex()
                val result = WorkspaceScanner(index).scanDirectory(dir) { _, _ -> }
                index.trimUsages()
                index.resources.load(ResourceTable.publicXml(dir))
                session = Session(dir.absolutePath, index)
                index to result
            } finally {
                indexing.unlock()
            }

            val duration = System.currentTimeMillis() - startTime
            val stats = index.getStats()
            val failedMsg = if (result.filesFailed > 0) "Failed: ${result.filesFailed}\n" else ""
            toolResult(
                "Successfully indexed $directory\n" +
                    "Files: ${result.filesSucceeded}/${result.filesProcessed}\n" +
                    failedMsg +
                    "Time: ${duration}ms\n" +
                    "Classes: ${stats.classes}, Methods: ${stats.methods}, Fields: ${stats.fields}, Strings: ${stats.strings}\n\n" +
                    "Index is now in memory. Subsequent queries will be fast (<1s)."
            )
        }

        server.tool(
            "smali_find_definition",
            "Find a definition. Pass 'symbol' (a class by fully qualified or simple name) for class info; or a position " +
                "(uri, line, character) for the declaration of whatever is there; or class_name with member_name " +
                "(plus descriptor / field_type to disambiguate) for a method or field declaration.",
            Arg("symbol", "Class name — fully qualified (e.g., 'Lcom/example/MainActivity;') or simple (e.g., 'MainActivity')", required = false),
            *positionArgs, *symbolArgs
        ) { args, s ->
            val index = s!!.index
            val symbol = args.string("symbol")
            when {
                symbol != null -> {
                    val matches = index.findClass(symbol)?.let { listOf(it) } ?: index.findClassesBySimpleName(symbol)
                    val results = matches.map { file ->
                        mapOf(
                            "type" to "class",
                            "name" to file.classDefinition.name,
                            "uri" to file.uri,
                            "superClass" to file.classDefinition.superClass,
                            "interfaces" to file.classDefinition.interfaces,
                            "methods" to file.methods.size,
                            "fields" to file.fields.size
                        )
                    }
                    json(mapOf("symbol" to symbol, "results" to results, "count" to results.size))
                }
                args.string("class_name") != null -> {
                    val className = args.require("class_name")
                    val member = args.string("member_name")
                    val locations = if (member == null) {
                        listOfNotNull(index.findClass(className)?.let { Location(it.uri, it.classDefinition.nameSpan.toRange()) })
                    } else {
                        membersByName(s, args, className, member).flatMap {
                            when (it) {
                                is Member.Method -> index.findMethod(it.className, it.name, it.descriptor)
                                is Member.Field -> listOfNotNull(index.findField(it.className, it.name, it.type))
                            }
                        }
                    }
                    json(mapOf("class_name" to className, "member_name" to member, "results" to locations.map { location(it) }, "count" to locations.size))
                }
                args.string("uri") == null -> throw ToolError("Missing arguments: pass 'symbol', or 'uri' with 'line' and 'character', or 'class_name' (with 'member_name' for a method or field)")
                else -> {
                    val (uri, position) = position(args)
                    val locations = s.definitions.findDefinition(uri, position)
                    json(mapOf("uri" to uri, "results" to locations.map { location(it) }, "count" to locations.size))
                }
            }
        }

        server.tool(
            "smali_search_symbols",
            "Search for classes, methods, or fields by name pattern. Returns the best 500 matches.",
            Arg("pattern", "Search pattern (case-insensitive substring match)")
        ) { args, s ->
            val pattern = args.require("pattern")
            val results = s!!.symbols.search(pattern).map { symbol ->
                mapOf(
                    "type" to when (symbol.kind) {
                        SymbolKind.Class -> "class"
                        SymbolKind.Method -> "method"
                        SymbolKind.Field -> "field"
                        else -> "unknown"
                    },
                    "name" to if (symbol.kind == SymbolKind.Class) symbol.name.removePrefix("L").removeSuffix(";").substringAfterLast('/') else symbol.name.substringAfterLast('.'),
                    "fullName" to symbol.name,
                    "containerName" to symbol.containerName,
                    "uri" to symbol.location.uri
                )
            }
            json(mapOf("pattern" to pattern, "results" to results, "count" to results.size))
        }

        server.tool(
            "smali_get_stats",
            "Get statistics about the indexed codebase: total classes, methods, fields."
        ) { _, s ->
            val stats = s!!.index.getStats()
            json(mapOf(
                "indexedDirectory" to s.directory,
                "classes" to stats.classes,
                "methods" to stats.methods,
                "fields" to stats.fields,
                "strings" to stats.strings
            ))
        }

        server.tool(
            "smali_find_references",
            "Find all references to a symbol, given either a position in a file (uri, line, character) " +
                "or a name (class_name alone for a class; class_name + member_name for a method or field). " +
                "'total' is the full count; 'results' holds at most max_results of them.",
            *positionArgs, *symbolArgs,
            Arg("max_results", "Most locations to return (default 500, at most 5000)", type = "number", required = false)
        ) { args, s ->
            val references: List<Location>
            val subject: Map<String, Any?>
            if (args.string("class_name") != null) {
                references = referencesByName(s!!, args)
                subject = mapOf("class_name" to args.string("class_name"), "member_name" to args.string("member_name"))
            } else {
                val (uri, position) = position(args)
                references = s!!.references.findReferences(uri, position, true)
                subject = mapOf("uri" to uri)
            }
            val shown = references.take(args.maxResults(default = 500))
            json(subject + mapOf("results" to shown.map { location(it) }, "count" to shown.size, "total" to references.size))
        }

        server.tool(
            "smali_hover",
            "Get hover information (type info, documentation) for a symbol, given either a position in a file " +
                "(uri, line, character) or a name (class_name alone for a class; class_name + member_name for a method or field).",
            *positionArgs, *symbolArgs
        ) { args, s ->
            val hover: Hover? = if (args.string("class_name") != null) {
                hoverByName(s!!, args)
            } else {
                val (uri, position) = position(args)
                s!!.hover.provideHover(uri, position)
            }
            if (hover == null) json(mapOf("hover" to null))
            else json(mapOf("contents" to (hover.contents?.right?.value ?: ""), "range" to hover.range?.let { range(it) }))
        }

        server.tool(
            "smali_diagnostics",
            "Get diagnostics (errors, warnings) for a smali file. Returns issues like undefined classes, invalid references.",
            uriArg, Arg("content", "File content to analyze")
        ) { args, s ->
            val uri = args.require("uri")
            val content = args.require("content")
            val results = s!!.diagnostics.computeDiagnostics(uri, content).map { diagnostic ->
                mapOf("range" to range(diagnostic.range), "severity" to diagnostic.severity.value, "message" to diagnostic.message.let { if (it.isLeft) it.left else it.right.value })
            }
            json(mapOf("diagnostics" to results, "count" to results.size))
        }

        server.tool(
            "smali_document_symbols",
            "Get document outline/symbols for a smali file. Returns class, methods, fields.",
            uriArg
        ) { args, s ->
            val uri = args.require("uri")
            val file = s!!.index.findFileByUri(uri) ?: throw ToolError("File not found in index: $uri")
            val className = file.classDefinition.name
            val symbols = mutableListOf<Map<String, Any?>>()
            symbols.add(mapOf(
                "kind" to "class",
                "name" to className,
                "superClass" to file.classDefinition.superClass,
                "interfaces" to file.classDefinition.interfaces,
                "range" to range(file.classDefinition.range)
            ))
            for (method in file.methods) {
                symbols.add(mapOf("kind" to "method", "name" to method.name, "descriptor" to method.descriptor, "className" to className, "range" to range(method.range)))
            }
            for (field in file.fields) {
                symbols.add(mapOf("kind" to "field", "name" to field.name, "type" to field.type, "className" to className, "range" to range(field.range)))
            }
            json(mapOf("uri" to uri, "symbols" to symbols, "count" to symbols.size))
        }

        server.tool(
            "smali_search_strings",
            "Search for string literals (const-string instructions) across all indexed smali files. " +
                "Useful for finding URLs, API keys, error messages, hardcoded paths, etc.",
            Arg("query", "Substring to search for in string literals (case-insensitive)"),
            Arg("max_results", "Maximum results to return (default 100, at most 5000)", type = "number", required = false)
        ) { args, s ->
            val query = args.require("query")
            val results = s!!.index.searchStrings(query, args.maxResults(default = 100)).map { result ->
                mapOf(
                    "value" to result.value,
                    "uri" to result.location.uri,
                    "file" to result.location.uri.substringAfterLast('/'),
                    "line" to (result.location.range.start.line + 1)
                )
            }
            json(mapOf("query" to query, "results" to results, "count" to results.size))
        }

        server.tool(
            "smali_call_graph",
            "Get call hierarchy (callers and callees) for a method. " +
                "Specify a class and method to find who calls it (incoming) and what it calls (outgoing).",
            Arg("class_name", "Fully qualified class name (e.g., 'Lcom/example/MyClass;')"),
            Arg("method_name", "Method name (e.g., 'onCreate')"),
            Arg("descriptor", "Method descriptor (e.g., '(Landroid/os/Bundle;)V'). If omitted, matches first method with given name.", required = false),
            Arg("direction", "Direction: 'incoming' (callers), 'outgoing' (callees), or 'both' (default: 'both')", required = false)
        ) { args, s ->
            val className = args.require("class_name")
            val methodName = args.require("method_name")
            val descriptor = args.string("descriptor")
            val direction = args.string("direction") ?: "both"
            if (direction !in listOf("incoming", "outgoing", "both")) {
                throw ToolError("Invalid direction: '$direction'. Must be 'incoming', 'outgoing', or 'both'.")
            }
            val classFile = s!!.index.findClass(className) ?: throw ToolError("Class not found: $className")
            val method = classFile.methods.find { it.name == methodName && (descriptor == null || it.descriptor == descriptor) }
                ?: throw ToolError("Method not found: $className->$methodName${descriptor ?: ""}")

            val item = CallHierarchyItem().apply {
                name = "${method.name}${method.descriptor}"
                kind = SymbolKind.Method
                uri = classFile.uri
                range = method.range
                selectionRange = method.nameSpan.toRange()
                detail = className
            }
            val result = mutableMapOf<String, Any>("class" to className, "method" to "${method.name}${method.descriptor}")
            if (direction != "outgoing") {
                val incoming = s.callHierarchy.incomingCalls(item)
                result["incoming_calls"] = incoming.map { call ->
                    mapOf("from_class" to (call.from.detail ?: "unknown"), "from_method" to call.from.name, "uri" to call.from.uri, "call_sites" to call.fromRanges.size)
                }
                result["incoming_count"] = incoming.size
                result["_note_incoming"] = "incoming_count = unique calling methods"
            }
            if (direction != "incoming") {
                val outgoing = s.callHierarchy.outgoingCalls(item)
                result["outgoing_calls"] = outgoing.map { call ->
                    mapOf("to_class" to (call.to.detail ?: "unknown"), "to_method" to call.to.name, "uri" to call.to.uri, "call_sites" to call.fromRanges.size)
                }
                result["outgoing_count"] = outgoing.size
                result["_note_outgoing"] = "outgoing_count = unique called methods"
            }
            json(result)
        }

        server.tool(
            "smali_xref_summary",
            "Get a cross-reference summary for a class: who extends it, who implements it, " +
                "who calls its methods, who accesses its fields, and which classes reference it as a type.",
            Arg("class_name", "Fully qualified class name (e.g., 'Lcom/example/MyClass;')")
        ) { args, s ->
            val className = args.require("class_name")
            val index = s!!.index
            val classFile = index.findClass(className)
            val subclasses = index.getDirectSubclasses(className).toList()
            val implementors = index.getDirectImplementors(className).toList()

            fun classesAt(locations: List<Location>): Set<String> = locations.mapNotNullTo(LinkedHashSet()) { index.findClassNameByUri(it.uri) }

            val methodCallers = (classFile?.methods ?: emptyList()).associate { method ->
                "${method.name}${method.descriptor}" to classesAt(index.findMethodUsages(className, method.name, method.descriptor))
            }.filterValues { it.isNotEmpty() }
            val fieldAccessors = (classFile?.fields ?: emptyList()).associate { field ->
                field.name to classesAt(index.findFieldUsages(className, field.name, field.type))
            }.filterValues { it.isNotEmpty() }
            val typeReferences = classesAt(index.findClassRefLocations(className))

            json(mapOf(
                "class" to className,
                "defined" to (classFile != null),
                "subclasses" to subclasses,
                "implementors" to implementors,
                "method_callers" to methodCallers.map { (method, callers) -> mapOf("method" to method, "callers" to callers.toList(), "caller_class_count" to callers.size) },
                "field_accessors" to fieldAccessors.map { (field, accessors) -> mapOf("field" to field, "accessors" to accessors.toList(), "accessor_class_count" to accessors.size) },
                "type_references" to typeReferences.toList(),
                "summary" to mapOf(
                    "subclass_count" to subclasses.size,
                    "implementor_count" to implementors.size,
                    "method_caller_count" to methodCallers.values.sumOf { it.size },
                    "field_accessor_count" to fieldAccessors.values.sumOf { it.size },
                    "type_reference_count" to typeReferences.size
                )
            ))
        }

        server.tool(
            "smali_read_method",
            "The source text of one method, with 1-based line numbers, so a method can be read without loading its whole file.",
            Arg("class_name", "Fully qualified class name (e.g., 'Lcom/example/MyClass;')"),
            Arg("method_name", "Method name (e.g., 'onCreate')"),
            Arg("descriptor", "Method descriptor (e.g., '(Landroid/os/Bundle;)V'). If omitted, the first method with that name.", required = false)
        ) { args, s ->
            val className = args.require("class_name")
            val methodName = args.require("method_name")
            val descriptor = args.string("descriptor")
            val classFile = s!!.index.findClass(className) ?: throw ToolError("Class not found: $className")
            val method = classFile.methods.find { it.name == methodName && (descriptor == null || it.descriptor == descriptor) }
                ?: throw ToolError("Method not found: $className->$methodName${descriptor ?: ""}")
            val lines = try {
                File(URI(classFile.uri)).readLines()
            } catch (e: Exception) {
                throw ToolError("Cannot read ${classFile.uri}: ${e.message}")
            }
            val first = method.range.start.line
            val last = minOf(method.range.end.line, lines.size - 1)
            val source = (first..last).joinToString("\n") { i -> "${i + 1}: ${lines[i]}" }
            json(mapOf("class" to className, "method" to "${method.name}${method.descriptor}", "uri" to classFile.uri, "start_line" to first + 1, "end_line" to last + 1, "source" to source))
        }

        server.tool(
            "smali_list_package",
            "The classes directly or transitively under a package, and its immediate sub-packages.",
            Arg("package", "Package prefix: 'Lcom/example/', 'com/example' or 'com.example'"),
            Arg("max_results", "Most classes to return (default 500, at most 5000)", type = "number", required = false)
        ) { args, s ->
            val raw = args.require("package").trim().removePrefix("L").replace('.', '/').trimEnd('/')
            val prefix = if (raw.isEmpty()) "L" else "L$raw/"
            val classes = s!!.index.getClassNames().filter { it.startsWith(prefix) }.sorted()
            val subpackages = classes.mapNotNullTo(sortedSetOf()) { name ->
                val rest = name.removePrefix(prefix)
                rest.substringBefore('/', "").takeIf { it.isNotEmpty() && rest.contains('/') }
            }
            val shown = classes.take(args.maxResults(default = 500))
            json(mapOf(
                "package" to prefix,
                "classes" to shown.map { mapOf("name" to it, "uri" to s.index.getUri(it)) },
                "count" to shown.size,
                "total" to classes.size,
                "subpackages" to subpackages.toList()
            ))
        }

        server.tool(
            "smali_type_hierarchy",
            "Get the type hierarchy for a class: supertypes (parent class + interfaces) and subtypes " +
                "(direct subclasses + implementors). Useful for understanding inheritance chains.",
            Arg("class_name", "Fully qualified class name (e.g., 'Lcom/example/MyClass;')"),
            Arg("direction", "Direction: 'supertypes', 'subtypes', or 'both' (default: 'both')", required = false)
        ) { args, s ->
            val className = args.require("class_name")
            val direction = args.string("direction") ?: "both"
            if (direction !in listOf("supertypes", "subtypes", "both")) {
                throw ToolError("Invalid direction: '$direction'. Must be 'supertypes', 'subtypes', or 'both'.")
            }
            val classFile = s!!.index.findClass(className)
                ?: return@tool json(mapOf("class" to className, "error" to "Class not found in index"))
            val classDef = classFile.classDefinition
            val item = TypeHierarchyItem(Types.simpleName(className), SymbolKind.Class, classFile.uri, classDef.range, classDef.nameSpan.toRange(), className)

            val result = mutableMapOf<String, Any>("class" to className)
            fun items(list: List<TypeHierarchyItem>) = list.map { mapOf("name" to it.name, "class" to (it.detail ?: ""), "kind" to it.kind.toString()) }
            if (direction != "subtypes") result["supertypes"] = items(s.typeHierarchy.supertypes(item))
            if (direction != "supertypes") result["subtypes"] = items(s.typeHierarchy.subtypes(item))
            json(result)
        }
    }

    // ======================================================================
    // Symbol resolution shared by find_references and hover
    // ======================================================================

    /** A member named by `class_name` + `member_name`: declared in the class, or pinned by `descriptor` / `field_type` when the class is outside the workspace. */
    private sealed class Member(val className: String, val name: String, val declared: Boolean) {
        class Method(className: String, name: String, val descriptor: String, declared: Boolean) : Member(className, name, declared)
        class Field(className: String, name: String, val type: String, declared: Boolean) : Member(className, name, declared)
    }

    private fun membersByName(s: Session, args: Args, className: String, member: String): List<Member> {
        val descriptor = args.string("descriptor")
        val fieldType = args.string("field_type")
        val file = s.index.findClass(className)
        val methods = file?.methods?.filter { it.name == member && (descriptor == null || it.descriptor == descriptor) }.orEmpty()
        val fields = file?.fields?.filter { it.name == member && (fieldType == null || it.type == fieldType) }.orEmpty()
        return when {
            methods.isNotEmpty() -> methods.map { Member.Method(className, it.name, it.descriptor, declared = true) }
            fields.isNotEmpty() -> fields.map { Member.Field(className, it.name, it.type, declared = true) }
            descriptor != null -> listOf(Member.Method(className, member, descriptor, declared = false))
            fieldType != null -> listOf(Member.Field(className, member, fieldType, declared = false))
            file != null -> throw ToolError("$className has no member named '$member'")
            else -> throw ToolError("$className is not in the workspace; pass descriptor for a method or field_type for a field")
        }
    }

    private fun referencesByName(s: Session, args: Args): List<Location> {
        val className = args.require("class_name")
        val member = args.string("member_name") ?: return s.references.findClassReferences(className, includeDeclaration = true)
        return membersByName(s, args, className, member).flatMap {
            when (it) {
                is Member.Method -> s.references.findMethodReferences(it.className, it.name, it.descriptor, includeDeclaration = it.declared)
                is Member.Field -> s.references.findFieldReferences(it.className, it.name, it.type, includeDeclaration = it.declared)
            }
        }
    }

    private fun hoverByName(s: Session, args: Args): Hover? {
        val className = args.require("class_name")
        val member = args.string("member_name") ?: return s.hover.hoverForClassReference(className)
        return when (val first = membersByName(s, args, className, member).first()) {
            is Member.Method -> s.hover.hoverForMethodReference(first.className, first.name, first.descriptor)
            is Member.Field -> s.hover.hoverForFieldReference(first.className, first.name, first.type)
        }
    }

    private fun position(args: Args): Pair<String, Position> {
        val uri = args.string("uri")
        val line = args.int("line")
        val character = args.int("character")
        if (uri == null || line == null || character == null) {
            throw ToolError("Pass either uri, line and character, or class_name (with member_name for a method or field)")
        }
        return uri to Position(line, character)
    }

    // ======================================================================
    // Result helpers
    // ======================================================================

    private fun json(value: Any): CallToolResult = toolResult(gson.toJson(value))

    private fun toolResult(text: String): CallToolResult = CallToolResult(content = listOf(TextContent(text)))

    private fun toolError(message: String): CallToolResult = CallToolResult(content = listOf(TextContent(message)), isError = true)

    private fun range(range: Range): Map<String, Any> = mapOf(
        "start" to mapOf("line" to range.start.line, "character" to range.start.character),
        "end" to mapOf("line" to range.end.line, "character" to range.end.character)
    )

    private fun location(location: Location): Map<String, Any> = mapOf("uri" to location.uri, "range" to range(location.range))
}
