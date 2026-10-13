package xyz.surendrajat.smalilsp.unit.parser

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.core.JumpInstruction
import xyz.surendrajat.smalilsp.parser.SmaliParser

/**
 * DEX member names may start with a digit (`1a`) or be all digits (`123`); obfuscators
 * use both. Identifiers now admit a leading digit, and all-digit names, which lex as
 * integer literals, are accepted as identifiers by the parser.
 */
class DigitLeadingNameTest {

    private val parser = SmaliParser()

    @ParameterizedTest
    @ValueSource(strings = ["1a", "2b3", "123", "0", "9_x", "0xg"])
    fun `digit-leading names work for fields, methods, call targets and labels`(name: String) {
        val content = """
            .class public final Lcom/example/D;
            .super Ljava/lang/Object;

            .field private $name:I

            .method public $name()V
                .registers 2
                iget v0, p0, Lcom/example/D;->$name:I
                invoke-virtual {p0}, Lcom/example/D;->$name()V
                goto :$name
                :$name
                return-void
            .end method
        """.trimIndent()
        val result = parser.parseWithErrors("file:///D.smali", content)

        assertEquals(emptyList<SmaliParser.SyntaxError>(), result.syntaxErrors, "unexpected syntax errors for $name")
        val file = result.smaliFile!!
        assertEquals(listOf(name), file.fields.map { it.name })
        val method = file.methods.single()
        assertEquals(name, method.name)
        assertEquals(name, method.instructions.filterIsInstance<InvokeInstruction>().single().methodName)
        assertEquals(name, method.instructions.filterIsInstance<JumpInstruction>().single().targetLabel)
        assertEquals(setOf(name), method.labels.keys)
    }

    @Test
    fun `numeric literals are unaffected`() {
        val content = """
            .class public final Lcom/example/N;
            .super Ljava/lang/Object;

            .field public static final B:B = 0x1t

            .method public static run()V
                .registers 3
                const/16 v0, 0x10
                const v1, 16
                const-wide v2, 0x7fffffffffffffffL
                .line 42
                return-void
            .end method
        """.trimIndent()
        val result = parser.parseWithErrors("file:///N.smali", content)
        assertEquals(emptyList<SmaliParser.SyntaxError>(), result.syntaxErrors)
        assertEquals(listOf("run"), result.smaliFile!!.methods.map { it.name })
    }
}
