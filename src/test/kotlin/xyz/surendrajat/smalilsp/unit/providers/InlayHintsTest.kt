package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.surendrajat.smalilsp.index.ResourceTable
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.InlayHints
import xyz.surendrajat.smalilsp.shared.openSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inlay hints: the decimal value of a hex literal, the resource a `0x7f...` id names, and the literals that get nothing. */
class InlayHintsTest {

    @TempDir
    lateinit var root: File

    private val source = """
        .class public Lcom/app/Lits;
        .super Ljava/lang/Object;
        .field public static A:I = 0x7f0a0012
        .field public static B:I = 0x64
        .field public static C:B = 0x41t
        .field public static D:Ljava/lang/String; = "0x3e8"
        .method public run()V
            .registers 4
            .annotation build Lcom/app/Marker;
                value = 0x1f
                ids = { 0x7f0a0012 }
            .end annotation
            const/4 v0, 0x5
            const/16 v0, 0x3e8
            const v0, -0x80
            const/high16 v0, 0x3f800000    # 1.0f
            const/high16 v1, 0x10000000
            const-wide/16 v2, 0x3e8
            const-wide v2, 0x400921fb54442d18L    # 3.141592653589793
            const-wide/high16 v2, 0x4000000000000000L
            const v0, 0x7f0a0012
            const v0, 0x1010000
            const v0, 0x7f0a9999
            const/16 v0, 1000
            mul-int/lit8 v0, v0, 0x1f
            add-int/lit8 v0, v0, 0x1
            and-int/lit16 v0, v0, 0xff
            const-string v0, "0x3e8"
            packed-switch v0, :pswitch_data_0
            sparse-switch v0, :sswitch_data_0
            fill-array-data v0, :array_0
            return-void
            :pswitch_data_0
            .packed-switch 0x10
                :pswitch_0
            .end packed-switch
            :sswitch_data_0
            .sparse-switch
                0x7f0a0012 -> :sswitch_0
                0x64 -> :sswitch_0
            .end sparse-switch
            :pswitch_0
            :sswitch_0
            return-void
            :array_0
            .array-data 4
                0x7f0a0012
                0x3e8
                0x5
            .end array-data
            :array_1
            .array-data 1
                0x41t
            .end array-data
        .end method
    """.trimIndent()
    private val lines = source.lines()
    private val whole = Range(Position(0, 0), Position(lines.size - 1, 0))

    private fun line(text: String): Int = lines.indexOfFirst { it.contains(text) }.also { require(it >= 0) { text } }
    private fun exactLine(text: String): Int = lines.indexOfFirst { it.trim() == text }.also { require(it >= 0) { text } }

    private fun hints(index: WorkspaceIndex, range: Range = whole): Map<Int, String> {
        index.openSource("file:///Lits.smali", source)
        return InlayHints.hints(index, lines, range).associate { it.position.line to it.label.left }
    }

    @Test
    fun `hex literals of const and lit instructions get their decimal value`() {
        val hints = hints(WorkspaceIndex())
        assertEquals(
            mapOf(
                line("const/16 v0, 0x3e8") to "= 1000",
                line("const v0, -0x80") to "= -128",
                line("const/high16 v1, 0x10000000") to "= 268435456",
                line("const-wide/16 v2, 0x3e8") to "= 1000",
                line("const-wide/high16 v2") to "= 4611686018427387904",
                line("mul-int/lit8") to "= 31",
                line("and-int/lit16") to "= 255",
                line(".field public static B") to "= 100",
                line("value = 0x1f") to "= 31",
                line(".packed-switch 0x10") to "= 16",
                line("0x64 -> ") to "= 100",
                exactLine("0x3e8") to "= 1000"
            ),
            hints,
            "const and lit operands, a field value, an annotation element, switch keys and an int array element"
        )
    }

    @Test
    fun `a hint sits right after the literal with padding`() {
        val index = WorkspaceIndex()
        index.openSource("file:///Lits.smali", source)
        val hint = InlayHints.hints(index, lines, whole).first()
        val text = lines[hint.position.line]
        assertEquals(text.length, hint.position.character, "after `0x3e8`, the end of the line")
        assertTrue(hint.paddingLeft)
        assertNull(hint.tooltip)
    }

    @Test
    fun `small values, decimal literals, commented floats, strings, switch tables and unknown resource ids get nothing`() {
        val hints = hints(WorkspaceIndex())
        for (text in listOf("const/4 v0, 0x5", "# 1.0f", "# 3.14", "const v0, 0x7f0a0012", "const v0, 0x1010000", "0x7f0a9999", "const/16 v0, 1000", "add-int/lit8", "const-string", "packed-switch v0", ".field public static A", ".field public static C", ".field public static D", "0x7f0a0012 -> ", "ids = {", ".array-data 4")) {
            assertNull(hints[line(text)], text)
        }
        for (text in listOf("0x7f0a0012", "0x5", "0x41t")) assertNull(hints[exactLine(text)], "array element $text")
    }

    @Test
    fun `a resource the table knows is named instead, with its value`() {
        val xml = ResourceTable.publicXml(root).apply { parentFile.mkdirs() }
        xml.writeText("<resources>\n    <public type=\"id\" name=\"login_button\" id=\"0x7f0a0012\" />\n    <public type=\"string\" name=\"app_name\" id=\"0x7f0a9999\" />\n</resources>\n")
        File(root, "res/values/strings.xml").writeText("<resources>\n    <string name=\"app_name\">${"long ".repeat(12)}name</string>\n</resources>\n")
        val index = WorkspaceIndex()
        assertEquals(2, index.resources.load(xml))
        index.openSource("file:///Lits.smali", source)
        val hints = InlayHints.hints(index, lines, whole).associateBy { it.position.line }
        assertEquals("id/login_button", hints.getValue(line("const v0, 0x7f0a0012")).label.left)
        val string = hints.getValue(line("0x7f0a9999"))
        assertTrue(string.label.left.startsWith("string/app_name = \"long long") && string.label.left.endsWith("…\""), string.label.left)
        assertEquals("${"long ".repeat(12)}name", string.tooltip.left, "the whole value when the label was cut")
        assertEquals("= 1000", hints.getValue(line("const/16 v0, 0x3e8")).label.left, "numbers still get their value")
        assertEquals("id/login_button", hints.getValue(line(".field public static A")).label.left, "a field value")
        assertEquals("id/login_button", hints.getValue(line("0x7f0a0012 -> ")).label.left, "a sparse-switch key")
        assertEquals("id/login_button", hints.getValue(exactLine("0x7f0a0012")).label.left, "an array element")
    }

    @Test
    fun `only the requested lines are scanned`() {
        val from = line("mul-int/lit8")
        assertEquals(mapOf(from to "= 31"), hints(WorkspaceIndex(), Range(Position(from, 0), Position(from + 1, 0))))
        assertEquals(emptyMap(), hints(WorkspaceIndex(), Range(Position(lines.size + 5, 0), Position(lines.size + 9, 0))), "a range past the end")
    }
}
