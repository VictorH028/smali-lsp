package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.CodeActionProvider
import xyz.surendrajat.smalilsp.providers.DiagnosticProvider
import xyz.surendrajat.smalilsp.shared.openSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Every diagnostic with one right answer offers it as a quick fix whose edit applies cleanly to the current text. */
class CodeActionProviderTest {

    private val index = WorkspaceIndex()
    private val parser = SmaliParser(index.strings)
    private val provider = CodeActionProvider(index, DiagnosticProvider(parser, index))
    private val uri = "file:///Edit.smali"

    private fun source(body: String, header: String = ".method public run(ILjava/lang/String;J)V"): String =
        ".class public Lcom/Edit;\n.super Ljava/lang/Object;\n\n$header\n$body\n.end method\n"

    /** The actions over the whole document, in diagnostic order, as title to the text after the edit. */
    private fun fixes(text: String): List<Pair<String, String>> {
        index.openSource(uri, text)
        return provider.actions(uri, Range(Position(0, 0), Position(text.lines().size, 0))).map { it.title to apply(text, it) }
    }

    private fun apply(text: String, action: CodeAction): String {
        val lines = text.lines()
        val starts = IntArray(lines.size + 1)
        for (i in lines.indices) starts[i + 1] = starts[i] + lines[i].length + 1
        fun offset(p: Position) = starts[p.line] + p.character
        var result = text
        for (edit in assertNotNull(action.edit.changes[uri]).sortedByDescending { offset(it.range.start) }) {
            result = result.substring(0, offset(edit.range.start)) + edit.newText + result.substring(offset(edit.range.end))
        }
        return result
    }

    private fun List<Pair<String, String>>.only(title: String): String = single { it.first == title }.second

    @Test
    fun `a member nothing declares is replaced by the closest one`() {
        index.openSource("file:///Worker.smali", ".class public Lcom/Worker;\n.super Ljava/lang/Object;\n\n.field public count:I\n\n.method public tick(I)V\n    .registers 2\n    return-void\n.end method\n")
        val fixes = fixes(source("    .registers 6\n    invoke-virtual {p0, v0}, Lcom/Worker;->tick(J)V\n    iget v0, p0, Lcom/Worker;->cont:I\n    return-void"))
        assertEquals(listOf("Use tick(I)V", "Use count:I"), fixes.map { it.first })
        assertEquals(source("    .registers 6\n    invoke-virtual {p0, v0}, Lcom/Worker;->tick(I)V\n    iget v0, p0, Lcom/Worker;->cont:I\n    return-void"), fixes.only("Use tick(I)V"))
        assertEquals(source("    .registers 6\n    invoke-virtual {p0, v0}, Lcom/Worker;->tick(J)V\n    iget v0, p0, Lcom/Worker;->count:I\n    return-void"), fixes.only("Use count:I"))
    }

    @Test
    fun `the wider const and the frame count`() {
        val fixes = fixes(source("    .registers 5\n    const/4 v5, 0x10\n    return-void"))
        assertEquals(listOf("Set .registers 6", "Use const/16"), fixes.map { it.first })
        assertEquals(source("    .registers 6\n    const/4 v5, 0x10\n    return-void"), fixes.only("Set .registers 6"))
        assertEquals(source("    .registers 5\n    const/16 v5, 0x10\n    return-void"), fixes.only("Use const/16"))
        val locals = fixes(source("    .locals 1\n    const/4 v6, 0x0\n    const-wide/16 v5, 0x1\n    add-int/lit8 v0, v0, 0x80\n    const-wide/high16 v0, 0x1L\n    const-wide/16 v0, 0x100000000L\n    return-void"))
        assertEquals(listOf("Set .locals 2", "Set .locals 2", "Use add-int/lit16", "Use const-wide/32", "Use const-wide"), locals.map { it.first }, "v6 and the pair at v5 both need seven registers, five of them parameters")
        assertEquals(source("    .locals 2\n    const/4 v6, 0x0\n    const-wide/16 v5, 0x1\n    add-int/lit8 v0, v0, 0x80\n    const-wide/high16 v0, 0x1L\n    const-wide/16 v0, 0x100000000L\n    return-void"), locals[1].second)
        assertEquals(emptyList(), fixes(source("    .registers 5\n    const/4 p5, 0x0\n    add-int/lit16 v0, v0, 0x8000\n    return-void")).map { it.first }, "a missing parameter and a literal that needs a register have no fix")
    }

    @Test
    fun `calls get their range form only when the registers are consecutive`() {
        val header = ".method public static run()V"
        val six = fixes(source("    .registers 6\n    invoke-static {v0, v1, v2, v3, v4, v5}, Lcom/Edit;->s(IIIIII)V\n    return-void", header))
        assertEquals(listOf("Use invoke-static/range" to source("    .registers 6\n    invoke-static/range {v0 .. v5}, Lcom/Edit;->s(IIIIII)V\n    return-void", header)), six)
        assertEquals(emptyList(), fixes(source("    .registers 6\n    invoke-static {v0, v2, v1, v3, v4, v5}, Lcom/Edit;->s(IIIIII)V\n    return-void", header)), "no range covers them in that order")
        val wide = fixes(source("    .registers 20\n    move v16, v1\n    invoke-virtual {p0, p1, p2, p3}, Lcom/Edit;->x(ILjava/lang/String;I)V\n    return-void"))
        assertEquals(listOf("Use move/from16", "Use invoke-virtual/range", "Use invoke-virtual/range", "Use invoke-virtual/range"), wide.map { it.first }, "one action per register that does not fit, all with the same edit")
        assertEquals(source("    .registers 20\n    move/from16 v16, v1\n    invoke-virtual {p0, p1, p2, p3}, Lcom/Edit;->x(ILjava/lang/String;I)V\n    return-void"), wide.only("Use move/from16"))
        assertEquals(1, wide.drop(1).map { it.second }.toSet().size)
        assertEquals(source("    .registers 20\n    move v16, v1\n    invoke-virtual/range {p0 .. p3}, Lcom/Edit;->x(ILjava/lang/String;I)V\n    return-void"), wide[1].second)
    }

    @Test
    fun `return and move-result kinds, and a move-result with nothing to move`() {
        val body = "    .registers 6\n    invoke-virtual {p0}, Lcom/Edit;->o()Ljava/lang/String;\n    move-result v0\n    invoke-virtual {p0}, Lcom/Edit;->v()V\n    move-result v0\n    move-result v1\n    return v0    # done"
        val fixes = fixes(source(body))
        assertEquals(listOf("Use move-result-object", "Remove this move-result", "Remove this move-result", "Use return-void"), fixes.map { it.first })
        assertEquals(source(body.replace("move-result v0\n    invoke", "move-result-object v0\n    invoke")), fixes[0].second)
        assertEquals(source(body.replace("    move-result v0\n    move-result v1", "    move-result v1")), fixes[1].second, "the move-result after the void call goes")
        assertEquals(source(body.replace("    move-result v0\n    move-result v1", "    move-result v0")), fixes[2].second, "the one that follows no call goes")
        assertEquals(source(body.replace("return v0    # done", "return-void    # done")), fixes[3].second, "the register goes, the comment stays")
        val value = fixes(source("    .registers 6\n    return p2\n    return-void", ".method public run(ILjava/lang/String;J)Ljava/lang/Object;"))
        assertEquals(listOf("Use return-object"), value.map { it.first }, "return-void in a method with a value has no fix: it needs a register")
        assertEquals(source("    .registers 6\n    return-object p2\n    return-void", ".method public run(ILjava/lang/String;J)Ljava/lang/Object;"), value[0].second)
    }

    @Test
    fun `the member's opcode, a label typo, a duplicate label and a second directive`() {
        index.openSource("file:///Util.smali", ".class public Lcom/Util;\n.super Ljava/lang/Object;\n\n.field public static COUNT:I\n\n.method public run()V\n    .registers 1\n    return-void\n.end method\n")
        val body = "    .registers 6\n    .locals 1\n    invoke-static {}, Lcom/Util;->run()V\n    iget v0, p0, Lcom/Util;->COUNT:I\n    goto :cond_1\n    :cond_0\n    :cond_0\n    return-void"
        val fixes = fixes(source(body))
        assertEquals(listOf("Remove this .locals", "Rename to :cond_0", "Remove the duplicate label", "Use invoke-virtual", "Use sget"), fixes.map { it.first })
        assertEquals(source(body.replace("    .locals 1\n", "")), fixes[0].second)
        assertEquals(source(body.replace("goto :cond_1", "goto :cond_0")), fixes[1].second)
        assertEquals(source(body.replace("    :cond_0\n    :cond_0", "    :cond_0")), fixes[2].second)
        assertEquals(source(body.replace("invoke-static {}", "invoke-virtual {}")), fixes[3].second)
        assertEquals(source(body.replace("iget v0, p0", "sget v0, p0")), fixes[4].second)
        assertEquals(emptyList(), fixes(source("    .registers 6\n    goto :nowhere\n    :cond_0\n    return-void")).map { it.first }, "no label is close enough to be meant")
    }

    @Test
    fun `a backwards range is swapped, and a caret anywhere on the line gets its fixes`() {
        val text = source("    .registers 6\n    invoke-virtual/range {v3 .. v0}, Lcom/Edit;->x()V\n    const/4 v0, 0x10\n    return-void")
        index.openSource(uri, text)
        val all = provider.actions(uri, Range(Position(0, 0), Position(20, 0)))
        assertEquals(listOf("Swap the range ends", "Use const/16"), all.map { it.title })
        assertEquals(source("    .registers 6\n    invoke-virtual/range {v0 .. v3}, Lcom/Edit;->x()V\n    const/4 v0, 0x10\n    return-void"), apply(text, all[0]))
        assertTrue(all.all { it.kind == CodeActionKind.QuickFix && it.diagnostics.size == 1 && it.isPreferred == true })
        assertEquals("call-arity", all[0].diagnostics[0].code.left)
        assertEquals(listOf("Use const/16"), provider.actions(uri, Range(Position(6, 0), Position(6, 0))).map { it.title })
        assertEquals(emptyList(), provider.actions(uri, Range(Position(7, 0), Position(7, 4))).map { it.title })
        assertEquals(emptyList(), provider.actions("file:///Closed.smali", Range(Position(0, 0), Position(1, 0))))
    }
}
