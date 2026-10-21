package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.surendrajat.smalilsp.index.ResourceTable
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.InlayHints
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
                <public type="color" name="accent" id="0x7f0d0003" />
            </resources>
        """.trimIndent())
        File(root, "res/values/strings.xml").writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"app_name\">Proton &amp; Mail</string>\n    <string name=\"other\">x</string>\n</resources>\n")
        File(root, "res/values/colors.xml").writeText("<resources>\n    <color name=\"accent\">#ff3366</color>\n</resources>\n")
        File(root, "res/layout").mkdirs()
        File(root, "res/layout/activity_main.xml").writeText("<LinearLayout />\n")
        val index = WorkspaceIndex()
        assertEquals(4, index.resources.load(xml))
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
    fun `values and files show in the hover and are where definition goes`() {
        val index = workspace()
        val stringSource = source.replace("const v0, 0x7f0a9999", "const v0, 0x7f0c0001").replace("const/4 v1, 0x5", "const v1, 0x7f0d0003")
        index.openSource("file:///Main.smali", stringSource)
        val stringHover = assertNotNull(HoverProvider(index).provideHover("file:///Main.smali", stringSource.positionOf("0x7f0c0001", "0x7f0c0001", 2))).contents.right.value
        assertTrue(stringHover.contains("**Value:** \"Proton & Mail\"") && stringHover.contains("res/values/strings.xml"), stringHover)
        assertTrue(assertNotNull(HoverProvider(index).provideHover("file:///Main.smali", stringSource.positionOf("0x7f0d0003", "0x7f0d0003", 2))).contents.right.value.contains("**Value:** #ff3366"))
        val layoutHover = assertNotNull(HoverProvider(index).provideHover("file:///Main.smali", stringSource.positionOf("const/high16", "0x7f0b0000", 0))).contents.right.value
        assertTrue(layoutHover.contains("res/layout/activity_main.xml"), layoutHover)

        val stringDefinition = DefinitionProvider(index).findDefinition("file:///Main.smali", stringSource.positionOf("0x7f0c0001", "0x7f0c0001", 2)).single()
        assertTrue(stringDefinition.uri.endsWith("/res/values/strings.xml"), stringDefinition.uri)
        assertEquals(2, stringDefinition.range.start.line)
        val layoutDefinition = DefinitionProvider(index).findDefinition("file:///Main.smali", stringSource.positionOf("const/high16", "0x7f0b0000", 0)).single()
        assertTrue(layoutDefinition.uri.endsWith("/res/layout/activity_main.xml"), layoutDefinition.uri)
        val idDefinition = DefinitionProvider(index).findDefinition("file:///Main.smali", stringSource.positionOf("const v0, 0x7f0a0012", "0x7f0a0012", 3)).single()
        assertTrue(idDefinition.uri.endsWith("/res/values/public.xml"), "an id has no value and no file: its public.xml entry")
    }

    @Test
    fun `inlay hints show the value when there is one, the name otherwise`() {
        val index = workspace()
        val stringSource = source.replace("const v0, 0x7f0a9999", "const v0, 0x7f0c0001")
        index.openSource("file:///Main.smali", stringSource)
        val lines = stringSource.lines()
        val hints = InlayHints.hints(index, lines, org.eclipse.lsp4j.Range(Position(0, 0), Position(lines.size - 1, 0)))
        assertEquals(listOf("id/login_button", "layout/activity_main", "\"Proton & Mail\""), hints.map { it.label.left })
        assertEquals("string/app_name", hints[2].tooltip.left, "the name moves to the tooltip")
        assertNull(hints[0].tooltip)
        val first = hints[0]
        assertEquals(4, first.position.line)
        assertEquals(lines[4].length, first.position.character, "right after the literal")
        assertTrue(first.paddingLeft)
        assertEquals(emptyList(), InlayHints.hints(index, lines, org.eclipse.lsp4j.Range(Position(0, 0), Position(3, 0))), "no literal in the first lines")
    }

    @Test
    fun `a field value, an array element and a switch key name their resource too`() {
        val index = workspace()
        val other = ".class public Lcom/app/Table;\n.super Ljava/lang/Object;\n.field public static A:I = 0x7f0a0012\n.method public run()V\n    .registers 1\n    sparse-switch v0, :sswitch_data_0\n    return-void\n    :sswitch_data_0\n    .sparse-switch\n        0x7f0b0000 -> :sswitch_0\n        0x64 -> :sswitch_0\n    .end sparse-switch\n    :sswitch_0\n    return-void\n    :array_0\n    .array-data 4\n        0x7f0d0003\n    .end array-data\n.end method\n"
        index.openSource("file:///Table.smali", other)
        val hovers = HoverProvider(index)
        assertTrue(assertNotNull(hovers.provideHover("file:///Table.smali", other.positionOf(".field public static A", "0x7f0a0012", 3))).contents.right.value.contains("`id/login_button`"))
        assertTrue(assertNotNull(hovers.provideHover("file:///Table.smali", other.positionOf("0x7f0b0000 ->", "0x7f0b0000", 3))).contents.right.value.contains("`layout/activity_main`"))
        assertTrue(assertNotNull(hovers.provideHover("file:///Table.smali", other.positionOf("0x7f0d0003", "0x7f0d0003", 3))).contents.right.value.contains("**Value:** #ff3366"))
        assertNull(hovers.provideHover("file:///Table.smali", other.positionOf("0x64 ->", "0x64", 1)), "a plain key")
        assertEquals(ResourceTable.publicXml(root).toPath().toUri().toString(), DefinitionProvider(index).findDefinition("file:///Table.smali", other.positionOf(".field public static A", "0x7f0a0012", 3)).single().uri)
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

    @Test
    fun `framework ids are named from the bundled table, with nowhere to go`() {
        val index = WorkspaceIndex()
        val fw = source.replace("const v0, 0x7f0a9999", "const v0, 0x10100c4").replace("const/4 v1, 0x5", "const v1, 0x101ffff")
        index.openSource("file:///Main.smali", fw)
        val hover = assertNotNull(HoverProvider(index).provideHover("file:///Main.smali", fw.positionOf("0x10100c4", "0x10100c4", 3))).contents.right.value
        assertTrue(hover.contains("`android:attr/orientation`") && hover.contains("android.R.attr.orientation"), hover)
        assertEquals(emptyList(), DefinitionProvider(index).findDefinition("file:///Main.smali", fw.positionOf("0x10100c4", "0x10100c4", 3)))
        assertNull(HoverProvider(index).provideHover("file:///Main.smali", fw.positionOf("0x101ffff", "0x101ffff", 3)), "not a public id")
        val lines = fw.lines()
        assertEquals(listOf("android:attr/orientation"), InlayHints.hints(index, lines, org.eclipse.lsp4j.Range(Position(0, 0), Position(lines.size - 1, 0))).map { it.label.left }, "no project table needed")
    }
}
