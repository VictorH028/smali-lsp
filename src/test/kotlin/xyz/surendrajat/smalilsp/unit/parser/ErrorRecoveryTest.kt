package xyz.surendrajat.smalilsp.unit.parser

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Malformed, empty and unusual input parses as far as it can, and the providers answer nothing rather than fail. */
class ErrorRecoveryTest {

    private val parser = SmaliParser()

    @Test
    fun `empty and whitespace-only content parse to nothing`() {
        assertNull(parser.parse("file:///empty.smali", ""))
        assertNull(parser.parse("file:///blank.smali", "   \n  \n  "))
    }

    @Test
    fun `an unterminated class name is reported on its line`() {
        val result = parser.parseWithErrors("file:///malformed.smali", ".class public LBroken\n.super Ljava/lang/Object;")
        assertTrue(result.syntaxErrors.isNotEmpty(), "unterminated class name must be reported")
        assertEquals(1, result.syntaxErrors.first().line)
    }

    @Test
    fun `a class without a super directive parses`() {
        val file = assertNotNull(parser.parse("file:///NoSuper.smali", ".class public Ltest/NoSuper;"))
        assertEquals("Ltest/NoSuper;", file.classDefinition.name)
        assertNull(file.classDefinition.superClass)
    }

    @Test
    fun `an unterminated method signature is reported and the class still parses`() {
        val result = parser.parseWithErrors("file:///BadMethod.smali", ".class public Ltest/BadMethod;\n.super Ljava/lang/Object;\n\n.method public broken(\n    return-void\n.end method")
        assertTrue(result.syntaxErrors.isNotEmpty(), "the unterminated signature must be reported")
        assertNotNull(result.smaliFile, "the class itself still parses")
    }

    @Test
    fun `very long and non-ASCII class names are kept whole`() {
        val longName = "L" + "a".repeat(500) + ";"
        assertEquals(longName, assertNotNull(parser.parse("file:///Long.smali", ".class public $longName\n.super Ljava/lang/Object;")).classDefinition.name)
        assertEquals("Ltest/日本語;", assertNotNull(parser.parse("file:///Unicode.smali", ".class public Ltest/日本語;\n.super Ljava/lang/Object;")).classDefinition.name)
    }

    @Test
    fun `a class without members is indexed`() {
        val index = WorkspaceIndex()
        index.openSource("file:///Empty.smali", ".class public Ltest/Empty;\n.super Ljava/lang/Object;")
        val found = assertNotNull(index.findClass("Ltest/Empty;"))
        assertEquals(0, found.methods.size)
        assertEquals(0, found.fields.size)
    }

    @Test
    fun `an unresolved superclass has no definition but still a hover`() {
        val index = WorkspaceIndex()
        val source = ".class public Ltest/Unknown;\n.super Lnonexistent/Missing;"
        index.openSource("file:///Unknown.smali", source)
        val onSuper = source.positionOf(".super", "Lnonexistent/Missing;", 3)
        assertEquals(emptyList<Location>(), DefinitionProvider(index).findDefinition("file:///Unknown.smali", onSuper))
        val hover = assertNotNull(HoverProvider(index).provideHover("file:///Unknown.smali", onSuper), "an unresolved superclass still shows the reference")
        assertTrue(hover.contents.right.value.contains("Not found in workspace"), hover.contents.right.value)
    }

    @Test
    fun `a cyclic hierarchy neither loops nor hides the superclass`() {
        val index = WorkspaceIndex()
        val a = ".class public Lcycle/A;\n.super Lcycle/B;"
        index.openSource("file:///A.smali", a)
        index.openSource("file:///B.smali", ".class public Lcycle/B;\n.super Lcycle/A;")
        val hover = assertNotNull(HoverProvider(index).provideHover("file:///A.smali", a.positionOf(".super", "Lcycle/B;", 3)))
        assertTrue(hover.contents.right.value.contains("cycle.B"), hover.contents.right.value)
    }

    @Test
    fun `positions past the line, past the file or in an unknown file answer nothing`() {
        val index = WorkspaceIndex()
        index.openSource("file:///Valid.smali", ".class public Ltest/Valid;\n.super Ljava/lang/Object;")
        assertEquals(emptyList<Location>(), DefinitionProvider(index).findDefinition("file:///Valid.smali", Position(1, 100)))
        assertNull(HoverProvider(index).provideHover("file:///Valid.smali", Position(100, 50)))
        assertEquals(emptyList<Location>(), DefinitionProvider(index).findDefinition("file:///nowhere.smali", Position(0, 0)))
        assertEquals(emptyList<Location>(), ReferenceProvider(index).findReferences("file:///nowhere.smali", Position(0, 0)))
    }
}
