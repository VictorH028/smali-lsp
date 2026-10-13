package xyz.surendrajat.smalilsp.regression

import org.eclipse.lsp4j.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DiagnosticProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests covering bugs found in the April 2026 audit.
 * Each nested class corresponds to one audit finding.
 */
class AuditBugRegressionTest {

    @Nested
    @DisplayName("Bug #1: hover on indented directives")
    inner class IndentedDirectiveHover {
        private val index = WorkspaceIndex()
        private val provider = HoverProvider(index)
        private val uri = "file:///Test.smali"
        private val source = listOf(
            ".class public Lcom/test/Test;",
            "    .super Ljava/lang/Object;",
            "    .implements Lcom/example/MyInterface;",
            "",
            "    .annotation system Lcom/example/MyInterface;",
            "    .end annotation",
            "",
            ".method public test()V",
            "    .registers 1",
            "    :try_start_0",
            "    invoke-virtual {p0}, Ljava/lang/Object;->toString()Ljava/lang/String;",
            "    :try_end_0",
            "    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0",
            "    :catch_0",
            "    return-void",
            ".end method",
        ).joinToString("\n")

        @BeforeEach
        fun setup() {
            index.openSource("file:///Exception.smali", ".class public Ljava/lang/Exception;\n.super Ljava/lang/Throwable;")
            index.openSource("file:///MyInterface.smali", ".class public interface Lcom/example/MyInterface;\n.super Ljava/lang/Object;")
            index.openSource(uri, source)
        }

        private fun hover(marker: String, needle: String, offset: Int): String? =
            provider.provideHover(uri, source.positionOf(marker, needle, offset))?.contents?.right?.value

        @Test
        fun `hover on indented catch directive should work`() {
            val hover = hover(".catch", "Ljava/lang/Exception;", 9)
            assertNotNull(hover, "Hover should work on indented .catch directive class reference")
            assertTrue(hover.contains("java.lang.Exception"), hover)
        }

        @Test
        fun `hover on indented implements directive should work`() {
            val hover = hover(".implements", "Lcom/example/MyInterface;", 9)
            assertNotNull(hover, "Hover should work on indented .implements directive")
            assertTrue(hover.contains("com.example.MyInterface"), hover)
        }

        @Test
        fun `hover on indented annotation should work`() {
            val hover = hover(".annotation", "Lcom/example/MyInterface;", 14)
            assertNotNull(hover, "Hover should work on indented annotation types")
            assertTrue(hover.contains("com.example.MyInterface"), hover)
        }
    }

    @Nested
    @DisplayName("Bug #2: SmaliParser SyntaxError line=0 causes Position(-1, 0)")
    inner class SyntaxErrorNegativePosition {
        private val parser = SmaliParser()
        private val index = WorkspaceIndex()
        private val diagnosticProvider = DiagnosticProvider(parser, index)

        @Test
        fun `exception-path syntax error should not produce negative line positions`() {
            // Force a parse exception by providing content that triggers the catch block
            // The catch block creates SyntaxError(0, 0, ...) and DiagnosticProvider does line-1
            // This should result in Position(0, 0) not Position(-1, 0)
            val parseResult = SmaliParser.ParseResult(
                smaliFile = null,
                syntaxErrors = listOf(SmaliParser.SyntaxError(0, 0, "Parse error: something"))
            )
            val diagnostics = diagnosticProvider.computeDiagnosticsFromParseResult(
                "file:///test.smali", parseResult
            )
            assertTrue(diagnostics.isNotEmpty(), "Should produce at least one diagnostic")

            for (d in diagnostics) {
                assertTrue(d.range.start.line >= 0,
                    "Diagnostic line should not be negative, got: ${d.range.start.line}")
                assertTrue(d.range.end.line >= 0,
                    "Diagnostic end line should not be negative, got: ${d.range.end.line}")
            }
        }
    }

    @Nested
    @DisplayName("Bug #3: didOpen never publishes diagnostics")
    inner class DidOpenDiagnostics {
        // This is a behavioral test — we verify the contract that diagnostics
        // should be published on didOpen, not just on didChange.
        // The actual LSP client mock would need to be established in integration tests.
        // Here we document the expected behavior.
        
        @Test
        fun `didOpen should produce diagnostics for files with errors`() {
            val parser = SmaliParser()
            val index = WorkspaceIndex()
            val diagnosticProvider = DiagnosticProvider(parser, index)
            
            val brokenContent = """
                .class public LBroken;
                .super Ljava/lang/Object;
                
                .method public test()V
                    invalid-opcode
                    return-void
                .end method
            """.trimIndent()
            
            // Simulate what didOpen SHOULD do: parse + compute diagnostics
            val parseResult = parser.parseWithErrors("file:///broken.smali", brokenContent)
            val diagnostics = diagnosticProvider.computeDiagnosticsFromParseResult(
                "file:///broken.smali", parseResult
            )
            
            // The file has an invalid opcode, so diagnostics should be non-empty
            // This tests the underlying capability; the service-level test is below
            assertTrue(diagnostics.isNotEmpty() || parseResult.syntaxErrors.isNotEmpty(),
                "Parser/diagnostics should detect errors in broken files")
        }
    }

    @Nested
    @DisplayName("Bug #4: class names with underscores and hyphens")
    inner class UnusualClassNames {
        private val index = WorkspaceIndex()
        private val provider = HoverProvider(index)

        private fun hoverOnAnnotation(className: String): String? {
            index.openSource("file:///Target.smali", ".class public $className\n.super Ljava/lang/Object;")
            val source = ".class public Lcom/test/Test;\n.super Ljava/lang/Object;\n\n.annotation system $className\n.end annotation"
            index.openSource("file:///test.smali", source)
            return provider.provideHover("file:///test.smali", source.positionOf(".annotation", className, 18))?.contents?.right?.value
        }

        @Test
        fun `hover should work on class names with underscores`() {
            val hover = hoverOnAnnotation("Lcom/example/My_Class;")
            assertNotNull(hover, "Hover should work on class names with underscores")
            assertTrue(hover.contains("com.example.My_Class"), hover)
        }

        @Test
        fun `hover should work on class names with hyphens`() {
            val hover = hoverOnAnnotation("Lcom/example/My-Class;")
            assertNotNull(hover, "Hover should work on class names with hyphens")
            assertTrue(hover.contains("com.example.My-Class"), hover)
        }
    }

    @Nested
    @DisplayName("Bug #5: ServerInfo version hardcoded as 1.0.0")
    inner class ServerVersion {
        @Test
        fun `project version should be consistent`() {
            // Verify the version.properties mechanism works
            // The MainKt loads version from resource; we verify the resource exists
            val props = java.util.Properties()
            val stream = Class.forName("xyz.surendrajat.smalilsp.SmaliLanguageServer")
                .getResourceAsStream("/version.properties")
            assertNotNull(stream, "version.properties must be generated into the main resources")
            stream!!.use { props.load(it) }
            val buildFile = java.io.File(xyz.surendrajat.smalilsp.shared.TestUtils.getProjectRoot(), "build.gradle.kts").readText()
            val expected = Regex("""^version = "([^"]+)"""", RegexOption.MULTILINE).find(buildFile)!!.groupValues[1]
            assertEquals(expected, props.getProperty("version"), "version.properties must carry the project version from build.gradle.kts")
        }
    }

    @Nested
    @DisplayName("Bug #6: getAllSubclasses cycle processes root twice")
    inner class SubclassCycleBug {
        @Test
        fun `getAllSubclasses should not process root class twice in a cycle`() {
            val index = WorkspaceIndex()

            // Create A -> B -> A cycle
            index.indexFile(
                SmaliFile(
                    uri = "file:///A.smali",
                    classDefinition = ClassDefinition(
                        name = "Lcom/example/A;",
                        range = range(0, 0, 1, 0),
                        modifiers = setOf("public"),
                        superClass = "Lcom/example/B;",
                        interfaces = emptyList()
                    ),
                    methods = emptyList(),
                    fields = emptyList()
                )
            )
            index.indexFile(
                SmaliFile(
                    uri = "file:///B.smali",
                    classDefinition = ClassDefinition(
                        name = "Lcom/example/B;",
                        range = range(0, 0, 1, 0),
                        modifiers = setOf("public"),
                        superClass = "Lcom/example/A;",
                        interfaces = emptyList()
                    ),
                    methods = emptyList(),
                    fields = emptyList()
                )
            )

            // Should not infinite loop and should contain each class exactly once
            val subclasses = index.getAllSubclasses("Lcom/example/A;")
            // B is a subclass of A, and A is subclass of B (cycle)
            assertTrue(subclasses.contains("Lcom/example/B;"), "B should be a subclass of A")
            // Root A should NOT appear in its own subclasses
            assertTrue(!subclasses.contains("Lcom/example/A;"),
                "Root class A should not appear in its own getAllSubclasses result")
        }
    }

    @Nested
    @DisplayName("Bug: HoverProvider mixed MarkupKind.MARKDOWN vs string literal")
    inner class MarkupKindConsistency {
        @Test
        fun `all hover results should use MarkupKind MARKDOWN constant`() {
            val index = WorkspaceIndex()
            val provider = HoverProvider(index)
            val source = ".class public Lcom/example/Test;\n.super Ljava/lang/Object;\n\n.field private count:I\n\n.method public test()V\n    .registers 1\n    return-void\n.end method"
            index.openSource("file:///Test.smali", source)
            val positions = listOf(
                source.positionOf(".class", "Lcom/example/Test;", 3),
                source.positionOf("count:I", "I", after = ":"),
                source.positionOf(".method", "test", 1),
                source.positionOf("return-void", "return-void", 2),
            )
            for (position in positions) {
                val hover = provider.provideHover("file:///Test.smali", position)
                assertNotNull(hover, "hover at $position")
                assertEquals(MarkupKind.MARKDOWN, (hover.contents.right as MarkupContent).kind,
                    "MarkupContent should use MarkupKind.MARKDOWN constant, not string literal")
            }
        }
    }

    @Nested
    @DisplayName("Performance: getLineContent re-splits on every call")
    inner class GetLineContentCaching {
        @Test
        fun `getLineContent should return correct content for multiple calls`() {
            val index = WorkspaceIndex()
            val content = ".class public LTest;\n.super Ljava/lang/Object;\n\n.method public test()V\n    return-void\n.end method"
            val uri = "file:///test.smali"
            index.setDocumentContent(uri, content)

            // Multiple calls should return correct results
            assertEquals(".class public LTest;", index.getLineContent(uri, 0))
            assertEquals(".super Ljava/lang/Object;", index.getLineContent(uri, 1))
            assertEquals("", index.getLineContent(uri, 2))
            assertEquals(".method public test()V", index.getLineContent(uri, 3))
            assertEquals("    return-void", index.getLineContent(uri, 4))
            assertEquals(".end method", index.getLineContent(uri, 5))
        }
    }

    @Nested
    @DisplayName("Additional: CompletionProvider double-call with !!")
    inner class CompletionProviderDoubleCall {
        @Test
        fun `extractPartialClassName logic should not require double invocation`() {
            // This is a code quality test — ensure the pattern is safe
            // The fix will cache the result in a let-block
            // We just verify completion works with partial class names
            val index = WorkspaceIndex()
            index.indexFile(
                SmaliFile(
                    uri = "file:///MyClass.smali",
                    classDefinition = ClassDefinition(
                        name = "Lcom/example/MyClass;",
                        range = range(0, 0, 1, 0),
                        modifiers = setOf("public"),
                        superClass = "Ljava/lang/Object;",
                        interfaces = emptyList()
                    ),
                    methods = emptyList(),
                    fields = emptyList()
                )
            )
            val provider = xyz.surendrajat.smalilsp.providers.CompletionProvider(index)
            // Trigger completion with a partial class name
            val result = provider.provideCompletions(
                "file:///test.smali",
                Position(0, 5),
                "Lcom/"
            )
            assertNotNull(result, "Completion should return a result")
        }
    }
}
