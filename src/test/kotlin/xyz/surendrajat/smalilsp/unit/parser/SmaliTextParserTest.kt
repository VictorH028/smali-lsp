package xyz.surendrajat.smalilsp.unit.parser

import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.core.ConstStringInstruction
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.parser.InstructionShapes
import xyz.surendrajat.smalilsp.parser.SmaliParser
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the hand-written front end reports and recovers from, beyond the behavior the grammar tests pin. */
class SmaliTextParserTest {

    private val parser = SmaliParser()

    private fun errors(content: String) = parser.parseWithErrors("file:///T.smali", content.trimIndent()).syntaxErrors

    @Test
    fun `an unknown opcode names itself and covers its token`() {
        val line = "    bogus-opcode v0, v1"
        val content = ".class public Lcom/T;\n.super Ljava/lang/Object;\n.method public run()V\n$line\n    return-void\n.end method\n"
        val error = parser.parseWithErrors("file:///T.smali", content).syntaxErrors.single()
        assertEquals(4, error.line)
        assertEquals(line.indexOf("bogus"), error.charPositionInLine)
        assertEquals("bogus-opcode".length, error.length)
        assertEquals("Unknown opcode 'bogus-opcode'", error.message)
    }

    @Test
    fun `every opcode of the grammar has an operand shape and parses with it`() {
        assertEquals(224, InstructionShapes.opcodes.size)
        for (opcode in listOf("const/4", "const-wide/high16", "invoke-static/range", "add-int/lit8", "goto/32", "move-result-object", "nop")) {
            assertNotNull(InstructionShapes[opcode], opcode)
        }
        assertNull(InstructionShapes["const/5"])
    }

    @Test
    fun `an operand of the wrong kind is reported on that operand`() {
        val content = """
            .class public Lcom/T;
            .super Ljava/lang/Object;
            .method public run()V
                .registers 2
                const/4 v0, "text"
                invoke-virtual {v0}, Lcom/T;
                iget v0, p0, Lcom/T;->f
                return-void
            .end method
        """
        val errors = errors(content)
        assertEquals(listOf(5, 6, 7), errors.map { it.line }, "a statement cut short is reported where it stopped, not on the next line: $errors")
        assertEquals("Expected a number, not '\"text\"'", errors[0].message)
        assertTrue(errors[1].message.startsWith("Expected '->'"), errors[1].message)
        assertEquals("    invoke-virtual {v0}, Lcom/T;".length, errors[1].charPositionInLine)
        assertEquals(1, errors[1].length)
        assertTrue(errors[2].message.startsWith("Expected ':'"), errors[2].message)
    }

    @Test
    fun `parsing resumes at the next line and keeps the rest of the method`() {
        val content = """
            .class public Lcom/T;
            .super Ljava/lang/Object;
            .method public run()V
                .registers 2
                invoke-virtual {v0}, Lcom/T;->a()V
                bogus v0
                invoke-virtual {v0}, Lcom/T;->b()V
                :end
                return-void
            .end method
        """
        val result = parser.parseWithErrors("file:///T.smali", content.trimIndent())
        assertEquals(1, result.syntaxErrors.size)
        val method = result.smaliFile!!.methods.single()
        assertEquals(listOf("a", "b"), method.instructions.filterIsInstance<InvokeInstruction>().map { it.methodName })
        assertEquals(setOf("end"), method.labels.keys)
    }

    @Test
    fun `a statement may wrap onto the next line, as the assembler allows`() {
        val content = """
            .class public Lcom/T;
            .super Ljava/lang/Object;
            .method public run()V
                .registers 2
                invoke-virtual {v0},
                    Lcom/T;->a()V
                const-string v1, "x"
                return-void
            .end method
        """
        val result = parser.parseWithErrors("file:///T.smali", content.trimIndent())
        assertEquals(emptyList(), result.syntaxErrors)
        val invoke = result.smaliFile!!.methods.single().instructions.filterIsInstance<InvokeInstruction>().single()
        assertEquals("a", invoke.methodName)
        assertEquals(4, invoke.startLine)
        assertEquals(5, invoke.endLine, "the instruction ends on the second line")
        assertEquals(5, invoke.targetLine)
        assertEquals(8, invoke.targetStart, "the target itself is one contiguous run on its own line")
        assertEquals(listOf("x"), result.smaliFile.methods.single().instructions.filterIsInstance<ConstStringInstruction>().map { it.value })
    }

    @Test
    fun `an unterminated string is reported at the quote`() {
        val content = ".class public Lcom/T;\n.super Ljava/lang/Object;\n.method public run()V\n    const-string v0, \"never closed\n    return-void\n.end method\n"
        val error = parser.parseWithErrors("file:///T.smali", content).syntaxErrors.first()
        assertEquals(4, error.line)
        assertEquals("    const-string v0, ".length, error.charPositionInLine)
    }

    @Test
    fun `a file that ends inside a method is reported at the end of the file`() {
        val content = ".class public Lcom/T;\n.super Ljava/lang/Object;\n.method public run()V\n    return-void"
        val result = parser.parseWithErrors("file:///T.smali", content)
        val error = result.syntaxErrors.single()
        assertEquals(4, error.line)
        assertEquals("    return-void".length, error.charPositionInLine)
        assertEquals(1, error.length)
        assertEquals("Missing '.end method'", error.message)
        assertEquals(listOf("run"), result.smaliFile!!.methods.map { it.name }, "the method is kept with what it had")
    }

    @Test
    fun `a method cut off by the next declaration is closed there`() {
        val content = """
            .class public Lcom/T;
            .super Ljava/lang/Object;
            .method public first()V
                return-void
            .method public second()V
                return-void
            .end method
        """
        val result = parser.parseWithErrors("file:///T.smali", content.trimIndent())
        assertEquals(listOf(5), result.syntaxErrors.map { it.line })
        assertEquals(listOf("first", "second"), result.smaliFile!!.methods.map { it.name })
    }

    @Test
    fun `stray end directives and members outside a method are reported once each`() {
        val content = """
            .class public Lcom/T;
            .super Ljava/lang/Object;
            .end method
            const/4 v0, 0x0
            :label
            .field public ok:I
        """
        val result = parser.parseWithErrors("file:///T.smali", content.trimIndent())
        assertEquals(listOf(3, 4, 5), result.syntaxErrors.map { it.line })
        assertEquals(listOf("ok"), result.smaliFile!!.fields.map { it.name })
    }

    @Test
    fun `a modifier spelled name still declares the member`() {
        val content = """
            .class public Lcom/T;
            .super Ljava/lang/Object;
            .field private static:I
            .method public final()V
                return-void
            .end method
        """
        val result = parser.parseWithErrors("file:///T.smali", content.trimIndent())
        assertEquals(emptyList(), result.syntaxErrors)
        assertEquals(listOf("static"), result.smaliFile!!.fields.map { it.name })
        assertEquals(setOf("private"), result.smaliFile.fields.single().modifiers)
        assertEquals("final", result.smaliFile.methods.single().name)
        assertEquals(setOf("public"), result.smaliFile.methods.single().modifiers)
    }

    @Test
    fun `comments and blank lines between tokens of a declaration do not reach the model`() {
        val content = """
            .class public Lcom/T;
            .super Ljava/lang/Object;
            .field public a:[I    # an array

            # direct methods
            .method public run()V   # comment after the signature
                return-void
            .end method
        """
        val file = parser.parse("file:///T.smali", content.trimIndent())!!
        assertEquals("[I", file.fields.single().type)
        assertEquals("()V", file.methods.single().descriptor)
    }
}
