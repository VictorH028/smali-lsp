package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Nested classes keep the `$` chain in their name: indexed, found by simple name and navigable like any class. */
class InnerClassNavigationTest {

    private val index = WorkspaceIndex()

    private val inner = ".class public Lcom/example/Outer\$Inner;\n.super Ljava/lang/Object;"
    private val deep = ".class Lcom/example/Outer\$Inner\$VeryInner;\n.super Ljava/lang/Object;"
    private val anonymous = ".class Lcom/example/Outer\$1;\n.super Ljava/lang/Object;"

    @Test
    fun `nested class names are indexed with their dollar chain and found by simple name`() {
        index.openSource("file:///Outer\$Inner.smali", inner)
        index.openSource("file:///Outer\$Inner\$VeryInner.smali", deep)
        index.openSource("file:///Outer\$1.smali", anonymous)

        assertTrue(index.hasClass("Lcom/example/Outer\$Inner;"))
        assertEquals("file:///Outer\$Inner.smali", index.getUri("Lcom/example/Outer\$Inner;"))
        assertEquals("Lcom/example/Outer\$Inner\$VeryInner;", assertNotNull(index.findClass("Lcom/example/Outer\$Inner\$VeryInner;")).classDefinition.name)
        assertEquals("Lcom/example/Outer\$1;", assertNotNull(index.findClass("Lcom/example/Outer\$1;")).classDefinition.name, "anonymous classes are numbered")
        assertEquals(listOf("Lcom/example/Outer\$Inner;"), index.findClassesBySimpleName("Outer\$Inner").map { it.classDefinition.name })
        assertEquals(listOf("Lcom/example/Outer\$Inner\$VeryInner;"), index.findClassesBySimpleName("Outer\$Inner\$VeryInner").map { it.classDefinition.name })
        assertTrue(index.findClassesBySimpleName("Inner").isEmpty(), "the simple name keeps the outer class")
    }

    @Test
    fun `a reference to a nested class navigates to its class name`() {
        index.openSource("file:///Outer\$Inner.smali", inner)
        val user = """
            .class public Lcom/example/User;
            .super Lcom/example/Outer${'$'}Inner;

            .method public make()V
                .registers 1
                new-instance v0, Lcom/example/Outer${'$'}Inner;
                return-void
            .end method
        """.trimIndent()
        index.openSource("file:///User.smali", user)
        val provider = DefinitionProvider(index)

        val name = inner.positionOf(".class", "Lcom/example/Outer\$Inner;")
        val expected = listOf(Location("file:///Outer\$Inner.smali", Range(name, Position(name.line, name.character + "Lcom/example/Outer\$Inner;".length))))
        assertEquals(expected, provider.findDefinition("file:///User.smali", user.positionOf(".super", "Outer")))
        assertEquals(expected, provider.findDefinition("file:///User.smali", user.positionOf("new-instance", "Inner")))
    }
}
