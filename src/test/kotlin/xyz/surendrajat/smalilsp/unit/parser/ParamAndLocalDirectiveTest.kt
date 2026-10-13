package xyz.surendrajat.smalilsp.unit.parser

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.parser.SmaliParser

/**
 * Debug-info directives as baksmali writes them with `--debug-info` / unstripped builds:
 * a named `.param` may carry annotations and end with `.end param`, and `.local` may omit
 * the name part or use `null` for it. Release builds in the corpus never exercise these.
 */
class ParamAndLocalDirectiveTest {

    private val parser = SmaliParser()

    private fun assertParses(content: String, vararg methods: String) {
        val result = parser.parseWithErrors("file:///D.smali", content.trimIndent())
        assertEquals(emptyList<SmaliParser.SyntaxError>(), result.syntaxErrors, "unexpected syntax errors")
        assertEquals(methods.toList(), result.smaliFile!!.methods.map { it.name })
    }

    @Test
    fun `named param with annotations and end param`() {
        assertParses("""
            .class public final Lcom/example/P;
            .super Ljava/lang/Object;

            .method public set(Ljava/lang/String;I)V
                .registers 3
                .param p1, "name"    # Ljava/lang/String;
                    .annotation build Lorg/jetbrains/annotations/NotNull;
                    .end annotation
                .end param
                .param p2, "count"    # I
                return-void
            .end method

            .method public after()V
                .registers 0
                return-void
            .end method
        """, "set", "after")
    }

    @Test
    fun `bare param followed by a method-level annotation`() {
        // No `.end param` here: the annotation belongs to the method, not the parameter.
        assertParses("""
            .class public final Lcom/example/Q;
            .super Ljava/lang/Object;

            .method public set(Ljava/lang/String;)V
                .registers 2
                .param p1    # Ljava/lang/String;
                .annotation system Ldalvik/annotation/Throws;
                    value = {
                        Ljava/io/IOException;
                    }
                .end annotation
                return-void
            .end method
        """, "set")
    }

    @Test
    fun `local directive variants`() {
        assertParses("""
            .class public final Lcom/example/R;
            .super Ljava/lang/Object;

            .method public static run()V
                .registers 3
                const/4 v0, 0x0
                .local v0, "i":I
                .local v1, "list":Ljava/util/List;, "Ljava/util/List<Ljava/lang/String;>;"
                .local v2, null:Ljava/lang/Object;
                .end local v2
                .restart local v2
                .local v2
                return-void
            .end method
        """, "run")
    }
}
