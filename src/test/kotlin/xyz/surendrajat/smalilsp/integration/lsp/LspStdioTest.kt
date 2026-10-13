package xyz.surendrajat.smalilsp.integration.lsp

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.surendrajat.smalilsp.shared.LspStdioClient
import java.io.File
import java.nio.file.Path

/**
 * Drives the server binary over stdio with real JSON-RPC, the way an editor does.
 * Covers the handshake, workspace indexing with progress, didOpen/didChange/didClose,
 * definition, hover, references, workspace symbols, and the shutdown/exit lifecycle.
 */
class LspStdioTest {

    @TempDir
    lateinit var workspace: Path

    private val calleeSmali = """
        .class public Lcom/app/Callee;
        .super Ljava/lang/Object;

        .method public static greet(Ljava/lang/String;)V
            .registers 1
            return-void
        .end method
    """.trimIndent()

    private val callerSmali = """
        .class public Lcom/app/Caller;
        .super Ljava/lang/Object;

        .method public run()V
            .registers 2
            const-string v0, "hi"
            invoke-static {v0}, Lcom/app/Callee;->greet(Ljava/lang/String;)V
            return-void
        .end method
    """.trimIndent()

    private fun writeWorkspace(): Pair<File, File> {
        val callee = workspace.resolve("smali/com/app/Callee.smali").toFile().apply { parentFile.mkdirs(); writeText(calleeSmali) }
        val caller = workspace.resolve("smali/com/app/Caller.smali").toFile().apply { writeText(callerSmali) }
        return callee to caller
    }

    private fun initParams(client: LspStdioClient, workspaceFolders: Boolean = true, rootUri: Boolean = true, dynamicWatchers: Boolean = true, progress: Boolean = true): Map<String, Any?> {
        val uri = workspace.toUri().toString().trimEnd('/')
        val params = mutableMapOf<String, Any?>(
            "processId" to ProcessHandle.current().pid(),
            "clientInfo" to mapOf("name" to "LspStdioTest", "version" to "1"),
            "capabilities" to mapOf(
                "window" to mapOf("workDoneProgress" to progress),
                "workspace" to mapOf("didChangeWatchedFiles" to mapOf("dynamicRegistration" to dynamicWatchers), "workspaceFolders" to true),
            ),
        )
        if (rootUri) params["rootUri"] = uri
        if (workspaceFolders) params["workspaceFolders"] = listOf(mapOf("uri" to uri, "name" to "ws"))
        return params
    }

    private fun LspStdioClient.initializeAndIndex(params: Map<String, Any?>): JsonObject {
        val init = result("initialize", params).asJsonObject
        notify("initialized")
        awaitNotification("$/progress") { it.getAsJsonObject("params").getAsJsonObject("value")["kind"]?.asString == "end" }
        return init
    }

    /** Poll until the workspace index can answer, for sessions where progress reporting is unavailable. */
    private fun LspStdioClient.awaitIndexed(timeoutSeconds: Long = 20): Boolean {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            val found = result("workspace/symbol", mapOf("query" to "Callee")).asJsonArray.any { it.asJsonObject["name"].asString == "Lcom/app/Callee;" }
            if (found) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun LspStdioClient.open(file: File, text: String) =
        notify("textDocument/didOpen", mapOf("textDocument" to mapOf("uri" to file.toURI().toString(), "languageId" to "smali", "version" to 1, "text" to text)))

    @Test
    fun `full workflow over stdio`() {
        val (callee, caller) = writeWorkspace()
        val calleeUri = callee.toURI().toString()
        val callerUri = caller.toURI().toString()
        LspStdioClient().use { client ->
            val init = client.initializeAndIndex(initParams(client))
            val caps = init.getAsJsonObject("capabilities")
            assertTrue(caps["definitionProvider"].asBoolean)
            assertTrue(caps["hoverProvider"].asBoolean)
            assertTrue(caps["referencesProvider"].asBoolean)
            assertEquals("Smali Language Server", init.getAsJsonObject("serverInfo")["name"].asString)

            // workspace was indexed: symbols from an unopened file are known
            val symbols = client.result("workspace/symbol", mapOf("query" to "greet")).asJsonArray
            assertTrue(symbols.any { it.asJsonObject["name"].asString.contains("greet") }, "workspace/symbol should see the indexed callee: $symbols")

            client.open(caller, callerSmali)
            val diagnostics = client.awaitNotification("textDocument/publishDiagnostics") { it.getAsJsonObject("params")["uri"].asString == callerUri }
            assertEquals(0, diagnostics.getAsJsonObject("params").getAsJsonArray("diagnostics").size(), "valid file has no diagnostics")

            // definition: from the invoke's method name to the declaration in the other file
            val invokeLine = callerSmali.lines().indexOfFirst { it.contains("invoke-static") }
            val nameCol = callerSmali.lines()[invokeLine].indexOf("greet")
            val definition = client.result("textDocument/definition", client.at(callerUri, invokeLine, nameCol)).asJsonArray
            assertEquals(1, definition.size(), "definition: $definition")
            assertEquals(calleeUri, definition[0].asJsonObject["uri"].asString)

            // hover on the same spot describes the method
            val hover = client.result("textDocument/hover", client.at(callerUri, invokeLine, nameCol)).asJsonObject
            assertTrue(hover.getAsJsonObject("contents")["value"].asString.contains("greet"), hover.toString())

            // references from the declaration (unopened file) find the call site
            val declLine = calleeSmali.lines().indexOfFirst { it.startsWith(".method") }
            val declCol = calleeSmali.lines()[declLine].indexOf("greet")
            val refs = client.result("textDocument/references", mapOf("textDocument" to client.textDocument(calleeUri), "position" to client.position(declLine, declCol), "context" to mapOf("includeDeclaration" to false))).asJsonArray
            assertEquals(listOf(callerUri), refs.map { it.asJsonObject["uri"].asString }, "references: $refs")

            // didChange: remove the call, the reference disappears
            val edited = callerSmali.replace("invoke-static {v0}, Lcom/app/Callee;->greet(Ljava/lang/String;)V", "nop")
            client.notify("textDocument/didChange", mapOf("textDocument" to mapOf("uri" to callerUri, "version" to 2), "contentChanges" to listOf(mapOf("text" to edited))))
            val refsAfterEdit = client.result("textDocument/references", mapOf("textDocument" to client.textDocument(calleeUri), "position" to client.position(declLine, declCol), "context" to mapOf("includeDeclaration" to false))).asJsonArray
            assertEquals(0, refsAfterEdit.size(), "reference must go away after the edit: $refsAfterEdit")

            // didClose keeps the file indexed
            client.notify("textDocument/didClose", mapOf("textDocument" to client.textDocument(callerUri)))
            client.awaitNotification("textDocument/publishDiagnostics") { it.getAsJsonObject("params")["uri"].asString == callerUri && it.getAsJsonObject("params").getAsJsonArray("diagnostics").size() == 0 }
            val stillThere = client.result("workspace/symbol", mapOf("query" to "Caller")).asJsonArray
            assertTrue(stillThere.any { it.asJsonObject["name"].asString == "Lcom/app/Caller;" }, "closed file stays indexed: $stillThere")

            assertTrue(client.result("shutdown").isJsonNull)
            client.notify("exit")
            assertEquals(0, client.waitForExit(), "exit after shutdown returns 0")
        }
    }

    @Test
    fun `rootUri alone is enough to index the workspace`() {
        writeWorkspace()
        LspStdioClient().use { client ->
            client.result("initialize", initParams(client, workspaceFolders = false, rootUri = true))
            client.notify("initialized")
            assertTrue(client.awaitIndexed(), "a client that sends rootUri but no workspaceFolders must still get its workspace indexed")
            client.result("shutdown"); client.notify("exit")
        }
    }

    @Test
    fun `a rejected progress request does not prevent indexing`() {
        writeWorkspace()
        LspStdioClient(rejectProgress = true).use { client ->
            client.result("initialize", initParams(client))
            client.notify("initialized")
            assertTrue(client.awaitIndexed(), "indexing must not depend on window/workDoneProgress/create succeeding")
            client.result("shutdown"); client.notify("exit")
        }
    }

    @Test
    fun `a client without progress support is not asked to create progress`() {
        writeWorkspace()
        LspStdioClient().use { client ->
            client.result("initialize", initParams(client, progress = false))
            client.notify("initialized")
            assertTrue(client.awaitIndexed())
            client.drain()
            assertFalse("window/workDoneProgress/create" in client.serverRequests, "server must not send progress requests to a client that did not advertise window.workDoneProgress")
            client.result("shutdown"); client.notify("exit")
        }
    }

    @Test
    fun `server registers a smali file watcher when the client allows it`() {
        writeWorkspace()
        LspStdioClient().use { client ->
            client.initializeAndIndex(initParams(client, dynamicWatchers = true))
            val deadline = System.nanoTime() + 10_000_000_000L
            while (client.registrations.isEmpty() && System.nanoTime() < deadline) { client.drain(); Thread.sleep(50) }
            val registration = client.registrations.firstOrNull()?.getAsJsonArray("registrations")?.firstOrNull()?.asJsonObject
            assertNotNull(registration, "server must register a workspace/didChangeWatchedFiles watcher; requests seen: ${client.serverRequests}")
            assertEquals("workspace/didChangeWatchedFiles", registration!!["method"].asString)
            val globs = registration.getAsJsonObject("registerOptions").getAsJsonArray("watchers").map { it.asJsonObject["globPattern"].asString }
            assertEquals(listOf("**/*.smali"), globs)
            client.result("shutdown"); client.notify("exit")
        }
    }

    @Test
    fun `no watcher registration without dynamic registration support`() {
        writeWorkspace()
        LspStdioClient().use { client ->
            client.initializeAndIndex(initParams(client, dynamicWatchers = false))
            Thread.sleep(300); client.drain()
            assertTrue(client.registrations.isEmpty(), "must not register capabilities the client cannot accept")
            client.result("shutdown"); client.notify("exit")
        }
    }

    @Test
    fun `exit without a prior shutdown returns 1`() {
        writeWorkspace()
        LspStdioClient().use { client ->
            client.initializeAndIndex(initParams(client))
            client.notify("exit")
            assertEquals(1, client.waitForExit(), "exit without shutdown must signal an unclean stop")
        }
    }

    @Test
    fun `stdout carries only JSON-RPC frames`() {
        writeWorkspace()
        LspStdioClient().use { client ->
            // Any stray stdout output would break the framing and make these requests time out.
            client.initializeAndIndex(initParams(client))
            assertNotNull(client.result("workspace/symbol", mapOf("query" to "Callee")))
            assertTrue(client.result("shutdown").isJsonNull)
            client.notify("exit")
            assertEquals(0, client.waitForExit())
        }
    }
}
