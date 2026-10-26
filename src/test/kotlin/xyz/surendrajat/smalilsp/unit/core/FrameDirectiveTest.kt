package xyz.surendrajat.smalilsp.unit.core

import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.core.RegisterFrame
import xyz.surendrajat.smalilsp.parser.SmaliParser
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The one reader of `.registers`/`.locals`: indentation, tabs, a trailing comment and a hex count are all the assembler's, and nothing else passes for the directive. */
class FrameDirectiveTest {

    @Test
    fun `the directive is read with a tab, a comment or a hex count, and nothing else passes for it`() {
        val tab = assertNotNull(RegisterFrame.frameDirective("\t.registers\t4\t# frame"))
        assertEquals(listOf(".registers", 1, 12, 13, 4), listOf(tab.name, tab.start, tab.countStart, tab.countEnd, tab.count))
        val hex = assertNotNull(RegisterFrame.frameDirective("    .locals 0x10"))
        assertEquals(".locals" to 16, hex.name to hex.count)
        assertNull(assertNotNull(RegisterFrame.frameDirective("    .registers")).count, "no count")
        assertNull(assertNotNull(RegisterFrame.frameDirective("    .locals abc")).count, "not a number")
        assertNull(RegisterFrame.frameDirective("    .localsx 3"))
        assertNull(RegisterFrame.frameDirective("    .line 4"))
        assertNull(RegisterFrame.frameDirective("    move v0, v1"))
    }

    @Test
    fun `the frame of a hand-written method comes through the same reader`() {
        val source = ".class Lx;\n.method public run(J)V\n\t.locals\t2 # two locals\n\treturn-void\n.end method\n"
        val method = assertNotNull(SmaliParser().parse("file:///x.smali", source)).methods.single()
        val frame = assertNotNull(RegisterFrame.of(method, source.lines()))
        assertEquals(listOf(5, 3, ".locals"), listOf(frame.total, frame.parameters, frame.directive), "2 locals + this + a wide parameter")
    }
}
