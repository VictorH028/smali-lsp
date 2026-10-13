package xyz.surendrajat.smalilsp.unit.parser

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.parser.SmaliParser

/**
 * `synchronized` (ACC_SYNCHRONIZED, which baksmali emits for native methods) is a method
 * modifier distinct from `declared-synchronized`; only the latter was a token.
 */
class SynchronizedModifierTest {

    @Test
    fun `synchronized native method modifier parses`() {
        val content = """
            .class public final Lcom/sun/jna/Native;
            .super Ljava/lang/Object;

            .method public static synchronized native createNativeCallback(Lcom/sun/jna/Callback;)J
            .end method

            .method public declared-synchronized lock()V
                .registers 1
                monitor-enter p0
                monitor-exit p0
                return-void
            .end method
        """.trimIndent()

        val result = SmaliParser().parseWithErrors("file:///Native.smali", content)

        assertEquals(emptyList<SmaliParser.SyntaxError>(), result.syntaxErrors)
        val file = result.smaliFile!!
        assertEquals(listOf("createNativeCallback", "lock"), file.methods.map { it.name })
        assertEquals(setOf("public", "static", "synchronized", "native"), file.methods[0].modifiers)
        assertEquals(setOf("public", "declared-synchronized"), file.methods[1].modifiers)
    }
}
