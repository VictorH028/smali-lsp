package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.DocumentHighlightKind
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.DocumentHighlightProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf
import kotlin.test.assertEquals

/** Occurrences of the symbol under the caret within its file, with reads and writes told apart for fields. */
class DocumentHighlightProviderTest {

    private val index = WorkspaceIndex()
    private val source = """
        .class public Lcom/Counter;
        .super Ljava/lang/Object;

        .field private count:I

        .method public bump()V
            .registers 2
            iget v0, p0, Lcom/Counter;->count:I
            add-int/lit8 v0, v0, 0x1
            iput v0, p0, Lcom/Counter;->count:I
            invoke-virtual {p0}, Lcom/Counter;->log()V
            if-eqz v0, :cond_0
            :cond_0
            return-void
        .end method

        .method public log()V
            .registers 1
            invoke-virtual {p0}, Lcom/Counter;->log()V
            return-void
        .end method
    """.trimIndent()
    private val other = ".class public Lcom/Other;\n.super Ljava/lang/Object;\n.method public run()V\n    .registers 1\n    iget v0, p0, Lcom/Counter;->count:I\n    return-void\n.end method"
    private val provider = DocumentHighlightProvider(index)

    init {
        index.openSource("file:///Counter.smali", source)
        index.openSource("file:///Other.smali", other)
    }

    private fun at(marker: String, needle: String) =
        provider.highlights("file:///Counter.smali", source.positionOf(marker, needle, 1)).map { it.range.start.line to it.kind }

    @Test
    fun `a field is highlighted at its declaration, its reads and its writes, in this file only`() {
        val expected = listOf(3 to DocumentHighlightKind.Read, 7 to DocumentHighlightKind.Read, 9 to DocumentHighlightKind.Write)
        assertEquals(expected, at(".field private count", "count"))
        assertEquals(expected, at("iput v0", "count"), "the same from an access")
    }

    @Test
    fun `a method is highlighted at its declaration and every call in the file`() {
        assertEquals(listOf(10 to DocumentHighlightKind.Text, 16 to DocumentHighlightKind.Text, 18 to DocumentHighlightKind.Text), at(".method public log", "log"))
    }

    @Test
    fun `a label is highlighted at its definition and its jumps`() {
        assertEquals(listOf(11 to DocumentHighlightKind.Text, 12 to DocumentHighlightKind.Text), at("if-eqz v0", ":cond_0"))
    }

    @Test
    fun `a class is highlighted at every mention in the file`() {
        assertEquals(listOf(0, 7, 9, 10, 18), at(".class public", "Lcom/Counter;").map { it.first })
    }
}
