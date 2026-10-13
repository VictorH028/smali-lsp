package xyz.surendrajat.smalilsp.unit.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.core.FieldAccessInstruction
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.core.Span
import xyz.surendrajat.smalilsp.core.TypeInstruction
import xyz.surendrajat.smalilsp.parser.SmaliParser

/** The builder records where every navigable token sits; expected spans are read off the source text. */
class AstSpansTest {

    private val source = """
        .class public final Lcom/app/Sample;
        .super Ljava/lang/Object;
        .implements Ljava/io/Serializable;

        .annotation system Ldalvik/annotation/EnclosingClass;
            value = Lcom/app/Outer;
        .end annotation

        .field private count:I
        .field public static items:[Ljava/lang/String;

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public run(ILjava/util/List;)Ljava/lang/String;
            .registers 4
            .local v0, "x":Ljava/lang/Integer;
            iget v0, p0, Lcom/app/Sample;->count:I
            if-eqz v0, :cond_0
            new-instance v1, Lcom/app/Helper;
            check-cast p2, [Ljava/lang/Object;
            :try_start_0
            invoke-virtual {p0, v1}, Lcom/app/Sample;->greet(Lcom/app/Helper;)V
            :try_end_0
            .catch Ljava/io/IOException; {:try_start_0 .. :try_end_0} :catch_0
            packed-switch v0, :pswitch_data_0
            :cond_0
            :catch_0
            const-string v1, "done"
            return-object v1
            :pswitch_data_0
            .packed-switch 0x0
                :cond_0
            .end packed-switch
        .end method
    """.trimIndent()

    private val lines = source.lines()
    private val file = SmaliParser().parse("file:///Sample.smali", source)!!

    /** Span of [needle] on the line that contains [marker], searching after [after] when given. */
    private fun spanOf(marker: String, needle: String, after: String? = null): Span {
        val line = lines.indexOfFirst { it.contains(marker) }
        require(line >= 0) { "no line contains $marker" }
        val from = lines[line].indexOf(needle, if (after == null) 0 else lines[line].indexOf(after) + after.length)
        require(from >= 0) { "no $needle on line $line" }
        return Span(line, from, from + needle.length)
    }

    @Test
    fun `class, super and implements tokens`() {
        val c = file.classDefinition
        assertEquals(spanOf(".class", "Lcom/app/Sample;"), c.nameSpan)
        assertEquals(spanOf(".super", "Ljava/lang/Object;").toRange(), c.superClassRange)
        assertEquals(spanOf(".implements", "Ljava/io/Serializable;").toRange(), c.interfaceRanges["Ljava/io/Serializable;"])
    }

    @Test
    fun `class references inside directives`() {
        val expected = listOf(
            "Ldalvik/annotation/EnclosingClass;" to spanOf(".annotation system", "Ldalvik/annotation/EnclosingClass;"),
            "Lcom/app/Outer;" to spanOf("value =", "Lcom/app/Outer;"),
            "Ljava/lang/Integer;" to spanOf(".local", "Ljava/lang/Integer;"),
            "Ljava/io/IOException;" to spanOf(".catch", "Ljava/io/IOException;"),
        )
        assertEquals(expected, file.typeRefs.map { it.className to it.span })
    }

    @Test
    fun `field name and type tokens`() {
        val count = file.fields.single { it.name == "count" }
        assertEquals(spanOf("count:I", "count"), count.nameSpan)
        assertEquals(spanOf("count:I", "I", after = ":"), count.typeSpan)
        val items = file.fields.single { it.name == "items" }
        assertEquals(spanOf("items:", "items"), items.nameSpan)
        assertEquals(spanOf("items:", "[Ljava/lang/String;"), items.typeSpan)
    }

    @Test
    fun `method name and descriptor tokens`() {
        val init = file.methods.single { it.name == "<init>" }
        assertEquals(spanOf("constructor <init>", "<init>"), init.nameSpan)
        assertEquals(spanOf("constructor <init>", "()V"), init.descriptorSpan)
        val run = file.methods.single { it.name == "run" }
        assertEquals(spanOf(".method public run", "run"), run.nameSpan)
        assertEquals(spanOf(".method public run", "(ILjava/util/List;)Ljava/lang/String;"), run.descriptorSpan)
    }

    @Test
    fun `invoke owner, name and descriptor tokens`() {
        val init = file.methods.single { it.name == "<init>" }.instructions.filterIsInstance<InvokeInstruction>().single()
        assertEquals(spanOf("invoke-direct", "Ljava/lang/Object;"), init.ownerSpan)
        assertEquals(spanOf("invoke-direct", "<init>"), init.nameSpan)
        assertEquals(spanOf("invoke-direct", "()V"), init.descriptorSpan)
        val greet = file.methods.single { it.name == "run" }.instructions.filterIsInstance<InvokeInstruction>().single()
        assertEquals(spanOf("invoke-virtual", "Lcom/app/Sample;"), greet.ownerSpan)
        assertEquals(spanOf("invoke-virtual", "greet"), greet.nameSpan)
        assertEquals(spanOf("invoke-virtual", "(Lcom/app/Helper;)V"), greet.descriptorSpan)
    }

    @Test
    fun `field access owner, name and type tokens`() {
        val iget = file.methods.single { it.name == "run" }.instructions.filterIsInstance<FieldAccessInstruction>().single()
        assertEquals(spanOf("iget v0", "Lcom/app/Sample;"), iget.ownerSpan)
        assertEquals(spanOf("iget v0", "count"), iget.nameSpan)
        assertEquals(spanOf("iget v0", "I", after = "->count:"), iget.typeSpan)
    }

    @Test
    fun `type operand tokens including array brackets`() {
        val types = file.methods.single { it.name == "run" }.instructions.filterIsInstance<TypeInstruction>()
        assertEquals(spanOf("new-instance", "Lcom/app/Helper;"), types.single { it.opcode == "new-instance" }.typeSpan)
        assertEquals(spanOf("check-cast", "[Ljava/lang/Object;"), types.single { it.opcode == "check-cast" }.typeSpan)
    }

    @Test
    fun `every label reference in the method body`() {
        val run = file.methods.single { it.name == "run" }
        val expected = listOf(
            "cond_0" to spanOf("if-eqz", ":cond_0"),
            "try_start_0" to spanOf(".catch", ":try_start_0"),
            "try_end_0" to spanOf(".catch", ":try_end_0"),
            "catch_0" to spanOf(".catch", ":catch_0"),
            "pswitch_data_0" to spanOf("packed-switch v0", ":pswitch_data_0"),
            "cond_0" to spanOf("    :cond_0", ":cond_0").let { Span(lines.indexOf("        :cond_0"), 8, 15) },
        )
        assertEquals(expected.map { it.first }, run.labelRefs.map { it.name })
        assertEquals(expected.map { it.second }, run.labelRefs.map { it.span })
        assertEquals(setOf("try_start_0", "try_end_0", "cond_0", "catch_0", "pswitch_data_0"), run.labels.keys)
        assertNotNull(run.labels["cond_0"])
        assertEquals(Span(run.labelRefs[0].span.line, run.labelRefs[0].span.start + 1, run.labelRefs[0].span.end), run.labelRefs[0].nameSpan)
    }
}
