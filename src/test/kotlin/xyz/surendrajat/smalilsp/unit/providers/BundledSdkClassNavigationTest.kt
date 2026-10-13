package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.shared.TempTestWorkspace

/**
 * APKs bundle classes under "SDK" prefixes (androidx.*, kotlin.*, desugared java.util.function.*).
 * When such a class is in the workspace its definition must be navigable like any other;
 * the SDK prefix only means "no source" for classes that are *not* indexed.
 *
 * On a real app half of all `.super`/`.implements` navigations hit this.
 */
class BundledSdkClassNavigationTest {

    private val workspace = TempTestWorkspace()

    @AfterEach
    fun cleanup() = workspace.cleanup()

    private fun setUp(): DefinitionProvider {
        workspace.addFile("java/util/function/Consumer.smali", """
            .class public interface abstract Ljava/util/function/Consumer;
            .super Ljava/lang/Object;

            .method public abstract accept(Ljava/lang/Object;)V
            .end method
        """.trimIndent())
        workspace.addFile("androidx/lifecycle/ViewModel.smali", """
            .class public abstract Landroidx/lifecycle/ViewModel;
            .super Ljava/lang/Object;

            .method public constructor <init>()V
                .registers 1
                return-void
            .end method
        """.trimIndent())
        workspace.addFile("com/app/Impl.smali", """
            .class public final Lcom/app/Impl;
            .super Landroidx/lifecycle/ViewModel;
            .implements Ljava/util/function/Consumer;

            .method public accept(Ljava/lang/Object;)V
                .registers 2
                invoke-static {}, Ljava/lang/System;->lineSeparator()Ljava/lang/String;
                new-instance v0, Landroidx/lifecycle/ViewModel;
                return-void
            .end method
        """.trimIndent())
        val index = workspace.buildIndex()
        index.setDocumentContent(workspace.getUri("com/app/Impl.smali"), workspace.getContent("com/app/Impl.smali"))
        return DefinitionProvider(index)
    }

    @Test
    fun `super and implements navigate to bundled classes under SDK prefixes`() {
        val defs = setUp()
        val uri = workspace.getUri("com/app/Impl.smali")

        val superTarget = defs.findDefinition(uri, Position(1, 10))      // on Landroidx/lifecycle/ViewModel;
        assertEquals(listOf(workspace.getUri("androidx/lifecycle/ViewModel.smali")), superTarget.map { it.uri })

        val ifaceTarget = defs.findDefinition(uri, Position(2, 15))      // on Ljava/util/function/Consumer;
        assertEquals(listOf(workspace.getUri("java/util/function/Consumer.smali")), ifaceTarget.map { it.uri })
    }

    @Test
    fun `instruction operands navigate to bundled classes under SDK prefixes`() {
        val defs = setUp()
        val uri = workspace.getUri("com/app/Impl.smali")
        val target = defs.findDefinition(uri, Position(7, 25))           // new-instance ..., Landroidx/lifecycle/ViewModel;
        assertEquals(listOf(workspace.getUri("androidx/lifecycle/ViewModel.smali")), target.map { it.uri })
    }

    @Test
    fun `classes that are not in the workspace stay non-navigable`() {
        val defs = setUp()
        val uri = workspace.getUri("com/app/Impl.smali")
        val target = defs.findDefinition(uri, Position(6, 30))           // invoke-static ..., Ljava/lang/System;
        assertEquals(emptyList<String>(), target.map { it.uri })
    }
}
