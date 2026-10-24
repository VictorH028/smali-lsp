package xyz.surendrajat.smalilsp.unit.index

import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.MemberKey
import xyz.surendrajat.smalilsp.index.UsageList
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.shared.openSource
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Usage lists keep every site through growth and trimming; member keys equal by content. */
class UsageTrimTest {

    @Test
    fun `trim keeps every entry and later adds still work`() {
        val list = UsageList()
        for (i in 0 until 37) list.add(i % 3, i, i * 2, i * 2 + 1)
        list.trim()
        assertEquals(37 * UsageList.STRIDE, list.snapshot().size)
        list.add(9, 99, 0, 3)
        val s = list.snapshot()
        assertEquals(38 * UsageList.STRIDE, s.size)
        assertEquals(listOf(9, 99, 0, 3), s.takeLast(4))
        assertEquals(listOf(0, 0, 0, 1), s.take(4))
        val empty = UsageList()
        empty.trim()
        assertEquals(0, empty.snapshot().size)
        empty.add(1, 1, 1, 2)
        assertEquals(4, empty.snapshot().size)
    }

    @Test
    fun `compact after trim drops the sites of retired file ids`() {
        val list = UsageList()
        list.add(0, 1, 0, 1)
        list.add(1, 2, 0, 1)
        list.add(2, 3, 0, 1)
        list.add(7, 4, 0, 1)
        list.trim()
        val alive = AtomicIntegerArray(intArrayOf(0, 1, 1))
        assertEquals(2, list.compact(alive), "file 0 was retired; file 7 is beyond the array, so it is not alive either")
        assertEquals(listOf(1, 2, 0, 1, 2, 3, 0, 1), list.snapshot().toList())
    }

    @Test
    fun `member keys equal by content whatever strings carry them`() {
        val a = MemberKey("Lcom/A;", "run", "(I)V")
        val b = MemberKey(String("Lcom/A;".toCharArray()), String("run".toCharArray()), String("(I)V".toCharArray()))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, MemberKey("Lcom/A;", "run", "(J)V"))
        assertNotEquals(a, MemberKey("Lcom/B;", "run", "(I)V"))
        assertEquals("Lcom/A;->run(I)V", a.toString())
    }

    @Test
    fun `trimming the index changes no answer`() {
        val index = WorkspaceIndex()
        index.openSource("file:///A.smali", ".class public Lcom/A;\n.super Ljava/lang/Object;\n.field private count:I\n.method public run()V\n    .registers 2\n    iget v0, p0, Lcom/A;->count:I\n    invoke-virtual {p0}, Lcom/A;->other()V\n    const-string v0, \"hello\"\n    return-void\n.end method\n.method public other()V\n    .registers 1\n    return-void\n.end method\n")
        index.openSource("file:///B.smali", ".class public Lcom/B;\n.super Lcom/A;\n.method public go()V\n    .registers 2\n    invoke-virtual {p0}, Lcom/A;->other()V\n    new-instance v0, Lcom/A;\n    return-void\n.end method\n")
        val before = Triple(index.findMethodUsages("Lcom/A;", "other", "()V"), index.findFieldUsages("Lcom/A;", "count", "I"), index.findClassRefLocations("Lcom/A;"))
        index.trimUsages()
        val after = Triple(index.findMethodUsages("Lcom/A;", "other", "()V"), index.findFieldUsages("Lcom/A;", "count", "I"), index.findClassRefLocations("Lcom/A;"))
        assertEquals(before, after)
        assertEquals(2, after.first.size)
        assertEquals(1, after.second.size)
        assertTrue(after.third.size >= 4, "owner of the field access, two invoke owners, the new-instance operand and the .super: ${after.third.size}")
    }
}
