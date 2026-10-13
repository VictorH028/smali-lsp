package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf

class DefinitionProviderTest {

    private lateinit var index: WorkspaceIndex
    private lateinit var provider: DefinitionProvider

    private val base = """
        .class public Lcom/example/Base;
        .super Ljava/lang/Object;

        .field public static shared:I

        .method public greet(Lcom/example/Widget;)V
            .registers 2
            return-void
        .end method
    """.trimIndent()

    private val widget = ".class public Lcom/example/Widget;\n.super Ljava/lang/Object;"
    private val data = ".class public Lcom/example/Data;\n.super Ljava/lang/Object;"
    private val iface = ".class public interface abstract Lcom/example/MyInterface;\n.super Ljava/lang/Object;"

    private val source = """
        .class public Lcom/example/MyClass;
        .super Lcom/example/Base;
        .implements Lcom/example/MyInterface;

        .annotation system Ldalvik/annotation/MemberClasses;
            value = Lcom/example/Data;
        .end annotation

        .field private data:Lcom/example/Data;
        .field private count:I

        .method public onClick(Lcom/example/Widget;I)Lcom/example/Data;
            .registers 4
            iget-object v0, p0, Lcom/example/MyClass;->data:Lcom/example/Data;
            sget v1, Lcom/example/Base;->shared:I
            if-eqz v1, :cond_0
            new-instance v1, Lcom/example/Widget;
            check-cast v0, [Lcom/example/Data;
            :try_start_0
            invoke-virtual {p0, p1}, Lcom/example/Base;->greet(Lcom/example/Widget;)V
            invoke-virtual {p0}, Ljava/lang/Object;->hashCode()I
            :try_end_0
            .catch Lcom/example/Data; {:try_start_0 .. :try_end_0} :cond_0
            :cond_0
            return-object v0
        .end method
    """.trimIndent()

    private val uri = "file:///MyClass.smali"

    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        provider = DefinitionProvider(index)
        index.openSource("file:///Base.smali", base)
        index.openSource("file:///Widget.smali", widget)
        index.openSource("file:///Data.smali", data)
        index.openSource("file:///MyInterface.smali", iface)
        index.openSource(uri, source)
    }

    private fun definitionAt(position: Position): List<Location> = provider.findDefinition(uri, position)
    private fun definitionAt(marker: String, needle: String, offset: Int = 0, after: String? = null) =
        definitionAt(source.positionOf(marker, needle, offset, after))

    private fun assertGoesTo(expectedUri: String, locations: List<Location>) {
        assertEquals(listOf(expectedUri), locations.map { it.uri })
    }

    @Test
    fun `class header`() {
        assertGoesTo(uri, definitionAt(".class", "Lcom/example/MyClass;", 5))
        assertGoesTo("file:///Base.smali", definitionAt(".super", "Lcom/example/Base;", 10))
        assertGoesTo("file:///MyInterface.smali", definitionAt(".implements", "Lcom/example/MyInterface;", 15))
        assertTrue(definitionAt(".super", ".super", 1).isEmpty(), "the directive keyword is not navigable")
    }

    @Test
    fun `classes that are not indexed are not navigable`() {
        val sdk = "file:///Sdk.smali"
        index.openSource(sdk, ".class public LMyClass;\n.super Ljava/lang/String;")
        assertTrue(provider.findDefinition(sdk, Position(1, 10)).isEmpty(), "platform SDK classes have no source")
        val unknown = "file:///Unknown.smali"
        index.openSource(unknown, ".class public LOther;\n.super Lcom/unknown/Class;")
        assertTrue(provider.findDefinition(unknown, Position(1, 10)).isEmpty())
    }

    @Test
    fun `field declaration navigates on the type only`() {
        assertGoesTo("file:///Data.smali", definitionAt("data:", "Lcom/example/Data;", 3))
        assertTrue(definitionAt("data:", "data").isEmpty(), "the field name is its own definition")
        assertTrue(definitionAt("count:I", "I", after = ":").isEmpty(), "primitives have no definition")
    }

    @Test
    fun `method declaration navigates on parameter and return types`() {
        assertGoesTo("file:///Widget.smali", definitionAt(".method", "Lcom/example/Widget;", 4))
        assertGoesTo("file:///Data.smali", definitionAt(".method", "Lcom/example/Data;", 4, after = ")"))
        assertTrue(definitionAt(".method", "onClick", 2).isEmpty())
        assertTrue(definitionAt(".method", "I", after = "Widget;").isEmpty())
    }

    @Test
    fun `directive references`() {
        assertGoesTo("file:///Data.smali", definitionAt("value =", "Lcom/example/Data;", 2))
        assertGoesTo("file:///Data.smali", definitionAt(".catch", "Lcom/example/Data;", 2))
        assertTrue(definitionAt(".annotation", "system").isEmpty())
    }

    @Test
    fun `member references`() {
        val greet = definitionAt("invoke-virtual {p0, p1}", "greet", 2)
        assertGoesTo("file:///Base.smali", greet)
        assertEquals(base.positionOf(".method", ".method").line, greet.single().range.start.line)
        assertGoesTo("file:///Base.smali", definitionAt("invoke-virtual {p0, p1}", "Lcom/example/Base;", 4), )
        assertGoesTo("file:///Widget.smali", definitionAt("invoke-virtual {p0, p1}", "Lcom/example/Widget;", 4, after = "greet("))
        assertGoesTo("file:///Base.smali", definitionAt("invoke-virtual {p0, p1}", "invoke-virtual"), )
        assertTrue(definitionAt("hashCode", "hashCode", 3).isEmpty(), "not in the workspace")

        val shared = definitionAt("sget", "shared", 3)
        assertGoesTo("file:///Base.smali", shared)
        assertEquals(base.positionOf(".field", ".field").line, shared.single().range.start.line)
        assertGoesTo("file:///Base.smali", definitionAt("sget", "I", after = "shared:"), )
        assertGoesTo("file:///Data.smali", definitionAt("iget-object", "Lcom/example/Data;", 3, after = "data:"))
        assertGoesTo(uri, definitionAt("iget-object", "Lcom/example/MyClass;", 3))
    }

    @Test
    fun `type operands`() {
        assertGoesTo("file:///Widget.smali", definitionAt("new-instance", "Lcom/example/Widget;", 1))
        assertGoesTo("file:///Data.smali", definitionAt("check-cast", "[Lcom/example/Data;", 0))
        assertGoesTo("file:///Widget.smali", definitionAt("new-instance", "new-instance", 3), )
    }

    @Test
    fun `labels`() {
        val definition = source.positionOf("    :cond_0", ":cond_0")
        assertEquals(listOf(definition.line), definitionAt("if-eqz", ":cond_0", 2).map { it.range.start.line })
        assertEquals(listOf(definition.line), definitionAt(".catch", ":cond_0", 2).map { it.range.start.line })
        assertEquals(listOf(definition.line), definitionAt("if-eqz", "if-eqz").map { it.range.start.line })
        assertEquals(listOf(definition.line), definitionAt(definition).map { it.range.start.line })
    }

    @Test
    fun `nothing at blank spots or in unknown files`() {
        assertTrue(definitionAt(Position(3, 0)).isEmpty())
        assertTrue(definitionAt(".registers", ".registers", 3).isEmpty())
        assertTrue(provider.findDefinition("file:///Missing.smali", Position(0, 0)).isEmpty())
    }
}
