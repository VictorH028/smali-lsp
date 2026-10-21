package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.FoldingRangeKind
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.FoldingRangeProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Foldable regions: multi-line methods, annotations, switch tables and array data. */
class FoldingRangeProviderTest {

    @Test
    fun `methods and directive blocks fold, one-line members do not`() {
        val source = """
            .class public Lcom/Folds;
            .super Ljava/lang/Object;

            .annotation system Ldalvik/annotation/MemberClasses;
                value = {
                    Lcom/Folds${'$'}Inner;
                }
            .end annotation

            .field public count:I

            .method public abstract tiny()V
            .end method

            .method public run(I)V
                .registers 2
                packed-switch p1, :pswitch_data_0
                fill-array-data p1, :array_0
                return-void
                :pswitch_data_0
                .packed-switch 0x0
                    :pswitch_0
                .end packed-switch
                :array_0
                .array-data 4
                    0x1
                .end array-data
                :pswitch_0
                return-void
            .end method
        """.trimIndent()
        val file = assertNotNull(SmaliParser().parse("file:///Folds.smali", source))
        val lines = source.lines()
        fun lineOf(text: String, from: Int = 0) = lines.withIndex().first { it.index >= from && it.value.trim().startsWith(text) }.index
        val ranges = FoldingRangeProvider().foldingRanges(file).map { it.startLine to it.endLine }
        val run = lineOf(".method public run")
        assertEquals(listOf(
            lineOf(".annotation") to lineOf(".end annotation"),
            lineOf(".method public abstract tiny") to lineOf(".end method"),
            run to lineOf(".end method", run),
            lineOf(".packed-switch") to lineOf(".end packed-switch"),
            lineOf(".array-data") to lineOf(".end array-data")
        ), ranges, "the one-line field does not fold; the two-line abstract method does")
        assertTrue(FoldingRangeProvider().foldingRanges(file).all { it.kind == FoldingRangeKind.Region })
    }
}
