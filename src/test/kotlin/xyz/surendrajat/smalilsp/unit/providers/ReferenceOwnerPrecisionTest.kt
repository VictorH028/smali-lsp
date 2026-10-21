package xyz.surendrajat.smalilsp.unit.providers

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf
import kotlin.test.assertEquals

/**
 * A call is a reference to the member it binds to and to nothing else: a call with the same
 * name and descriptor on an unrelated owner is not one, and a member outside the workspace
 * has the calls on that exact owner only, never a lookalike's.
 */
class ReferenceOwnerPrecisionTest {

    private val index = WorkspaceIndex()
    private val provider = ReferenceProvider(index)

    private val base = """
        .class public Lcom/Base;
        .super Ljava/lang/Object;

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public doWork(Ljava/lang/String;I)V
            .registers 3
            return-void
        .end method
    """.trimIndent()

    private val subclass = """
        .class public Lcom/Subclass;
        .super Lcom/Base;

        .method public test()V
            .registers 3
            invoke-virtual {p0, v1, v2}, Lcom/Base;->doWork(Ljava/lang/String;I)V
            return-void
        .end method
    """.trimIndent()

    private val unrelated = """
        .class public Lcom/Unrelated;
        .super Ljava/lang/Object;

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public doWork(Ljava/lang/String;I)V
            .registers 3
            return-void
        .end method

        .method public test()V
            .registers 3
            invoke-virtual {p0, v1, v2}, Lcom/Unrelated;->doWork(Ljava/lang/String;I)V
            return-void
        .end method
    """.trimIndent()

    private val myEnum = """
        .class public final enum Lcom/MyEnum;
        .super Ljava/lang/Enum;

        .method private constructor <init>(Ljava/lang/String;I)V
            .registers 3
            invoke-direct {p0, p1, p2}, Ljava/lang/Enum;-><init>(Ljava/lang/String;I)V
            return-void
        .end method
    """.trimIndent()

    private val factory = """
        .class public Lcom/Factory;
        .super Ljava/lang/Object;

        .method public static make()V
            .registers 3
            new-instance v0, Lcom/MyEnum;
            invoke-direct {v0, v1, v2}, Lcom/MyEnum;-><init>(Ljava/lang/String;I)V
            invoke-direct {v0, v1, v2}, Lcom/jna/ParameterType;-><init>(Ljava/lang/String;I)V
            invoke-direct {v0, v1, v2}, Ljava/lang/Enum;-><init>(Ljava/lang/String;I)V
            return-void
        .end method
    """.trimIndent()

    private val sources = mapOf(
        "Base.smali" to base,
        "Subclass.smali" to subclass,
        "Unrelated.smali" to unrelated,
        "MyEnum.smali" to myEnum,
        "Factory.smali" to factory
    )

    @BeforeEach
    fun setup() {
        for ((file, source) in sources) index.openSource("file:///$file", source)
    }

    /** `(file, line)` of every reference from a caret on [needle] in the line of [file] containing [marker], in file then line order. */
    private fun referencesFrom(file: String, marker: String, needle: String, includeDeclaration: Boolean = true): List<Pair<String, Int>> =
        provider.findReferences("file:///$file", sources.getValue(file).positionOf(marker, needle, 2), includeDeclaration)
            .map { it.uri.substringAfterLast('/') to it.range.start.line }.ordered()

    /** The expected entry for the line of [file] containing [marker]. */
    private fun lineIn(file: String, marker: String) = file to sources.getValue(file).positionOf(marker, marker).line

    private fun List<Pair<String, Int>>.ordered() = sortedWith(compareBy({ it.first }, { it.second }))

    @Test
    fun `a call with the same name and descriptor on an unrelated class is not a reference`() {
        val baseDoWork = listOf(lineIn("Base.smali", ".method public doWork"), lineIn("Subclass.smali", "invoke-virtual")).ordered()
        assertEquals(baseDoWork, referencesFrom("Subclass.smali", "invoke-virtual", "doWork"), "from the call through the subclass")
        assertEquals(baseDoWork, referencesFrom("Base.smali", ".method public doWork", "doWork"), "from the declaration")
        val unrelatedDoWork = listOf(lineIn("Unrelated.smali", ".method public doWork"), lineIn("Unrelated.smali", "invoke-virtual")).ordered()
        assertEquals(unrelatedDoWork, referencesFrom("Unrelated.smali", "invoke-virtual", "doWork"))
    }

    @Test
    fun `a constructor outside the workspace matches the calls on its exact owner only`() {
        val enumInit = listOf(lineIn("Factory.smali", "Ljava/lang/Enum;-><init>"), lineIn("MyEnum.smali", "Ljava/lang/Enum;-><init>")).ordered()
        assertEquals(enumInit, referencesFrom("Factory.smali", "Ljava/lang/Enum;-><init>", "<init>", includeDeclaration = false), "not the lookalike owner, not the enum's own constructor")
        assertEquals(enumInit, referencesFrom("MyEnum.smali", "Ljava/lang/Enum;-><init>", "<init>"), "no declaration to add")
        assertEquals(listOf(lineIn("Factory.smali", "Lcom/jna/ParameterType;")), referencesFrom("Factory.smali", "Lcom/jna/ParameterType;", "<init>"))
        val ownInit = listOf(lineIn("MyEnum.smali", ".method private constructor <init>"), lineIn("Factory.smali", "Lcom/MyEnum;-><init>")).ordered()
        assertEquals(ownInit, referencesFrom("MyEnum.smali", ".method private constructor <init>", "<init>"), "the enum's own constructor is another member")
    }

    @Test
    fun `Object's constructor is referenced by every class that calls it`() {
        val objectInit = listOf(lineIn("Base.smali", "Ljava/lang/Object;-><init>"), lineIn("Unrelated.smali", "Ljava/lang/Object;-><init>")).ordered()
        assertEquals(objectInit, referencesFrom("Base.smali", "Ljava/lang/Object;-><init>", "<init>", includeDeclaration = false))
        assertEquals(objectInit, referencesFrom("Base.smali", "Ljava/lang/Object;-><init>", "<init>"), "no declaration to add")
    }
}
