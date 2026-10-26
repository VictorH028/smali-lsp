package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.RenameProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenameProviderTest {

    private lateinit var index: WorkspaceIndex
    private lateinit var provider: RenameProvider

    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        provider = RenameProvider(index)
    }

    /** Edits of [edit] in [uri] as (line, startChar, endChar, newText), sorted. */
    private fun edits(edit: WorkspaceEdit?, uri: String): List<List<Any>> =
        edit?.changes?.get(uri).orEmpty().map { listOf(it.range.start.line, it.range.start.character, it.range.end.character, it.newText) }.sortedWith(compareBy({ it[0] as Int }, { it[1] as Int }))

    private fun edit(source: String, marker: String, needle: String, offset: Int, newText: String, after: String? = null): List<Any> {
        val p = source.positionOf(marker, needle, offset, after)
        return listOf(p.line, p.character, p.character + needle.length, newText)
    }

    private val target = """
        .class public Lcom/example/Target;
        .super Ljava/lang/Object;

        .field public name:Ljava/lang/String;
        .field public count:I

        .method public constructor <init>()V
            .registers 1
            return-void
        .end method

        .method public doWork(I)V
            .registers 2
            return-void
        .end method
    """.trimIndent()

    private val caller = """
        .class public Lcom/example/Caller;
        .super Ljava/lang/Object;

        .method public test()V
            .registers 3
            new-instance v0, Lcom/example/Target;
            invoke-direct {v0}, Lcom/example/Target;-><init>()V
            invoke-virtual {v0, v1}, Lcom/example/Target;->doWork(I)V
            iget-object v1, v0, Lcom/example/Target;->name:Ljava/lang/String;
            iget v2, v0, Lcom/example/Target;->count:I
            invoke-virtual {v1}, Ljava/lang/String;->length()I
            return-void
        .end method
    """.trimIndent()

    private val targetUri = "file:///Target.smali"
    private val callerUri = "file:///Caller.smali"

    private fun openTargetAndCaller() {
        index.openSource(targetUri, target)
        index.openSource(callerUri, caller)
    }

    @Nested
    @DisplayName("prepareRename")
    inner class PrepareRenameTests {

        @Test
        fun `returns null for class names and constructors`() {
            openTargetAndCaller()
            assertNull(provider.prepareRename(targetUri, target.positionOf(".class", "Lcom/example/Target;", 5)), "Class rename should not be supported")
            assertNull(provider.prepareRename(targetUri, target.positionOf("constructor", "<init>", 2)), "<init> methods should not be renameable")
            assertNull(provider.prepareRename(callerUri, caller.positionOf("invoke-direct", "<init>", 2)))
            assertNull(provider.prepareRename(targetUri, target.positionOf(".method public doWork", "public")))
        }

        @Test
        fun `returns the name range of a method, field and label`() {
            openTargetAndCaller()
            val method = provider.prepareRename(targetUri, target.positionOf(".method public doWork", "doWork", 3))
            assertNotNull(method, "Method should be renameable")
            assertEquals("doWork", method.right.placeholder)
            assertEquals(Range(target.positionOf(".method public doWork", "doWork"), target.positionOf(".method public doWork", "doWork", 6)), method.right.range)

            val field = provider.prepareRename(targetUri, target.positionOf("count:I", "count", 2))
            assertEquals("count", field!!.right.placeholder)

            val source = ".class public Lcom/example/Test;\n.super Ljava/lang/Object;\n\n.method public test()V\n    :start\n    return-void\n.end method"
            index.openSource("file:///Test.smali", source)
            val label = provider.prepareRename("file:///Test.smali", source.positionOf(":start", ":start", 3))
            assertEquals("start", label!!.right.placeholder)
            assertEquals(Range(Position(4, 5), Position(4, 10)), label.right.range, "the colon stays")
        }

        @Test
        fun `members of classes outside the workspace are not renameable`() {
            openTargetAndCaller()
            assertNull(provider.prepareRename(callerUri, caller.positionOf("length", "length", 2)), "String.length is not in the workspace")
        }
    }

    @Nested
    @DisplayName("Method rename")
    inner class MethodRenameTests {

        @Test
        fun `renames the declaration and the call site at their name tokens`() {
            openTargetAndCaller()
            val edit = provider.rename(targetUri, target.positionOf(".method public doWork", "doWork", 2), "execute")
            assertEquals(listOf(edit(target, ".method public doWork", "doWork", 0, "execute")), edits(edit, targetUri))
            assertEquals(listOf(edit(caller, "doWork(I)V", "doWork", 0, "execute")), edits(edit, callerUri))
        }

        @Test
        fun `rename from the call site reaches the declaration`() {
            openTargetAndCaller()
            val edit = provider.rename(callerUri, caller.positionOf("doWork(I)V", "doWork", 4), "execute")
            assertEquals(listOf(edit(target, ".method public doWork", "doWork", 0, "execute")), edits(edit, targetUri))
            assertEquals(listOf(edit(caller, "doWork(I)V", "doWork", 0, "execute")), edits(edit, callerUri))
        }

        @Test
        fun `polymorphic rename cascades to subclass overrides and their call sites`() {
            val base = ".class public Lcom/example/Base;\n.super Ljava/lang/Object;\n\n.method public run()V\n    return-void\n.end method"
            val child = ".class public Lcom/example/Child;\n.super Lcom/example/Base;\n\n.method public run()V\n    return-void\n.end method"
            val calls = ".class public Lcom/example/Calls;\n.super Ljava/lang/Object;\n\n.method public test()V\n    .registers 2\n    invoke-virtual {v0}, Lcom/example/Base;->run()V\n    invoke-virtual {v0}, Lcom/example/Child;->run()V\n    return-void\n.end method"
            index.openSource("file:///Base.smali", base)
            index.openSource("file:///Child.smali", child)
            index.openSource("file:///Calls.smali", calls)

            val edit = provider.rename("file:///Base.smali", base.positionOf(".method", "run", 1), "execute")
            assertEquals(listOf(edit(base, ".method", "run", 0, "execute")), edits(edit, "file:///Base.smali"))
            assertEquals(listOf(edit(child, ".method", "run", 0, "execute")), edits(edit, "file:///Child.smali"))
            assertEquals(
                listOf(edit(calls, "Lcom/example/Base;->run", "run", 0, "execute", after = "->"), edit(calls, "Lcom/example/Child;->run", "run", 0, "execute", after = "->")),
                edits(edit, "file:///Calls.smali")
            )
        }

        @Test
        fun `constructors and members of classes outside the workspace are not renamed`() {
            openTargetAndCaller()
            assertNull(provider.rename(callerUri, caller.positionOf("invoke-direct", "<init>", 2), "create"))
            assertNull(provider.rename(callerUri, caller.positionOf("length", "length", 2), "size"))
        }
    }

    @Nested
    @DisplayName("Field rename")
    inner class FieldRenameTests {

        @Test
        fun `renames the declaration and every access site`() {
            openTargetAndCaller()
            val edit = provider.rename(targetUri, target.positionOf("name:", "name", 2), "displayName")
            assertEquals(listOf(edit(target, "name:", "name", 0, "displayName")), edits(edit, targetUri))
            assertEquals(listOf(edit(caller, "iget-object", "name", 0, "displayName", after = "->")), edits(edit, callerUri))
        }

        @Test
        fun `rename from an access site`() {
            openTargetAndCaller()
            val edit = provider.rename(callerUri, caller.positionOf("iget v2", "count", 1, after = "->"), "total")
            assertEquals(listOf(edit(target, "count:I", "count", 0, "total")), edits(edit, targetUri))
            assertEquals(listOf(edit(caller, "iget v2", "count", 0, "total", after = "->")), edits(edit, callerUri))
        }
    }

    @Nested
    @DisplayName("Label rename")
    inner class LabelRenameTests {

        private val uri = "file:///Test.smali"
        private val source = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;

            .method public test(I)V
                .registers 2
                if-eqz p1, :skip
                :try_start
                invoke-virtual {p0}, Lcom/example/Test;->other()V
                :try_end
                .catch Ljava/lang/Exception; {:try_start .. :try_end} :skip
                packed-switch p1, :table
                :skip
                return-void
                :table
                .packed-switch 0x0
                    :skip
                    :try_start
                .end packed-switch
            .end method

            .method public other()V
                .registers 1
                :skip
                goto :skip
            .end method
        """.trimIndent()

        @BeforeEach
        fun open() { index.openSource(uri, source) }

        @Test
        fun `renames the definition and every reference, catch and switch table included`() {
            val edit = provider.rename(uri, source.positionOf("    :skip", ":skip", 2), "done")
            val expected = listOf(
                edit(source, "if-eqz", "skip", 0, "done"),
                edit(source, ".catch", "skip", 0, "done"),
                edit(source, "    :skip", "skip", 0, "done"),
                edit(source, "        :skip", "skip", 0, "done"),
            ).sortedWith(compareBy({ it[0] as Int }, { it[1] as Int }))
            assertEquals(expected, edits(edit, uri))
            assertTrue(edits(edit, uri).none { (it[0] as Int) > source.lines().indexOf(".end method") }, "the other method's :skip is untouched")
        }

        @Test
        fun `rename from a reference in a jump, a catch block or a switch table`() {
            val fromJump = provider.rename(uri, source.positionOf("if-eqz", ":skip", 3), "done")
            assertEquals(4, edits(fromJump, uri).size)
            val fromCatch = provider.rename(uri, source.positionOf(".catch", ":try_end", 4), "try_stop")
            assertEquals(listOf(edit(source, ".catch", "try_end", 0, "try_stop"), edit(source, "    :try_end", "try_end", 0, "try_stop")).sortedBy { it[0] as Int }, edits(fromCatch, uri))
            val fromTable = provider.rename(uri, source.positionOf("        :try_start", ":try_start", 4), "begin")
            assertEquals(3, edits(fromTable, uri).size, "definition, .catch reference and switch entry")
        }

        @Test
        fun `label names repeat across methods and stay separate`() {
            val otherMethod = source.lines().indexOf(".method public other()V")
            val edit = provider.rename(uri, Position(otherMethod + 3, 10), "loop")
            assertEquals(listOf(listOf(otherMethod + 2, 5, 9, "loop"), listOf(otherMethod + 3, 10, 14, "loop")), edits(edit, uri))
        }
    }

    @Nested
    @DisplayName("Edge cases")
    inner class EdgeCaseTests {

        private val uri = "file:///Test.smali"
        private val source = ".class public Lcom/example/Test;\n.super Ljava/lang/Object;\n\n.field public myField:I\n\n.method public test()V\n    :myLabel\n    goto :myLabel\n    return-void\n.end method"
        private val onMethod = Position(5, 16)
        private val onField = Position(3, 16)
        private val onLabel = Position(6, 6)

        @BeforeEach
        fun open() { index.openSource(uri, source) }

        @Test
        fun `a blank name is an error and an unknown file nothing to rename`() {
            assertEquals("A name is required", assertThrows<ResponseErrorException> { provider.rename(uri, onMethod, "  ") }.responseError.message)
            assertNull(provider.rename("file:///nonexistent.smali", Position(0, 0), "foo"))
            assertNull(provider.prepareRename("file:///nonexistent.smali", Position(0, 0)))
        }

        @Test
        fun `a name the assembler would not read is an error naming it`() {
            for (bad in listOf("bad name", "bad/name", "bad(name", "<init>", "ends-")) assertEquals("'$bad' is not a smali member name", assertThrows<ResponseErrorException> { provider.rename(uri, onMethod, bad) }.responseError.message)
            for (bad in listOf("bad name", "bad;name")) assertThrows<ResponseErrorException>(bad) { provider.rename(uri, onField, bad) }
            for (bad in listOf("bad name", "-leading", "bad:name")) assertEquals("'$bad' is not a smali label name", assertThrows<ResponseErrorException> { provider.rename(uri, onLabel, bad) }.responseError.message)
        }

        @Test
        fun `what the lexer reads as a name is accepted, a leading digit and non-ASCII included`() {
            for (good in listOf("newName", "_private", "\$synthetic", "name-with-hyphens", "123start", "-lambda", "名前")) assertNotNull(provider.rename(uri, onMethod, good), good)
            for (good in listOf("loop_0", "a-b", "0")) assertNotNull(provider.rename(uri, onLabel, good), good)
        }
    }

    @Nested
    @DisplayName("Name validation")
    inner class NameValidationTests {

        @Test
        fun `member names are what the parser reads as one`() {
            for (name in listOf("foo", "_bar", "\$baz", "a1", "my-method", "access\$000", "1start", "ünïcode", "-\$\$Lambda\$0", "public", "move-result")) assertTrue(RenameProvider.isValidMemberName(name), name)
            for (name in listOf("", "has space", "has/slash", "has(paren", "has;semi", "a->b", "ends-", "<clinit>", "\"quoted\"", "a#b")) assertTrue(!RenameProvider.isValidMemberName(name), name)
        }

        @Test
        fun `label names are what the parser reads after a colon`() {
            for (name in listOf("try_start_0", "catch_0", "_label", "a123", "1start", "has-hyphen", "has\$dollar")) assertTrue(RenameProvider.isValidLabelName(name), name)
            for (name in listOf("", "has space", "-leading", "a:b", "a..b")) assertTrue(!RenameProvider.isValidLabelName(name), name)
        }
    }
}
