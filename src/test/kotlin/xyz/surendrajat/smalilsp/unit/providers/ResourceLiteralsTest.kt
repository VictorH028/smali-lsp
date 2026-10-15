package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.surendrajat.smalilsp.index.ResourceTable
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A `0x7f...` literal is the resource apktool named in `res/values/public.xml`: hover says which, definition opens the entry. */
class ResourceLiteralsTest {

    @TempDir
    lateinit var root: File

    private val source = """
        .class public Lcom/app/Main;
        .super Ljava/lang/Object;
        .method public onCreate()V
            .registers 2
            const v0, 0x7f0a0012
            const/high16 v1, 0x7f0b0000
            const v0, 0x7f0a9999
            const/4 v1, 0x5
            invoke-virtual {p0, v0}, Lcom/app/Main;->setContentView(I)V
            return-void
        .end method
    """.trimIndent()

    private fun workspace(): WorkspaceIndex {
        val xml = ResourceTable.publicXml(root).apply { parentFile.mkdirs() }
        xml.writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <public type="id" name="login_button" id="0x7f0a0012" />
                <public type="layout" name="activity_main" id="0x7f0b0000" />
                <public type="string" name="app_name" id="0x7f0c0001" />
            </resources>
        """.trimIndent())
        val index = WorkspaceIndex()
        assertEquals(3, index.resources.load(xml))
        index.openSource("file:///Main.smali", source)
        return index
    }

    @Test
    fun `hover names the resource and definition opens its public xml entry`() {
        val index = workspace()
        val hover = HoverProvider(index).provideHover("file:///Main.smali", source.positionOf("const v0, 0x7f0a0012", "0x7f0a0012", 3))
        val text = assertNotNull(hover).contents.right.value
        assertTrue(text.contains("`id/login_button`") && text.contains("0x7f0a0012"), text)
        assertTrue(assertNotNull(HoverProvider(index).provideHover("file:///Main.smali", source.positionOf("const/high16", "0x7f0b0000", 0))).contents.right.value.contains("layout/activity_main"))

        val definition = DefinitionProvider(index).findDefinition("file:///Main.smali", source.positionOf("const v0, 0x7f0a0012", "0x7f0a0012", 3)).single()
        assertEquals(ResourceTable.publicXml(root).toPath().toUri().toString(), definition.uri)
        val xmlLine = ResourceTable.publicXml(root).readLines()[definition.range.start.line]
        assertEquals("login_button", xmlLine.substring(definition.range.start.character, definition.range.end.character))
    }

    @Test
    fun `an id the table does not know and an ordinary literal give nothing`() {
        val index = workspace()
        assertNull(HoverProvider(index).provideHover("file:///Main.smali", source.positionOf("0x7f0a9999", "0x7f0a9999", 2)))
        assertEquals(emptyList(), DefinitionProvider(index).findDefinition("file:///Main.smali", source.positionOf("0x7f0a9999", "0x7f0a9999", 2)))
        assertNull(HoverProvider(index).provideHover("file:///Main.smali", source.positionOf("const/4 v1", "0x5", 1)))
        assertNull(HoverProvider(index).provideHover("file:///Main.smali", source.positionOf("const v0, 0x7f0a0012", "v0", 1))?.takeIf { it.contents.right.value.contains("Resource") }, "the register, not the literal")
    }

    @Test
    fun `a rewritten public xml replaces its entries and a removed root drops them`() {
        val index = workspace()
        val xml = ResourceTable.publicXml(root)
        xml.writeText("<resources>\n    <public type=\"id\" name=\"renamed\" id=\"0x7f0a0012\" />\n</resources>\n")
        assertEquals(1, index.resources.load(xml))
        assertEquals("id/renamed", index.resources[0x7f0a0012]?.qualifiedName)
        assertNull(index.resources[0x7f0b0000], "entries of the earlier read are gone")
        index.resources.removeUnder(root)
        assertEquals(0, index.resources.size)
        assertEquals(0, index.resources.load(File(root, "res/values/missing.xml")), "no file, no entries, no error")
    }

    @Test
    fun `without a public xml nothing changes`() {
        val index = WorkspaceIndex()
        index.openSource("file:///Main.smali", source)
        assertNull(HoverProvider(index).provideHover("file:///Main.smali", source.positionOf("const v0, 0x7f0a0012", "0x7f0a0012", 3)))
        assertEquals(emptyList(), DefinitionProvider(index).findDefinition("file:///Main.smali", source.positionOf("const v0, 0x7f0a0012", "0x7f0a0012", 3)))
    }
}
