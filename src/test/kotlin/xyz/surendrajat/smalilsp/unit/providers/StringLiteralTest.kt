package xyz.surendrajat.smalilsp.unit.providers

import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** String literals: references are every `const-string` of the same value in the workspace, hover shows the text and the count. */
class StringLiteralTest {

    private val index = WorkspaceIndex()
    private val a = """
        .class public Lcom/A;
        .super Ljava/lang/Object;
        .method public a()V
            .registers 2
            const-string v0, "shared"
            const-string v0, "only `here`"
            return-void
        .end method
    """.trimIndent()
    private val b = """
        .class public Lcom/B;
        .super Ljava/lang/Object;
        .method public b()V
            .registers 2
            const-string/jumbo v0, "shared"
            const-string v0, "shared"
            const-string v0, "shared "
            return-void
        .end method
    """.trimIndent()
    private val references = ReferenceProvider(index)
    private val hovers = HoverProvider(index)

    init {
        index.openSource("file:///A.smali", a)
        index.openSource("file:///B.smali", b)
    }

    @Test
    fun `references of a string are every load of exactly that value`() {
        val found = references.findReferences("file:///A.smali", a.positionOf("\"shared\"", "shared", 2))
        assertEquals(listOf("file:///A.smali:4", "file:///B.smali:4", "file:///B.smali:5"), found.map { "${it.uri}:${it.range.start.line}" }.sorted())
        assertEquals(4, found[0].range.start.character, "the instruction, from its opcode")
        assertEquals(found, references.findReferences("file:///A.smali", a.positionOf("\"shared\"", "shared", 2), includeDeclaration = false), "a string has no declaration")
        assertEquals(listOf("file:///A.smali:5"), references.findReferences("file:///A.smali", a.positionOf("only", "only", 1)).map { "${it.uri}:${it.range.start.line}" })
        assertEquals(emptyList(), references.findReferences("file:///A.smali", a.positionOf("\"shared\"", "const-string", 3)), "the opcode is not the string")
        assertEquals(emptyList(), references.findReferences("file:///A.smali", a.positionOf("\"shared\"", "v0", 1)), "nor is the register")
        assertEquals(emptyList(), DefinitionProvider(index).findDefinition("file:///A.smali", a.positionOf("\"shared\"", "shared", 2)), "a string has nowhere to go")
    }

    @Test
    fun `hover shows the text and how many places load it`() {
        val shared = assertNotNull(hovers.provideHover("file:///A.smali", a.positionOf("\"shared\"", "shared", 2))).contents.right.value
        assertEquals("**String:** `\"shared\"`\n\n**References:** 3", shared)
        val fenced = assertNotNull(hovers.provideHover("file:///A.smali", a.positionOf("only", "only", 1))).contents.right.value
        assertEquals("**String:** ``\"only `here`\"``\n\n**References:** 1", fenced, "a backtick in the value needs a longer fence")
        val register = assertNotNull(hovers.provideHover("file:///A.smali", a.positionOf("\"shared\"", "v0", 1))).contents.right.value
        assertTrue(register.startsWith("**Register** `v0`"), "the register wins on its token: $register")
        val opcode = assertNotNull(hovers.provideHover("file:///A.smali", a.positionOf("\"shared\"", "const-string", 3))).contents.right.value
        assertTrue(opcode.contains("const-string") && !opcode.contains("**String:**"), "the opcode wins on its token: $opcode")
    }
}
