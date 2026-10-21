package xyz.surendrajat.smalilsp.unit.core

import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.core.Literals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where a smali line keeps its one numeric literal, and which lines have none. */
class LiteralsTest {

    private fun found(line: String): String? = Literals.find(line)?.let { "${it.kind}:${it.token.text}" + (it.opcode?.let { op -> "@$op" } ?: "") }

    @Test
    fun `every place baksmali writes a literal`() {
        assertEquals("Instruction:0x3e8@const/16", found("    const/16 v0, 0x3e8"))
        assertEquals("Instruction:-0x1@add-int/lit8", found("    add-int/lit8 v0, v0, -0x1"))
        assertEquals("Instruction:0x3f800000@const/high16", found("    const/high16 v0, 0x3f800000    # 1.0f"), "the comment is not part of the literal")
        assertEquals("Field:0x7f04000c", found(".field public static a:I = 0x7f04000c"))
        assertEquals("Field:0x41t", found(".field public static final B:B = 0x41t"))
        assertEquals("Annotation:0x1f", found("        value = 0x1f"))
        assertEquals("Element:0x7f04003d", found("        0x7f04003d"))
        assertEquals("Element:0x1t", found("        0x1t"))
        assertEquals("SwitchKey:-0x64", found("        -0x64 -> :sswitch_0"))
        assertEquals("PackedSwitch:0x10", found("    .packed-switch 0x10"))
    }

    @Test
    fun `lines without a literal`() {
        for (line in listOf("", "    # comment 0x10", "    :array_0", "    .line 12", "    .registers 4", "    .array-data 4", "    .end array-data",
            "    const-string v0, \"0x3e8\"", ".field public static D:Ljava/lang/String; = \"0x3e8\"", ".field public static E:[I", "        ids = { 0x7f0a0012 }",
            "    invoke-virtual {p0, v0}, Lcom/A;->b(I)V", "    packed-switch v0, :pswitch_data_0", "    move-result v0", "        :pswitch_0")) {
            assertNull(Literals.find(line), line)
        }
    }
}
