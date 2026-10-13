package xyz.surendrajat.smalilsp.unit.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.parser.SmaliParser

/**
 * DEX names may contain `-`, so the identifier token allows it — but that made `I-` in
 * `[I->clone()` lex as one identifier and split the arrow, producing a false syntax
 * error on every primitive-array `clone()` call. Identifiers now cannot end with a hyphen.
 */
class HyphenatedNameTest {

    private val parser = SmaliParser()

    private fun parseClean(content: String): xyz.surendrajat.smalilsp.core.SmaliFile {
        val result = parser.parseWithErrors("file:///Hy.smali", content.trimIndent())
        assertEquals(emptyList<SmaliParser.SyntaxError>(), result.syntaxErrors, "unexpected syntax errors")
        return result.smaliFile!!
    }

    @ParameterizedTest
    @ValueSource(strings = ["[I", "[B", "[J", "[[B", "[Ljava/lang/Object;", "[[Lcom/example/Pt;"])
    fun `array-typed invoke targets parse`(owner: String) {
        val file = parseClean("""
            .class public final Lcom/example/A;
            .super Ljava/lang/Object;

            .method public static copy(Ljava/lang/Object;)Ljava/lang/Object;
                .registers 2
                check-cast p0, $owner
                invoke-virtual {p0}, $owner->clone()Ljava/lang/Object;
                move-result-object p0
                return-object p0
            .end method
        """)
        val invoke = file.methods.single().instructions.filterIsInstance<InvokeInstruction>().single()
        assertEquals(owner, invoke.className)
        assertEquals("clone", invoke.methodName)
    }

    @Test
    fun `hyphens inside and at the start of names still parse`() {
        val file = parseClean("""
            .class public final Lcom/example/-Hy;
            .super Ljava/lang/Object;

            .field private a-b:I

            .field private -c:I

            .method public static -deprecated_run(I)I
                .registers 1
                return p0
            .end method

            .method public x-y()V
                .registers 2
                iget v0, p0, Lcom/example/-Hy;->a-b:I
                iget v0, p0, Lcom/example/-Hy;->-c:I
                invoke-static {v0}, Lcom/example/-Hy;->-deprecated_run(I)I
                invoke-virtual {p0}, Lcom/example/-Hy;->x-y()V
                goto :a-b
                :a-b
                return-void
            .end method
        """)
        assertEquals("Lcom/example/-Hy;", file.classDefinition.name)
        assertEquals(listOf("a-b", "-c"), file.fields.map { it.name })
        assertEquals(listOf("-deprecated_run", "x-y"), file.methods.map { it.name })
        val invokes = file.methods[1].instructions.filterIsInstance<InvokeInstruction>()
        assertEquals(listOf("-deprecated_run", "x-y"), invokes.map { it.methodName })
        assertEquals(setOf("a-b"), file.methods[1].labels.keys)
    }
}
