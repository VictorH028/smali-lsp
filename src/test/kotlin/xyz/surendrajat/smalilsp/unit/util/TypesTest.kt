package xyz.surendrajat.smalilsp.unit.util

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.util.Types

class TypesTest {

    // --- className ---

    @Test
    fun `className of object types`() {
        assertEquals("Ljava/lang/String;", Types.className("Ljava/lang/String;"))
        assertEquals("Lcom/example/MyClass;", Types.className("Lcom/example/MyClass;"))
    }

    @Test
    fun `className strips array brackets`() {
        assertEquals("Ljava/lang/String;", Types.className("[Ljava/lang/String;"))
        assertEquals("Ljava/lang/Integer;", Types.className("[[Ljava/lang/Integer;"))
        assertEquals("Lcom/example/MyClass;", Types.className("[[[Lcom/example/MyClass;"))
    }

    @Test
    fun `className is null for primitives and primitive arrays`() {
        for (d in listOf("I", "J", "Z", "V", "[I", "[[J", "[[[Z")) assertNull(Types.className(d), d)
    }

    @Test
    fun `className is null for malformed input`() {
        assertNull(Types.className(""))
        assertNull(Types.className("Lcom/foo"))
        assertNull(Types.className("[["))
    }

    @Test
    fun `className stops at the first semicolon`() {
        assertEquals("Lcom/Foo;", Types.className("Lcom/Foo;->bar()V"))
    }

    // --- isPrimitive ---

    @Test
    fun `isPrimitive covers every primitive and primitive arrays`() {
        for (d in listOf("I", "J", "Z", "B", "C", "S", "F", "D", "V", "[I", "[[J", "[[[Z")) assertTrue(Types.isPrimitive(d), d)
    }

    @Test
    fun `isPrimitive is false for objects and malformed input`() {
        for (d in listOf("Ljava/lang/String;", "[Ljava/lang/String;", "Lcom/example/MyClass;", "", "X", "[")) assertFalse(Types.isPrimitive(d), d)
    }

    // --- readableName ---

    @Test
    fun `readableName of primitives`() {
        val expected = mapOf("I" to "int", "J" to "long", "Z" to "boolean", "B" to "byte", "C" to "char", "S" to "short", "F" to "float", "D" to "double", "V" to "void")
        expected.forEach { (d, name) -> assertEquals(name, Types.readableName(d)) }
    }

    @Test
    fun `readableName of objects and arrays`() {
        assertEquals("java.lang.String", Types.readableName("Ljava/lang/String;"))
        assertEquals("android.content.Context", Types.readableName("Landroid/content/Context;"))
        assertEquals("int[]", Types.readableName("[I"))
        assertEquals("int[][]", Types.readableName("[[I"))
        assertEquals("java.lang.String[][]", Types.readableName("[[Ljava/lang/String;"))
        assertEquals("android.content.Context[][][]", Types.readableName("[[[Landroid/content/Context;"))
    }

    @Test
    fun `readableName of malformed input is unknown`() {
        assertEquals("unknown", Types.readableName(""))
        assertEquals("unknown", Types.readableName("X"))
        assertEquals("unknown", Types.readableName("Lcom/foo"))
        assertEquals("unknown[]", Types.readableName("["))
    }

    // --- simpleName / isSdkClass ---

    @Test
    fun `simpleName strips package, nesting and array brackets`() {
        assertEquals("MyClass", Types.simpleName("Lcom/example/MyClass;"))
        assertEquals("Inner", Types.simpleName("Lcom/example/Outer\$Inner;"))
        assertEquals("q", Types.simpleName("La/b/c/q;"))
        assertEquals("String", Types.simpleName("[Ljava/lang/String;"))
    }

    @Test
    fun `isSdkClass covers framework packages, primitives and arrays`() {
        for (d in listOf("Ljava/lang/String;", "Landroid/content/Context;", "Lkotlin/Unit;", "[Ljava/lang/String;", "I", "[I")) assertTrue(Types.isSdkClass(d), d)
        for (d in listOf("Lcom/example/MyClass;", "[Lcom/example/MyClass;", "Lorg/example/Foo;")) assertFalse(Types.isSdkClass(d), d)
    }

    // --- parseTypeSequence ---

    @Test
    fun `parseTypeSequence of an empty string`() {
        assertEquals(emptyList<Types.TypeSpan>(), Types.parseTypeSequence(""))
    }

    @Test
    fun `parseTypeSequence of primitives`() {
        val types = Types.parseTypeSequence("IJZ")
        assertEquals(listOf("I", "J", "Z"), types.map { it.type })
        assertTrue(types.all { it.isPrimitive && !it.isArray && !it.isClassType })
    }

    @Test
    fun `parseTypeSequence of an object type`() {
        val types = Types.parseTypeSequence("Ljava/lang/String;")
        assertEquals(1, types.size)
        assertEquals("Ljava/lang/String;", types[0].type)
        assertTrue(types[0].isClassType)
        assertEquals("Ljava/lang/String;", types[0].className)
    }

    @Test
    fun `parseTypeSequence of arrays`() {
        val prim = Types.parseTypeSequence("[[J").single()
        assertEquals("[[J", prim.type)
        assertTrue(prim.isArray && prim.isPrimitive && !prim.isClassType)
        assertNull(prim.className)

        val obj = Types.parseTypeSequence("[[Ljava/lang/Object;").single()
        assertEquals("[[Ljava/lang/Object;", obj.type)
        assertTrue(obj.isArray && obj.isClassType && !obj.isPrimitive)
        assertEquals("Ljava/lang/Object;", obj.className)
    }

    @Test
    fun `parseTypeSequence of a full descriptor skips the parentheses`() {
        assertEquals(listOf("I", "Ljava/lang/String;", "V"), Types.parseTypeSequence("(ILjava/lang/String;)V").map { it.type })
        assertEquals(
            listOf("I", "Ljava/lang/String;", "[I", "[[Ljava/lang/Object;", "Z", "Ljava/lang/Integer;"),
            Types.parseTypeSequence("(ILjava/lang/String;[I[[Ljava/lang/Object;Z)Ljava/lang/Integer;").map { it.type }
        )
    }

    @Test
    fun `parseTypeSequence keeps duplicates in order`() {
        assertEquals(listOf("Lcom/Foo;", "Lcom/Foo;"), Types.parseTypeSequence("(Lcom/Foo;ILcom/Foo;)V").mapNotNull { it.className })
    }

    @Test
    fun `parseTypeSequence skips truncated types`() {
        assertEquals(listOf("I"), Types.parseTypeSequence("ILcom/foo").map { it.type })
        assertEquals(listOf("I"), Types.parseTypeSequence("I[").map { it.type })
    }

    @Test
    fun `parseTypeSequence offsets`() {
        val types = Types.parseTypeSequence("ILjava/lang/String;Z")
        assertEquals(listOf(0 to 1, 1 to 19, 19 to 20), types.map { it.start to it.end })
    }

    @Test
    fun `parseTypeStrings matches parseTypeSequence`() {
        for (s in listOf("", "IJZ", "(ILjava/lang/String;[I[[Ljava/lang/Object;Z)Ljava/lang/Integer;", "ILcom/foo", "I[", "[[")) {
            assertEquals(Types.parseTypeSequence(s).map { it.type }, Types.parseTypeStrings(s), s)
        }
    }

    // --- typeAtOffset ---

    @Test
    fun `typeAtOffset finds the covering type`() {
        assertEquals("J", Types.typeAtOffset("IJZ", 1)?.type)
        assertEquals("Ljava/lang/String;", Types.typeAtOffset("Ljava/lang/String;", 0)?.type)
        assertEquals("Ljava/lang/String;", Types.typeAtOffset("Ljava/lang/String;", 10)?.type)
        assertEquals("I", Types.typeAtOffset("(I)V", 1)?.type)
        val array = Types.typeAtOffset("[I", 0)
        assertNotNull(array)
        assertEquals("[I", array.type)
        assertTrue(array.isArray)
    }

    @Test
    fun `typeAtOffset is null on delimiters`() {
        assertNull(Types.typeAtOffset("(I)V", 0))
        assertNull(Types.typeAtOffset("(I)V", 2))
    }
}
