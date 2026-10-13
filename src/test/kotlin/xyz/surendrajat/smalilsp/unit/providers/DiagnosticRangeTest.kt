package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DiagnosticProvider
import xyz.surendrajat.smalilsp.shared.positionOf

/** An unresolved class is underlined at its token, not across the whole class or instruction. */
class DiagnosticRangeTest {

    private val source = """
        .class public Lcom/app/Foo;
        .super Lcom/missing/Base;
        .implements Lcom/missing/Iface;

        .field private data:Lcom/missing/Data;

        .method public run()V
            .registers 2
            invoke-static {}, Lcom/missing/Util;->go()V
            iget v0, p0, Lcom/missing/Holder;->x:I
            new-instance v0, Lcom/missing/Thing;
            return-void
        .end method
    """.trimIndent()

    private fun rangeOf(marker: String, needle: String): Range =
        Range(source.positionOf(marker, needle), source.positionOf(marker, needle, needle.length))

    @Test
    fun `every undefined-class diagnostic covers exactly the class token`() {
        val index = WorkspaceIndex()
        val diagnostics = DiagnosticProvider(SmaliParser(index.strings), index).computeDiagnostics("file:///Foo.smali", source)
        val byClass = diagnostics.associate { d -> Regex("'(L[^']+;)'").find(d.message)!!.groupValues[1] to d.range }
        assertEquals(
            mapOf(
                "Lcom/missing/Base;" to rangeOf(".super", "Lcom/missing/Base;"),
                "Lcom/missing/Iface;" to rangeOf(".implements", "Lcom/missing/Iface;"),
                "Lcom/missing/Data;" to rangeOf("data:", "Lcom/missing/Data;"),
                "Lcom/missing/Util;" to rangeOf("invoke-static", "Lcom/missing/Util;"),
                "Lcom/missing/Holder;" to rangeOf("iget", "Lcom/missing/Holder;"),
                "Lcom/missing/Thing;" to rangeOf("new-instance", "Lcom/missing/Thing;"),
            ),
            byClass
        )
    }
}
