package xyz.surendrajat.smalilsp.regression

import java.io.File
import org.eclipse.lsp4j.Position
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.loadVersionInfo
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.CompletionProvider
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.DiagnosticProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.shared.TestUtils
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf

/** Behaviours fixed from real-editor sessions in earlier releases that had no test of their own. */
class EarlierIdeFixesTest {

    @Test
    fun `a UTF-8 BOM is stripped and positions are those of the text the editor shows`() {
        val index = WorkspaceIndex()
        val plain = ".class public Lcom/app/Bom;\n.super Ljava/lang/Object;\n\n.method public run()V\n    .registers 1\n    return-void\n.end method"
        val withBom = "﻿" + plain
        val file = index.openSource("file:///Bom.smali", withBom)
        assertEquals("Lcom/app/Bom;", file.classDefinition.name)
        assertEquals(plain.indexOf("Lcom/app/Bom;"), file.classDefinition.nameSpan.start, "columns ignore the BOM, as the editor does")
        val hover = HoverProvider(index).provideHover("file:///Bom.smali", Position(0, 15))
        assertNotNull(hover)
        assertTrue(hover.contents.right.value.contains("com.app.Bom"))
        assertTrue(DiagnosticProvider(SmaliParser(index.strings), index).computeDiagnostics("file:///Bom.smali", withBom).isEmpty())
    }

    @Test
    fun `package-info and digit-leading path components are classes like any other`() {
        val index = WorkspaceIndex()
        index.openSource("file:///package-info.smali", ".class synthetic Lcom/app/package-info;\n.super Ljava/lang/Object;")
        index.openSource("file:///2K.smali", ".class public Lcom/facebook/ads/redexgen/X/2K;\n.super Ljava/lang/Object;")
        val user = ".class public Lcom/app/User;\n.super Lcom/facebook/ads/redexgen/X/2K;"
        index.openSource("file:///User.smali", user)
        assertNotNull(index.findClass("Lcom/app/package-info;"))
        val definition = DefinitionProvider(index).findDefinition("file:///User.smali", user.positionOf(".super", "Lcom/facebook/ads/redexgen/X/2K;", 20)).single()
        assertEquals("file:///2K.smali", definition.uri)
    }

    @Test
    fun `class completion replaces the typed L-prefix instead of inserting a second L`() {
        val index = WorkspaceIndex()
        index.openSource("file:///Foo.smali", ".class public Lcom/app/Foo;\n.super Ljava/lang/Object;")
        val line = "    new-instance v0, Lcom/ap"
        val items = CompletionProvider(index).provideCompletions("file:///Any.smali", Position(3, line.length), line).items
        val foo = items.single { it.label == "com/app/Foo" }
        val edit = foo.textEdit.left
        assertEquals("Lcom/app/Foo;", edit.newText)
        assertEquals(line.indexOf("Lcom/ap"), edit.range.start.character, "the replacement starts at the L")
        assertEquals(line.length, edit.range.end.character)
    }

    @Test
    fun `the server reports the project version from the build`() {
        val build = File(TestUtils.getProjectRoot(), "build.gradle.kts").readText()
        val declared = Regex("""^version = "([^"]+)"""", RegexOption.MULTILINE).find(build)?.groupValues?.get(1)
        assertEquals(declared, loadVersionInfo().version, "version.properties is generated from build.gradle.kts; ServerInfo once said 1.0.0")
    }
}
