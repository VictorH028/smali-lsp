package xyz.surendrajat.smalilsp.unit.parser

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.core.ConstStringInstruction
import xyz.surendrajat.smalilsp.parser.SmaliParser

/**
 * The string-literal rule excluded `'` instead of `"` from its character class, so a
 * string ran to the last quote on the line (`"a", "b"` became one token) and an
 * unescaped apostrophe was a lexer error. Char literals had the mirror problem.
 */
class StringLiteralLexingTest {

    private val parser = SmaliParser()

    private fun strings(body: String): List<String> {
        val content = """
            .class public final Lcom/example/S;
            .super Ljava/lang/Object;

            .method public static run()V
                .registers 3
                $body
                return-void
            .end method
        """.trimIndent()
        val result = parser.parseWithErrors("file:///S.smali", content)
        assertEquals(emptyList<SmaliParser.SyntaxError>(), result.syntaxErrors, "unexpected syntax errors")
        return result.smaliFile!!.methods.single().instructions.filterIsInstance<ConstStringInstruction>().map { it.value }
    }

    @Test
    fun `two string literals on one line are separate tokens`() {
        val content = """
            .class public final Lcom/example/L;
            .super Ljava/lang/Object;

            .method public static run()V
                .registers 2
                const-string v0, "first"
                .local v0, "name":Ljava/lang/String;, "Ljava/util/List<Ljava/lang/String;>;"
                return-void
            .end method
        """.trimIndent()
        val result = parser.parseWithErrors("file:///L.smali", content)
        assertEquals(emptyList<SmaliParser.SyntaxError>(), result.syntaxErrors)
        assertEquals(listOf("first"), result.smaliFile!!.methods.single().instructions.filterIsInstance<ConstStringInstruction>().map { it.value })
    }

    @Test
    fun `unescaped apostrophes and escaped quotes inside strings parse`() {
        assertEquals(
            listOf("don't", "say \\\"hi\\\"", "mixed ' and \\\" quotes"),
            strings("""
                const-string v0, "don't"
                const-string v1, "say \"hi\""
                const-string/jumbo v2, "mixed ' and \" quotes"
            """.trimIndent())
        )
    }

    @Test
    fun `a trailing comment containing quotes does not extend the string`() {
        assertEquals(listOf("x"), strings("""const-string v0, "x"    # see "docs" and 'notes'"""))
    }

    @Test
    fun `char literals accept quotes and escaped apostrophes`() {
        val content = """
            .class public final Lcom/example/C;
            .super Ljava/lang/Object;

            .field public static final Q:C = '"'

            .field public static final A:C = '\''

            .field public static final U:C = 'A'

            .method public static after()V
                .registers 0
                return-void
            .end method
        """.trimIndent()
        val result = parser.parseWithErrors("file:///C.smali", content)
        assertEquals(emptyList<SmaliParser.SyntaxError>(), result.syntaxErrors)
        assertEquals(listOf("Q", "A", "U"), result.smaliFile!!.fields.map { it.name })
        assertEquals(listOf("after"), result.smaliFile.methods.map { it.name })
    }
}
