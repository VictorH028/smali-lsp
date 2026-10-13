package xyz.surendrajat.smalilsp.unit.providers

import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.ImplementationProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf
import kotlin.test.assertEquals

/** Implementations: the concrete declarations of a method's family, the subtypes of a class. */
class ImplementationProviderTest {

    private val index = WorkspaceIndex()
    private val shape = ".class public interface abstract Lcom/Shape;\n.super Ljava/lang/Object;\n.method public abstract area()I\n.end method"
    private val base = ".class public Lcom/Base;\n.super Ljava/lang/Object;\n.implements Lcom/Shape;\n.method public area()I\n    .registers 1\n    const/4 v0, 0x0\n    return v0\n.end method"
    private val child = ".class public Lcom/Child;\n.super Lcom/Base;\n.method public area()I\n    .registers 1\n    const/4 v0, 0x1\n    return v0\n.end method"
    private val user = ".class public Lcom/User;\n.super Ljava/lang/Object;\n.method public run()V\n    .registers 1\n    invoke-interface {p0}, Lcom/Shape;->area()I\n    new-instance v0, Lcom/Shape;\n    return-void\n.end method"
    private val provider = ImplementationProvider(index)

    init {
        index.openSource("file:///Shape.smali", shape)
        index.openSource("file:///Base.smali", base)
        index.openSource("file:///Child.smali", child)
        index.openSource("file:///User.smali", user)
    }

    private fun at(uri: String, source: String, marker: String, needle: String) =
        provider.findImplementations(uri, source.positionOf(marker, needle, 1)).map { it.uri.substringAfterLast('/') to it.range.start.line }

    @Test
    fun `an interface method's implementations are the concrete declarations, from the declaration or a call`() {
        assertEquals(listOf("Base.smali" to 3, "Child.smali" to 2), at("file:///Shape.smali", shape, ".method public abstract area", "area"))
        assertEquals(listOf("Base.smali" to 3, "Child.smali" to 2), at("file:///User.smali", user, "Lcom/Shape;->area", "area"), "from a call")
    }

    @Test
    fun `from an override the other concrete declarations are listed, not itself and not the abstract root`() {
        assertEquals(listOf("Base.smali" to 3), at("file:///Child.smali", child, ".method public area", "area"))
    }

    @Test
    fun `a class's implementations are its subclasses and implementors`() {
        assertEquals(listOf("Base.smali" to 0, "Child.smali" to 0), at("file:///Shape.smali", shape, ".class", "Lcom/Shape;"))
        assertEquals(listOf("Base.smali" to 0, "Child.smali" to 0), at("file:///User.smali", user, "new-instance", "Lcom/Shape;"), "from a type operand")
        assertEquals(emptyList<Pair<String, Int>>(), at("file:///Child.smali", child, ".class", "Lcom/Child;"))
    }
}
