package xyz.surendrajat.smalilsp.integration.lsp


import org.eclipse.lsp4j.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import xyz.surendrajat.smalilsp.shared.E2ETestHarness
import xyz.surendrajat.smalilsp.shared.TestWorkspace
/**
 * E2E tests for Diagnostics feature.
 * Tests diagnostic computation on real-world Mastodon APK.
 */
class DiagnosticE2ETest {
    
    private lateinit var harness: E2ETestHarness
    private lateinit var workspace: TestWorkspace
    
    @BeforeEach
    fun setup() {
        println("=== SETUP: DiagnosticE2ETest ===")
        val tempDir = kotlin.io.path.createTempDirectory("smali-test").toFile()
        workspace = TestWorkspace(tempDir)
        harness = E2ETestHarness(workspace)
        harness.initialize()
    }
    
    @AfterEach
    fun teardown() {
        println("=== TEARDOWN: DiagnosticE2ETest ===")
        harness.cleanup()
    }
    
    @Test
    fun `diagnostics published on file open`() {
        println("=== TEST: diagnostics published on file open ===")
        
        val server = harness.server
        val uri = "file:///test.smali"
        
        // Open file with valid content
        val validContent = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                return-void
            .end method
        """.trimIndent()
        
        server.textDocumentService.didOpen(DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem(
                uri,
                "smali",
                1,
                validContent
            )
        })

        val diagnostics = harness.waitForDiagnostics(uri)
        assertEquals(emptyList(), diagnostics, "Valid file should publish an empty diagnostics list")

        println("✅ Diagnostics published without crash")
    }
    
    @Test
    fun `diagnostics detect undefined class references`() {
        println("=== TEST: diagnostics detect undefined class references ===")
        
        val server = harness.server
        val uri = "file:///test.smali"
        
        // Content with undefined class reference
        val content = """
            .class public LTest;
            .super Lcom/example/NonExistentSuperClass;
            
            .method public test()V
                invoke-virtual {v0}, Lcom/example/UndefinedClass;->foo()V
                return-void
            .end method
        """.trimIndent()
        
        server.textDocumentService.didOpen(DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem(
                uri,
                "smali",
                1,
                content
            )
        })

        val diagnostics = harness.waitForDiagnostics(uri)
        assertEquals(2, diagnostics.size, "Should publish one superclass warning and one undefined invoke target warning")
        assertTrue(diagnostics.any { it.message.contains("NonExistentSuperClass") }, "Should report the missing superclass")
        assertTrue(diagnostics.any { it.message.contains("UndefinedClass") }, "Should report the missing invoke target class")

        println("✅ Handled undefined class references")
    }
    

    
    @Test
    fun `diagnostics updated on file change`() {
        println("=== TEST: diagnostics updated on file change ===")
        
        val server = harness.server
        val uri = "file:///test.smali"
        
        // Open file with error
        val invalidContent = """
            .class public LTest;
            .super Lcom/example/Missing;
        """.trimIndent()
        
        server.textDocumentService.didOpen(DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem(uri, "smali", 1, invalidContent)
        })

        val initialDiagnostics = harness.waitForDiagnostics(uri)
        assertTrue(initialDiagnostics.isNotEmpty(), "Invalid file should publish diagnostics before the edit")
        
        // Fix error
        val validContent = """
            .class public LTest;
            .super Ljava/lang/Object;
        """.trimIndent()
        
        server.textDocumentService.didChange(DidChangeTextDocumentParams().apply {
            textDocument = VersionedTextDocumentIdentifier(uri, 2)
            contentChanges = listOf(
                TextDocumentContentChangeEvent(validContent)
            )
        })

        harness.client.waitForDiagnosticsCleared(uri)
        assertEquals(emptyList(), harness.getDiagnostics(uri), "Diagnostics should be cleared after fixing the file")
        
        println("✅ Diagnostics updated on change")
    }
    
    @Test
    fun `diagnostics cleared on file close`() {
        println("=== TEST: diagnostics cleared on file close ===")
        
        val server = harness.server
        val uri = "file:///test.smali"
        
        val invalidContent = """
            .class public LTest;
            .super Lcom/example/Missing;
        """.trimIndent()

        // Open file with diagnostics
        server.textDocumentService.didOpen(DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem(
                uri,
                "smali",
                1,
                invalidContent
            )
        })

        val diagnostics = harness.waitForDiagnostics(uri)
        assertTrue(diagnostics.isNotEmpty(), "File should have diagnostics before close")
        
        // Close file (should clear diagnostics)
        server.textDocumentService.didClose(DidCloseTextDocumentParams().apply {
            textDocument = TextDocumentIdentifier(uri)
        })

        harness.client.waitForDiagnosticsCleared(uri)
        assertEquals(emptyList(), harness.getDiagnostics(uri), "Closing the file should clear diagnostics")
        
        println("✅ Diagnostics cleared on close")
    }
    
    @Test
    fun `massive file with many errors handles gracefully`() {
        println("=== TEST: massive file with many errors ===")
        
        val server = harness.server
        
        // Generate file with 100 undefined class references
        val errors = (1..100).joinToString("\n") { i ->
            """
            .method public method$i()V
                invoke-virtual {v0}, Lcom/example/Missing$i;->foo()V
                return-void
            .end method
            """.trimIndent()
        }
        
        val content = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            $errors
        """.trimIndent()
        
        server.textDocumentService.didOpen(DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem(
                "file:///test.smali",
                "smali",
                1,
                content
            )
        })
        
        val diagnostics = harness.waitForDiagnostics("file:///test.smali")
        val undefinedClassDiagnostics = diagnostics.count { it.message.contains("not found in workspace or SDK") }

        assertEquals(100, undefinedClassDiagnostics, "Should publish one undefined-class diagnostic per broken invoke")
        
        println("✅ Massive file handled gracefully")
    }
    
    @Test
    fun `concurrent diagnostic requests`() {
        println("=== TEST: concurrent diagnostic requests ===")
        
        val server = harness.server
        
        // Open 10 files concurrently
        val threads = (1..10).map { i ->
            Thread {
                val content = """
                    .class public LTest$i;
                    .super Ljava/lang/Object;
                    
                    .method public test()V
                        return-void
                    .end method
                """.trimIndent()
                
                server.textDocumentService.didOpen(DidOpenTextDocumentParams().apply {
                    textDocument = TextDocumentItem(
                        "file:///test$i.smali",
                        "smali",
                        1,
                        content
                    )
                })
            }
        }
        
        val startTime = System.currentTimeMillis()
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val elapsed = System.currentTimeMillis() - startTime

        val diagnosticsByFile = (1..10).associateWith { i ->
            harness.waitForDiagnostics("file:///test$i.smali", 2_000)
        }
        
        println("Concurrent diagnostic requests (10 files): ${elapsed}ms")
        assertTrue(diagnosticsByFile.values.all { it.isEmpty() }, "All concurrent valid files should publish empty diagnostics")
        
        println("✅ Concurrent requests handled")
    }
}
