package xyz.surendrajat.smalilsp.unit.parser

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.shared.TempTestWorkspace
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

import xyz.surendrajat.smalilsp.shared.TestWorkspace
/**
 * Error recovery and edge case tests.
 * 
 * Verifies that LSP providers handle errors gracefully:
 * - Malformed smali files
 * - Missing class references
 * - Invalid positions
 * - Null/empty inputs
 * - Concurrent access (basic)
 * 
 * These tests are critical for production readiness.
 */
class ErrorRecoveryTest {
    
    @Test
    fun `parse whitespace-only content returns null`() {
        val parser = SmaliParser()
        val result = parser.parse("file:///test.smali", "   \n  \n  ")
        
        // Should handle gracefully (returns null, doesn't crash)
        assertNull(result, "Should return null for whitespace-only content")
    }
    
    @Test
    fun `parse empty content returns null`() {
        val parser = SmaliParser()
        val result = parser.parse("file:///empty.smali", "")
        
        // Empty content should return null
        assertNull(result, "Should return null for empty content")
    }
    
    @Test
    fun `parse malformed class directive`() {
        val parser = SmaliParser()
        val result = parser.parseWithErrors("file:///malformed.smali", """
            .class public LBroken
            .super Ljava/lang/Object;
        """.trimIndent())

        // `LBroken` (no ';') is not a type: the directive is reported, nothing crashes
        assertTrue(result.syntaxErrors.isNotEmpty(), "unterminated class name must be reported")
        assertEquals(1, result.syntaxErrors.first().line)
    }
    
    @Test
    fun `parse missing super directive`() {
        val parser = SmaliParser()
        val result = parser.parse("file:///missing-super.smali", """
            .class public Ltest/NoSuper;
        """.trimIndent())

        // .super is optional (java.lang.Object itself has none)
        assertNotNull(result)
        assertEquals("Ltest/NoSuper;", result!!.classDefinition.name)
        assertNull(result.classDefinition.superClass)
    }
    
    @Test
    fun `goto on unknown class returns empty`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Unknown.smali", """
            .class public Ltest/Unknown;
            .super Lnonexistent/Missing;
        """.trimIndent())
        
        val index = WorkspaceIndex()
        index.indexFile(workspace.parseFile("test/Unknown.smali"))
        
        val provider = DefinitionProvider(index)
        val locations = provider.findDefinition(
            uri = workspace.getUri("test/Unknown.smali"),
            position = Position(1, 15)
        )
        
        // Should return empty for unknown class (not crash)
        assertEquals(0, locations.size, "Should return empty for unknown class")
        
        workspace.cleanup()
    }
    
    @Test
    fun `hover on missing class handled gracefully`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Missing.smali", """
            .class public Ltest/Missing;
            .super Lnonexistent/Gone;
        """.trimIndent())
        
        val index = WorkspaceIndex()
        index.indexFile(workspace.parseFile("test/Missing.smali"))
        
        val provider = HoverProvider(index)
        val hover = provider.provideHover(
            uri = workspace.getUri("test/Missing.smali"),
            position = Position(1, 15) // on Lnonexistent/Gone;
        )

        assertNotNull(hover, "hover on an unresolved superclass still shows the reference")
        assertTrue(hover!!.contents.right.value.contains("Not found in workspace"), hover.contents.right.value)
        
        workspace.cleanup()
    }
    
    @Test
    fun `hover on invalid position returns null`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Valid.smali", """
            .class public Ltest/Valid;
            .super Ljava/lang/Object;
        """.trimIndent())
        
        val index = WorkspaceIndex()
        index.indexFile(workspace.parseFile("test/Valid.smali"))
        
        val provider = HoverProvider(index)
        
        // Position beyond file length
        val hover = provider.provideHover(
            uri = workspace.getUri("test/Valid.smali"),
            position = Position(100, 50)
        )
        
        // Should return null (not crash)
        assertNull(hover, "Should return null for invalid position")
        
        workspace.cleanup()
    }
    
    @Test
    fun `goto with empty content returns empty`() {
        val index = WorkspaceIndex()
        val provider = DefinitionProvider(index)
        
        val locations = provider.findDefinition(
            uri = "file:///empty.smali",
            position = Position(0, 0)
        )
        
        assertEquals(0, locations.size, "Should return empty for empty content")
    }
    
    @Test
    fun `references with null handling`() {
        val index = WorkspaceIndex()
        val provider = ReferenceProvider(index)
        
        // Empty index + empty content
        val references = provider.findReferences(
            uri = "file:///null.smali",
            position = Position(0, 0)
        )
        
        assertEquals(0, references.size, "Should return empty for empty content")
    }
    
    @Test
    fun `circular inheritance handled gracefully`() {
        val workspace = TempTestWorkspace.create()
        
        // A extends B, B extends A (invalid but shouldn't crash)
        workspace.addFile("cycle/A.smali", """
            .class public Lcycle/A;
            .super Lcycle/B;
        """.trimIndent())
        
        workspace.addFile("cycle/B.smali", """
            .class public Lcycle/B;
            .super Lcycle/A;
        """.trimIndent())
        
        val index = WorkspaceIndex()
        index.indexFile(workspace.parseFile("cycle/A.smali"))
        index.indexFile(workspace.parseFile("cycle/B.smali"))
        
        val provider = HoverProvider(index)
        
        // Hover on B in A
        val hover = provider.provideHover(
            uri = workspace.getUri("cycle/A.smali"),
            position = Position(1, 15)
        )
        
        // No stack overflow, and the (cyclic) superclass is still described
        assertNotNull(hover)
        assertTrue(hover!!.contents.right.value.contains("cycle.B"), hover.contents.right.value)
        
        workspace.cleanup()
    }
    
    @Test
    fun `very long class name handled`() {
        val longName = "L" + "a".repeat(500) + ";"
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Long.smali", """
            .class public $longName
            .super Ljava/lang/Object;
        """.trimIndent())
        
        try {
            val file = workspace.parseFile("test/Long.smali")
            // Should parse successfully and preserve the full class name
            assertTrue(file.classDefinition.name.isNotEmpty(), "Should parse long class name")
            assertEquals(longName, file.classDefinition.name, "Should preserve full long class name")
        } finally {
            workspace.cleanup()
        }
    }
    
    @Test
    fun `class with no methods or fields indexed correctly`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Empty.smali", """
            .class public Ltest/Empty;
            .super Ljava/lang/Object;
        """.trimIndent())
        
        val index = WorkspaceIndex()
        val file = workspace.parseFile("test/Empty.smali")
        index.indexFile(file)
        
        // Should index successfully
        val found = index.findClass("Ltest/Empty;")
        assertNotNull(found, "Should find empty class in index")
        assertEquals(0, found.methods.size, "Should have no methods")
        assertEquals(0, found.fields.size, "Should have no fields")
        
        workspace.cleanup()
    }
    
    @Test
    fun `unicode characters in class names`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Unicode.smali", """
            .class public Ltest/日本語;
            .super Ljava/lang/Object;
        """.trimIndent())
        
        try {
            val file = workspace.parseFile("test/Unicode.smali")
            assertEquals("Ltest/日本語;", file.classDefinition.name)
        } finally {
            workspace.cleanup()
        }
    }
    
    @Test
    fun `position at end of line handled`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/Test.smali", """
            .class public Ltest/Test;
            .super Ljava/lang/Object;
        """.trimIndent())
        
        val index = WorkspaceIndex()
        index.indexFile(workspace.parseFile("test/Test.smali"))
        
        val provider = DefinitionProvider(index)
        
        // Position at end of line (after semicolon)
        val locations = provider.findDefinition(
            uri = workspace.getUri("test/Test.smali"),
            position = Position(1, 100) // Way past line end
        )
        
        assertEquals(emptyList<org.eclipse.lsp4j.Location>(), locations, "a position past the end of the line is on nothing")
        
        workspace.cleanup()
    }
    
    @Test
    fun `concurrent index access basic`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("concurrent/Test.smali", """
            .class public Lconcurrent/Test;
            .super Ljava/lang/Object;
            
            .method public foo()V
                return-void
            .end method
        """.trimIndent())
        
        val index = WorkspaceIndex()
        index.indexFile(workspace.parseFile("concurrent/Test.smali"))
        
        val provider = HoverProvider(index)
        
        // Simulate concurrent requests (basic test)
        val results = mutableListOf<Any?>()
        val threads = (1..10).map { i ->
            Thread {
                val hover = provider.provideHover(
                    uri = workspace.getUri("concurrent/Test.smali"),
                    position = Position(0, 20)
                )
                synchronized(results) {
                    results.add(hover)
                }
            }
        }
        
        threads.forEach { it.start() }
        threads.forEach { it.join(1000) } // Wait max 1s per thread
        
        // All threads should complete without crash
        assertEquals(10, results.size, "All concurrent requests should complete")
        
        workspace.cleanup()
    }
    
    @Test
    fun `malformed method descriptor handled`() {
        val workspace = TempTestWorkspace.create()
        workspace.addFile("test/BadMethod.smali", """
            .class public Ltest/BadMethod;
            .super Ljava/lang/Object;
            
            .method public broken(
                return-void
            .end method
        """.trimIndent())
        
        try {
            val result = SmaliParser().parseWithErrors(workspace.getUri("test/BadMethod.smali"), workspace.getContent("test/BadMethod.smali"))
            assertTrue(result.syntaxErrors.isNotEmpty(), "the unterminated signature must be reported")
            assertNotNull(result.smaliFile, "the class itself still parses")
        } finally {
            workspace.cleanup()
        }
    }
}
