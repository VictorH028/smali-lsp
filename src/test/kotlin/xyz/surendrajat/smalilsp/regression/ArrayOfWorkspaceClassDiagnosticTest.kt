package xyz.surendrajat.smalilsp.regression

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DiagnosticProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression test: check-cast/instance-of/new-array operands keep their array
 * brackets ("[Lcom/app/Foo;"), but the index stores classes under the base name
 * ("Lcom/app/Foo;"). The undefined-class check used the bracketed name verbatim,
 * so arrays of *workspace* classes were flagged as undefined even though the
 * class was indexed. (Primitive and SDK arrays were already handled.)
 */
class ArrayOfWorkspaceClassDiagnosticTest {

    private lateinit var parser: SmaliParser
    private lateinit var index: WorkspaceIndex
    private lateinit var diagnosticProvider: DiagnosticProvider

    @BeforeEach
    fun setup() {
        parser = SmaliParser()
        index = WorkspaceIndex()
        diagnosticProvider = DiagnosticProvider(parser, index)
    }

    @Test
    fun `check-cast to array of indexed workspace class produces no undefined-class warning`() {
        val fooUri = "file:///com/example/Foo.smali"
        val foo = parser.parse(fooUri, """
            .class public Lcom/example/Foo;
            .super Ljava/lang/Object;
        """.trimIndent())
        assertNotNull(foo)
        index.indexFile(foo!!)

        val uri = "file:///com/example/UsesFooArray.smali"
        val content = """
            .class public Lcom/example/UsesFooArray;
            .super Ljava/lang/Object;

            .method public cast(Ljava/lang/Object;)V
                check-cast p1, [Lcom/example/Foo;
                return-void
            .end method
        """.trimIndent()

        val diagnostics = diagnosticProvider.computeDiagnostics(uri, content)
        val falsePositives = diagnostics.filter { it.message.contains("Lcom/example/Foo;") }
        assertEquals(emptyList(), falsePositives,
            "array of an indexed workspace class must not be flagged as undefined")
    }

    @Test
    fun `check-cast to array of truly missing class still warns`() {
        val uri = "file:///com/example/UsesMissingArray.smali"
        val content = """
            .class public Lcom/example/UsesMissingArray;
            .super Ljava/lang/Object;

            .method public cast(Ljava/lang/Object;)V
                check-cast p1, [Lcom/example/Missing;
                return-void
            .end method
        """.trimIndent()

        val diagnostics = diagnosticProvider.computeDiagnostics(uri, content)
        assertTrue(diagnostics.any { it.message.contains("Lcom/example/Missing;") },
            "array of an unknown class must still be reported")
    }
}
