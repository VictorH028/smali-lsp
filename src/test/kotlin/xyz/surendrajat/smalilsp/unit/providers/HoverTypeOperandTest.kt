package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.shared.openSource
import xyz.surendrajat.smalilsp.shared.positionOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A type token hovers the same description wherever it sits: declarations, descriptors, invoke
 * owners, field types, type operands and directives. The grid is types x contexts; the plain
 * tests cover what sits next to a type token (keywords, opcodes, registers, comments) and the
 * caret at either end of a token.
 */
class HoverTypeOperandTest {

    private enum class Kind { CLASS, PRIMITIVE, VOID, ARRAY }

    private class TypeCase(val type: String, val kind: Kind, val hover: String)

    private enum class Slot { SUPER, HEADER, MEMBER, METHOD, BODY }

    /** One line holding a type token, placed in [slot] of a minimal class. */
    private class Context(val name: String, val slot: Slot, val fits: Set<Kind>, val line: (TypeCase) -> String)

    private val widget = "Lcom/app/Widget;"
    private val part = "Lcom/app/Widget\$Part;"
    private val bundle = "Landroid/os/Bundle;"
    private val entry = "Ljava/util/Map\$Entry;"

    private val widgetSource = """
        .class public $widget
        .super Ljava/lang/Object;
    """.trimIndent()

    private val partSource = """
        .class public final $part
        .super $widget
    """.trimIndent()

    private val builtIn = "*Built-in type* - Part of the Dalvik/Java type system"
    private val widgetHover = "**Class:** `com.app.Widget`\n\n**Modifiers:** public\n\n**Extends:** `java.lang.Object`\n\n**Methods:** 0  **Fields:** 0"
    private val partHover = "**Class:** `com.app.Widget\$Part`\n\n**Modifiers:** public, final\n\n**Extends:** `com.app.Widget`\n\n**Methods:** 0  **Fields:** 0"
    private fun sdk(name: String) = "**Class:** `$name`\n\n*SDK Class* - Part of Android SDK or Java standard library"
    private fun primitive(name: String, description: String) =
        "**Primitive Type:** `$name`\n\n**Description:** $description\n\n$builtIn"
    private fun primitiveArray(name: String, description: String, dimensions: Int) =
        "**Primitive Array:** `$name${"[]".repeat(dimensions)}`\n\n**Base Type:** `$name` ($description)\n\n**Dimensions:** $dimensions\n\n$builtIn"

    private val intHover = primitive("int", "32-bit signed integer")
    private val booleanHover = primitive("boolean", "true or false")
    private val longArrayHover = primitiveArray("long", "64-bit signed integer", 1)

    // An array of a workspace class describes its element class; an array of an SDK class keeps its brackets.
    private val types = listOf(
        TypeCase(widget, Kind.CLASS, widgetHover),
        TypeCase(part, Kind.CLASS, partHover),
        TypeCase(bundle, Kind.CLASS, sdk("android.os.Bundle")),
        TypeCase(entry, Kind.CLASS, sdk("java.util.Map\$Entry")),
        TypeCase("Z", Kind.PRIMITIVE, booleanHover),
        TypeCase("B", Kind.PRIMITIVE, primitive("byte", "8-bit signed integer")),
        TypeCase("C", Kind.PRIMITIVE, primitive("char", "16-bit Unicode character")),
        TypeCase("S", Kind.PRIMITIVE, primitive("short", "16-bit signed integer")),
        TypeCase("I", Kind.PRIMITIVE, intHover),
        TypeCase("J", Kind.PRIMITIVE, primitive("long", "64-bit signed integer")),
        TypeCase("F", Kind.PRIMITIVE, primitive("float", "32-bit floating point")),
        TypeCase("D", Kind.PRIMITIVE, primitive("double", "64-bit floating point")),
        TypeCase("V", Kind.VOID, primitive("void", "no return value")),
        TypeCase("[J", Kind.ARRAY, longArrayHover),
        TypeCase("[[J", Kind.ARRAY, primitiveArray("long", "64-bit signed integer", 2)),
        TypeCase("[$bundle", Kind.ARRAY, sdk("android.os.Bundle[]")),
        TypeCase("[[$bundle", Kind.ARRAY, sdk("android.os.Bundle[][]")),
        TypeCase("[$widget", Kind.ARRAY, widgetHover),
        TypeCase("[[$widget", Kind.ARRAY, widgetHover)
    )

    private val classes = setOf(Kind.CLASS)
    private val arrays = setOf(Kind.ARRAY)
    private val references = setOf(Kind.CLASS, Kind.ARRAY)
    private val values = setOf(Kind.CLASS, Kind.PRIMITIVE, Kind.ARRAY)
    private val anyType = values + Kind.VOID

    private val contexts = listOf(
        Context(".super", Slot.SUPER, classes) { ".super ${it.type}" },
        Context(".implements", Slot.HEADER, classes) { ".implements ${it.type}" },
        Context(".field type", Slot.MEMBER, values) { ".field public probe:${it.type}" },
        Context(".method parameter", Slot.METHOD, values) { ".method public probe(${it.type})V" },
        Context(".method return type", Slot.METHOD, anyType) { ".method public probe()${it.type}" },
        Context("invoke descriptor parameter", Slot.BODY, values) { "invoke-static {v0}, $widget->run(${it.type})V" },
        Context("invoke descriptor return type", Slot.BODY, anyType) { "invoke-static {v0}, $widget->run()${it.type}" },
        Context("invoke owner", Slot.BODY, references) { "invoke-virtual {v0}, ${it.type}->clone()Ljava/lang/Object;" },
        Context("iget field type", Slot.BODY, values) { "${if (it.kind == Kind.PRIMITIVE) "iget" else "iget-object"} v0, p0, Lcom/app/Cell;->f:${it.type}" },
        Context("sget field type", Slot.BODY, values) { "${if (it.kind == Kind.PRIMITIVE) "sget" else "sget-object"} v0, Lcom/app/Cell;->s:${it.type}" },
        Context("new-array", Slot.BODY, arrays) { "new-array v0, v1, ${it.type}" },
        Context("filled-new-array", Slot.BODY, arrays) { "filled-new-array {v0, v1}, ${it.type}" },
        Context("filled-new-array/range", Slot.BODY, arrays) { "filled-new-array/range {v0 .. v1}, ${it.type}" },
        Context("check-cast", Slot.BODY, references) { "check-cast v0, ${it.type}" },
        Context("instance-of", Slot.BODY, references) { "instance-of v0, v1, ${it.type}" },
        Context("new-instance", Slot.BODY, classes) { "new-instance v0, ${it.type}" },
        Context("const-class", Slot.BODY, references) { "const-class v0, ${it.type}" },
        Context(".catch", Slot.BODY, classes) { ".catch ${it.type} {:try_start_0 .. :try_end_0} :catch_0" }
    )

    /** The cell's file and the position of the type token's first character in it. */
    private fun cell(context: Context, type: TypeCase): Pair<String, Position> {
        val probe = context.line(type)
        fun slot(at: Slot, default: String) = if (context.slot == at) probe else default
        val lines = listOf(
            ".class public Lcom/app/Cell;",
            slot(Slot.SUPER, ".super Ljava/lang/Object;"),
            slot(Slot.HEADER, ""),
            slot(Slot.MEMBER, ""),
            slot(Slot.METHOD, ".method public probe()V"),
            "    .registers 4",
            "    :try_start_0",
            "    nop",
            "    :try_end_0",
            "    " + slot(Slot.BODY, "nop"),
            "    :catch_0",
            "    return-void",
            ".end method"
        )
        val line = lines.indexOfFirst { it.endsWith(probe) }
        return lines.joinToString("\n") to Position(line, lines[line].lastIndexOf(type.type))
    }

    /** A fresh index holding the two workspace classes and [source] under [uri]. */
    private fun workspaceWith(uri: String, source: String): WorkspaceIndex {
        val index = WorkspaceIndex()
        index.openSource("file:///Widget.smali", widgetSource)
        index.openSource("file:///WidgetPart.smali", partSource)
        index.openSource(uri, source)
        return index
    }

    @Test
    fun `a type token hovers the same wherever it sits`() {
        val uri = "file:///Cell.smali"
        for (context in contexts) for (type in types) {
            if (type.kind !in context.fits) continue
            val (source, first) = cell(context, type)
            val provider = HoverProvider(workspaceWith(uri, source))
            val last = Position(first.line, first.character + type.type.length - 1)
            val where = "${type.type} in ${context.name}"
            assertEquals(type.hover, provider.provideHover(uri, first)?.contents?.right?.value, "first character of $where")
            assertEquals(type.hover, provider.provideHover(uri, last)?.contents?.right?.value, "last character of $where")
        }
    }

    private val source = """
        .class public Lcom/app/Probe;
        .super Ljava/lang/Object;

        # fields first
        .field private static final items:[Ljava/lang/String;
        .field private static count:I

        .method public constructor <init>()V
            .locals 1
            .prologue
            .line 12
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public static run(ILjava/lang/String;[JZ)Landroid/os/Bundle;
            .registers 8
            # the body
            const/4 v0, 0x0 # trailing comment
            check-cast v0, Lcom/app/Widget;
            instance-of v1, v0, [Lcom/app/Widget;
            new-instance v2, Lcom/app/Probe;
            invoke-direct {v2}, Lcom/app/Probe;-><init>()V
            const-class v3, [J
            new-array v1, v0, [J
            filled-new-array {v0, v1}, [I
            filled-new-array/range {v0 .. v1}, [[I
            sget v0, Lcom/app/Probe;->count:I
            invoke-static {v0, v1, v2, v3}, Lcom/app/Probe;->run(ILjava/lang/String;[JZ)Landroid/os/Bundle;
            return-object v0
        .end method
    """.trimIndent()

    private val uri = "file:///Probe.smali"
    private val probeHover = "**Class:** `com.app.Probe`\n\n**Modifiers:** public\n\n**Extends:** `java.lang.Object`\n\n**Methods:** 2  **Fields:** 2"
    private fun initHover() = "**Method:** `<init>`\n\n**Declared in:** `com.app.Probe`\n\n**Modifiers:** public, constructor\n\n**Signature:** `()void`\n\n**Returns:** `void`\n\n**References:** " +
        ReferenceProvider(index).findMethodReferences("Lcom/app/Probe;", "<init>", "()V", includeDeclaration = false).size

    private lateinit var index: WorkspaceIndex
    private lateinit var provider: HoverProvider

    @BeforeEach
    fun setup() {
        index = workspaceWith(uri, source)
        provider = HoverProvider(index)
    }

    private fun hoverAt(marker: String, needle: String, offset: Int = 0, after: String? = null): String? =
        provider.provideHover(uri, source.positionOf(marker, needle, offset, after))?.contents?.right?.value

    @Test
    fun `the member name hovers, its keywords and modifiers do not`() {
        assertNull(hoverAt("constructor", ".method", 2))
        assertNull(hoverAt("constructor", "constructor", 3))
        assertEquals(initHover(), hoverAt("constructor", "<init>", 1))
        assertEquals(initHover(), hoverAt("invoke-direct {v2}", "<init>", 1), "the reference resolves to the declaration")
        assertNull(hoverAt("items:", ".field", 2))
        assertNull(hoverAt("items:", "static"))
        val itemAccesses = ReferenceProvider(index).findFieldReferences("Lcom/app/Probe;", "items", "[Ljava/lang/String;", includeDeclaration = false).size
        assertEquals("**Field:** `items`\n\n**Declared in:** `com.app.Probe`\n\n**Modifiers:** private, static, final\n\n**Type:** `java.lang.String[]`\n\n**References:** $itemAccesses", hoverAt("items:", "items", 2))
        assertNull(hoverAt(".end method", ".end", 2))
    }

    @Test
    fun `the opcode word documents the instruction, a register itself`() {
        val opcodes = listOf(
            "check-cast" to "1F", "instance-of" to "20", "new-instance" to "22", "const-class" to "1C",
            "new-array" to "23", "filled-new-array {" to "24", "filled-new-array/range" to "25",
            "sget" to "60", "invoke-direct {v2}" to "70", "invoke-static" to "71"
        )
        for ((marker, hex) in opcodes) {
            val opcode = marker.substringBefore(' ')
            val hover = hoverAt(marker, opcode, 2)
            assertTrue(hover != null && hover.startsWith("## ${opcode.uppercase()}\n\n**Opcode:** `0x$hex`\n\n"), "$opcode: $hover")
        }
        assertTrue(assertNotNull(hoverAt("check-cast", "v0")).contains("Register"))
        assertTrue(assertNotNull(hoverAt("filled-new-array {", "v1")).contains("Register"))
        assertTrue(assertNotNull(hoverAt("invoke-static", "v2")).contains("Register"))
    }

    @Test
    fun `a caret at the end of a token still counts`() {
        assertEquals(probeHover, hoverAt(".class", "Lcom/app/Probe;", "Lcom/app/Probe;".length))
        assertEquals(sdk("java.lang.String[]"), hoverAt("items:", "[Ljava/lang/String;", "[Ljava/lang/String;".length))
        assertEquals(longArrayHover, hoverAt("new-array", "[J", 2), "end of the line")
        assertEquals(probeHover, hoverAt("invoke-static", "->"), "the arrow right after the owner")
        val name = hoverAt(".method public static run", "(")
        assertTrue(name != null && name.startsWith("**Method:** `run`\n\n**Declared in:** `com.app.Probe`\n\n**Modifiers:** public, static\n\n"), "the parenthesis right after the name: $name")
        val call = hoverAt("invoke-static", ")")
        assertTrue(call != null && call.startsWith("**Method:** `run`\n\n"), "a descriptor delimiter in a call is the method: $call")
        assertNull(hoverAt(".method public static run", ")"), "a descriptor delimiter in a declaration is nothing")
    }

    @Test
    fun `each type in a mixed descriptor resolves by offset`() {
        val expected = listOf(
            "I" to intHover,
            "Ljava/lang/String;" to sdk("java.lang.String"),
            "[J" to longArrayHover,
            "Z" to booleanHover,
            "Landroid/os/Bundle;" to sdk("android.os.Bundle")
        )
        for (marker in listOf(".method public static run", "invoke-static")) for ((needle, hover) in expected) {
            assertEquals(hover, hoverAt(marker, needle, after = "("), "$needle in $marker")
        }
    }

    @Test
    fun `directives and comments hover nothing`() {
        assertNull(hoverAt(".locals", ".locals", 2))
        assertNull(hoverAt(".prologue", ".prologue", 2))
        assertNull(hoverAt(".line", "12"))
        assertNull(hoverAt("# fields first", "fields"))
        assertNull(hoverAt("# the body", "body"))
        assertNull(hoverAt("# trailing comment", "#"))
        assertNull(hoverAt("# trailing comment", "comment"))
        val opcode = hoverAt("# trailing comment", "const/4", 2)
        assertTrue(opcode != null && opcode.startsWith("## CONST/4\n\n**Opcode:** `0x12`\n\n"), "the opcode before the comment: $opcode")
    }

    @Test
    fun `a workspace class named like an SDK class shows the class, not the SDK label`() {
        assertEquals(sdk("java.lang.Object"), hoverAt(".super", "Ljava/lang/Object;", 3))
        index.openSource("file:///Object.smali", ".class public Ljava/lang/Object;\n.super Ljava/lang/Object;")
        assertEquals(
            "**Class:** `java.lang.Object`\n\n**Modifiers:** public\n\n**Extends:** `java.lang.Object`\n\n**Methods:** 0  **Fields:** 0",
            hoverAt(".super", "Ljava/lang/Object;", 3)
        )
    }
}
