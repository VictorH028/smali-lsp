package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf

class HoverProviderTest {

    private lateinit var index: WorkspaceIndex
    private lateinit var provider: HoverProvider

    private val activity = """
        .class public Lcom/app/BaseActivity;
        .super Ljava/lang/Object;

        .field protected tag:Ljava/lang/String;

        .method protected onCreate(Landroid/os/Bundle;)V
            .registers 2
            return-void
        .end method
    """.trimIndent()

    private val source = """
        .class public final Lcom/app/MyActivity;
        .super Lcom/app/BaseActivity;
        .implements Ljava/io/Serializable;

        .field private static final TAG:Ljava/lang/String;
        .field private count:I

        .method public onClick(Landroid/view/View;I)V
            .registers 4
            iget v0, p0, Lcom/app/MyActivity;->count:I
            iget-object v1, p0, Lcom/app/BaseActivity;->tag:Ljava/lang/String;
            if-eqz v0, :cond_0
            invoke-virtual {p0, p1}, Lcom/app/BaseActivity;->onCreate(Landroid/os/Bundle;)V
            invoke-virtual {p1}, Landroid/view/View;->getId()I
            new-array v1, v0, [I
            :try_start_0
            move v0, v1
            :try_end_0
            .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :cond_0
            :cond_0
            const/4 v0, 0x1
            aput-object v0, v1, v2
            return-void
        .end method
    """.trimIndent()

    private val uri = "file:///MyActivity.smali"

    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        provider = HoverProvider(index)
        index.openSource("file:///BaseActivity.smali", activity)
        index.openSource(uri, source)
    }

    private fun hoverAt(position: Position): String? = provider.provideHover(uri, position)?.contents?.right?.value
    private fun hoverAt(marker: String, needle: String, offset: Int = 0, after: String? = null) =
        hoverAt(source.positionOf(marker, needle, offset, after))

    @Test
    fun `class name shows the class`() {
        val hover = hoverAt(".class", "Lcom/app/MyActivity;", 6)!!
        assertTrue(hover.contains("```smali\n.class public final Lcom/app/MyActivity;"), hover)
        assertTrue(hover.contains("public") && hover.contains("final"), hover)
        assertTrue(hover.contains("\n.super Lcom/app/BaseActivity;\n"), hover)
        assertTrue(hover.contains("\n.implements Ljava/io/Serializable;\n"), hover)
        assertTrue(hover.contains("`com.app.MyActivity` · 1 method · 2 fields"), hover)
        assertTrue(hover.endsWith("· 1 method · 2 fields"), "the readable line closes the hover: $hover")
        assertNull(hoverAt(".class", ".class", 2), "the directive keyword")
        assertNull(hoverAt(".class", "final"))
    }

    @Test
    fun `super and implements show the referenced class`() {
        val base = hoverAt(".super", "Lcom/app/BaseActivity;", 8)!!
        assertTrue(base.contains("`com.app.BaseActivity`") && base.contains("1 method"), base)
        val sdk = hoverAt(".implements", "Ljava/io/Serializable;", 3)!!
        assertTrue(sdk.contains("`java.io.Serializable`") && sdk.contains("developer.android.com/reference/java/io/Serializable)"), sdk)
    }

    @Test
    fun `method declaration`() {
        val hover = hoverAt(".method", "onClick", 3)!!
        assertTrue(hover.contains("```smali\n.method public onClick(Landroid/view/View;I)V\n```"), hover)
        assertTrue(hover.contains(" in `com.app.MyActivity` · "), hover)
        assertTrue(hover.contains("`void onClick(View p1, int p2)`"), hover)
        assertTrue(!hover.contains("Lcom/app/") || hover.indexOf("Lcom/app/") < hover.indexOf("`void onClick"), "descriptors only in the code block: $hover")
        assertTrue(hoverAt(".method", "Landroid/view/View;", 5)!!.contains("developer.android.com/reference/android/view/View)"))
        assertTrue(hoverAt(".method", "I", after = "View;")!!.contains("`int` · 32-bit signed integer"))
        assertNull(hoverAt(".method", "public"))
    }

    @Test
    fun `field declaration`() {
        val hover = hoverAt("TAG:", "TAG", 1)!!
        assertTrue(hover.contains("```smali\n.field private static final TAG:Ljava/lang/String;\n```"), hover)
        assertTrue(hover.contains("`String TAG` in `com.app.MyActivity` · "), hover)
        assertTrue(hover.endsWith(" reference") || hover.endsWith(" references"), hover)
        assertTrue(hoverAt("count:I", "I", after = ":")!!.contains("`int` · 32-bit signed integer"))
        assertNull(hoverAt("TAG:", "private"))
    }

    @Test
    fun `member references`() {
        val declared = hoverAt("invoke-virtual {p0, p1}", "onCreate", 2)!!
        assertTrue(declared.contains(".method protected onCreate(") && declared.contains(" onCreate(Bundle p1)` in `com.app.BaseActivity`"), declared)
        val sdk = hoverAt("getId", "getId", 2)!!
        assertTrue(sdk.contains("```smali\nLandroid/view/View;->getId()I\n```") && sdk.contains("`int getId()` in `android.view.View` · [Android reference](https://developer.android.com/reference/android/view/View#getId())"), sdk)
        assertTrue(hoverAt("getId", "I", after = "getId()")!!.contains("`int` · 32-bit signed integer"))
        assertTrue(hoverAt("invoke-virtual {p0, p1}", "Lcom/app/BaseActivity;", 3)!!.contains("`com.app.BaseActivity`"))
        val field = hoverAt("iget-object", "tag", 1)!!
        assertTrue(field.contains(".field protected tag:"), field)
        assertTrue(hoverAt("iget v0", "I", after = "count:")!!.contains("`int` · 32-bit signed integer"))
        val array = hoverAt("new-array", "[I", 1)!!
        assertTrue(array.contains("`int[]` · array of `int`"), array)
    }

    @Test
    fun `labels count every reference`() {
        val definition = hoverAt("    :cond_0", ":cond_0", 2)!!
        assertTrue(definition.startsWith("`:cond_0` · Conditional branch target · "), definition)
        assertTrue(definition.contains(" · 2 references · lines "), "if-eqz and .catch: $definition")
        assertTrue(definition.contains("Conditional branch target"), definition)
        assertTrue(hoverAt("if-eqz", ":cond_0", 3)!!.startsWith("`:cond_0` · "))
        assertTrue(hoverAt(".catch", ":try_end_0", 3)!!.contains("Try block end"))
    }

    @Test
    fun `opcode keywords show the Dalvik documentation`() {
        for ((marker, hex) in listOf("return-void" to "0e", "const/4" to "12", "aput-object" to "4d", "move v0" to "01", "iget v0" to "52", "invoke-virtual {p0, p1}" to "6e")) {
            val hover = hoverAt(marker, marker.substringBefore(' '), 2)!!
            assertTrue(hover.contains(marker.substringBefore(' ')), "$marker: $hover")
            assertTrue(hover.lowercase().contains(hex), "$marker should document opcode 0x$hex: $hover")
        }
        assertTrue(assertNotNull(hoverAt("move v0", "v1"), "a register").contains("**Parameter** `v1`"), "a register hovers as what it holds")
        assertTrue(hoverAt(".registers", ".registers", 2)!!.startsWith("**Frame:** 4 registers"), "the frame, not an opcode")
    }

    @Test
    fun `nothing at blank spots, out of range or in unknown files`() {
        assertNull(hoverAt(Position(3, 0)))
        assertNull(hoverAt(Position(100, 0)))
        assertNull(provider.provideHover("file:///Missing.smali", Position(0, 0)))
    }

    private fun hoverIn(fileUri: String, text: String, marker: String, needle: String): String? =
        provider.provideHover(fileUri, text.positionOf(marker, needle, needle.length / 2))?.contents?.right?.value

    @Test
    fun `indented directives resolve the class they name`() {
        index.openSource("file:///Marker.smali", ".class public interface abstract annotation Lcom/app/Marker;\n.super Ljava/lang/Object;")
        index.openSource("file:///Boom.smali", ".class public Lcom/app/Boom;\n.super Ljava/lang/Exception;")
        val indented = "file:///Indented.smali"
        val text = listOf(
            ".class public Lcom/app/Indented;",
            "    .super Lcom/app/BaseActivity;",
            "    .implements Ljava/io/Serializable;",
            "",
            "    .annotation system Lcom/app/Marker;",
            "    .end annotation",
            "",
            ".method public run()V",
            "    .registers 1",
            "    :try_start_0",
            "    return-void",
            "    :try_end_0",
            "    .catch Lcom/app/Boom; {:try_start_0 .. :try_end_0} :catch_0",
            "    :catch_0",
            "    return-void",
            ".end method",
        ).joinToString("\n")
        index.openSource(indented, text)
        val directives = mapOf(
            ".super" to "com.app.BaseActivity",
            ".implements" to "java.io.Serializable",
            ".annotation" to "com.app.Marker",
            ".catch" to "com.app.Boom",
        )
        for ((directive, readable) in directives) {
            val hover = hoverIn(indented, text, directive, "L${readable.replace('.', '/')};")
            assertTrue(hover != null && hover.contains("`$readable` · "), "$directive: $hover")
        }
    }

    @Test
    fun `class names with underscores and hyphens resolve`() {
        index.openSource("file:///My_Class.smali", ".class public Lcom/app/My_Class;\n.super Ljava/lang/Object;")
        index.openSource("file:///My-Class.smali", ".class public Lcom/app/My-Class;\n.super Ljava/lang/Object;")
        val named = "file:///Named.smali"
        val text = ".class public Lcom/app/Named;\n.super Lcom/app/My_Class;\n\n.annotation system Lcom/app/My-Class;\n.end annotation"
        index.openSource(named, text)
        val underscore = hoverIn(named, text, ".super", "Lcom/app/My_Class;")
        assertTrue(underscore != null && underscore.contains("`com.app.My_Class` · "), "underscore: $underscore")
        val hyphen = hoverIn(named, text, ".annotation", "Lcom/app/My-Class;")
        assertTrue(hyphen != null && hyphen.contains("`com.app.My-Class` · "), "hyphen: $hyphen")
    }

    @Test
    fun `every hover is markdown`() {
        val positions = listOf(
            source.positionOf(".class", "Lcom/app/MyActivity;", 6),
            source.positionOf(".implements", "Ljava/io/Serializable;", 3),
            source.positionOf("count:I", "I", after = ":"),
            source.positionOf(".method", "onClick", 3),
            source.positionOf("getId", "getId", 2),
            source.positionOf("return-void", "return-void", 2),
            source.positionOf("    :cond_0", ":cond_0", 2),
        )
        for (position in positions) {
            assertEquals(MarkupKind.MARKDOWN, provider.provideHover(uri, position)?.contents?.right?.kind, "at $position")
        }
    }
}
