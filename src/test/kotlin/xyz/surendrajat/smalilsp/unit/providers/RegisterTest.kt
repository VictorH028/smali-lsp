package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.DocumentHighlightProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Registers: what a `pN` carries, where a register is read and written in its method, with the debug names when present. */
class RegisterTest {

    private val index = WorkspaceIndex()
    private val source = """
        .class public Lcom/Regs;
        .super Ljava/lang/Object;

        .method public run(ILjava/lang/String;J)V
            .registers 7
            .param p1, "count"    # I
            .local v0, "total":I
            const/4 v0, 0x0
            add-int v0, v0, p1
            invoke-virtual {p0, p2}, Lcom/Regs;->log(Ljava/lang/String;)V
            move-wide v1, p3
            if-eqz v0, :cond_0
            :cond_0
            return-void
        .end method

        .method public static util(I)I
            .registers 2
            move v0, p0
            return v0
        .end method
    """.trimIndent()
    private val uri = "file:///Regs.smali"
    private val hovers = HoverProvider(index)
    private val highlights = DocumentHighlightProvider(index)

    init { index.openSource(uri, source) }

    private fun hoverAt(marker: String, needle: String, offset: Int = 1): String =
        assertNotNull(hovers.provideHover(uri, source.positionOf(marker, needle, offset))).contents.right.value

    @Test
    fun `a parameter register names its parameter, this, or the second half of a wide one`() {
        assertEquals("**Parameter** `p0`: `this` (`com.Regs`)\n\n**Frame:** 7 registers (2 local, 5 parameter) in `run`", hoverAt("invoke-virtual", "p0"), "this, int, String and a two-slot long")
        val p1 = hoverAt("add-int v0", "p1")
        assertTrue(p1.startsWith("**Parameter** `p1`: parameter 1 of 3, `int`\n\n**Name:** count\n\n"), p1)
        assertTrue(hoverAt("invoke-virtual", "p2").startsWith("**Parameter** `p2`: parameter 2 of 3, `java.lang.String`"))
        assertTrue(hoverAt("move-wide", "p3").startsWith("**Parameter** `p3`: parameter 3 of 3, `long`"))
        val p0Static = hoverAt("move v0, p0", "p0")
        assertTrue(p0Static.startsWith("**Parameter** `p0`: parameter 1 of 1, `int`"), "no `this` in a static method: $p0Static")
    }

    @Test
    fun `a local register shows its slot and debug name, and the opcode hover still wins on the opcode`() {
        assertEquals("**Register** `v0`: local 1 of 2\n\n**Local:** total: `int`\n\n**Frame:** 7 registers (2 local, 5 parameter) in `run`", hoverAt("const/4 v0", "v0"))
        assertTrue(hoverAt("const/4 v0", "const/4", 2).contains("CONST/4"), "the opcode word documents the instruction")
    }

    @Test
    fun `highlighting a register marks every use in the method, writes apart from reads`() {
        val v0 = highlights.highlights(uri, source.positionOf("add-int v0", "v0", 1)).map { it.range.start.line to it.kind }
        assertEquals(listOf(7 to DocumentHighlightKind.Write, 8 to DocumentHighlightKind.Write, 8 to DocumentHighlightKind.Read, 11 to DocumentHighlightKind.Read), v0)
        val p0 = highlights.highlights(uri, source.positionOf("invoke-virtual", "p0", 1)).map { it.range.start.line }
        assertEquals(listOf(9), p0, "the static method's p0 is another register")
        assertEquals(listOf(18 to DocumentHighlightKind.Write, 19 to DocumentHighlightKind.Read), highlights.highlights(uri, Position(18, 9)).map { it.range.start.line to it.kind })
    }
}
