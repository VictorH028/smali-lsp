package xyz.surendrajat.smalilsp.unit.providers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.CodeLensProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.providers.RenameProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf

/**
 * Which call sites belong to a member: calls through subclasses bind to an inherited member
 * unless the subclass redeclares it, overrides of a virtual method are the same member,
 * constructors and private methods belong to their own class only.
 */
class MemberFamilyTest {

    private val index = WorkspaceIndex()

    private val shape = ".class public interface abstract Lcom/Shape;\n.super Ljava/lang/Object;\n.method public abstract area()I\n.end method"
    private val other = ".class public Lcom/Other;\n.super Ljava/lang/Object;\n.implements Lcom/Shape;\n.method public area()I\n    .registers 1\n    const/4 v0, 0x2\n    return v0\n.end method"

    private val base = """
        .class public Lcom/Base;
        .super Ljava/lang/Object;
        .implements Lcom/Shape;

        .field public static count:I

        .field protected name:Ljava/lang/String;

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public static make()Lcom/Base;
            .registers 1
            const/4 v0, 0x0
            return-object v0
        .end method

        .method private secret()V
            .registers 1
            return-void
        .end method

        .method public area()I
            .registers 1
            const/4 v0, 0x0
            return v0
        .end method

        .method public virtualOnly()V
            .registers 1
            return-void
        .end method
    """.trimIndent()

    private val child = """
        .class public Lcom/Child;
        .super Lcom/Base;

        .field public static count:I

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Lcom/Base;-><init>()V
            return-void
        .end method

        .method public static make()Lcom/Base;
            .registers 1
            const/4 v0, 0x0
            return-object v0
        .end method

        .method private secret()V
            .registers 1
            return-void
        .end method

        .method public area()I
            .registers 1
            const/4 v0, 0x1
            return v0
        .end method
    """.trimIndent()

    private val grand = ".class public Lcom/Grand;\n.super Lcom/Child;"

    private val caller = """
        .class public Lcom/Caller;
        .super Ljava/lang/Object;

        .method public run()V
            .registers 2
            new-instance v0, Lcom/Child;
            invoke-direct {v0}, Lcom/Child;-><init>()V
            invoke-direct {v0}, Lcom/Base;-><init>()V
            invoke-virtual {v0}, Lcom/Base;->area()I
            invoke-virtual {v0}, Lcom/Child;->area()I
            invoke-virtual {v0}, Lcom/Grand;->area()I
            invoke-interface {v0}, Lcom/Shape;->area()I
            invoke-virtual {v0}, Lcom/Other;->area()I
            invoke-static {}, Lcom/Base;->make()Lcom/Base;
            invoke-static {}, Lcom/Child;->make()Lcom/Base;
            invoke-static {}, Lcom/Grand;->make()Lcom/Base;
            invoke-direct {v0}, Lcom/Base;->secret()V
            invoke-direct {v0}, Lcom/Child;->secret()V
            sget v1, Lcom/Base;->count:I
            sget v1, Lcom/Child;->count:I
            sget v1, Lcom/Grand;->count:I
            iget-object v1, v0, Lcom/Child;->name:Ljava/lang/String;
            invoke-virtual {v0}, Lcom/Grand;->virtualOnly()V
            return-void
        .end method
    """.trimIndent()

    @BeforeEach
    fun setup() {
        index.openSource("file:///Shape.smali", shape)
        index.openSource("file:///Other.smali", other)
        index.openSource("file:///Base.smali", base)
        index.openSource("file:///Child.smali", child)
        index.openSource("file:///Grand.smali", grand)
        index.openSource("file:///Caller.smali", caller)
    }

    /** Lines in Caller whose owner-qualified member matches [owner] -> [member]. */
    private fun callerLines(owner: String, member: String): List<Int> =
        caller.lines().withIndex().filter { it.value.contains("$owner->$member") }.map { it.index }

    private fun referencesAt(uri: String, source: String, marker: String, needle: String): List<Int> =
        ReferenceProvider(index).findReferences(uri, source.positionOf(marker, needle), includeDeclaration = false)
            .filter { it.uri == "file:///Caller.smali" }.map { it.range.start.line }.sorted()

    private fun lensCount(uri: String, member: String): String {
        val provider = CodeLensProvider(index)
        return provider.provideCodeLenses(uri).map { provider.resolveCodeLens(it) }
            .first { (it.data as com.google.gson.JsonObject).get("memberName").asString == member }.command.title
    }

    @Test
    fun `a constructor is called on its own class only`() {
        assertEquals(callerLines("Lcom/Base;", "<init>"), referencesAt("file:///Base.smali", base, ".method public constructor", "<init>"))
        assertEquals(callerLines("Lcom/Child;", "<init>"), referencesAt("file:///Child.smali", child, ".method public constructor", "<init>"))
        assertEquals("2 references", lensCount("file:///Base.smali", "<init>"), "the call in Caller and Child's super call; the subclass constructor is a different method")
    }

    @Test
    fun `a private method is never inherited`() {
        assertEquals(callerLines("Lcom/Base;", "secret"), referencesAt("file:///Base.smali", base, ".method private secret", "secret"))
        assertEquals(callerLines("Lcom/Child;", "secret"), referencesAt("file:///Child.smali", child, ".method private secret", "secret"))
    }

    @Test
    fun `a static method is inherited unless the subclass redeclares it`() {
        assertEquals(callerLines("Lcom/Base;", "make"), referencesAt("file:///Base.smali", base, ".method public static make", "make"), "Child hides make; Grand inherits Child's")
        assertEquals(callerLines("Lcom/Child;", "make") + callerLines("Lcom/Grand;", "make"), referencesAt("file:///Child.smali", child, ".method public static make", "make"))
    }

    @Test
    fun `a field is inherited unless the subclass redeclares it`() {
        assertEquals(callerLines("Lcom/Base;", "count"), referencesAt("file:///Base.smali", base, ".field public static count", "count"))
        assertEquals(callerLines("Lcom/Child;", "count") + callerLines("Lcom/Grand;", "count"), referencesAt("file:///Child.smali", child, ".field public static count", "count"))
        assertEquals(callerLines("Lcom/Child;", "name"), referencesAt("file:///Base.smali", base, ".field protected name", "name"), "inherited by Child")
        val edit = RenameProvider(index).rename("file:///Base.smali", base.positionOf(".field public static count", "count"), "total")!!
        assertEquals(setOf("file:///Base.smali", "file:///Caller.smali"), edit.changes.keys, "Child's own count is not renamed")
        assertEquals(callerLines("Lcom/Base;", "count"), edit.changes["file:///Caller.smali"]!!.map { it.range.start.line })
    }

    @Test
    fun `an interface constant is reached through the implementing class`() {
        val consts = ".class public interface abstract Lcom/Consts;\n.super Ljava/lang/Object;\n.field public static final MAX:I = 0x10"
        val impl = ".class public Lcom/Impl;\n.super Ljava/lang/Object;\n.implements Lcom/Consts;"
        val user = ".class public Lcom/User;\n.super Ljava/lang/Object;\n.method public run()V\n    .registers 1\n    sget v0, Lcom/Impl;->MAX:I\n    sget v0, Lcom/Consts;->MAX:I\n    return-void\n.end method"
        index.openSource("file:///Consts.smali", consts)
        index.openSource("file:///Impl.smali", impl)
        index.openSource("file:///User.smali", user)
        val onImplMax = user.positionOf("Lcom/Impl;->MAX", "MAX")
        assertEquals("file:///Consts.smali", xyz.surendrajat.smalilsp.providers.DefinitionProvider(index).findDefinition("file:///User.smali", onImplMax).single().uri)
        val refs = ReferenceProvider(index).findReferences("file:///User.smali", onImplMax, includeDeclaration = false)
        assertEquals(listOf(4, 5), refs.map { it.range.start.line }.sorted(), "both accesses bind to Consts.MAX")
    }

    @Test
    fun `a virtual method includes calls that dispatch to it and its overrides`() {
        assertEquals(callerLines("Lcom/Grand;", "virtualOnly"), referencesAt("file:///Base.smali", base, ".method public virtualOnly", "virtualOnly"))
        assertEquals("2 references", lensCount("file:///Child.smali", "area"), "the lens on an override counts calls on Child and Grand")
        assertEquals("3 references", lensCount("file:///Base.smali", "area"), "Base, Child and Grand")
    }

    @Test
    fun `an override and the interface method it implements are one member`() {
        val everyAreaCall = caller.lines().withIndex().filter { it.value.contains("->area()I") }.map { it.index }
        assertEquals(everyAreaCall, referencesAt("file:///Child.smali", child, ".method public area", "area"), "from an override: calls through Shape, Base, Child, Grand and Other")
        assertEquals(everyAreaCall, referencesAt("file:///Shape.smali", shape, ".method public abstract area", "area"))
        assertEquals(everyAreaCall, referencesAt("file:///Caller.smali", caller, "Lcom/Other;->area", "area"), "from a call on another implementor")
        val withDeclaration = ReferenceProvider(index).findReferences("file:///Child.smali", child.positionOf(".method public area", "area"), includeDeclaration = true)
        assertEquals(setOf("file:///Shape.smali", "file:///Base.smali", "file:///Child.smali", "file:///Other.smali"), withDeclaration.filter { it.uri != "file:///Caller.smali" }.map { it.uri }.toSet(), "every declaration of the member: the root and its overrides")
        val declaration = withDeclaration.first { it.uri == "file:///Shape.smali" }
        assertEquals(shape.positionOf(".method public abstract area", "area"), declaration.range.start, "the declaration entry is the name token")
        assertEquals("area".length, declaration.range.end.character - declaration.range.start.character)
        val edit = RenameProvider(index).rename("file:///Child.smali", child.positionOf(".method public area", "area"), "size")!!
        assertEquals(setOf("file:///Shape.smali", "file:///Base.smali", "file:///Child.smali", "file:///Other.smali", "file:///Caller.smali"), edit.changes.keys)
        assertEquals(everyAreaCall, edit.changes["file:///Caller.smali"]!!.map { it.range.start.line }.sorted())
        for (uri in listOf("file:///Shape.smali", "file:///Base.smali", "file:///Child.smali", "file:///Other.smali")) assertEquals(1, edit.changes[uri]!!.size, uri)
    }
}
