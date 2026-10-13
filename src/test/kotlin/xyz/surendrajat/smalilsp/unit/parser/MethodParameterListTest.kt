package xyz.surendrajat.smalilsp.unit.parser

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.parser.SmaliParser

/**
 * Consecutive primitive parameters (`IIS`) lex as one token. The parser used to accept *any*
 * identifier in that position, so `(IX)V` parsed without error. A dedicated token keeps the
 * fast path and rejects non-type letters, while members named like `ID` still parse.
 */
class MethodParameterListTest {

    private val parser = SmaliParser()

    @ParameterizedTest
    @ValueSource(strings = ["()V", "(I)V", "(IIS)V", "(ID)V", "(ZBSCIJFD)V", "(IILjava/lang/String;)V", "(Ljava/lang/String;II)V", "([I[[J)V", "(J[Ljava/lang/Object;ZZ)V"])
    fun `valid parameter lists parse`(descriptor: String) {
        val result = parser.parseWithErrors("file:///P.smali", """
            .class public final Lcom/example/P;
            .super Ljava/lang/Object;

            .method public static f$descriptor
                .registers 9
                return-void
            .end method

            .method public static call()V
                .registers 9
                invoke-static/range {v0 .. v8}, Lcom/example/P;->f$descriptor
                return-void
            .end method
        """.trimIndent())
        assertEquals(emptyList<SmaliParser.SyntaxError>(), result.syntaxErrors, "unexpected syntax errors for $descriptor")
        val file = result.smaliFile!!
        assertEquals(descriptor, file.methods[0].descriptor)
        assertEquals(descriptor, file.methods[1].instructions.filterIsInstance<InvokeInstruction>().single().descriptor)
    }

    @ParameterizedTest
    @ValueSource(strings = ["(IX)V", "(Iabc)V", "(IV)V", "(Q)V"])
    fun `non-type letters in a parameter list are syntax errors`(descriptor: String) {
        val result = parser.parseWithErrors("file:///Q.smali", """
            .class public final Lcom/example/Q;
            .super Ljava/lang/Object;

            .method public static broken$descriptor
                .registers 1
                return-void
            .end method
        """.trimIndent())
        assertTrue(result.syntaxErrors.isNotEmpty(), "$descriptor must be reported")
        assertEquals(4, result.syntaxErrors.first().line)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ID", "IS", "ZZ", "BSD"])
    fun `members and labels spelled like primitive sequences still parse`(name: String) {
        val result = parser.parseWithErrors("file:///N.smali", """
            .class public final Lcom/example/N;
            .super Ljava/lang/Object;

            .field private $name:I

            .method public $name()V
                .registers 2
                iget v0, p0, Lcom/example/N;->$name:I
                invoke-virtual {p0}, Lcom/example/N;->$name()V
                goto :$name
                :$name
                return-void
            .end method
        """.trimIndent())
        assertEquals(emptyList<SmaliParser.SyntaxError>(), result.syntaxErrors, "unexpected syntax errors for $name")
        val file = result.smaliFile!!
        assertEquals(listOf(name), file.fields.map { it.name })
        assertEquals(name, file.methods.single().name)
        assertEquals(setOf(name), file.methods.single().labels.keys)
    }
}
