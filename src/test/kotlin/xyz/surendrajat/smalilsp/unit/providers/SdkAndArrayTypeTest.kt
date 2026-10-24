package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.providers.RenameProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Classes outside the workspace (SDK or unknown), bundled SDK-named classes and array types across definition, hover, references and rename. */
class SdkAndArrayTypeTest {

    private val index = WorkspaceIndex()

    private val fooUri = "file:///Foo.smali"
    private val foo = ".class public Lcom/app/Foo;\n.super Ljava/lang/Object;"

    private val listenerUri = "file:///Listener.smali"
    private val listener = """
        .class public interface abstract Lcom/app/Listener;
        .super Ljava/lang/Object;

        .method public abstract onEvent(Lcom/app/Foo;)V
        .end method
    """.trimIndent()

    private val uri = "file:///Main.smali"
    private val source = """
        .class public Lcom/app/Main;
        .super Landroid/app/Activity;
        .implements Ljava/lang/Runnable;
        .implements Lcom/app/Listener;

        .field private list:Ljava/util/ArrayList;
        .field private grid:[[I
        .field private foos:[Lcom/app/Foo;

        .method public run()V
            .registers 5
            invoke-super {p0}, Landroid/app/Activity;->onResume()V
            new-instance v0, Ljava/lang/StringBuilder;
            invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
            const-string v1, "a"
            invoke-virtual {v1}, Ljava/lang/String;->length()I
            invoke-virtual {v2}, Ljava/lang/String;->length()I
            invoke-virtual {v1}, Ljava/lang/String;->hashCode()I
            invoke-virtual {v0}, Ljava/lang/Object;->hashCode()I
            sget-object v2, Ljava/lang/System;->out:Ljava/io/PrintStream;
            sget-object v3, Ljava/lang/System;->out:Ljava/io/PrintStream;
            sget-object v3, Ljava/util/Locale${'$'}Category;->DISPLAY:Ljava/util/Locale${'$'}Category;
            new-instance v3, Ljava/lang/Thread${'$'}State;
            invoke-interface {p0}, Ljava/lang/Runnable;->run()V
            invoke-interface {p0, v0}, Lcom/app/Listener;->onEvent(Lcom/app/Foo;)V
            invoke-static {}, Lcom/app/Missing;->make()V
            return-void
        .end method

        .method public arrays([[Ljava/lang/String;[Lcom/app/Foo;)[Lcom/app/Foo;
            .registers 7
            new-array v0, v0, [I
            invoke-virtual {v0}, [I->clone()Ljava/lang/Object;
            invoke-virtual {v1}, [I->clone()Ljava/lang/Object;
            new-array v1, v1, [Ljava/lang/String;
            invoke-virtual {v1}, [Ljava/lang/String;->clone()Ljava/lang/Object;
            invoke-virtual {v2}, [[Ljava/lang/Object;->clone()Ljava/lang/Object;
            filled-new-array/range {v0 .. v1}, [[Lcom/app/Foo;
            iget-object v3, p0, Lcom/app/Main;->foos:[Lcom/app/Foo;
            check-cast v3, [[[[[Lcom/app/Missing;
            return-object v3
        .end method
    """.trimIndent()

    @BeforeEach
    fun setup() {
        index.openSource(fooUri, foo)
        index.openSource(listenerUri, listener)
        index.openSource(uri, source)
    }

    private fun at(marker: String, needle: String, offset: Int = 0, after: String? = null) = source.positionOf(marker, needle, offset, after)
    private fun lineOf(marker: String) = at(marker, marker).line

    private fun hoverAt(position: Position, where: String = "$position"): String {
        val hover = HoverProvider(index).provideHover(uri, position)
        assertNotNull(hover, "no hover on $where")
        return hover.contents.right.value
    }
    private fun definitionAt(position: Position) = DefinitionProvider(index).findDefinition(uri, position)
    private fun referencesAt(position: Position, includeDeclaration: Boolean = true) =
        ReferenceProvider(index).findReferences(uri, position, includeDeclaration)
    private fun renameAt(position: Position) = RenameProvider(index).prepareRename(uri, position)

    @Test
    fun `an SDK class hovers as SDK and has no definition in any context`() {
        val contexts = listOf(
            Triple(".super", "Landroid/app/Activity;", "android.app.Activity"),
            Triple(".implements Ljava/lang/Runnable;", "Ljava/lang/Runnable;", "java.lang.Runnable"),
            Triple("list:", "Ljava/util/ArrayList;", "java.util.ArrayList"),
            Triple(".method public arrays", "[[Ljava/lang/String;", "java.lang.String[][]"),
            Triple("new-instance v0", "Ljava/lang/StringBuilder;", "java.lang.StringBuilder"),
            Triple("invoke-direct", "Ljava/lang/StringBuilder;", "java.lang.StringBuilder"),
            Triple("sget-object v2", "Ljava/lang/System;", "java.lang.System"),
            Triple("sget-object v2", "Ljava/io/PrintStream;", "java.io.PrintStream"),
            Triple("new-array v1", "[Ljava/lang/String;", "java.lang.String[]"),
            Triple("[[Ljava/lang/Object;->clone", "[[Ljava/lang/Object;", "java.lang.Object[][]"),
            Triple("DISPLAY", "Ljava/util/Locale\$Category;", "java.util.Locale\$Category"),
            Triple("Thread", "Ljava/lang/Thread\$State;", "java.lang.Thread\$State")
        )
        for ((marker, needle, name) in contexts) {
            val position = at(marker, needle, 3)
            val hover = hoverAt(position, "$needle in `$marker`")
            assertTrue(hover.contains("`$name` · [Android reference](https://developer.android.com/reference/"), hover)
            assertTrue(definitionAt(position).isEmpty(), "$needle in `$marker` has no source")
        }
        val superRefs = referencesAt(at(".super", "Landroid/app/Activity;", 3))
        assertEquals(listOf(lineOf(".super"), lineOf("invoke-super")), superRefs.map { it.range.start.line }.sorted(), "its uses: the subclass and the call owner")
    }

    @Test
    fun `an SDK member hovers, lists the exact owner's call sites and is neither navigable nor renameable`() {
        val length = at("{v1}, Ljava/lang/String;->length", "length", 2)
        val method = hoverAt(length)
        assertTrue(method.contains("```smali\nLjava/lang/String;->length()I\n```") && method.contains("`int length()` in `java.lang.String` · [Android reference](https://developer.android.com/reference/java/lang/String#length())"), method)
        assertTrue(definitionAt(length).isEmpty())
        assertNull(renameAt(length))
        val calls = listOf(lineOf("{v1}, Ljava/lang/String;->length"), lineOf("{v2}, Ljava/lang/String;->length"))
        assertEquals(calls, referencesAt(length).map { it.range.start.line }.sorted(), "no declaration to include")
        assertEquals(calls, referencesAt(length, includeDeclaration = false).map { it.range.start.line }.sorted())
        val hashCode = at("Ljava/lang/String;->hashCode", "hashCode", 2)
        assertEquals(listOf(lineOf("Ljava/lang/String;->hashCode")), referencesAt(hashCode).map { it.range.start.line }, "not the call on Object")

        val out = at("sget-object v2", "out", 1, after = "->")
        val field = hoverAt(out)
        assertTrue(field.contains("```smali\nLjava/lang/System;->out:Ljava/io/PrintStream;\n```") && field.contains("`PrintStream out` in `java.lang.System` · [Android reference](https://developer.android.com/reference/java/lang/System#out)"), field)
        assertTrue(definitionAt(out).isEmpty())
        assertNull(renameAt(out))
        assertEquals(listOf(lineOf("sget-object v2"), lineOf("sget-object v3")), referencesAt(out).map { it.range.start.line }.sorted())
        assertTrue(hoverAt(at("DISPLAY", "DISPLAY", 2)).contains("developer.android.com/reference/java/util/Locale.Category#DISPLAY"), "a nested SDK class links with a dot")
    }

    @Test
    fun `an array of a workspace class navigates and hovers by its element class in every context`() {
        val fooName = foo.positionOf(".class", "Lcom/app/Foo;")
        val contexts = listOf(
            at("foos:", "[Lcom/app/Foo;", 3) to "field type",
            at(".method public arrays", "[Lcom/app/Foo;", 3) to "parameter type",
            at(".method public arrays", "[Lcom/app/Foo;", 3, after = ")") to "return type",
            at("filled-new-array/range", "[[Lcom/app/Foo;", 3) to "type operand",
            at("iget-object", "[Lcom/app/Foo;", 3) to "field access type"
        )
        for ((position, context) in contexts) {
            val definition = definitionAt(position).single()
            assertEquals(fooUri, definition.uri, context)
            assertEquals(fooName, definition.range.start, "$context lands on the class name token")
            val hover = hoverAt(position, context)
            assertTrue(hover.contains("`com.app.Foo` · ") && !hover.contains("Not found"), "$context: $hover")
        }
        val refs = ReferenceProvider(index).findReferences(fooUri, foo.positionOf(".class", "Lcom/app/Foo;", 3), includeDeclaration = false)
        val arrays = lineOf(".method public arrays")
        assertEquals(listOf(lineOf("foos:"), arrays, arrays, lineOf("filled-new-array/range"), lineOf("iget-object")), refs.filter { it.uri == uri }.map { it.range.start.line }.sorted(), "every array use counts: $refs")
        assertEquals(listOf(listenerUri), refs.filter { it.uri != uri }.map { it.uri }, "the onEvent parameter")
        assertEquals(refs, referencesAt(at("foos:", "[Lcom/app/Foo;", 3), includeDeclaration = false), "the same answer from a field's type")
        assertEquals(refs, referencesAt(at(".method public arrays", "[Lcom/app/Foo;", 3), includeDeclaration = false), "and from a parameter type")
    }

    @Test
    fun `a primitive array is a built-in type whose owner names no class`() {
        val grid = at("grid:", "[[I", 1)
        val hover = hoverAt(grid)
        assertEquals("`int[][]` · 2-dimensional array of `int` (32-bit signed integer)", hover)
        assertTrue(definitionAt(grid).isEmpty())

        val owner = at("{v0}, [I->clone", "[I", 1)
        assertEquals("`int[]` · array of `int` (32-bit signed integer)", hoverAt(owner))
        assertTrue(definitionAt(owner).isEmpty() && referencesAt(owner).isEmpty(), "not a class reference")

        val clone = at("{v0}, [I->clone", "clone", 2)
        val method = hoverAt(clone)
        assertTrue(method.contains("```smali\n[I->clone()Ljava/lang/Object;\n```") && method.contains("`Object clone()` in `int[]` · *Platform type*"), method)
        assertTrue(definitionAt(clone).isEmpty())
        assertNull(renameAt(clone))
        assertEquals(listOf(lineOf("{v0}, [I->clone"), lineOf("{v1}, [I->clone")), referencesAt(clone).map { it.range.start.line }.sorted())
        val stringClone = at("[Ljava/lang/String;->clone", "clone", 2)
        assertEquals(listOf(lineOf("[Ljava/lang/String;->clone")), referencesAt(stringClone).map { it.range.start.line }, "each array owner is its own method")
    }

    @Test
    fun `invoke-interface resolves on a workspace interface and stays unresolved on an SDK one`() {
        val owner = at("invoke-interface {p0, v0}", "Lcom/app/Listener;", 3)
        assertEquals(listOf(listenerUri), definitionAt(owner).map { it.uri })
        val onEvent = at("invoke-interface {p0, v0}", "onEvent", 2)
        val definition = definitionAt(onEvent).single()
        assertEquals(listenerUri, definition.uri)
        assertEquals(listener.positionOf(".method", "onEvent"), definition.range.start, "the abstract declaration's name token")
        val hover = hoverAt(onEvent)
        assertTrue(hover.contains(".method public abstract onEvent("), hover)
        val toListener = ReferenceProvider(index).findReferences(listenerUri, listener.positionOf(".class", "Lcom/app/Listener;", 3), includeDeclaration = true)
        assertEquals(1, toListener.count { it.uri == listenerUri }, "the declaration: $toListener")
        assertEquals(listOf(lineOf(".implements Lcom/app/Listener;"), lineOf("invoke-interface {p0, v0}")), toListener.filter { it.uri == uri }.map { it.range.start.line }.sorted())

        val run = at("Ljava/lang/Runnable;->run", "run", 1)
        assertTrue(definitionAt(run).isEmpty())
        assertTrue(hoverAt(run).contains("developer.android.com/reference/java/lang/Runnable#run()"))
        assertEquals(listOf(lineOf("Ljava/lang/Runnable;->run")), referencesAt(run).map { it.range.start.line }, "not Main's implementation")
        assertEquals(listOf(lineOf(".method public run")), referencesAt(at(".method public run", "run", 1)).map { it.range.start.line }, "the implementation is not linked to the SDK interface")
    }

    @Test
    fun `a class outside the SDK that is not indexed is not found rather than SDK`() {
        val missing = at("check-cast", "[[[[[Lcom/app/Missing;", 6)
        val hover = hoverAt(missing)
        assertTrue(hover.contains("`com.app.Missing[][][][][]` · *Not found in workspace*"), hover)
        assertTrue(definitionAt(missing).isEmpty())
        val operandStart = at("check-cast", "[[[[[Lcom/app/Missing;")
        val operand = Location(uri, Range(operandStart, Position(operandStart.line, operandStart.character + "[[[[[Lcom/app/Missing;".length)))
        val ownerStart = at("invoke-static", "Lcom/app/Missing;")
        val owner = Location(uri, Range(ownerStart, Position(ownerStart.line, ownerStart.character + "Lcom/app/Missing;".length)))
        assertEquals(listOf(owner, operand), referencesAt(missing).sortedBy { it.range.start.line }, "its uses, filed under the element class")

        val make = at("invoke-static", "make", 2)
        val method = hoverAt(make)
        assertTrue(method.contains("Lcom/app/Missing;->make(") && method.contains("*Not found in workspace*"), method)
        assertTrue(definitionAt(make).isEmpty())
        assertEquals(listOf(ownerStart.line), referencesAt(make).map { it.range.start.line }, "the one call site")
    }

    @Test
    fun `a bundled class under an SDK prefix is a workspace class`() {
        val viewModelUri = "file:///ViewModel.smali"
        index.openSource(viewModelUri, ".class public abstract Landroidx/lifecycle/ViewModel;\n.super Ljava/lang/Object;\n\n.method protected onCleared()V\n    .registers 1\n    return-void\n.end method")
        val modelUri = "file:///Model.smali"
        val model = ".class public Lcom/app/Model;\n.super Landroidx/lifecycle/ViewModel;\n\n.method public close()V\n    .registers 1\n    invoke-virtual {p0}, Lcom/app/Model;->onCleared()V\n    return-void\n.end method"
        index.openSource(modelUri, model)
        val hover = HoverProvider(index).provideHover(modelUri, model.positionOf(".super", "Landroidx/lifecycle/ViewModel;", 3))?.contents?.right?.value
        assertNotNull(hover)
        assertTrue(hover.contains("`androidx.lifecycle.ViewModel` · 1 method") && !hover.contains("developer.android.com"), hover)
        val onCleared = model.positionOf("invoke-virtual", "onCleared", 2)
        assertEquals(listOf(viewModelUri), DefinitionProvider(index).findDefinition(modelUri, onCleared).map { it.uri }, "inherited from the bundled class")
        assertNotNull(RenameProvider(index).prepareRename(modelUri, onCleared), "renameable like any workspace member")
    }

    @Test
    fun `obfuscated single-letter names resolve like any other`() {
        val a = ".class public La/a;\n.super Ljava/lang/Object;\n\n.field public a:La/a;\n\n.method public a(La/a;)La/a;\n    .registers 2\n    return-object p1\n.end method"
        val b = ".class public La/b;\n.super Ljava/lang/Object;\n\n.method public a()V\n    .registers 2\n    new-instance v0, La/a;\n    iget-object v1, v0, La/a;->a:La/a;\n    invoke-virtual {v0, v1}, La/a;->a(La/a;)La/a;\n    return-void\n.end method"
        index.openSource("file:///a.smali", a)
        index.openSource("file:///b.smali", b)
        val definitions = DefinitionProvider(index)
        fun definitionFromB(position: Position) = definitions.findDefinition("file:///b.smali", position).map { it.uri to it.range.start }
        assertEquals(listOf("file:///a.smali" to a.positionOf(".class", "La/a;")), definitionFromB(b.positionOf("new-instance", "La/a;", 1)))
        assertEquals(listOf("file:///a.smali" to a.positionOf(".method", "a(")), definitionFromB(b.positionOf("invoke-virtual", "a(", after = "->")), "the method a")
        assertEquals(listOf("file:///a.smali" to a.positionOf(".field", "a:")), definitionFromB(b.positionOf("iget-object", "a:", after = "->")), "the field a")
        val refs = ReferenceProvider(index).findReferences("file:///a.smali", a.positionOf(".class", "La/a;", 1), includeDeclaration = true)
        assertEquals(mapOf("file:///a.smali" to 4, "file:///b.smali" to 4), refs.groupingBy { it.uri }.eachCount(), "declaration, field type, parameter, return; operand, owner, field type, owner: $refs")
    }
}
