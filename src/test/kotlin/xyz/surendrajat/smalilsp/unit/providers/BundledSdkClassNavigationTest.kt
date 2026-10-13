package xyz.surendrajat.smalilsp.unit.providers

import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf
import kotlin.test.assertEquals

/**
 * APKs bundle classes under SDK-looking prefixes (androidx, kotlin, desugared java.util.function);
 * when such a class is indexed it is navigable like any other, and the prefix only means "no
 * source" for classes that are not. On a real app half of all `.super`/`.implements` navigations hit this.
 */
class BundledSdkClassNavigationTest {

    private val index = WorkspaceIndex()
    private val impl = """
        .class public final Lcom/app/Impl;
        .super Landroidx/lifecycle/ViewModel;
        .implements Ljava/util/function/Consumer;

        .method public accept(Ljava/lang/Object;)V
            .registers 2
            invoke-static {}, Ljava/lang/System;->lineSeparator()Ljava/lang/String;
            new-instance v0, Landroidx/lifecycle/ViewModel;
            return-void
        .end method
    """.trimIndent()

    init {
        index.openSource("file:///java/util/function/Consumer.smali", ".class public interface abstract Ljava/util/function/Consumer;\n.super Ljava/lang/Object;\n\n.method public abstract accept(Ljava/lang/Object;)V\n.end method")
        index.openSource("file:///androidx/lifecycle/ViewModel.smali", ".class public abstract Landroidx/lifecycle/ViewModel;\n.super Ljava/lang/Object;\n\n.method public constructor <init>()V\n    .registers 1\n    return-void\n.end method")
        index.openSource("file:///com/app/Impl.smali", impl)
    }

    private fun definitionAt(marker: String, needle: String) =
        DefinitionProvider(index).findDefinition("file:///com/app/Impl.smali", impl.positionOf(marker, needle, 3)).map { it.uri }

    @Test
    fun `super and implements navigate to bundled classes under SDK prefixes`() {
        assertEquals(listOf("file:///androidx/lifecycle/ViewModel.smali"), definitionAt(".super", "Landroidx/lifecycle/ViewModel;"))
        assertEquals(listOf("file:///java/util/function/Consumer.smali"), definitionAt(".implements", "Ljava/util/function/Consumer;"))
    }

    @Test
    fun `instruction operands navigate to bundled classes under SDK prefixes`() {
        assertEquals(listOf("file:///androidx/lifecycle/ViewModel.smali"), definitionAt("new-instance", "Landroidx/lifecycle/ViewModel;"))
    }

    @Test
    fun `classes that are not in the workspace stay non-navigable`() {
        assertEquals(emptyList<String>(), definitionAt("invoke-static", "Ljava/lang/System;"))
    }
}
