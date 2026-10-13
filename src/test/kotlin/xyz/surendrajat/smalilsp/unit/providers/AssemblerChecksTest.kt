package xyz.surendrajat.smalilsp.unit.providers

import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.AssemblerChecks
import xyz.surendrajat.smalilsp.shared.text
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The checks an assembler would fail a hand-edited method on, each reported on its token. */
class AssemblerChecksTest {

    private fun check(body: String, header: String = ".method public run(ILjava/lang/String;J)V"): List<Triple<String, Int, String>> {
        val source = ".class public Lcom/Edit;\n.super Ljava/lang/Object;\n\n$header\n$body\n.end method\n"
        val file = assertNotNull(SmaliParser().parse("file:///Edit.smali", source))
        return AssemblerChecks.diagnostics(file, source.lines()).map { Triple(it.code.left, it.range.start.line - 4, it.text) }
    }

    @Test
    fun `baksmali-style code passes`() {
        val body = """
            |    .registers 7
            |    .param p1, "count"    # I
            |    const/4 v0, 0x0
            |    const/16 v1, 0x100
            |    const/high16 v2, 0x7f0a0000
            |    const-wide/high16 v2, -0x3ff0000000000000L
            |    add-int/lit8 v0, v0, 0x1
            |    invoke-virtual {p0, p1, p2}, Lcom/Edit;->other(ILjava/lang/String;)V
            |    move-result-object v1
            |    filled-new-array {v0, v1}, [I
            |    move-result-object v1
            |    if-eqz v0, :cond_0
            |    :cond_0
            |    return-void""".trimMargin()
        assertEquals(emptyList<Triple<String, Int, String>>(), check(body))
    }

    @Test
    fun `a register the method does not declare`() {
        val body = "    .registers 5\n    const/4 v5, 0x0\n    move-object v0, p5\n    move-wide v0, p3\n    return-void"
        assertEquals(listOf(
            Triple("register-out-of-range", 1, "Register v5 is beyond the 5 registers (.registers 5) this method declares"),
            Triple("register-out-of-range", 2, "Register p5 is beyond the 5 parameter register(s) this method declares")
        ), check(body), "this, int, String and the two halves of the long are p0..p4")
        assertEquals(listOf("register-out-of-range"), check("    .locals 1\n    const/4 v6, 0x0\n    return-void").map { it.first }, ".locals 1 plus five parameter slots: v0..v5")
    }

    @Test
    fun `registers too wide for the instruction form, parameters included`() {
        val body = "    .registers 20\n    move v16, v1\n    move v0, p1\n    invoke-virtual {p0, p1, p2, p3}, Lcom/Edit;->x(ILjava/lang/String;J)V\n    invoke-virtual/range {p0 .. p3}, Lcom/Edit;->x(ILjava/lang/String;J)V\n    return-void"
        assertEquals(listOf(
            Triple("register-width", 1, "v16 does not fit move, whose operand addresses v0..v15; use the move/from16 form"),
            Triple("register-width", 2, "p1 (v16) does not fit move, whose operand addresses v0..v15; use the move/from16 form"),
            Triple("register-width", 3, "p1 (v16) does not fit invoke-virtual, whose operand addresses v0..v15; use the invoke-virtual/range form"),
            Triple("register-width", 3, "p2 (v17) does not fit invoke-virtual, whose operand addresses v0..v15; use the invoke-virtual/range form"),
            Triple("register-width", 3, "p3 (v18) does not fit invoke-virtual, whose operand addresses v0..v15; use the invoke-virtual/range form")
        ), check(body), "p0 is v15 and still fits; the /range form on the next line is fine")
        assertEquals(listOf("register-width"), check("    .registers 8\n    invoke-static {v0, v1, v2, v3, v4, v5}, Lcom/Edit;->s(IIIIII)V\n    return-void", ".method public static run()V").map { it.first })
    }

    @Test
    fun `literals that do not fit`() {
        val body = "    .registers 4\n    const/4 v0, 0x10\n    const/4 v0, -0x9\n    const/16 v0, 0x8000\n    const/high16 v0, 0x7f0a0012\n    add-int/lit8 v0, v0, 0x80\n    add-int/lit16 v0, v0, -0x8001\n    const-wide/high16 v0, 0x1L\n    const/4 v0, -0x8\n    const/high16 v0, -0x10000\n    const v0, 0x7fffffff\n    return-void"
        assertEquals(listOf(
            Triple("literal-out-of-range", 1, "Literal 0x10 does not fit const/4, which fits -8..7; use const/16"),
            Triple("literal-out-of-range", 2, "Literal -0x9 does not fit const/4, which fits -8..7; use const/16"),
            Triple("literal-out-of-range", 3, "Literal 0x8000 does not fit const/16, which fits -32768..32767; use const"),
            Triple("literal-out-of-range", 4, "Literal 0x7f0a0012 does not fit const/high16, which holds a 16-bit value shifted into the top of 32 bits; use const"),
            Triple("literal-out-of-range", 5, "Literal 0x80 does not fit add-int/lit8, which fits -128..127; use add-int/lit16"),
            Triple("literal-out-of-range", 6, "Literal -0x8001 does not fit add-int/lit16, which fits -32768..32767; use a register operand"),
            Triple("literal-out-of-range", 7, "Literal 0x1L does not fit const-wide/high16, which holds a 16-bit value shifted into the top of 64 bits; use const-wide/32 or const-wide")
        ), check(body), "the last three lines are at the edges and fine")
    }

    @Test
    fun `move-result must follow a call`() {
        val body = "    .registers 3\n    move-result v0\n    invoke-virtual {p0}, Lcom/Edit;->x()I\n    .line 12\n\n    move-result v0\n    const/4 v1, 0x0\n    move-result v0\n    return-void"
        assertEquals(listOf(
            Triple("move-result-placement", 1, "move-result must directly follow an invoke or filled-new-array"),
            Triple("move-result-placement", 7, "move-result must directly follow an invoke or filled-new-array")
        ), check(body))
    }

    @Test
    fun `without a registers directive only literal and placement checks apply`() {
        assertEquals(listOf("literal-out-of-range"), check("    const/4 v9, 0x10\n    return-void").map { it.first })
    }
}
