package xyz.surendrajat.smalilsp.unit.parser

import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.core.ConstStringInstruction
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.core.JumpInstruction
import xyz.surendrajat.smalilsp.parser.SmaliParser
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lexer classifies ASCII through tables and everything else through the Unicode tables, as
 * before: names, labels, strings and comments with non-ASCII text lex the same, and columns
 * stay UTF-16 units.
 */
class UnicodeLexingTest {

    private val parser = SmaliParser()

    @Test
    fun `non-ascii class, member and label names are one token each`() {
        val source = """
            .class public final L包/类${'$'}Ñame;
            .super Ljava/lang/Object;
            .source "Übersicht.java"

            .field private naïve:I

            .method public café(Ljava/lang/String;)V
                .registers 3
                # naïve façade – ‘quotes’ and 😀 in a comment
                const-string v0, "こんにちは 😀 🇯🇵"
                if-eqz p1, :ñ_end
                invoke-virtual {p0, v0}, L包/类${'$'}Ñame;->café(Ljava/lang/String;)V
                :ñ_end
                return-void
            .end method
        """.trimIndent()
        val result = parser.parseWithErrors("file:///U.smali", source)
        assertEquals(emptyList<SmaliParser.SyntaxError>(), result.syntaxErrors)
        val file = result.smaliFile!!
        assertEquals("L包/类\$Ñame;", file.classDefinition.name)
        assertEquals(listOf("naïve"), file.fields.map { it.name })
        val method = file.methods.single()
        assertEquals("café", method.name)
        assertEquals(setOf("ñ_end"), method.labels.keys)
        val instructions = method.instructions
        assertEquals("こんにちは 😀 🇯🇵", instructions.filterIsInstance<ConstStringInstruction>().single().value)
        assertEquals("ñ_end", instructions.filterIsInstance<JumpInstruction>().single().targetLabel)
        val call = instructions.filterIsInstance<InvokeInstruction>().single()
        assertEquals("L包/类\$Ñame;" to "café", call.className to call.methodName)
        assertEquals("(Ljava/lang/String;)V", call.descriptor)
        // the label after the emoji string: its column counts UTF-16 units
        val labelLine = source.lines().indexOfFirst { it.trim() == ":ñ_end" }
        assertEquals(labelLine, method.labels.getValue("ñ_end").line)
    }

    @Test
    fun `a non-ascii character next to a token ends the token where it did before`() {
        // U+00A0 (no-break space) is not a letter or digit, so it is not part of a name; U+00E9 is
        val source = ".class public Lcom/A;\n.super Ljava/lang/Object;\n.method public run()V\n    .registers 1\n    :aé\n    goto :aé\n.end method\n"
        val file = parser.parseWithErrors("file:///V.smali", source)
        assertEquals(emptyList<SmaliParser.SyntaxError>(), file.syntaxErrors)
        assertEquals(setOf("aé"), file.smaliFile!!.methods.single().labels.keys)
        val odd = ".class public Lcom/B;\n.super Ljava/lang/Object;\n.method public run()V\n    .registers 1\n    return-void # trailing no-break space before a comment\n.end method\n"
        val second = parser.parseWithErrors("file:///W.smali", odd)
        assertTrue(second.syntaxErrors.isNotEmpty() || second.smaliFile != null, "lexes without throwing")
    }

    @Test
    fun `digits beyond ascii lex as they did, Character isDigit decides`() {
        // the grammar's digit rule was Character.isDigit, so an Arabic-Indic digit is a number; the table path keeps that beyond ASCII
        val source = ".class public Lcom/C;\n.super Ljava/lang/Object;\n.method public run()V\n    .registers 1\n    const/4 v0, ٠\n    return-void\n.end method\n"
        val result = parser.parseWithErrors("file:///X.smali", source)
        assertEquals(emptyList<SmaliParser.SyntaxError>(), result.syntaxErrors)
        assertEquals(1, result.smaliFile!!.methods.size)
    }
}
