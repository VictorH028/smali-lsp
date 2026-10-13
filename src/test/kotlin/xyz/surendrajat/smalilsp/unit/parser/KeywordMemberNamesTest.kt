package xyz.surendrajat.smalilsp.unit.parser

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.core.JumpInstruction
import xyz.surendrajat.smalilsp.parser.SmaliParser

/**
 * Member names that the lexer classifies as literals or opcodes (`NaN` in Kotlin's
 * `DoubleCompanionObject`, `Infinity`, `true`, `null`, obfuscated `move`/`goto`) were
 * rejected by the `identifier`/`methodName` rules. DEX allows any of them.
 */
class KeywordMemberNamesTest {

    private val parser = SmaliParser()
    private val uri = "file:///Names.smali"

    private fun parseClean(content: String): xyz.surendrajat.smalilsp.core.SmaliFile {
        val result = parser.parseWithErrors(uri, content.trimIndent())
        assertEquals(emptyList<SmaliParser.SyntaxError>(), result.syntaxErrors, "unexpected syntax errors")
        return result.smaliFile!!
    }

    @Test
    fun `Kotlin companion object NaN fields parse`() {
        val file = parseClean("""
            .class public final Lkotlin/jvm/internal/DoubleCompanionObject;
            .super Ljava/lang/Object;

            .field public static final NaN:D = NaN

            .field public static final NEGATIVE_INFINITY:D = -Infinity

            .field public static final POSITIVE_INFINITY:F = Infinityf

            .method public final getNaN()D
                .registers 3
                sget-wide v0, Lkotlin/jvm/internal/DoubleCompanionObject;->NaN:D
                return-wide v0
            .end method
        """)
        assertEquals(listOf("NaN", "NEGATIVE_INFINITY", "POSITIVE_INFINITY"), file.fields.map { it.name })
        assertEquals(listOf("getNaN"), file.methods.map { it.name })
    }

    @ParameterizedTest
    @ValueSource(strings = ["NaN", "Infinity", "true", "false", "null", "move", "goto", "const", "return", "throw", "nop", "if-eq", "synchronized", "strictfp", "I", "V"])
    fun `keyword-like names work for fields, methods, call targets and labels`(name: String) {
        val file = parseClean("""
            .class public final Lcom/example/K;
            .super Ljava/lang/Object;

            .field private $name:I

            .method public $name()V
                .registers 2
                iget v0, p0, Lcom/example/K;->$name:I
                invoke-virtual {p0}, Lcom/example/K;->$name()V
                if-eqz v0, :$name
                goto :$name
                :$name
                return-void
            .end method
        """)
        assertEquals(listOf(name), file.fields.map { it.name })
        val method = file.methods.single()
        assertEquals(name, method.name)
        val invoke = method.instructions.filterIsInstance<InvokeInstruction>().single()
        assertEquals(name, invoke.methodName)
        assertEquals(listOf(name, name), method.instructions.filterIsInstance<JumpInstruction>().map { it.targetLabel })
        assertEquals(setOf(name), method.labels.keys)
    }

    @Test
    fun `a label line followed by an instruction keeps the instruction`() {
        // Guard against a repeated identifier rule swallowing the opcode after a label.
        val file = parseClean("""
            .class public final Lcom/example/L;
            .super Ljava/lang/Object;

            .method public f(I)I
                .registers 2
                if-eqz p1, :cond_0
                :cond_0
                move v0, p1
                :goto_0
                return v0
            .end method
        """)
        val method = file.methods.single()
        assertEquals(setOf("cond_0", "goto_0"), method.labels.keys)
        assertEquals(1, method.instructions.filterIsInstance<JumpInstruction>().size)
    }
}
