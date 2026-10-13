package xyz.surendrajat.smalilsp.unit.indexer

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.surendrajat.smalilsp.core.FieldAccessInstruction
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import xyz.surendrajat.smalilsp.shared.positionOf
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Files found by the scanner resolve each other: a reference parsed from one file finds the
 * declaration the scanner indexed from another, under the URI the scanner gave that file.
 */
class CrossFileResolutionTest {

    @TempDir
    lateinit var dir: File

    private val index = WorkspaceIndex()

    private val iface = """
        .class public interface abstract Lcom/example/IBase;
        .super Ljava/lang/Object;

        .method public abstract baseMethod()V
        .end method
    """.trimIndent()

    private val abstractImpl = """
        .class public abstract Lcom/example/AbstractImpl;
        .super Ljava/lang/Object;

        # interfaces
        .implements Lcom/example/IBase;

        .field public static MAX_VALUE:I

        .method public abstract extraMethod()V
        .end method
    """.trimIndent()

    private val concrete = """
        .class public Lcom/example/ConcreteImpl;
        .super Lcom/example/AbstractImpl;

        .method public baseMethod()V
            .registers 1
            return-void
        .end method

        .method public extraMethod()V
            .registers 1
            return-void
        .end method

        .method public print(I)V
            .registers 2
            return-void
        .end method

        .method public print(Ljava/lang/String;)V
            .registers 2
            return-void
        .end method

        .method public print(II)V
            .registers 3
            return-void
        .end method
    """.trimIndent()

    private val caller = """
        .class public Lcom/example/Caller;
        .super Ljava/lang/Object;

        .method public run(Lcom/example/ConcreteImpl;)V
            .registers 3
            const/4 v0, 0x5
            invoke-virtual {p1, v0}, Lcom/example/ConcreteImpl;->print(I)V
            sget v1, Lcom/example/ConcreteImpl;->MAX_VALUE:I
            return-void
        .end method
    """.trimIndent()

    /** Simple class name to the URI the scanner indexed its file under. */
    private lateinit var uris: Map<String, String>

    @BeforeEach
    fun scan() {
        val sources = mapOf("IBase" to iface, "AbstractImpl" to abstractImpl, "ConcreteImpl" to concrete, "Caller" to caller)
        uris = sources.mapValues { (name, text) ->
            File(dir, "com/example/$name.smali").apply { parentFile.mkdirs(); writeText(text) }.toPath().toUri().toString()
        }
        val result = runBlocking { WorkspaceScanner(index).scanDirectory(dir) }
        assertEquals(sources.size, result.filesSucceeded)
    }

    /** The [needle] token on the first line of [source] containing [marker], as a location in the scanned file [name]. */
    private fun token(name: String, source: String, marker: String, needle: String): Location {
        val start = source.positionOf(marker, needle)
        return Location(uris.getValue(name), Range(start, Position(start.line, start.character + needle.length)))
    }

    @Test
    fun `scanned files are indexed under their file URIs with their hierarchy`() {
        for ((name, uri) in uris) assertEquals(uri, index.getUri("Lcom/example/$name;"), name)
        assertEquals("Lcom/example/AbstractImpl;", assertNotNull(index.findClass("Lcom/example/ConcreteImpl;")).classDefinition.superClass)
        assertEquals(listOf("Lcom/example/IBase;"), assertNotNull(index.findClass("Lcom/example/AbstractImpl;")).classDefinition.interfaces)
        assertEquals(setOf(uris.getValue("AbstractImpl")), index.findClassUsages("Lcom/example/IBase;"))
        assertEquals(setOf(uris.getValue("ConcreteImpl")), index.findClassUsages("Lcom/example/AbstractImpl;"))
    }

    @Test
    fun `a reference parsed from one file resolves to the declaration scanned from another`() {
        val run = assertNotNull(index.findFileByUri(uris.getValue("Caller"))).methods.single()
        val call = run.instructions.filterIsInstance<InvokeInstruction>().single()
        assertEquals(listOf(token("ConcreteImpl", concrete, "print(I)V", "print")), index.findMethod(call.className, call.methodName, call.descriptor))
        assertEquals(listOf(token("Caller", caller, "invoke-virtual", "print")), index.findMethodUsages(call.className, call.methodName, call.descriptor))

        val access = run.instructions.filterIsInstance<FieldAccessInstruction>().single()
        assertEquals(token("AbstractImpl", abstractImpl, ".field", "MAX_VALUE"), index.findField(access.className, access.fieldName, access.fieldType), "declared in the superclass's file")
        assertEquals(listOf(token("Caller", caller, "sget", "MAX_VALUE")), index.findFieldUsages(access.className, access.fieldName, access.fieldType))

        assertEquals(listOf(token("IBase", iface, "baseMethod", "baseMethod")), index.findMethod("Lcom/example/AbstractImpl;", "baseMethod", "()V"), "an interface method is reached through the implementor")
    }

    @Test
    fun `overloads scanned from one file are distinct declarations`() {
        assertEquals(
            listOf("print(I)V", "print(Ljava/lang/String;)V", "print(II)V").map { token("ConcreteImpl", concrete, it, "print") },
            listOf("(I)V", "(Ljava/lang/String;)V", "(II)V").flatMap { index.findMethod("Lcom/example/ConcreteImpl;", "print", it) }
        )
    }
}
