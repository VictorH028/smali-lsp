package xyz.surendrajat.smalilsp.unit.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser

/** The storage model: files keyed by canonical URI, usages retired by file generation, typed field keys. */
class WorkspaceIndexStorageTest {

    private val index = WorkspaceIndex()
    private val parser = SmaliParser()

    private fun source(className: String, body: String = "") = """
        .class public $className
        .super Ljava/lang/Object;

        .method public run()V
            .registers 2
            $body
            return-void
        .end method
    """.trimIndent()

    private fun indexSource(uri: String, text: String) = index.indexFile(parser.parse(uri, text)!!)

    @Test
    fun `re-indexing a file retires its old usages without ghosts`() {
        indexSource("file:///Target.smali", source("Lcom/Target;"))
        indexSource("file:///Caller.smali", source("Lcom/Caller;", "invoke-virtual {p0}, Lcom/Target;->run()V\n    invoke-virtual {p0}, Lcom/Target;->run()V"))
        assertEquals(2, index.findMethodUsages("Lcom/Target;", "run", "()V").size)

        indexSource("file:///Caller.smali", source("Lcom/Caller;", "invoke-virtual {p0}, Lcom/Target;->run()V"))
        val usages = index.findMethodUsages("Lcom/Target;", "run", "()V")
        assertEquals(1, usages.size, "only the current version of the file counts")
        assertEquals(listOf("file:///Caller.smali"), usages.map { it.uri })

        indexSource("file:///Caller.smali", source("Lcom/Caller;"))
        assertTrue(index.findMethodUsages("Lcom/Target;", "run", "()V").isEmpty())
        assertTrue(index.findClassRefLocations("Lcom/Target;").isEmpty())
    }

    @Test
    fun `a file that changes its class name replaces its old class`() {
        indexSource("file:///A.smali", source("Lcom/Old;"))
        assertNotNull(index.findClass("Lcom/Old;"))
        indexSource("file:///A.smali", source("Lcom/New;"))
        assertNull(index.findClass("Lcom/Old;"), "the old class came from the same file and is gone")
        assertNotNull(index.findClass("Lcom/New;"))
        assertEquals(1, index.getStats().classes)
        assertEquals(1, index.getStats().methods)
    }

    @Test
    fun `URI forms map to one entry`() {
        indexSource("file:/tmp/A.smali", source("Lcom/A;"))
        indexSource("file:///tmp/A.smali", source("Lcom/A;", "invoke-virtual {p0}, Lcom/A;->run()V"))
        assertEquals(1, index.getStats().classes)
        assertNotNull(index.findFileByUri("file:/tmp/A.smali"))
        assertNotNull(index.findFileByUri("file:///tmp/A.smali"))
        assertEquals("Lcom/A;", index.findClassNameByUri("file://tmp/A.smali"))
        assertEquals(1, index.findMethodUsages("Lcom/A;", "run", "()V").size, "the first version's usages were retired")
        assertEquals("file:///tmp/A.smali", index.findMethodUsages("Lcom/A;", "run", "()V").single().uri)
        assertTrue(index.removeFile("file:/tmp/A.smali"))
        assertNull(index.findClass("Lcom/A;"))
        assertTrue(index.findMethodUsages("Lcom/A;", "run", "()V").isEmpty())
    }

    @Test
    fun `fields with the same name and different types are distinct`() {
        indexSource("file:///Holder.smali", """
            .class public Lcom/Holder;
            .super Ljava/lang/Object;

            .field public value:I
            .field public value:Ljava/lang/String;
        """.trimIndent())
        indexSource("file:///User.smali", source("Lcom/User;", "iget v0, p0, Lcom/Holder;->value:I\n    iget-object v1, p0, Lcom/Holder;->value:Ljava/lang/String;\n    iget v0, p0, Lcom/Holder;->value:I"))
        assertEquals(3, index.findField("Lcom/Holder;", "value", "I")!!.range.start.line)
        assertEquals(4, index.findField("Lcom/Holder;", "value", "Ljava/lang/String;")!!.range.start.line)
        assertNull(index.findField("Lcom/Holder;", "value", "J"))
        assertEquals(2, index.findFieldUsages("Lcom/Holder;", "value", "I").size)
        assertEquals(1, index.findFieldUsages("Lcom/Holder;", "value", "Ljava/lang/String;").size)
    }

    @Test
    fun `declarations resolve through the hierarchy and point at the name`() {
        indexSource("file:///Base.smali", """
            .class public Lcom/Base;
            .super Ljava/lang/Object;

            .field protected count:I

            .method public greet()V
                .registers 1
                return-void
            .end method
        """.trimIndent())
        indexSource("file:///Child.smali", ".class public Lcom/Child;\n.super Lcom/Base;")
        val greet = index.findMethod("Lcom/Child;", "greet", "()V").single()
        assertEquals("file:///Base.smali", greet.uri)
        assertEquals(5, greet.range.start.line)
        assertEquals(".method public ".length, greet.range.start.character)
        assertEquals(".method public greet".length, greet.range.end.character)
        assertEquals(3, index.findField("Lcom/Child;", "count", "I")!!.range.start.line)
        assertTrue(index.findMethod("Lcom/Child;", "missing", "()V").isEmpty())
        assertEquals(setOf("file:///Child.smali"), index.findClassUsages("Lcom/Base;"))
    }

    @Test
    fun `compaction keeps live usages while dropping retired ones`() {
        val calls = (1..1000).joinToString("\n    ") { "invoke-virtual {p0}, Lcom/Target;->run()V" }
        indexSource("file:///Target.smali", source("Lcom/Target;"))
        indexSource("file:///Keep.smali", source("Lcom/Keep;", "invoke-virtual {p0}, Lcom/Target;->run()V"))
        // 1000 calls + 1000 owner references per version; 120 versions retire well over the 100k-entry threshold
        repeat(120) { indexSource("file:///Churn.smali", source("Lcom/Churn;", calls)) }
        val usages = index.findMethodUsages("Lcom/Target;", "run", "()V")
        assertEquals(1001, usages.size)
        assertEquals(1, usages.count { it.uri == "file:///Keep.smali" })
        assertEquals(1000, usages.count { it.uri == "file:///Churn.smali" })
        index.removeFile("file:///Churn.smali")
        assertEquals(listOf("file:///Keep.smali"), index.findMethodUsages("Lcom/Target;", "run", "()V").map { it.uri })
    }

    @Test
    fun `string literal search follows file changes`() {
        indexSource("file:///S.smali", source("Lcom/S;", "const-string v0, \"first\""))
        assertEquals(listOf("first"), index.searchStrings("fir").map { it.value })
        indexSource("file:///S.smali", source("Lcom/S;", "const-string v0, \"second\""))
        assertTrue(index.searchStrings("fir").isEmpty())
        assertEquals(listOf("second"), index.searchStrings("sec").map { it.value })
        assertEquals(1, index.getStats().strings)
    }
}
