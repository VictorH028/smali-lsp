package xyz.surendrajat.smalilsp.unit.parser

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import xyz.surendrajat.smalilsp.parser.SmaliParser

/**
 * baksmali emits typed integer literals for byte and short values: `0x1t`, `0x2s`
 * (and `l`/`L` for long). The lexer only knew the long suffix, so `0x1t` lexed as
 * `0x1` followed by an identifier `t`, which ended the `.field` directive early.
 */
class IntegerLiteralSuffixTest {

    private val parser = SmaliParser()

    private fun classWith(field: String) = """
        .class public final Lcom/example/Consts;
        .super Ljava/lang/Object;

        $field

        .method public static one()I
            .registers 1
            const/4 v0, 0x1
            return v0
        .end method

        .method public static two()I
            .registers 1
            const/4 v0, 0x2
            return v0
        .end method
    """.trimIndent()

    @ParameterizedTest
    @ValueSource(strings = [
        ".field public static final B1:B = 0x1t",
        ".field public static final B2:B = 0x0T",
        ".field public static final B3:B = -0x1t",
        ".field public static final B4:B = 127t",
        ".field public static final S1:S = 0x2s",
        ".field public static final S2:S = -0x8000S",
        ".field public static final S3:S = 42s",
        ".field public static final J1:J = 0x1L",
        ".field public static final J2:J = 0x7fffffffffffffffl",
        ".field public static final O1:B = 0_17t",
        ".field public static final N1:B = 0b1010t",
    ])
    fun `typed integer literal suffixes parse and keep the rest of the class`(field: String) {
        val result = parser.parseWithErrors("file:///Consts.smali", classWith(field))

        assertEquals(emptyList<SmaliParser.SyntaxError>(), result.syntaxErrors, "unexpected syntax errors for: $field")
        val file = result.smaliFile!!
        assertEquals(1, file.fields.size, "field should be captured for: $field")
        assertEquals(listOf("one", "two"), file.methods.map { it.name }, "methods after the field must survive: $field")
    }

    @Test
    fun `array-data entries with byte and short suffixes parse`() {
        val content = """
            .class public final Lcom/example/Arr;
            .super Ljava/lang/Object;

            .method public static bytes()[B
                .registers 2
                const/4 v0, 0x3
                new-array v0, v0, [B
                fill-array-data v0, :array_0
                return-object v0

                :array_0
                .array-data 1
                    0x1t
                    -0x2t
                    0x7ft
                .end array-data
            .end method

            .method public static shorts()[S
                .registers 2
                const/4 v0, 0x2
                new-array v0, v0, [S
                fill-array-data v0, :array_1
                return-object v0

                :array_1
                .array-data 2
                    0x1s
                    -0x8000s
                .end array-data
            .end method

            .method public static after()V
                .registers 0
                return-void
            .end method
        """.trimIndent()

        val result = parser.parseWithErrors("file:///Arr.smali", content)

        assertEquals(emptyList<SmaliParser.SyntaxError>(), result.syntaxErrors)
        assertEquals(listOf("bytes", "shorts", "after"), result.smaliFile!!.methods.map { it.name })
    }
}
