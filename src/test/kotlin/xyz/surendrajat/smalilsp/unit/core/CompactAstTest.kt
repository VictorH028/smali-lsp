package xyz.surendrajat.smalilsp.unit.core

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.core.Block
import xyz.surendrajat.smalilsp.core.BlockList
import xyz.surendrajat.smalilsp.core.ClassDefinition
import xyz.surendrajat.smalilsp.core.FieldDefinition
import xyz.surendrajat.smalilsp.core.LabelDefinition
import xyz.surendrajat.smalilsp.core.LabelMap
import xyz.surendrajat.smalilsp.core.LabelRef
import xyz.surendrajat.smalilsp.core.RangeList
import xyz.surendrajat.smalilsp.core.TypeRefList
import xyz.surendrajat.smalilsp.core.LabelRefList
import xyz.surendrajat.smalilsp.core.MethodDefinition
import xyz.surendrajat.smalilsp.core.Parameter
import xyz.surendrajat.smalilsp.core.ParameterList
import xyz.surendrajat.smalilsp.core.Span
import xyz.surendrajat.smalilsp.core.TypeRef
import xyz.surendrajat.smalilsp.core.range
import xyz.surendrajat.smalilsp.util.StringPool
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The compact forms behind the AST's `Map` and `List` interfaces behave exactly like the collections they replace. */
class CompactAstTest {

    private fun label(name: String, line: Int) = LabelDefinition(name, line, 4, 4 + name.length + 1)

    @Test
    fun `a label map answers like the linked map it was built from`() {
        val source = linkedMapOf("goto_1" to label("goto_1", 9), "cond_0" to label("cond_0", 3), "sswitch_data_0" to label("sswitch_data_0", 20), "cond_1" to label("cond_1", 7))
        val map = LabelMap.of(source)
        assertTrue(map is LabelMap)
        assertEquals(4, map.size)
        for ((name, def) in source) {
            assertEquals(def, map[name], "definitions are rebuilt from the arrays, equal by value")
            assertTrue(name in map)
        }
        assertNull(map["cond_2"])
        assertFalse("cond" in map)
        assertEquals(source.keys, map.keys)
        assertEquals(source.values.toSet(), map.values.toSet())
        assertEquals(source as Map<String, LabelDefinition>, map, "content equality against a LinkedHashMap")
        assertEquals(map, source as Map<String, LabelDefinition>)
        assertEquals(source.hashCode(), map.hashCode())
        assertEquals(source.entries.map { it.key to it.value }.toSet(), map.entries.map { it.key to it.value }.toSet())
    }

    @Test
    fun `empty and single-label methods get the smallest map`() {
        assertEquals(emptyMap<String, LabelDefinition>(), LabelMap.of(emptyMap()))
        val one = LabelMap.of(mapOf("cond_0" to label("cond_0", 3)))
        assertEquals(1, one.size)
        assertEquals(3, one["cond_0"]?.line)
        assertTrue(one !is LabelMap, "a singleton map needs no arrays")
    }

    @Test
    fun `block and parameter lists are lists`() {
        val blocks = listOf(Block(3, 9), Block(12, 40), Block(50, 51))
        val compact = BlockList.of(blocks)
        assertEquals(blocks, compact)
        assertEquals(compact, blocks)
        assertEquals(blocks.hashCode(), compact.hashCode())
        assertEquals(Block(12, 40), compact[1])
        assertEquals(listOf(3, 12, 50), compact.map { it.startLine })
        assertTrue(BlockList.of(emptyList()).isEmpty())

        val parameters = ParameterList.of(listOf("I", "Ljava/lang/String;", "J"))
        assertEquals(listOf(Parameter("I", null), Parameter("Ljava/lang/String;", null), Parameter("J", null)), parameters)
        assertEquals(3, parameters.size)
        assertEquals("J", parameters[2].type)
        assertNull(parameters[0].name)
        assertTrue(ParameterList.of(emptyList()).isEmpty())
    }

    @Test
    fun `declarations built from ranges and spans read back the same objects`() {
        val method = MethodDefinition("run", "(I)V", range(10, 0, 20, 11), setOf("public"), ParameterList.of(listOf("I")), "V",
            nameSpan = Span(10, 15, 18), descriptorSpan = Span(10, 18, 22), labelRefs = listOf(LabelRef("cond_0", Span(14, 12, 19))))
        assertEquals(range(10, 0, 20, 11), method.range)
        assertEquals(Span(10, 15, 18), method.nameSpan)
        assertEquals(Span(10, 18, 22), method.descriptorSpan)
        assertEquals(Span(14, 12, 19), method.labelRefs[0].span)
        assertEquals(Span(14, 13, 19), method.labelRefs[0].nameSpan)
        assertEquals(method, MethodDefinition("run", "(I)V", 10, 0, 20, 11, setOf("public"), listOf(Parameter("I", null)), "V",
            nameLine = 10, nameStart = 15, nameEnd = 18, descriptorLine = 10, descriptorStart = 18, descriptorEnd = 22, labelRefs = listOf(LabelRef("cond_0", 14, 12, 19))))
        assertTrue(method.touches(Position(10, 0)) && method.touches(Position(20, 11)) && method.touches(Position(15, 99)))
        assertFalse(method.touches(Position(9, 99)) || method.touches(Position(20, 12)))

        val field = FieldDefinition("count", "I", range(4, 0, 4, 30), setOf("private", "final"), nameSpan = Span(4, 21, 26), typeSpan = Span(4, 27, 28))
        assertEquals(range(4, 0, 4, 30), field.range)
        assertEquals(Span(4, 21, 26), field.nameSpan)
        assertEquals(Span(4, 27, 28), field.typeSpan)
        val defaulted = FieldDefinition("count", "I", range(4, 0, 4, 30), setOf("private"))
        assertEquals(Span(4, 0, 0), defaulted.nameSpan, "the default span sits at the declaration start, as before")

        val cls = ClassDefinition("Lcom/A;", range(0, 0, 100, 0), setOf("public"), "Ljava/lang/Object;", emptyList(), nameSpan = Span(0, 14, 21))
        assertEquals(range(0, 0, 100, 0), cls.range)
        assertEquals(Span(0, 14, 21), cls.nameSpan)
        assertEquals(Span(7, 11, 19), TypeRef("Lcom/B;", Span(7, 11, 19)).span)
    }

    @Test
    fun `label refs, type refs and interface tokens are lists of what they were built from`() {
        val refs = listOf(LabelRef("cond_0", Span(14, 12, 19)), LabelRef("goto_1", Span(20, 4, 11)))
        val packedRefs = LabelRefList.of(refs)
        assertEquals(refs, packedRefs)
        assertEquals(packedRefs, refs)
        assertEquals(refs.hashCode(), packedRefs.hashCode())
        assertEquals(Span(20, 5, 11), packedRefs[1].nameSpan)
        assertEquals(1, packedRefs.count { it.name == "goto_1" })
        assertTrue(LabelRefList.of(emptyList()).isEmpty())

        val types = listOf(TypeRef("Lcom/A;", Span(3, 10, 17)), TypeRef("Lcom/B;", Span(5, 2, 9)))
        val packedTypes = TypeRefList.of(types)
        assertEquals(types, packedTypes)
        assertEquals("Lcom/B;", packedTypes[1].className)
        assertEquals(Span(5, 2, 9), packedTypes[1].span)
        assertTrue(TypeRefList.of(emptyList()).isEmpty())

        val ranges = listOf(range(1, 12, 1, 30), range(2, 12, 3, 4))
        assertEquals(ranges, RangeList.of(ranges))
        assertEquals(range(2, 12, 3, 4), RangeList.of(ranges)[1])
    }

    @Test
    fun `a class definition reads back its super and implements tokens`() {
        val cls = ClassDefinition("Lcom/A;", range(0, 0, 0, 22), setOf("public"), "Lcom/Base;", listOf("Lcom/I1;", "Lcom/I2;"),
            superClassRange = range(1, 7, 1, 17), interfaceRanges = mapOf("Lcom/I2;" to range(3, 12, 3, 20)), nameSpan = Span(0, 14, 21))
        assertEquals(range(1, 7, 1, 17), cls.superClassRange)
        assertEquals(mapOf("Lcom/I2;" to range(3, 12, 3, 20)), cls.interfaceRanges, "an interface without a recorded token is left out")
        assertEquals(cls, ClassDefinition("Lcom/A;", 0, 0, 0, 22, setOf("public"), "Lcom/Base;", listOf("Lcom/I1;", "Lcom/I2;"),
            superLine = 1, superStart = 7, superEndLine = 1, superEnd = 17, interfaceTokens = listOf(range(-1, -1, -1, -1), range(3, 12, 3, 20)), nameLine = 0, nameStart = 14, nameEnd = 21))
        val plain = ClassDefinition("Lcom/A;", range(0, 0, 0, 22), setOf("public"), null, emptyList())
        assertNull(plain.superClassRange)
        assertEquals(emptyMap<String, org.eclipse.lsp4j.Range>(), plain.interfaceRanges)
    }

    @Test
    fun `a label map built from arrays hands out equal definitions`() {
        val map = LabelMap.of(mapOf("b" to label("b", 2), "a" to label("a", 1), "c" to label("c", 3)))
        assertEquals(label("a", 1), map["a"])
        assertEquals(listOf(label("a", 1), label("b", 2), label("c", 3)), map.values.toList())
        assertEquals(setOf("a", "b", "c"), map.keys)
    }

    @Test
    fun `modifier sets are shared per combination and keep source order`() {
        val pool = StringPool()
        val a = pool.internModifiers(listOf("public", "static", "final"))
        val b = pool.internModifiers(listOf("public", "static", "final"))
        val c = pool.internModifiers(listOf("final", "static", "public"))
        assertSame(a, b)
        assertNotSame(a, c, "order is part of the combination")
        assertEquals(listOf("public", "static", "final"), a.toList())
        assertEquals(setOf("final", "static", "public"), a, "set equality ignores order")
        assertTrue("static" in a)
        assertEquals(emptySet<String>(), pool.internModifiers(emptyList()))
        assertEquals("[public, static, final]", a.toString())
    }

    @Test
    fun `short literals are interned and long ones pass through`() {
        val pool = StringPool()
        val value = "resource_key_" + "x".repeat(10)
        assertSame(pool.internLiteral(value), pool.internLiteral(String(value.toCharArray())))
        val long = "y".repeat(200)
        assertSame(long, pool.internLiteral(long))
    }
}
