package xyz.surendrajat.smalilsp.unit.service

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.SmaliTextDocumentService
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.CallHierarchyProvider
import xyz.surendrajat.smalilsp.providers.CodeLensProvider
import xyz.surendrajat.smalilsp.providers.CompletionProvider
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.providers.RenameProvider
import xyz.surendrajat.smalilsp.providers.TypeHierarchyProvider
import xyz.surendrajat.smalilsp.shared.TestLSPClient
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Diagnostics go out on didOpen and didChange and are cleared on didClose; the undefined-class
 * warnings wait for workspace indexing to complete (only syntax errors until then), and the
 * refresh afterwards republishes every file still open against the full index.
 */
class DiagnosticPublishingTest {

    private val index = WorkspaceIndex()
    private val client = TestLSPClient()
    private val service = SmaliTextDocumentService(
        index, DefinitionProvider(index), HoverProvider(index), ReferenceProvider(index), CallHierarchyProvider(index),
        TypeHierarchyProvider(index), CodeLensProvider(index), CompletionProvider(index), RenameProvider(index)
    ).also { it.connect(client) }

    private fun source(name: String = "Lcom/example/Test;", superClass: String = "Ljava/lang/Object;", body: String = "") =
        ".class public $name\n.super $superClass\n\n$body"

    private fun missingSuperclass(name: String) = "Superclass '$name' not found in workspace or SDK"

    /** Indexes [text] under [uri] as the workspace scan would, without opening it. */
    private fun scan(uri: String, text: String) {
        index.indexFile(SmaliParser(index.strings).parse(uri, text) ?: error("failed to parse $uri"))
    }

    private fun open(uri: String, text: String) =
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "smali", 1, text)))

    private fun change(uri: String, text: String, version: Int) =
        service.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, version), listOf(TextDocumentContentChangeEvent(text))))

    private fun close(uri: String) = service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))

    /** What the server last published for [uri] once every notification so far is applied; fails when nothing was published. */
    private fun published(uri: String): List<Diagnostic> {
        service.awaitWrites()
        return client.waitForDiagnostics(uri)
    }

    @Test
    fun `didOpen during indexing publishes syntax errors but no undefined-class warnings`() {
        val missing = "file:///Missing.smali"
        open(missing, source(superClass = "Lcom/example/MissingClass;"))
        assertEquals(emptyList(), published(missing), "the superclass may simply not be indexed yet")

        val broken = "file:///Broken.smali"
        open(broken, source(name = "Lcom/example/Broken;", body = ".method public run()V\n    .registers 1\n    bogus-opcode v0\n    return-void\n.end method\n"))
        val diagnostics = published(broken)
        assertTrue(diagnostics.isNotEmpty(), "a syntax error is not an indexing artefact")
        assertEquals(setOf("syntax-error"), diagnostics.map { it.code.left }.toSet())
    }

    @Test
    fun `didOpen after indexing publishes the undefined-class warnings too`() {
        service.indexingComplete = true
        val uri = "file:///Test.smali"
        open(uri, source(
            superClass = "Lcom/example/NonExistentSuperClass;",
            body = ".method public test()V\n    invoke-virtual {v0}, Lcom/example/UndefinedClass;->foo()V\n    return-void\n.end method\n"
        ))
        val diagnostics = published(uri)
        assertEquals(
            listOf(missingSuperclass("Lcom/example/NonExistentSuperClass;"), "Class 'Lcom/example/UndefinedClass;' not found in workspace or SDK"),
            diagnostics.map { it.message }
        )
        assertTrue(diagnostics.all { it.severity == DiagnosticSeverity.Warning && it.code.left == "undefined-class" }, diagnostics.toString())
    }

    @Test
    fun `the refresh after indexing republishes every open file against the full index`() {
        val resolved = "file:///Resolved.smali"
        val unresolved = "file:///Unresolved.smali"
        open(resolved, source(name = "Lcom/example/Resolved;", superClass = "Lcom/example/Dependency;"))
        open(unresolved, source(name = "Lcom/example/Unresolved;", superClass = "Lcom/example/ReallyMissing;"))
        assertEquals(emptyList(), published(resolved))
        assertEquals(emptyList(), published(unresolved))

        // The scan reaches the dependency, then indexing completes.
        scan("file:///Dependency.smali", source(name = "Lcom/example/Dependency;"))
        client.clearAll()
        service.indexingComplete = true
        service.refreshDiagnosticsForOpenFiles()

        assertEquals(emptyList(), published(resolved), "the dependency is indexed now")
        assertEquals(listOf(missingSuperclass("Lcom/example/ReallyMissing;")), published(unresolved).map { it.message }, "a class nobody indexed stays undefined")
    }

    @Test
    fun `didChange is gated like didOpen and republishes once indexing is complete`() {
        val uri = "file:///Test.smali"
        open(uri, source())
        assertEquals(emptyList(), published(uri))

        change(uri, source(superClass = "Lcom/example/NotIndexedYet;"), version = 2)
        assertEquals(emptyList(), published(uri), "an edit mid-indexing must not flood the file with warnings")

        service.indexingComplete = true
        change(uri, source(superClass = "Lcom/example/NotIndexedYet;"), version = 3)
        assertEquals(listOf(missingSuperclass("Lcom/example/NotIndexedYet;")), published(uri).map { it.message })
    }

    @Test
    fun `fixing the file on didChange clears its warning`() {
        service.indexingComplete = true
        val uri = "file:///Test.smali"
        open(uri, source(superClass = "Lcom/example/Missing;"))
        assertEquals(listOf(missingSuperclass("Lcom/example/Missing;")), published(uri).map { it.message })

        change(uri, source(), version = 2)
        assertEquals(emptyList(), published(uri))
    }

    @Test
    fun `didClose clears the diagnostics and leaves the file out of later refreshes`() {
        service.indexingComplete = true
        val closed = "file:///Closed.smali"
        val stillOpen = "file:///Open.smali"
        open(closed, source(name = "Lcom/example/Closed;", superClass = "Lcom/example/Missing;"))
        open(stillOpen, source(name = "Lcom/example/Open;", superClass = "Lcom/example/Missing;"))
        assertEquals(listOf(missingSuperclass("Lcom/example/Missing;")), published(closed).map { it.message })
        assertEquals(listOf(missingSuperclass("Lcom/example/Missing;")), published(stillOpen).map { it.message })

        close(closed)
        assertEquals(emptyList(), published(closed), "closing clears what the editor shows")

        client.clearAll()
        service.refreshDiagnosticsForOpenFiles()
        assertEquals(listOf(missingSuperclass("Lcom/example/Missing;")), published(stillOpen).map { it.message })
        // Every write has been applied by now, so a short wait is a pure negative check.
        assertFailsWith<TimeoutException>("a closed file is not refreshed") { client.waitForDiagnostics(closed, timeoutMs = 200) }
    }

    @Test
    fun `a hundred undefined invoke owners publish a hundred warnings`() {
        service.indexingComplete = true
        val uri = "file:///Test.smali"
        val methods = (1..100).joinToString("\n") { i ->
            ".method public method$i()V\n    invoke-virtual {v0}, Lcom/example/Missing$i;->foo()V\n    return-void\n.end method\n"
        }
        open(uri, source(body = methods))
        assertEquals(
            (1..100).map { "Class 'Lcom/example/Missing$it;' not found in workspace or SDK" },
            published(uri).map { it.message }
        )
    }

    @Test
    fun `files opened from ten threads at once each get their own diagnostics`() {
        service.indexingComplete = true
        fun uri(i: Int) = "file:///Test$i.smali"
        val threads = (1..10).map { i ->
            Thread { open(uri(i), source(name = "LTest$i;", superClass = if (i % 2 == 0) "Lcom/example/Missing$i;" else "Ljava/lang/Object;")) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        for (i in 1..10) {
            val expected = if (i % 2 == 0) listOf(missingSuperclass("Lcom/example/Missing$i;")) else emptyList()
            assertEquals(expected, published(uri(i)).map { it.message }, "file $i")
            assertTrue(index.hasClass("LTest$i;"), "file $i is indexed")
        }
    }
}
