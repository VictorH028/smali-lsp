package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.Position
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.CodeLensProvider
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.providers.RenameProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf

/** A member used through a subclass is the member of the class that declares it. */
class InheritedMemberTest {

    private val index = WorkspaceIndex()

    private val base = """
        .class public Lcom/Base;
        .super Ljava/lang/Object;

        .field protected count:I

        .method public greet()V
            .registers 1
            return-void
        .end method
    """.trimIndent()
    private val child = ".class public Lcom/Child;\n.super Lcom/Base;"
    private val sibling = ".class public Lcom/Sibling;\n.super Lcom/Base;"
    private val caller = """
        .class public Lcom/Caller;
        .super Ljava/lang/Object;

        .method public run()V
            .registers 3
            invoke-virtual {v0}, Lcom/Child;->greet()V
            invoke-virtual {v1}, Lcom/Sibling;->greet()V
            invoke-virtual {v2}, Lcom/Base;->greet()V
            iget v0, v0, Lcom/Child;->count:I
            iget v1, v2, Lcom/Base;->count:I
            return-void
        .end method
    """.trimIndent()

    @BeforeEach
    fun setup() {
        index.openSource("file:///Base.smali", base)
        index.openSource("file:///Child.smali", child)
        index.openSource("file:///Sibling.smali", sibling)
        index.openSource("file:///Caller.smali", caller)
    }

    private val onChildGreet = caller.positionOf("Lcom/Child;->greet", "greet", 2)
    private val onChildCount = caller.positionOf("Lcom/Child;->count", "count", 2)

    @Test
    fun `definition and hover resolve to the declaring class`() {
        val definition = DefinitionProvider(index).findDefinition("file:///Caller.smali", onChildGreet).single()
        assertEquals("file:///Base.smali", definition.uri)
        val hover = HoverProvider(index).provideHover("file:///Caller.smali", onChildGreet)!!.contents.right.value
        assertTrue(hover.contains(" greet(") && !hover.contains("Not found"), hover)
        val fieldHover = HoverProvider(index).provideHover("file:///Caller.smali", onChildCount)!!.contents.right.value
        assertTrue(fieldHover.contains(".field protected count:"), fieldHover)
    }

    @Test
    fun `references from a subclass call include the declaration and calls through every subclass`() {
        val refs = ReferenceProvider(index).findReferences("file:///Caller.smali", onChildGreet, includeDeclaration = true)
        assertEquals(1, refs.count { it.uri == "file:///Base.smali" }, "declaration: $refs")
        assertEquals(listOf(5, 6, 7), refs.filter { it.uri == "file:///Caller.smali" }.map { it.range.start.line }.sorted(), "all three call sites: $refs")
        val fieldRefs = ReferenceProvider(index).findReferences("file:///Caller.smali", onChildCount, includeDeclaration = false)
        assertEquals(listOf(8, 9), fieldRefs.map { it.range.start.line }.sorted())
    }

    @Test
    fun `rename from a subclass call renames the declaration and every call site`() {
        val edit = RenameProvider(index).rename("file:///Caller.smali", onChildGreet, "hello")!!
        assertEquals(1, edit.changes["file:///Base.smali"]!!.size, "the declaration in Base")
        assertEquals(listOf(5, 6, 7), edit.changes["file:///Caller.smali"]!!.map { it.range.start.line }.sorted())
        val fieldEdit = RenameProvider(index).rename("file:///Caller.smali", onChildCount, "total")!!
        assertEquals(1, fieldEdit.changes["file:///Base.smali"]!!.size)
        assertEquals(listOf(8, 9), fieldEdit.changes["file:///Caller.smali"]!!.map { it.range.start.line }.sorted())
        assertNull(RenameProvider(index).rename("file:///Caller.smali", caller.positionOf("return-void", "return-void"), "x"))
    }

    @Test
    fun `code lenses count calls through subclasses`() {
        val provider = CodeLensProvider(index)
        val lenses = provider.provideCodeLenses("file:///Base.smali").map { provider.resolveCodeLens(it) }
        val titles = lenses.associate { (it.data as com.google.gson.JsonObject).get("memberName").asString to it.command.title }
        assertEquals("3 references", titles["greet"])
        assertEquals("2 references", titles["count"])
    }

    @Test
    fun `a bundled class with an SDK-looking name is a workspace class`() {
        // AndroidX, Kotlin and Play Services ship inside the APK: their smali is indexed, so calls
        // through app subclasses are references to their members like for any other class
        val lib = ".class public Landroidx/lib/Widget;\n.super Ljava/lang/Object;\n.field protected size:I\n.method public draw()V\n    .registers 1\n    return-void\n.end method"
        val app = ".class public Lcom/app/Button;\n.super Landroidx/lib/Widget;"
        val user = ".class public Lcom/app/Screen;\n.super Ljava/lang/Object;\n.method public run()V\n    .registers 2\n    invoke-virtual {v0}, Lcom/app/Button;->draw()V\n    iget v1, v0, Lcom/app/Button;->size:I\n    return-void\n.end method"
        index.openSource("file:///Widget.smali", lib)
        index.openSource("file:///Button.smali", app)
        index.openSource("file:///Screen.smali", user)
        val onDraw = lib.positionOf(".method public draw", "draw")
        val refs = ReferenceProvider(index).findReferences("file:///Widget.smali", onDraw, includeDeclaration = false)
        assertEquals(listOf("file:///Screen.smali"), refs.map { it.uri }, "the call through the app subclass: $refs")
        val onSize = lib.positionOf(".field protected size", "size")
        assertEquals(1, ReferenceProvider(index).findReferences("file:///Widget.smali", onSize, includeDeclaration = false).size)
        val edit = RenameProvider(index).rename("file:///Widget.smali", onDraw, "paint")!!
        assertEquals(setOf("file:///Widget.smali", "file:///Screen.smali"), edit.changes.keys)
        val provider = CodeLensProvider(index)
        val titles = provider.provideCodeLenses("file:///Widget.smali").map { provider.resolveCodeLens(it) }.associate { (it.data as com.google.gson.JsonObject).get("memberName").asString to it.command.title }
        assertEquals("1 reference", titles["draw"])
    }

    @Test
    fun `a member of a class outside the workspace keeps the owner-based answer`() {
        val onToString = "invoke-virtual {v0}, Lcom/Child;->toString()Ljava/lang/String;"
        index.openSource("file:///Sdk.smali", ".class public Lcom/Sdk;\n.super Ljava/lang/Object;\n.method public run()V\n    .registers 1\n    $onToString\n    return-void\n.end method")
        val p = Position(4, 4 + onToString.indexOf("toString") + 2)
        assertTrue(DefinitionProvider(index).findDefinition("file:///Sdk.smali", p).isEmpty())
        assertTrue(HoverProvider(index).provideHover("file:///Sdk.smali", p)!!.contents.right.value.contains("Not found"))
        assertNull(RenameProvider(index).prepareRename("file:///Sdk.smali", p))
        assertEquals(1, ReferenceProvider(index).findReferences("file:///Sdk.smali", p, includeDeclaration = true).size, "only the exact call site")
    }
}
