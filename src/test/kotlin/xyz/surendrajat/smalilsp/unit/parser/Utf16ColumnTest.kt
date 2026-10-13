package xyz.surendrajat.smalilsp.unit.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.core.ConstStringInstruction
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.parser.SmaliParser

/**
 * LSP positions are UTF-16 code units. ANTLR's default string stream counts code points,
 * so after a supplementary character (emoji) on a line every column was off by one per
 * such character, and token offsets no longer matched the source string. The parser now
 * lexes UTF-16 code units directly.
 */
class Utf16ColumnTest {

    private val parser = SmaliParser()

    @Test
    fun `ranges after an emoji on the same line are in UTF-16 units`() {
        val emojiLine = """    const-string v0, "😀 hi""""   // 😀 is two UTF-16 units
        val content = """
            .class public final Lcom/example/U;
            .super Ljava/lang/Object;

            .method public static run()V
                .registers 1
            $emojiLine
                invoke-static {}, Lcom/example/U;->run()V
                return-void
            .end method
        """.trimIndent()
        val result = parser.parseWithErrors("file:///U.smali", content)
        assertEquals(emptyList<SmaliParser.SyntaxError>(), result.syntaxErrors)

        val method = result.smaliFile!!.methods.single()
        val constString = method.instructions.filterIsInstance<ConstStringInstruction>().single()
        assertEquals("😀 hi", constString.value)
        assertEquals(emojiLine.trimStart().length + 4, constString.range.end.character, "end column must count the emoji as two units")

        // The symbol text extracted from the source must stay exact after the emoji line.
        val invoke = method.instructions.filterIsInstance<InvokeInstruction>().single()
        assertEquals("Lcom/example/U;", invoke.className)
        assertEquals("run", invoke.methodName)
        assertEquals("()V", invoke.descriptor)
    }

    @Test
    fun `syntax error columns after an emoji are in UTF-16 units`() {
        val content = ".class public final Lcom/example/E;\n.super Ljava/lang/Object;\n.field public static final S:Ljava/lang/String; = \"😀\" oops\n"
        val result = parser.parseWithErrors("file:///E.smali", content)
        val error = result.syntaxErrors.first()
        assertEquals(3, error.line)
        assertEquals(content.lines()[2].indexOf("oops"), error.charPositionInLine)
    }
}
