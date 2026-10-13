package xyz.surendrajat.smalilsp.unit.core

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.parser.SmaliParser

/** [symbolAt] resolves the caret to the token under it, for every kind of token the providers act on. */
class SymbolAtTest {

    private val source = """
        .class public final Lcom/app/Sample;
        .super Lcom/app/Base;
        .implements Ljava/io/Serializable;

        .annotation system Ldalvik/annotation/EnclosingClass;
            value = Lcom/app/Outer;
        .end annotation

        .field private count:I
        .field public items:[Lcom/app/Item;

        .method public run(ILcom/app/Item;[I)Lcom/app/Item;
            .registers 4
            iget v0, p0, Lcom/app/Sample;->count:I
            iget-object v1, p0, Lcom/app/Sample;->items:[Lcom/app/Item;
            if-eqz v0, :cond_0
            new-instance v1, Lcom/app/Helper;
            check-cast p2, [Ljava/lang/Object;
            new-array v1, v0, [I
            :try_start_0
            invoke-virtual {p0, v1, v2}, Lcom/app/Sample;->greet(Lcom/app/Item;ILcom/app/Item;)Lcom/app/Item;
            :try_end_0
            .catch Ljava/io/IOException; {:try_start_0 .. :try_end_0} :catch_0
            :cond_0
            :catch_0
            const-string v1, "done"
            return-object v1
        .end method
    """.trimIndent()

    private val lines = source.lines()
    private val file = SmaliParser().parse("file:///Sample.smali", source)!!

    /** Position of the character at [offset] into [needle] on the line containing [marker], searching after [after]. */
    private fun at(marker: String, needle: String, offset: Int = 0, after: String? = null): Position {
        val line = lines.indexOfFirst { it.contains(marker) }
        val from = lines[line].indexOf(needle, if (after == null) 0 else lines[line].indexOf(after) + after.length)
        require(line >= 0 && from >= 0) { "$needle not found on the line containing $marker" }
        return Position(line, from + offset)
    }

    private fun classRef(p: Position) = file.symbolAt(p) as Symbol.ClassRef

    @Test
    fun `class header`() {
        assertEquals(Symbol.ClassDecl(file.classDefinition), file.symbolAt(at(".class", "Lcom/app/Sample;", 3)))
        assertNull(file.symbolAt(at(".class", ".class", 2)), "the directive keyword is not a symbol")
        assertNull(file.symbolAt(at(".class", "public")))
        assertEquals("Lcom/app/Base;", classRef(at(".super", "Lcom/app/Base;")).className)
        assertEquals("Ljava/io/Serializable;", classRef(at(".implements", "Ljava/io/Serializable;", 10)).className)
    }

    @Test
    fun `directive references`() {
        assertEquals("Ldalvik/annotation/EnclosingClass;", classRef(at(".annotation", "Ldalvik/annotation/EnclosingClass;", 5)).className)
        assertEquals("Lcom/app/Outer;", classRef(at("value =", "Lcom/app/Outer;")).className)
        assertEquals("Ljava/io/IOException;", classRef(at(".catch", "Ljava/io/IOException;", 8)).className)
        assertNull(file.symbolAt(at(".annotation", "system")))
    }

    @Test
    fun `field declarations`() {
        val count = file.fields.single { it.name == "count" }
        assertEquals(Symbol.FieldDecl(count), file.symbolAt(at("count:I", "count", 2)))
        val primitive = file.symbolAt(at("count:I", "I", after = ":")) as Symbol.Primitive
        assertEquals("I", primitive.type)
        assertNull(primitive.inside)
        val items = classRef(at("items:", "[Lcom/app/Item;", 0))
        assertEquals("Lcom/app/Item;", items.className)
        assertEquals("[Lcom/app/Item;", items.type)
        assertNull(file.symbolAt(at("count:I", "private")))
    }

    @Test
    fun `method declaration name and descriptor`() {
        val run = file.methods.single()
        assertEquals(Symbol.MethodDecl(run), file.symbolAt(at(".method", "run", 1)))
        assertEquals("Lcom/app/Item;", classRef(at(".method", "Lcom/app/Item;", 4, after = "(I")).className)
        assertEquals("Lcom/app/Item;", classRef(at(".method", "Lcom/app/Item;", 0, after = ")")).className)
        val primitive = file.symbolAt(at(".method", "[I", 1, after = "(")) as Symbol.Primitive
        assertEquals("[I", primitive.type)
        assertEquals("I", (file.symbolAt(at(".method", "I", 0, after = "(")) as Symbol.Primitive).type)
        assertEquals(Symbol.MethodDecl(run), file.symbolAt(at(".method", "(")), "right after the name still counts as the name")
        assertNull(file.symbolAt(at(".method", ")")), "a parenthesis in a declaration is nothing")
        assertNull(file.symbolAt(at(".method", "public")))
    }

    @Test
    fun `invoke owner, name, descriptor types and duplicates`() {
        val invoke = file.methods.single().instructions.filterIsInstance<InvokeInstruction>().single()
        val owner = classRef(at("invoke-virtual", "Lcom/app/Sample;", 5))
        assertEquals("Lcom/app/Sample;", owner.className)
        assertNull(owner.descriptorOf, "the owner is not part of the descriptor")
        assertEquals(Symbol.MethodRef(invoke, invoke.nameSpan!!.toRange()), file.symbolAt(at("invoke-virtual", "greet", 2)))

        val first = classRef(at("invoke-virtual", "Lcom/app/Item;", 3, after = "greet("))
        val second = classRef(at("invoke-virtual", "Lcom/app/Item;", 3, after = "greet(Lcom/app/Item;I"))
        val ret = classRef(at("invoke-virtual", "Lcom/app/Item;", 3, after = ")"))
        assertEquals(listOf("Lcom/app/Item;", "Lcom/app/Item;", "Lcom/app/Item;"), listOf(first, second, ret).map { it.className })
        assertTrue(first.range.start.character < second.range.start.character && second.range.start.character < ret.range.start.character)
        assertEquals(invoke, first.descriptorOf)

        val primitive = file.symbolAt(at("invoke-virtual", "I", 0, after = "greet(Lcom/app/Item;")) as Symbol.Primitive
        assertEquals("I", primitive.type)
        assertEquals(invoke, primitive.inside)

        assertEquals(Symbol.MethodRef(invoke, invoke.nameSpan!!.toRange()), file.symbolAt(at("invoke-virtual", "(", 0, after = "greet")), "a parenthesis in an invoke descriptor is the method")
    }

    @Test
    fun `invoke keyword, registers and arrow are only the instruction`() {
        val run = file.methods.single()
        val invoke = run.instructions.filterIsInstance<InvokeInstruction>().single()
        for (p in listOf(at("invoke-virtual", "invoke-virtual", 3), at("invoke-virtual", "{p0"), at("invoke-virtual", "->", 1), at("invoke-virtual", " Lcom/app/Sample;"))) {
            assertEquals(Symbol.OnInstruction(invoke, run), file.symbolAt(p), "at $p")
        }
    }

    @Test
    fun `field access owner, name and type`() {
        val run = file.methods.single()
        val iget = run.instructions.filterIsInstance<FieldAccessInstruction>().first { it.fieldName == "count" }
        assertEquals("Lcom/app/Sample;", classRef(at("iget v0", "Lcom/app/Sample;", 1)).className)
        assertEquals(Symbol.FieldRef(iget, iget.nameSpan!!.toRange()), file.symbolAt(at("iget v0", "count", 4)))
        val primitive = file.symbolAt(at("iget v0", "I", 0, after = "count:")) as Symbol.Primitive
        assertEquals(iget, primitive.inside)
        val array = classRef(at("iget-object", "[Lcom/app/Item;", 0, after = "items:"))
        assertEquals("Lcom/app/Item;", array.className)
        assertEquals("[Lcom/app/Item;", array.type)
        assertEquals(Symbol.OnInstruction(iget, run), file.symbolAt(at("iget v0", "p0")))
    }

    @Test
    fun `type operands`() {
        val run = file.methods.single()
        assertEquals("Lcom/app/Helper;", classRef(at("new-instance", "Lcom/app/Helper;", 2)).className)
        val cast = classRef(at("check-cast", "[Ljava/lang/Object;", 0))
        assertEquals("Ljava/lang/Object;", cast.className)
        assertEquals("[Ljava/lang/Object;", cast.type)
        val newArray = run.instructions.filterIsInstance<TypeInstruction>().single { it.opcode == "new-array" }
        val primitive = file.symbolAt(at("new-array", "[I", 1)) as Symbol.Primitive
        assertEquals("[I", primitive.type)
        assertEquals(newArray, primitive.inside)
        assertEquals(Symbol.OnInstruction(newArray, run), file.symbolAt(at("new-array", "v0")))
    }

    @Test
    fun `labels and their references`() {
        val run = file.methods.single()
        assertEquals(Symbol.LabelDecl(run.labels.getValue("cond_0"), run), file.symbolAt(at("    :cond_0", ":cond_0", 3)))
        val jump = file.symbolAt(at("if-eqz", ":cond_0", 1)) as Symbol.LabelRef
        assertEquals("cond_0", jump.name)
        assertEquals(run, jump.method)
        assertEquals("try_end_0", (file.symbolAt(at(".catch", ":try_end_0", 4)) as Symbol.LabelRef).name)
        assertEquals("catch_0", (file.symbolAt(at(".catch", ":catch_0", 0)) as Symbol.LabelRef).name)
        val ifInstr = run.instructions.filterIsInstance<JumpInstruction>().single()
        assertEquals(Symbol.OnInstruction(ifInstr, run), file.symbolAt(at("if-eqz", "v0")))
    }

    @Test
    fun `a caret right after a token still counts, blank spots do not`() {
        val run = file.methods.single()
        val invoke = run.instructions.filterIsInstance<InvokeInstruction>().single()
        assertEquals(Symbol.MethodRef(invoke, invoke.nameSpan!!.toRange()), file.symbolAt(at("invoke-virtual", "greet", 5)))
        assertEquals(Symbol.ClassDecl(file.classDefinition), file.symbolAt(at(".class", "Lcom/app/Sample;", "Lcom/app/Sample;".length)))
        assertNull(file.symbolAt(Position(3, 0)), "blank line")
        assertNull(file.symbolAt(at(".registers", ".registers", 3)))
        assertNull(file.symbolAt(at("return-object", "return-object", 2)), "an instruction that is not modelled")
        assertNull(file.symbolAt(Position(500, 0)))
    }
}
