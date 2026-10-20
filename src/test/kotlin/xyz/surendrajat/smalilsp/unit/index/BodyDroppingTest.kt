package xyz.surendrajat.smalilsp.unit.index

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.CallHierarchyProvider
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.shared.positionOf
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A file indexed without its body keeps its declarations; the body comes back from the disk when a request needs it. */
class BodyDroppingTest {

    @TempDir
    lateinit var dir: File

    private val caller = """
        .class public Lcom/Caller;
        .super Ljava/lang/Object;
        .method public run()V
            .registers 2
            const-string v0, "hello"
            invoke-static {}, Lcom/Callee;->work()V
            invoke-static {}, Lcom/Callee;->work()V
            return-void
        .end method
    """.trimIndent()
    private val callee = """
        .class public Lcom/Callee;
        .super Ljava/lang/Object;
        .method public static work()V
            .registers 1
            const-string v0, "hello"
            const-string v0, "only in callee"
            invoke-static {}, Lcom/Callee;->helper()V
            return-void
        .end method
        .method private static helper()V
            .registers 0
            return-void
        .end method
    """.trimIndent()

    private val index = WorkspaceIndex()
    private val parser = SmaliParser()

    private fun write(name: String, text: String): String {
        val file = File(dir, name).apply { writeText(text) }
        return file.toPath().toUri().toString()
    }

    private fun indexFromDisk(uri: String): String {
        index.indexFile(parser.parse(uri, File(java.net.URI(uri)).readText())!!, keepBody = false)
        return uri
    }

    @Test
    fun `declarations stay, the body is parsed again on demand and cached`() {
        val uri = indexFromDisk(write("Caller.smali", caller))
        val shell = assertNotNull(index.findDeclarations(uri))
        assertEquals(listOf("run"), shell.methods.map { it.name })
        assertTrue(shell.methods.single().instructions.isEmpty(), "no instructions stored")
        val full = assertNotNull(index.findFileByUri(uri))
        assertEquals(3, full.methods.single().instructions.size, "parsed from the disk: the string and two calls")
        assertSame(full, index.findFileByUri(uri), "kept for the next request")
        assertEquals(1, index.getStats().classes)

        index.indexFile(parser.parse(uri, caller)!!)
        assertEquals(3, assertNotNull(index.findDeclarations(uri)).methods.single().instructions.size, "indexed with its body, as an open file is")
        index.dropBody(uri)
        assertTrue(assertNotNull(index.findDeclarations(uri)).methods.single().instructions.isEmpty(), "closed again")
        assertEquals(3, assertNotNull(index.findFileByUri(uri)).methods.single().instructions.size)
    }

    @Test
    fun `requests on a closed file see its body, and usages are indexed from it`() {
        val callerUri = indexFromDisk(write("Caller.smali", caller))
        val calleeUri = indexFromDisk(write("Callee.smali", callee))
        val hover = assertNotNull(HoverProvider(index).provideHover(callerUri, caller.positionOf("invoke-static {}, Lcom/Callee;->work", "work", 1)))
        assertTrue(hover.contents.right.value.contains("**Method:** `work`"), hover.contents.right.value)
        assertEquals(calleeUri, DefinitionProvider(index).findDefinition(callerUri, caller.positionOf("invoke-static {}, Lcom/Callee;->work", "work", 1)).single().uri)
        assertEquals(2, ReferenceProvider(index).findReferences(calleeUri, callee.positionOf(".method public static work", "work", 1), includeDeclaration = false).size, "call sites came from the body at index time")

        val hierarchy = CallHierarchyProvider(index)
        val item = hierarchy.prepare(callerUri, caller.positionOf("invoke-static {}, Lcom/Callee;->work", "work", 1)).single()
        val outgoing = hierarchy.outgoingCalls(item)
        assertEquals(listOf("helper()V"), outgoing.map { it.to.name }, "outgoing calls read the closed callee's body")
        assertEquals(listOf("run()V"), hierarchy.incomingCalls(item).map { it.from.name })
    }

    @Test
    fun `string statistics survive replacing and removing a file stored without its body`() {
        val callerUri = indexFromDisk(write("Caller.smali", caller))
        val calleeUri = indexFromDisk(write("Callee.smali", callee))
        assertEquals(2, index.getStats().strings, "hello, only in callee")
        index.indexFile(parser.parse(calleeUri, callee.replace("\"only in callee\"", "\"renamed\""))!!, keepBody = false)
        assertEquals(2, index.getStats().strings)
        assertTrue(index.removeFile(calleeUri))
        assertEquals(1, index.getStats().strings, "hello stays with the caller")
        assertNull(index.findFileByUri(calleeUri))
        assertNotNull(index.findFileByUri(callerUri))
    }

    @Test
    fun `a body that cannot be read leaves the declarations`() {
        val uri = indexFromDisk(write("Gone.smali", caller))
        File(java.net.URI(uri)).delete()
        val file = assertNotNull(index.findFileByUri(uri))
        assertEquals(listOf("run"), file.methods.map { it.name })
        assertNull(HoverProvider(index).provideHover(uri, Position(5, 40)), "no body, nothing under the caret")
    }
}
