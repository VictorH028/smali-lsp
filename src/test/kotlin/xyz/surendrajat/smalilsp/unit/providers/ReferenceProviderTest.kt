package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Find references from a declaration and from every kind of use: a class at its `.class` name,
 * in `.super` and `.implements`, as an owner, a type operand, a field type, a declaration
 * descriptor type or a directive; a method or field at its declaration or any access; a label
 * within its method. Which call sites belong to an inherited or overridden member is
 * [MemberFamilyTest]'s question.
 */
class ReferenceProviderTest {

    private lateinit var index: WorkspaceIndex
    private lateinit var provider: ReferenceProvider

    private val shapeUri = "file:///Shape.smali"
    private val widgetUri = "file:///Widget.smali"
    private val subUri = "file:///Sub.smali"
    private val userUri = "file:///User.smali"

    private val shape = ".class public interface abstract Lcom/Shape;\n.super Ljava/lang/Object;"
    private val sub = ".class public Lcom/Sub;\n.super Lcom/Widget;"

    private val widget = """
        .class public Lcom/Widget;
        .super Ljava/lang/Object;
        .implements Lcom/Shape;

        .field public count:I

        .field public static final MAX:I = 0x64

        .field public next:Lcom/Widget;

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public size()I
            .registers 2
            iget v0, p0, Lcom/Widget;->count:I
            return v0
        .end method

        .method public size(I)I
            .registers 2
            iput p1, p0, Lcom/Widget;->count:I
            return p1
        .end method

        .method public size(Ljava/lang/String;)I
            .registers 2
            invoke-virtual {p1}, Ljava/lang/String;->length()I
            move-result v0
            return v0
        .end method

        .method public grow(Lcom/Widget;)Lcom/Widget;
            .registers 3
            invoke-virtual {p0}, Lcom/Widget;->size()I
            move-result v0
            if-eqz v0, :cond_0
            invoke-virtual {p0, v0}, Lcom/Widget;->size(I)I
            goto :cond_0
            :cond_0
            return-object p1
        .end method
    """.trimIndent()

    private val user = """
        .class public Lcom/User;
        .super Landroid/app/Activity;

        .annotation system Ldalvik/annotation/MemberClasses;
            value = Lcom/Widget;
        .end annotation

        .field private widget:Lcom/Widget;

        .method public static use(Lcom/Widget;)V
            .registers 4
            .local v0, "w":Lcom/Widget;
            new-instance v0, Lcom/Widget;
            invoke-direct {v0}, Lcom/Widget;-><init>()V
            const-class v1, Lcom/Widget;
            check-cast v1, [Lcom/Widget;
            invoke-virtual {v0}, Lcom/Widget;->size()I
            invoke-virtual {p0}, Lcom/Widget;->size()I
            invoke-virtual {p0, v1}, Lcom/Widget;->size(Ljava/lang/String;)I
            iget v2, v0, Lcom/Widget;->count:I
            iget-object v3, v0, Lcom/User;->widget:Lcom/Widget;
            sget v2, Lcom/Widget;->MAX:I
            sget v3, Lcom/Widget;->MAX:I
            invoke-virtual {v1}, Ljava/lang/String;->length()I
            invoke-virtual {v3}, [I->clone()Ljava/lang/Object;
            if-nez v2, :cond_0
            :try_start_0
            invoke-static {v0}, Lcom/User;->use(Lcom/Widget;)V
            :try_end_0
            .catch Lcom/Widget; {:try_start_0 .. :try_end_0} :cond_0
            :cond_0
            return-void
        .end method
    """.trimIndent()

    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        provider = ReferenceProvider(index)
        index.openSource(shapeUri, shape)
        index.openSource(widgetUri, widget)
        index.openSource(subUri, sub)
        index.openSource(userUri, user)
    }

    /** `(file, line)` of every reference in file then line order; a line holding two reference tokens appears twice. */
    private fun referencesAt(uri: String, position: Position, includeDeclaration: Boolean = true): List<Pair<String, Int>> =
        provider.findReferences(uri, position, includeDeclaration).map { it.uri.substringAfterLast('/') to it.range.start.line }.ordered()

    private fun List<Pair<String, Int>>.ordered() = sortedWith(compareBy({ it.first }, { it.second }))

    private fun fromWidget(marker: String, needle: String, offset: Int = 0, after: String? = null, includeDeclaration: Boolean = true) =
        referencesAt(widgetUri, widget.positionOf(marker, needle, offset, after), includeDeclaration)

    private fun fromUser(marker: String, needle: String, offset: Int = 0, after: String? = null, includeDeclaration: Boolean = true) =
        referencesAt(userUri, user.positionOf(marker, needle, offset, after), includeDeclaration)

    private fun fromSub(marker: String, needle: String, offset: Int = 0) =
        referencesAt(subUri, sub.positionOf(marker, needle, offset))

    private fun fromShape(marker: String, needle: String, offset: Int = 0, includeDeclaration: Boolean = true) =
        referencesAt(shapeUri, shape.positionOf(marker, needle, offset), includeDeclaration)

    /** The expected entry for the line of a source containing [marker]. */
    private fun String.lineOf(marker: String) = positionOf(marker, marker).line
    private fun inWidget(marker: String) = "Widget.smali" to widget.lineOf(marker)
    private fun inUser(marker: String) = "User.smali" to user.lineOf(marker)
    private fun inSub(marker: String) = "Sub.smali" to sub.lineOf(marker)
    private fun inShape(marker: String) = "Shape.smali" to shape.lineOf(marker)

    /** Every reference to `Lcom/Widget;` but its declaration. A type in a call's descriptor is the call's reference, not the class's. */
    private val widgetUses = listOf(
        inSub(".super Lcom/Widget;"),
        inUser("value = Lcom/Widget;"),
        inUser(".field private widget:Lcom/Widget;"),
        inUser(".method public static use(Lcom/Widget;)V"),
        inUser(".local v0"),
        inUser("new-instance v0, Lcom/Widget;"),
        inUser("invoke-direct {v0}, Lcom/Widget;-><init>()V"),
        inUser("const-class v1, Lcom/Widget;"),
        inUser("check-cast v1, [Lcom/Widget;"),
        inUser("invoke-virtual {v0}, Lcom/Widget;->size()I"),
        inUser("invoke-virtual {p0}, Lcom/Widget;->size()I"),
        inUser("invoke-virtual {p0, v1}, Lcom/Widget;->size(Ljava/lang/String;)I"),
        inUser("iget v2, v0, Lcom/Widget;->count:I"),
        inUser("iget-object v3, v0, Lcom/User;->widget:Lcom/Widget;"),
        inUser("sget v2, Lcom/Widget;->MAX:I"),
        inUser("sget v3, Lcom/Widget;->MAX:I"),
        inUser(".catch Lcom/Widget;"),
        inWidget(".field public next:Lcom/Widget;"),
        inWidget("iget v0, p0, Lcom/Widget;->count:I"),
        inWidget("iput p1, p0, Lcom/Widget;->count:I"),
        inWidget(".method public grow(Lcom/Widget;)Lcom/Widget;"),
        inWidget(".method public grow(Lcom/Widget;)Lcom/Widget;"),
        inWidget("invoke-virtual {p0}, Lcom/Widget;->size()I"),
        inWidget("invoke-virtual {p0, v0}, Lcom/Widget;->size(I)I")
    ).ordered()

    private val widgetReferences = (widgetUses + inWidget(".class public Lcom/Widget;")).ordered()

    @Test
    fun `class references from the declaration`() {
        assertEquals(widgetReferences, fromWidget(".class", "Lcom/Widget;", 5))
        assertEquals(widgetUses, fromWidget(".class", "Lcom/Widget;", 5, includeDeclaration = false))
        val declaration = provider.findReferences(widgetUri, widget.positionOf(".class", "Lcom/Widget;", 5))
            .single { it.uri == widgetUri && it.range.start.line == 0 }
        assertEquals(widget.positionOf(".class", "Lcom/Widget;"), declaration.range.start, "the declaration entry is the name token")
        assertEquals("Lcom/Widget;".length, declaration.range.end.character - declaration.range.start.character)
    }

    @Test
    fun `class references from any use are the same answer`() {
        assertEquals(widgetReferences, fromSub(".super", "Lcom/Widget;", 3))
        assertEquals(widgetReferences, fromWidget(".field public next", "Lcom/Widget;", 3), "a field type")
        assertEquals(widgetReferences, fromWidget(".method public grow", "Lcom/Widget;", 3), "a parameter type")
        assertEquals(widgetReferences, fromWidget(".method public grow", "Lcom/Widget;", 3, after = ")"), "a return type")
        assertEquals(widgetReferences, fromWidget("iget v0, p0", "Lcom/Widget;", 3), "the owner of a field access")
        assertEquals(widgetReferences, fromUser("invoke-virtual {v0}", "Lcom/Widget;", 3), "the owner of a call")
        assertEquals(widgetReferences, fromUser("iget-object", "Lcom/Widget;", 3), "the type of a field access")
        assertEquals(widgetReferences, fromUser("new-instance", "Lcom/Widget;", 3))
        assertEquals(widgetReferences, fromUser("check-cast", "[Lcom/Widget;", 4), "an array of the class")
    }

    @Test
    fun `directive references`() {
        assertEquals(widgetReferences, fromUser("const-class", "Lcom/Widget;", 3))
        assertEquals(widgetReferences, fromUser("value =", "Lcom/Widget;", 3), "an annotation value")
        assertEquals(widgetReferences, fromUser(".local v0", "Lcom/Widget;", 3))
        assertEquals(widgetReferences, fromUser(".catch", "Lcom/Widget;", 3))
    }

    @Test
    fun `interface references are its implementors`() {
        val all = listOf(inShape(".class"), inWidget(".implements Lcom/Shape;")).ordered()
        assertEquals(all, fromShape(".class", "Lcom/Shape;", 5))
        assertEquals(all, fromWidget(".implements", "Lcom/Shape;", 5))
        assertEquals(listOf(inWidget(".implements Lcom/Shape;")), fromShape(".class", "Lcom/Shape;", 5, includeDeclaration = false))
    }

    @Test
    fun `a class outside the workspace has no declaration, only its uses`() {
        assertEquals(listOf(inUser(".super Landroid/app/Activity;")), fromUser(".super", "Landroid/app/Activity;", 5))
        val objectUses = listOf(inShape(".super Ljava/lang/Object;"), inWidget(".super Ljava/lang/Object;"), inWidget("Ljava/lang/Object;-><init>()V")).ordered()
        assertEquals(objectUses, fromWidget(".super", "Ljava/lang/Object;", 5), "two .super lines and a super call; the return type of [I->clone() is not a reference")
    }

    @Test
    fun `method references from the declaration and from a call`() {
        val calls = listOf(
            inWidget("invoke-virtual {p0}, Lcom/Widget;->size()I"),
            inUser("invoke-virtual {v0}, Lcom/Widget;->size()I"),
            inUser("invoke-virtual {p0}, Lcom/Widget;->size()I")
        ).ordered()
        val all = (calls + inWidget(".method public size()I")).ordered()
        assertEquals(all, fromWidget(".method public size()I", "size", 2))
        assertEquals(calls, fromWidget(".method public size()I", "size", 2, includeDeclaration = false))
        assertEquals(all, fromUser("invoke-virtual {v0}, Lcom/Widget;->size()I", "size", 2), "from a call")
        for (offset in 0.."size".length) assertEquals(all, fromWidget(".method public size()I", "size", offset), "caret at offset $offset of the name")
        val entries = provider.findReferences(widgetUri, widget.positionOf(".method public size()I", "size"))
        val declaration = entries.single { it.uri == widgetUri && it.range.start.line == widget.lineOf(".method public size()I") }
        assertEquals(widget.positionOf(".method public size()I", "size"), declaration.range.start, "the declaration entry is the name token")
        assertEquals("size".length, declaration.range.end.character - declaration.range.start.character)
        val call = entries.single { it.uri == userUri && it.range.start.line == user.lineOf("invoke-virtual {v0}, Lcom/Widget;->size()I") }
        assertEquals(user.positionOf("invoke-virtual {v0}, Lcom/Widget;->size()I", "size"), call.range.start, "a call entry is its name token")
    }

    @Test
    fun `overloads are different members`() {
        assertEquals(listOf(inWidget("invoke-virtual {p0, v0}, Lcom/Widget;->size(I)I")), fromWidget(".method public size(I)I", "size", 2, includeDeclaration = false))
        assertEquals(listOf(inUser("Lcom/Widget;->size(Ljava/lang/String;)I")), fromWidget(".method public size(Ljava/lang/String;)I", "size", 2, includeDeclaration = false))
    }

    @Test
    fun `field references from the declaration and from an access`() {
        val accesses = listOf(
            inWidget("iget v0, p0, Lcom/Widget;->count:I"),
            inWidget("iput p1, p0, Lcom/Widget;->count:I"),
            inUser("iget v2, v0, Lcom/Widget;->count:I")
        ).ordered()
        val all = (accesses + inWidget(".field public count:I")).ordered()
        assertEquals(all, fromWidget(".field public count:I", "count", 2))
        assertEquals(accesses, fromWidget(".field public count:I", "count", 2, includeDeclaration = false))
        assertEquals(all, fromWidget("iput p1", "count", 2), "from an access")
        for (offset in 0.."count".length) assertEquals(all, fromWidget(".field public count:I", "count", offset), "caret at offset $offset of the name")
        val maxAccesses = listOf(inUser("sget v2, Lcom/Widget;->MAX:I"), inUser("sget v3, Lcom/Widget;->MAX:I"))
        assertEquals((maxAccesses + inWidget(".field public static final MAX:I")).ordered(), fromWidget(".field public static final MAX:I", "MAX", 1), "a static field read twice from another file")
        assertEquals(maxAccesses, fromUser("sget v3", "MAX", 1, includeDeclaration = false))
    }

    @Test
    fun `a type inside a call is the call, inside a declaration it is the type`() {
        val useReferences = listOf(inUser(".method public static use(Lcom/Widget;)V"), inUser("invoke-static {v0}, Lcom/User;->use(Lcom/Widget;)V")).ordered()
        assertEquals(useReferences, fromUser("invoke-static", "Lcom/Widget;", 3), "a class type in a call's descriptor")
        assertEquals(useReferences, fromUser("invoke-static", "("), "a spot in the target that is not a type")
        val sizeOfString = listOf(inWidget(".method public size(Ljava/lang/String;)I"), inUser("Lcom/Widget;->size(Ljava/lang/String;)I")).ordered()
        assertEquals(sizeOfString, fromUser("Lcom/Widget;->size(Ljava/lang/String;)I", "Ljava/lang/String;", 5), "an SDK type in a call's descriptor: neither the class nor length()")
        val sizeOfInt = listOf(inWidget(".method public size(I)I"), inWidget("invoke-virtual {p0, v0}, Lcom/Widget;->size(I)I")).ordered()
        assertEquals(sizeOfInt, fromWidget("invoke-virtual {p0, v0}", "I", after = "size("), "a primitive in a call's descriptor")
        val count = listOf(
            inWidget(".field public count:I"),
            inWidget("iget v0, p0, Lcom/Widget;->count:I"),
            inWidget("iput p1, p0, Lcom/Widget;->count:I"),
            inUser("iget v2, v0, Lcom/Widget;->count:I")
        ).ordered()
        assertEquals(count, fromWidget("iget v0, p0", "I", after = "count:"), "the primitive type of a field access")
        assertEquals(widgetReferences, fromUser(".method public static use", "Lcom/Widget;", 3), "a class type in a declaration's descriptor")
        assertTrue(fromWidget(".method public size(I)I", "I", after = "size(").isEmpty(), "a primitive in a declaration's descriptor")
    }

    @Test
    fun `a member of a class outside the workspace lists the calls on that exact owner`() {
        val lengthCalls = listOf(inWidget("Ljava/lang/String;->length()I"), inUser("Ljava/lang/String;->length()I")).ordered()
        assertEquals(lengthCalls, fromWidget("Ljava/lang/String;->length()I", "length", 2), "no declaration to add")
        assertEquals(lengthCalls, fromUser("Ljava/lang/String;->length()I", "length", 2, includeDeclaration = false))
        assertEquals(listOf(inUser("[I->clone()")), fromUser("[I->clone()", "clone", 2))
        assertTrue(fromUser("[I->clone()", "[I").isEmpty(), "a primitive array is not a class")
    }

    @Test
    fun `labels are method-local`() {
        val jumps = listOf(inWidget("if-eqz v0, :cond_0"), inWidget("goto :cond_0"))
        val all = (jumps + inWidget("    :cond_0")).ordered()
        assertEquals(all, fromWidget("    :cond_0", ":cond_0", 2), "from the definition")
        assertEquals(all, fromWidget("if-eqz", ":cond_0", 2), "from a jump")
        assertEquals(all, fromWidget("goto :cond_0", "goto"), "from a jump's opcode")
        assertEquals(jumps, fromWidget("if-eqz", ":cond_0", 2, includeDeclaration = false))
        val userLabel = listOf(inUser("if-nez v2, :cond_0"), inUser(".catch Lcom/Widget;"), inUser("    :cond_0")).ordered()
        assertEquals(userLabel, fromUser(".catch", ":cond_0", 2), "the same name in another method is another label")
    }

    @Test
    fun `nothing at blank spots, keywords, beyond the file or in unknown files`() {
        assertTrue(referencesAt(widgetUri, Position(3, 0)).isEmpty(), "a blank line")
        assertTrue(fromWidget(".super", ".super", 1).isEmpty(), "a directive keyword")
        assertTrue(fromWidget(".registers 2", ".registers", 3).isEmpty())
        assertTrue(referencesAt(widgetUri, Position(100, 0)).isEmpty(), "beyond the end of the file")
        assertTrue(referencesAt("file:///Missing.smali", Position(0, 0)).isEmpty())
        assertTrue(ReferenceProvider(WorkspaceIndex()).findReferences(widgetUri, Position(0, 20)).isEmpty(), "an empty index")
    }

    @Test
    fun `a caret on the opcode of a call, an access or a type operand answers like its symbol`() {
        assertEquals(fromWidget("String;->length", "length"), fromWidget("String;->length", "invoke-virtual"), "the call's method")
        assertEquals(fromWidget("iget v0, p0", "count"), fromWidget("iget v0, p0", "iget"), "the access's field")
        assertEquals(fromUser("new-instance v0", "Lcom/Widget;"), fromUser("new-instance v0", "new-instance"), "the operand's class")
    }
}
