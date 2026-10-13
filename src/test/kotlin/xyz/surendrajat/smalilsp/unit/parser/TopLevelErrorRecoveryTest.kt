package xyz.surendrajat.smalilsp.unit.parser

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.parser.SmaliParser

/**
 * The start rule used to lack `EOF`: at the first top-level construct it could not
 * match, the parser returned success and the rest of the file silently vanished from
 * the AST. On a real APK this hid all 893 methods of a class behind one bad field.
 * Now the construct is reported and parsing resumes after it.
 */
class TopLevelErrorRecoveryTest {

    private val parser = SmaliParser()

    @Test
    fun `an unparseable top-level construct is reported and parsing resumes`() {
        // `.bogus` is not a directive: the lexer yields DOT + IDENTIFIER, which no statement can start with.
        val content = """
            .class public final Lcom/example/Broken;
            .super Ljava/lang/Object;

            .method public static before()V
                .registers 0
                return-void
            .end method

            .bogus directive here

            .method public static after()V
                .registers 0
                return-void
            .end method
        """.trimIndent()

        val result = parser.parseWithErrors("file:///Broken.smali", content)

        assertTrue(result.syntaxErrors.isNotEmpty(), "the bogus directive must produce a syntax error")
        assertEquals(9, result.syntaxErrors.first().line, "error should point at the bogus line (1-based)")
        assertEquals(listOf("before", "after"), result.smaliFile!!.methods.map { it.name })
    }

    @Test
    fun `trailing garbage after the last method is reported`() {
        val content = """
            .class public final Lcom/example/Trailing;
            .super Ljava/lang/Object;

            .method public static only()V
                .registers 0
                return-void
            .end method

            this is not smali
        """.trimIndent()

        val result = parser.parseWithErrors("file:///Trailing.smali", content)

        assertTrue(result.syntaxErrors.isNotEmpty(), "trailing garbage must produce a syntax error")
        assertEquals(listOf("only"), result.smaliFile!!.methods.map { it.name })
    }

    @Test
    fun `a field whose initializer cannot be parsed does not swallow the following methods`() {
        // Mirrors the real-world case: an unexpected token after a field initializer.
        val content = """
            .class public final Lcom/example/Init;
            .super Ljava/lang/Object;

            .field public static final X:I = 0x1 unexpected

            .method public static after()V
                .registers 0
                return-void
            .end method
        """.trimIndent()

        val result = parser.parseWithErrors("file:///Init.smali", content)

        assertTrue(result.syntaxErrors.isNotEmpty())
        assertEquals(listOf("after"), result.smaliFile!!.methods.map { it.name })
    }
}
