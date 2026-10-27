package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.SemanticTokensProvider
import xyz.surendrajat.smalilsp.providers.SemanticTokensProvider.Companion.CLASS
import xyz.surendrajat.smalilsp.providers.SemanticTokensProvider.Companion.COMMENT
import xyz.surendrajat.smalilsp.providers.SemanticTokensProvider.Companion.DECLARATION
import xyz.surendrajat.smalilsp.providers.SemanticTokensProvider.Companion.DEFAULT_LIBRARY
import xyz.surendrajat.smalilsp.providers.SemanticTokensProvider.Companion.ENUM_MEMBER
import xyz.surendrajat.smalilsp.providers.SemanticTokensProvider.Companion.KEYWORD
import xyz.surendrajat.smalilsp.providers.SemanticTokensProvider.Companion.LABEL
import xyz.surendrajat.smalilsp.providers.SemanticTokensProvider.Companion.METHOD
import xyz.surendrajat.smalilsp.providers.SemanticTokensProvider.Companion.MODIFIER
import xyz.surendrajat.smalilsp.providers.SemanticTokensProvider.Companion.NUMBER
import xyz.surendrajat.smalilsp.providers.SemanticTokensProvider.Companion.PARAMETER
import xyz.surendrajat.smalilsp.providers.SemanticTokensProvider.Companion.PROPERTY
import xyz.surendrajat.smalilsp.providers.SemanticTokensProvider.Companion.STATIC
import xyz.surendrajat.smalilsp.providers.SemanticTokensProvider.Companion.STRING
import xyz.surendrajat.smalilsp.providers.SemanticTokensProvider.Companion.TYPE
import xyz.surendrajat.smalilsp.providers.SemanticTokensProvider.Companion.VARIABLE
import xyz.surendrajat.smalilsp.shared.openSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Semantic tokens: every token of a file classified, encoded as the protocol asks, with deltas and ranges. */
class SemanticTokensTest {

    private val index = WorkspaceIndex()
    private val provider = SemanticTokensProvider(index)
    private val uri = "file:///Tok.smali"
    private val source = """
        .class public final Lcom/app/Tok;
        .super Ljava/lang/Object;
        .source "Tok.java"

        # static fields
        .field private static count:I = 0x2a

        .field public name:Ljava/lang/String;

        .annotation system Ldalvik/annotation/MemberClasses;
            value = {
                Lcom/app/Tok${'$'}Inner;
            }
        .end annotation

        .method public static run(Ljava/lang/String;I)V
            .registers 4
            .param p0, "text"    # Ljava/lang/String;
            .local v1, "name":Ljava/lang/String;
            const-string v0, "hi # not a comment"
            const/16 v1, 0x3e8   # a thousand
            if-eqz p1, :cond_0
            invoke-static {v0}, Lcom/app/Tok;->helper(Ljava/lang/String;)V
            invoke-virtual {p0}, Ljava/lang/String;->length()I
            sget v1, Lcom/app/Tok;->count:I
            :cond_0
            return-void
        .end method

        .method private static helper(Ljava/lang/String;)V
            .registers 1
            return-void
        .end method
    """.trimIndent()
    private val lines = source.lines()

    init { index.openSource(uri, source) }

    private fun at(marker: String, needle: String, occurrence: Int = 0): SemanticTokensProvider.Token {
        val line = lines.indexOfFirst { it.contains(marker) }
        var col = -1
        repeat(occurrence + 1) { col = lines[line].indexOf(needle, col + 1) }
        val tokens = assertNotNull(provider.collect(uri))
        return assertNotNull(tokens.firstOrNull { it.line == line && it.col == col }, "no token at $line:$col for `$needle` in `${lines[line].trim()}`: ${tokens.filter { it.line == line }.map { "${it.col}+${it.length}:${it.type}" }}")
    }

    private fun assertToken(marker: String, needle: String, type: Int, modifiers: Int = 0, occurrence: Int = 0) {
        val t = at(marker, needle, occurrence)
        assertEquals(needle.length to type, t.length to t.type, "`$needle` on `$marker`")
        assertEquals(modifiers, t.modifiers, "modifiers of `$needle`")
    }

    @Test
    fun `declarations, members, types, labels, literals and comments are classified`() {
        assertToken(".class", ".class", KEYWORD); assertToken(".class", "public", MODIFIER); assertToken(".class", "final", MODIFIER)
        assertToken(".class", "Lcom/app/Tok;", CLASS, DECLARATION)
        assertToken(".super", "Ljava/lang/Object;", TYPE, DEFAULT_LIBRARY)
        assertToken(".source", "\"Tok.java\"", STRING)
        assertToken("# static fields", "# static fields", COMMENT)
        assertToken("count:I", "count", PROPERTY, DECLARATION or STATIC); assertToken("count:I", "I", TYPE); assertToken("count:I", "0x2a", NUMBER)
        assertToken("name:Ljava", "name", PROPERTY, DECLARATION); assertToken("name:Ljava", "Ljava/lang/String;", TYPE, DEFAULT_LIBRARY)
        assertToken(".annotation system", ".annotation", KEYWORD); assertToken(".annotation system", "system", MODIFIER); assertToken(".annotation system", "Ldalvik/annotation/MemberClasses;", TYPE, DEFAULT_LIBRARY)
        assertToken("value = {", "value", PROPERTY)
        assertToken("Lcom/app/Tok\$Inner;", "Lcom/app/Tok\$Inner;", TYPE)
        assertToken(".end annotation", ".end annotation", KEYWORD)
        assertToken(".method public static run", "run", METHOD, DECLARATION or STATIC)
        assertToken(".method public static run", "Ljava/lang/String;", TYPE, DEFAULT_LIBRARY); assertToken(".method public static run", "I", TYPE, 0, 0); assertToken(".method public static run", "V", TYPE)
        assertToken(".registers 4", ".registers", KEYWORD); assertToken(".registers 4", "4", NUMBER)
        assertToken(".param p0", "p0", PARAMETER); assertToken(".param p0", "\"text\"", STRING); assertToken(".param p0", "# Ljava/lang/String;", COMMENT)
        assertToken(".local v1", ".local", KEYWORD); assertToken(".local v1", "v1", VARIABLE); assertToken(".local v1", "\"name\"", STRING); assertToken(".local v1", "Ljava/lang/String;", TYPE, DEFAULT_LIBRARY)
        assertToken("const-string v0", "const-string", KEYWORD); assertToken("const-string v0", "v0", VARIABLE); assertToken("const-string v0", "\"hi # not a comment\"", STRING)
        assertToken("const/16 v1", "0x3e8", NUMBER); assertToken("const/16 v1", "# a thousand", COMMENT)
        assertToken("if-eqz p1", "p1", PARAMETER); assertToken("if-eqz p1", ":cond_0", LABEL)
        assertToken("invoke-static {v0}", "Lcom/app/Tok;", TYPE); assertToken("invoke-static {v0}", "helper", METHOD, STATIC)
        assertToken("invoke-virtual {p0}", "Ljava/lang/String;", TYPE, DEFAULT_LIBRARY); assertToken("invoke-virtual {p0}", "length", METHOD, DEFAULT_LIBRARY)
        assertToken("sget v1", "count", PROPERTY, STATIC); assertToken("sget v1", "I", TYPE, 0, 0)
        assertToken("    :cond_0", ":cond_0", LABEL, DECLARATION)
        assertToken(".end method", ".end method", KEYWORD)
    }

    @Test
    fun `tokens are in order, never overlap, and encode to the protocol's deltas`() {
        val tokens = assertNotNull(provider.collect(uri))
        var lastLine = -1; var lastEnd = 0
        for (t in tokens) {
            assertTrue(t.line > lastLine || (t.line == lastLine && t.col >= lastEnd), "overlap or disorder at ${t.line}:${t.col}")
            assertTrue(t.col + t.length <= lines[t.line].length, "past the end of line ${t.line}")
            lastLine = t.line; lastEnd = t.col + t.length
        }
        val full = assertNotNull(provider.full(uri))
        assertEquals(tokens.size * 5, full.data.size)
        var line = 0; var col = 0
        for ((i, t) in tokens.withIndex()) {
            val d = full.data.subList(i * 5, i * 5 + 5)
            if (d[0] != 0) { line += d[0]; col = d[1] } else col += d[1]
            assertEquals(Triple(t.line, t.col, t.length), Triple(line, col, d[2]), "token $i decodes to its position")
            assertEquals(t.type to t.modifiers, d[3] to d[4])
        }
    }

    @Test
    fun `a delta covers only the edited tokens and a range only its lines`() {
        val first = assertNotNull(provider.full(uri))
        val same = source.replace("const/16 v1, 0x3e8", "const/16 v1, 0x7d0")
        index.openSource(uri, same)
        val unchanged = assertNotNull(provider.delta(uri, first.resultId)).right
        assertEquals(emptyList(), unchanged.edits, "a literal of the same length and class leaves the token stream as it was")
        val edited = source.replace("const/16 v1, 0x3e8   # a thousand", "const/16 v1, 0x3e8")
        index.openSource(uri, edited)
        val delta = assertNotNull(provider.delta(uri, unchanged.resultId)).right
        assertEquals(1, delta.edits.size, "the comment token is gone: one edit")
        assertEquals(5, delta.edits[0].deleteCount); assertEquals(0, delta.edits[0].data.size)
        val unknown = assertNotNull(provider.delta(uri, "no-such-result")).left
        assertEquals(first.data.size - 5, unknown.data.size, "an unknown result id gets the full tokens, one token fewer than at first")
        val methodLine = lines.indexOfFirst { it.contains(".method public static run") }
        val ranged = assertNotNull(provider.range(uri, Range(Position(methodLine, 0), Position(methodLine + 1, 0))))
        val inRange = assertNotNull(provider.collect(uri)).count { it.line == methodLine || it.line == methodLine + 1 }
        assertEquals(inRange * 5, ranged.data.size)
        assertEquals(methodLine to 0, ranged.data[0] to ranged.data[1], "the first token of a range is `.method`, relative to line 0 as the protocol asks")
    }

    @Test
    fun `a closed file is forgotten and unknown files answer nothing`() {
        provider.full(uri)
        provider.forget(uri)
        assertEquals(null, provider.full("file:///Missing.smali"))
    }
}
