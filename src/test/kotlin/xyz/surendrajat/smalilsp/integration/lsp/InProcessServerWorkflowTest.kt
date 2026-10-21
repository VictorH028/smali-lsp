package xyz.surendrajat.smalilsp.integration.lsp

import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.shared.TestWorkspace
import xyz.surendrajat.smalilsp.shared.positionOf
import xyz.surendrajat.smalilsp.shared.withE2ETest
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * One editing session against the in-process server: initialize, open, navigate across files,
 * rename, edit and close, each step checked against the exact answer. The same session over
 * real JSON-RPC is [LspStdioTest].
 */
class InProcessServerWorkflowTest {

    private val config = """
        .class public LConfig;
        .super Ljava/lang/Object;

        .field public static DEBUG:Z
    """.trimIndent()

    private val service = """
        .class public LService;
        .super Ljava/lang/Object;

        .field private name:Ljava/lang/String;

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public process(Ljava/lang/String;)I
            .registers 3
            sget-boolean v0, LConfig;->DEBUG:Z
            const/4 v0, 0x1
            return v0
        .end method
    """.trimIndent()

    private val classA = """
        .class public LClassA;
        .super Ljava/lang/Object;

        .method public foo()V
            .registers 3
            new-instance v0, LService;
            invoke-direct {v0}, LService;-><init>()V
            const-string v1, "a"
            invoke-virtual {v0, v1}, LService;->process(Ljava/lang/String;)I
            return-void
        .end method
    """.trimIndent()

    private val classB = """
        .class public LClassB;
        .super Ljava/lang/Object;

        .method public bar(LService;)V
            .registers 3
            const-string v0, "b"
            invoke-virtual {p1, v0}, LService;->process(Ljava/lang/String;)I
            return-void
        .end method
    """.trimIndent()

    /** ClassA with the call to process moved into a second method. */
    private val classAEdited = """
        .class public LClassA;
        .super Ljava/lang/Object;

        .method public foo()V
            .registers 3
            new-instance v0, LService;
            invoke-direct {v0}, LService;-><init>()V
            return-void
        .end method

        .method public again(LService;)V
            .registers 3
            const-string v0, "a"
            invoke-virtual {p1, v0}, LService;->process(Ljava/lang/String;)I
            return-void
        .end method
    """.trimIndent()

    private val workspace = TestWorkspace.createTemp(mapOf(
        "src/Config.smali" to config,
        "src/Service.smali" to service,
        "src/ClassA.smali" to classA,
        "src/ClassB.smali" to classB,
    ))
    private val configUri = workspace.getFileUri("src/Config.smali")
    private val serviceUri = workspace.getFileUri("src/Service.smali")
    private val classAUri = workspace.getFileUri("src/ClassA.smali")
    private val classBUri = workspace.getFileUri("src/ClassB.smali")

    /** The [needle] token on the first line of [source] containing [marker], as a location in [uri]. */
    private fun token(uri: String, source: String, marker: String, needle: String): Location {
        val start = source.positionOf(marker, needle)
        return Location(uri, Range(start, Position(start.line, start.character + needle.length)))
    }

    private val processDeclaration = token(serviceUri, service, ".method public process", "process")
    private val callInA = token(classAUri, classA, "invoke-virtual", "process")
    private val callInB = token(classBUri, classB, "invoke-virtual", "process")

    private fun List<Location>.ordered() = sortedWith(compareBy({ it.uri }, { it.range.start.line }))

    @Test
    fun `a session from initialize to close`() {
        withE2ETest(workspace) {
            // initialize: the workspace was scanned before any file is opened
            assertEquals(listOf("LService;"), workspaceSymbols("Service").map { it.name })

            // open: the buffer is indexed and diagnosed
            openFile("src/ClassA.smali")
            assertEquals(emptyList(), getDiagnostics(classAUri), "a valid file has no diagnostics")

            // definition across files: a class, a method and, from the second open file, a field
            assertEquals(listOf(token(serviceUri, service, ".class", "LService;")), gotoDefinition(classAUri, classA.positionOf("new-instance", "LService;")))
            assertEquals(listOf(processDeclaration), gotoDefinition(classAUri, callInA.range.start))
            openFile("src/Service.smali")
            assertEquals(listOf(token(configUri, config, ".field", "DEBUG")), gotoDefinition(serviceUri, service.positionOf("sget-boolean", "DEBUG")))

            // hover on the call describes the declaration
            val hover = assertNotNull(hover(classAUri, callInA.range.start))
            assertEquals(
                "**Method:** `process`\n\n**Declared in:** `Service`\n\n**Modifiers:** public\n\n**Signature:** `(java.lang.String)int`\n\n**Parameters:**\n- `p1` `java.lang.String`\n\n**Returns:** `int`\n\n**References:** 2",
                hover.contents.right.value
            )

            // references from the declaration: its name token and both call sites
            assertEquals(listOf(callInA, callInB, processDeclaration), findReferences(serviceUri, processDeclaration.range.start).ordered())

            // document symbols: the outline of the open file, methods before fields
            val outline = documentSymbols(serviceUri).single()
            assertEquals("Service" to "public, extends java.lang.Object", outline.name to outline.detail)
            assertEquals(SymbolKind.Class, outline.kind)
            assertEquals(
                listOf(
                    Triple("<init>", SymbolKind.Constructor, "()V"),
                    Triple("process", SymbolKind.Method, "(Ljava/lang/String;)I"),
                    Triple("name", SymbolKind.Field, "java.lang.String"),
                ),
                outline.children.map { Triple(it.name, it.kind, it.detail) }
            )
            assertEquals(processDeclaration.range, outline.children[1].selectionRange, "selectionRange is the name token")

            // rename from a call site: the declaration and every call site, at their name tokens
            val rename = RenameParams(TextDocumentIdentifier(classAUri), callInA.range.start, "handle")
            val edit = assertNotNull(server.textDocumentService.rename(rename).get(5, TimeUnit.SECONDS))
            assertEquals(
                mapOf(serviceUri to listOf(processDeclaration.range), classAUri to listOf(callInA.range), classBUri to listOf(callInB.range)),
                edit.changes.mapValues { (_, edits) -> edits.map { it.range } }
            )
            assertEquals(setOf("handle"), edit.changes.values.flatten().map { it.newText }.toSet())

            // change: the buffer replaces the file in the index
            changeFile(classAUri, classAEdited, 2)
            val movedCall = token(classAUri, classAEdited, "invoke-virtual", "process")
            assertEquals(listOf("foo", "again"), documentSymbols(classAUri).single().children.map { it.name })
            assertEquals(listOf(movedCall, callInB), findReferences(serviceUri, processDeclaration.range.start, includeDeclaration = false).ordered())

            // close: the unsaved edit is discarded, the file on disk is indexed again
            server.textDocumentService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(classAUri)))
            server.awaitWrites()
            assertEquals(listOf(callInA, callInB), findReferences(serviceUri, processDeclaration.range.start, includeDeclaration = false).ordered())
        }
    }
}
