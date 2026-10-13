package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.providers.RenameProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Labels are method-local: definition, references, hover and rename resolve `:name` inside the
 * method containing the caret, from every kind of reference (jumps, `.catch`/`.catchall` ranges
 * and handlers, switch instructions and their tables, `fill-array-data`). Ranges cover the colon
 * and a caret on it counts; rename edits the name only.
 */
class LabelNavigationTest {

    /** One method referencing labels from every construct; `:lonely` is never referenced. */
    private val flow = """
        .class public Lcom/example/Flow;
        .super Ljava/lang/Object;

        .method public run(I)V
            .registers 3
            if-eqz p1, :cond_0
            goto :goto_0
            :cond_0
            :try_start_0
            packed-switch p1, :pswitch_data_0
            sparse-switch p1, :sswitch_data_0
            fill-array-data v0, :array_0
            :try_end_0
            .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0
            .catchall {:try_start_0 .. :try_end_0} :catchall_0
            :catch_0
            :catchall_0
            :goto_0
            return-void
            :lonely
            :pswitch_data_0
            .packed-switch 0x0
                :cond_0
                :goto_0
            .end packed-switch
            :sswitch_data_0
            .sparse-switch
                0x1 -> :cond_0
                0x2 -> :goto_0
            .end sparse-switch
            :array_0
            .array-data 1
                0x1t
            .end array-data
        .end method
    """.trimIndent()

    /** Two methods defining `:cond_0`, as baksmali output does in nearly every method. */
    private val twins = """
        .class public Lcom/example/Twins;
        .super Ljava/lang/Object;

        .method public first()V
            .registers 1
            if-eqz v0, :cond_0
            :cond_0
            return-void
        .end method

        .method public second()V
            .registers 1
            if-nez v0, :cond_0
            goto :cond_0
            :cond_0
            return-void
        .end method
    """.trimIndent()

    private val flowUri = "file:///Flow.smali"
    private val twinsUri = "file:///Twins.smali"
    private val index = WorkspaceIndex().also { it.openSource(flowUri, flow); it.openSource(twinsUri, twins) }
    private val definitions = DefinitionProvider(index)
    private val references = ReferenceProvider(index)
    private val hover = HoverProvider(index)
    private val rename = RenameProvider(index)

    /** The range of `:name` on its definition line (the first one, or with [last] the last), colon included. */
    private fun String.definitionOf(name: String, last: Boolean = false): Range {
        val line = if (last) lines().lastIndexOf("    :$name") else lines().indexOf("    :$name")
        require(line >= 0) { "no definition of :$name" }
        return Range(Position(line, 4), Position(line, 5 + name.length))
    }

    /** The range of the `:name` reference on the first line containing [marker], colon included. */
    private fun String.referenceOf(marker: String, name: String): Range {
        val start = positionOf(marker, ":$name")
        return Range(start, Position(start.line, start.character + 1 + name.length))
    }

    /** The name inside a `:name` range: what rename edits. */
    private fun Range.name() = Range(Position(start.line, start.character + 1), end)

    private fun hoverAt(uri: String, caret: Position): String {
        val text = hover.provideHover(uri, caret)?.contents?.right?.value
        assertNotNull(text, "hover at $caret")
        return text
    }

    /** The ranges rename replaces with [newName] from [caret], in file order. */
    private fun renamed(uri: String, caret: Position, newName: String): List<Range> {
        val edit = rename.rename(uri, caret, newName)
        assertNotNull(edit, "rename at $caret")
        val edits = edit.changes.getValue(uri)
        assertTrue(edits.all { it.newText == newName })
        return edits.map { it.range }.sortedWith(compareBy({ it.start.line }, { it.start.character }))
    }

    @Test
    fun `definition from every kind of reference, caret on the colon`() {
        val sites = listOf(
            "if-eqz" to "cond_0", "goto :" to "goto_0",
            ".catch " to "try_start_0", ".catch " to "try_end_0", ".catch " to "catch_0",
            ".catchall" to "try_start_0", ".catchall" to "try_end_0", ".catchall" to "catchall_0",
            "packed-switch p1" to "pswitch_data_0", "        :goto_0" to "goto_0",
            "sparse-switch p1" to "sswitch_data_0", "0x1 ->" to "cond_0",
            "fill-array-data" to "array_0",
        )
        for ((marker, name) in sites) {
            val found = definitions.findDefinition(flowUri, flow.positionOf(marker, ":$name"))
            assertEquals(listOf(Location(flowUri, flow.definitionOf(name))), found, "$marker :$name")
        }
    }

    @Test
    fun `references from the definition, from any reference or from the jump opcode`() {
        val expected = listOf(
            flow.definitionOf("cond_0"), flow.referenceOf("if-eqz", "cond_0"),
            flow.referenceOf("        :cond_0", "cond_0"), flow.referenceOf("0x1 ->", "cond_0"),
        )
        val fromDefinition = references.findReferences(flowUri, flow.definitionOf("cond_0").start)
        assertEquals(expected, fromDefinition.map { it.range })
        assertTrue(fromDefinition.all { it.uri == flowUri })
        assertEquals(expected, references.findReferences(flowUri, flow.positionOf("0x1 ->", ":cond_0", 4)).map { it.range }, "from a switch table entry")
        assertEquals(expected, references.findReferences(flowUri, flow.positionOf("if-eqz", "if-eqz", 3)).map { it.range }, "from the opcode of a jump")
        assertEquals(expected.drop(1), references.findReferences(flowUri, flow.positionOf("if-eqz", ":cond_0", 3), includeDeclaration = false).map { it.range })
        assertEquals(listOf(flow.definitionOf("lonely")), references.findReferences(flowUri, flow.definitionOf("lonely").start).map { it.range }, "an unreferenced label is only its definition")
    }

    @Test
    fun `hover counts the references in the method and names the kind of label`() {
        val labels = listOf(
            Triple("cond_0", 3, "Conditional branch target"), Triple("goto_0", 3, "Unconditional branch target"),
            Triple("try_start_0", 2, "Try block start"), Triple("try_end_0", 2, "Try block end"),
            Triple("catch_0", 1, "Exception handler"), Triple("catchall_0", 1, "Catch-all exception handler"),
            Triple("pswitch_data_0", 1, "Packed switch target"), Triple("sswitch_data_0", 1, "Sparse switch target"),
            Triple("array_0", 1, "Array data target"), Triple("lonely", 0, "Branch target"),
        )
        for ((name, count, kind) in labels) {
            val text = hoverAt(flowUri, flow.definitionOf(name).start)
            assertTrue(text.contains("**Label:** `:$name`") && text.contains("**References:** $count") && text.contains("**Type:** $kind"), text)
        }
    }

    @Test
    fun `rename edits the name at the definition and every reference, never the colon`() {
        val gotoSites = listOf(flow.referenceOf("goto :", "goto_0"), flow.definitionOf("goto_0"), flow.referenceOf("        :goto_0", "goto_0"), flow.referenceOf("0x2 ->", "goto_0"))
        assertEquals(gotoSites.map { it.name() }, renamed(flowUri, flow.definitionOf("goto_0").start, "exit"))
        val trySites = listOf(flow.definitionOf("try_start_0"), flow.referenceOf(".catch ", "try_start_0"), flow.referenceOf(".catchall", "try_start_0"))
        assertEquals(trySites.map { it.name() }, renamed(flowUri, flow.positionOf(".catchall", ":try_start_0", 5), "begin"), "from the .catchall range")
        val arraySites = listOf(flow.referenceOf("fill-array-data", "array_0"), flow.definitionOf("array_0"))
        assertEquals(arraySites.map { it.name() }, renamed(flowUri, flow.positionOf("fill-array-data", ":array_0", 3), "data"), "from fill-array-data")
        val prepared = rename.prepareRename(flowUri, flow.positionOf("0x1 ->", ":cond_0", 2))
        assertNotNull(prepared, "a switch table entry is renameable")
        assertEquals("cond_0", prepared.right.placeholder)
        assertEquals(flow.referenceOf("0x1 ->", "cond_0").name(), prepared.right.range)
    }

    @Test
    fun `labels with the same name in different methods are separate`() {
        val firstDef = twins.definitionOf("cond_0")
        val secondDef = twins.definitionOf("cond_0", last = true)
        val firstJump = twins.referenceOf("if-eqz", "cond_0")
        val secondJumps = listOf(twins.referenceOf("if-nez", "cond_0"), twins.referenceOf("goto :", "cond_0"))
        assertEquals(listOf(firstDef), definitions.findDefinition(twinsUri, firstJump.start).map { it.range })
        assertEquals(listOf(secondDef), definitions.findDefinition(twinsUri, secondJumps[0].start).map { it.range })
        assertEquals(listOf(firstDef, firstJump), references.findReferences(twinsUri, firstDef.start).map { it.range })
        assertEquals(listOf(secondDef) + secondJumps, references.findReferences(twinsUri, secondJumps[1].start).map { it.range })
        assertTrue(hoverAt(twinsUri, firstDef.start).contains("**References:** 1"))
        assertTrue(hoverAt(twinsUri, secondDef.start).contains("**References:** 2"))
        assertEquals(listOf(firstJump, firstDef).map { it.name() }, renamed(twinsUri, firstDef.start, "done"))
        assertEquals((secondJumps + secondDef).map { it.name() }, renamed(twinsUri, secondJumps[0].start, "done"))
    }

    @Test
    fun `a reference to a label the method does not define`() {
        val uri = "file:///Orphan.smali"
        index.openSource(uri, ".class public Lcom/example/Orphan;\n.super Ljava/lang/Object;\n\n.method public run()V\n    .registers 1\n    goto :nowhere\n    return-void\n.end method")
        val caret = Position(5, 11)
        assertTrue(definitions.findDefinition(uri, caret).isEmpty())
        assertEquals(listOf(Range(Position(5, 9), Position(5, 17))), references.findReferences(uri, caret).map { it.range }, "the reference itself is still listed")
        assertEquals("**Jump to label:** `:nowhere`", hoverAt(uri, caret))
        assertNull(rename.prepareRename(uri, caret))
        assertNull(rename.rename(uri, caret, "somewhere"))
    }
}
