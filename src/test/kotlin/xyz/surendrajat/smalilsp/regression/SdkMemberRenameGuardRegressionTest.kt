package xyz.surendrajat.smalilsp.regression

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.RenameProvider
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression test: RenameProvider used to accept rename requests on call/access
 * sites of SDK (or otherwise unindexed) members. The declaration could never be
 * rewritten — it lives outside the workspace — so the rename rewrote every call
 * site (e.g. all String.length() calls → size()) while the real method kept its
 * name, breaking each rewritten site.
 */
class SdkMemberRenameGuardRegressionTest {

    private lateinit var parser: SmaliParser
    private lateinit var index: WorkspaceIndex
    private lateinit var renameProvider: RenameProvider

    @BeforeEach
    fun setup() {
        parser = SmaliParser()
        index = WorkspaceIndex()
        renameProvider = RenameProvider(index)
    }

    private fun indexContent(uri: String, content: String) {
        val file = parser.parse(uri, content)
        assertNotNull(file, "test fixture must parse")
        index.indexFile(file!!)
        index.setDocumentContent(uri, content)
    }

    @Test
    fun `rename on SDK method call site is rejected`() {
        val uri = "file:///com/example/UsesSdk.smali"
        val content = """
            .class public Lcom/example/UsesSdk;
            .super Ljava/lang/Object;

            .method public go(Ljava/lang/String;)I
                invoke-virtual {p1}, Ljava/lang/String;->length()I
                move-result v0
                return v0
            .end method
        """.trimIndent()
        indexContent(uri, content)

        val nameIdx = content.lines()[4].indexOf("length")
        assertTrue(nameIdx > 0)

        assertNull(renameProvider.prepareRename(uri, Position(4, nameIdx + 1)),
            "SDK method must not be renameable — its declaration can never be rewritten")
        assertNull(renameProvider.rename(uri, Position(4, nameIdx + 1), "size"),
            "renaming an SDK method would corrupt every call site")
    }

    @Test
    fun `rename on SDK field access site is rejected`() {
        val uri = "file:///com/example/UsesSdkField.smali"
        val content = """
            .class public Lcom/example/UsesSdkField;
            .super Ljava/lang/Object;

            .method public go(Landroid/graphics/Point;)I
                iget v0, p1, Landroid/graphics/Point;->x:I
                return v0
            .end method
        """.trimIndent()
        indexContent(uri, content)

        val nameIdx = content.lines()[4].indexOf("x:I")
        assertTrue(nameIdx > 0)

        assertNull(renameProvider.rename(uri, Position(4, nameIdx), "px"),
            "renaming an SDK field would corrupt every access site")
    }

    @Test
    fun `rename on workspace method call site still works`() {
        val helperUri = "file:///com/example/Helper.smali"
        indexContent(helperUri, """
            .class public Lcom/example/Helper;
            .super Ljava/lang/Object;

            .method public util()V
                return-void
            .end method
        """.trimIndent())

        val callerUri = "file:///com/example/Caller.smali"
        val callerContent = """
            .class public Lcom/example/Caller;
            .super Ljava/lang/Object;

            .method public run(Lcom/example/Helper;)V
                invoke-virtual {p1}, Lcom/example/Helper;->util()V
                return-void
            .end method
        """.trimIndent()
        indexContent(callerUri, callerContent)

        val nameIdx = callerContent.lines()[4].indexOf("util()V")
        val edit = renameProvider.rename(callerUri, Position(4, nameIdx + 1), "helper")
        assertNotNull(edit, "workspace method rename from a call site must keep working")
        assertTrue(edit!!.changes.containsKey(helperUri), "declaration must be renamed")
        assertTrue(edit.changes.containsKey(callerUri), "call site must be renamed")
    }
}
