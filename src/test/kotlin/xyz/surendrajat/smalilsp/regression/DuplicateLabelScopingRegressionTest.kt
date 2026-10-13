package xyz.surendrajat.smalilsp.regression

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.RenameProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests: labels are method-local in smali, and baksmali reuses the
 * same names (:cond_0, :goto_0, …) in nearly every method. Providers used to
 * resolve labels by scanning methods in file order, so any label operation in
 * a second-or-later method silently targeted the first method:
 *  - RenameProvider rewrote the wrong method's labels (silent code corruption)
 *  - DefinitionProvider jumped to the wrong method's label
 *  - HoverProvider counted jumps across all methods
 */
class DuplicateLabelScopingRegressionTest {

    private lateinit var parser: SmaliParser
    private lateinit var index: WorkspaceIndex
    private lateinit var renameProvider: RenameProvider
    private lateinit var definitionProvider: DefinitionProvider
    private lateinit var hoverProvider: HoverProvider

    private val uri = "file:///com/example/Dup.smali"

    /*  Line map (0-based):
     *   3  .method public first()V
     *   5      if-eqz v0, :cond_0        (jump in first)
     *   7      :cond_0                   (def in first)
     *  11  .method public second()V
     *  13      if-nez v0, :cond_0        (jump 1 in second)
     *  15      if-eqz v0, :cond_0        (jump 2 in second)
     *  17      :cond_0                   (def in second)
     */
    private val content = """
        .class public Lcom/example/Dup;
        .super Ljava/lang/Object;

        .method public first()V
            const/4 v0, 0x0
            if-eqz v0, :cond_0
            const/4 v1, 0x1
            :cond_0
            return-void
        .end method

        .method public second()V
            const/4 v0, 0x0
            if-nez v0, :cond_0
            const/4 v1, 0x1
            if-eqz v0, :cond_0
            const/4 v1, 0x0
            :cond_0
            return-void
        .end method
    """.trimIndent()

    @BeforeEach
    fun setup() {
        parser = SmaliParser()
        index = WorkspaceIndex()
        renameProvider = RenameProvider(index)
        definitionProvider = DefinitionProvider(index)
        hoverProvider = HoverProvider(index)

        val file = parser.parse(uri, content)
        assertNotNull(file, "test fixture must parse")
        index.indexFile(file!!)
        index.setDocumentContent(uri, content)
    }

    @Test
    fun `goto definition from jump in second method resolves second method's label`() {
        // Cursor on ":cond_0" of "if-nez v0, :cond_0" (line 13)
        val locations = definitionProvider.findDefinition(uri, Position(13, 17))
        assertEquals(1, locations.size, "expected exactly one label definition")
        assertEquals(17, locations[0].range.start.line,
            "must resolve to second method's :cond_0 (line 17), not first method's (line 7)")
    }

    @Test
    fun `goto definition from jump in first method still resolves first method's label`() {
        val locations = definitionProvider.findDefinition(uri, Position(5, 17))
        assertEquals(1, locations.size)
        assertEquals(7, locations[0].range.start.line)
    }

    @Test
    fun `hover on label counts only jumps within the containing method`() {
        val hoverFirst = hoverProvider.provideHover(uri, Position(7, 6))
        assertNotNull(hoverFirst)
        assertTrue(hoverFirst!!.contents.right.value.contains("**References:** 1"),
            "first method has exactly 1 jump to :cond_0, got: ${hoverFirst.contents.right.value}")

        val hoverSecond = hoverProvider.provideHover(uri, Position(17, 6))
        assertNotNull(hoverSecond)
        assertTrue(hoverSecond!!.contents.right.value.contains("**References:** 2"),
            "second method has exactly 2 jumps to :cond_0, got: ${hoverSecond.contents.right.value}")
    }

    @Test
    fun `rename label from jump in second method only edits second method`() {
        val edit = renameProvider.rename(uri, Position(13, 17), "target")
        assertNotNull(edit, "rename should succeed")
        val editedLines = edit!!.changes[uri]!!.map { it.range.start.line }.toSet()
        assertEquals(setOf(13, 15, 17), editedLines,
            "rename must touch both jumps + definition in second method and nothing in first")
    }

    @Test
    fun `rename label from definition in first method only edits first method`() {
        val edit = renameProvider.rename(uri, Position(7, 6), "done")
        assertNotNull(edit)
        val editedLines = edit!!.changes[uri]!!.map { it.range.start.line }.toSet()
        assertEquals(setOf(5, 7), editedLines,
            "rename must touch only first method's jump + definition")
    }
}
