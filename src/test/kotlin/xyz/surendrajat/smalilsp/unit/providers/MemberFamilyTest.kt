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

    private val base = """
        .class public Lcom/Base;
        .super Ljava/lang/Object;

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
    fun `a virtual method includes calls that dispatch to it and its overrides`() {
        assertEquals(callerLines("Lcom/Grand;", "virtualOnly"), referencesAt("file:///Base.smali", base, ".method public virtualOnly", "virtualOnly"))
        val onChildArea = child.positionOf(".method public area", "area")
        assertEquals(callerLines("Lcom/Child;", "area") + callerLines("Lcom/Grand;", "area"), referencesAt("file:///Child.smali", child, ".method public area", "area"))
        assertEquals("2 references", lensCount("file:///Child.smali", "area"))
        val edit = RenameProvider(index).rename("file:///Child.smali", onChildArea, "size")!!
        assertEquals(1, edit.changes["file:///Child.smali"]!!.size, "the override's declaration")
    }
}
