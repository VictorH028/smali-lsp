package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DocumentSymbolProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The outline is one Class symbol whose children are the methods (Constructor for `<init>` and
 * `<clinit>`) followed by the fields, each with a detail, a declaration range and a name range.
 */
class DocumentSymbolProviderTest {

    /** The single class symbol the provider returns for the source made of [lines]. */
    private fun outline(vararg lines: String): DocumentSymbol {
        val source = lines.joinToString("\n")
        val file = assertNotNull(SmaliParser().parse("file:///test/T.smali", source), "source failed to parse")
        return DocumentSymbolProvider().provideDocumentSymbols(file).single()
    }

    /** A `.method` with the given declaration and no body. */
    private fun method(declaration: String) = ".method $declaration\n.end method"

    private fun DocumentSymbol.child(name: String): DocumentSymbol =
        assertNotNull(children.find { it.name == name }, "no child named $name")

    private val DocumentSymbol.childNames: List<String> get() = children.map { it.name }

    /** Range of [needle] on the first line containing [marker]; the whole line when [needle] is null. */
    private fun String.rangeOf(marker: String, needle: String? = null): Range {
        val lines = lines()
        val line = lines.indexOfFirst { it.contains(marker) }
        val start = if (needle == null) 0 else lines[line].indexOf(needle)
        return Range(Position(line, start), Position(line, start + (needle ?: lines[line]).length))
    }

    @Test
    fun `class symbol lists methods then fields in declaration order`() {
        val symbol = outline(
            ".class public Ltest/Example;", ".super Ljava/lang/Object;",
            ".field public name:Ljava/lang/String;", ".field private age:I",
            method("public constructor <init>()V"), method("static constructor <clinit>()V"),
            method("public getName()Ljava/lang/String;")
        )

        assertEquals("test.Example", symbol.name)
        assertEquals(SymbolKind.Class, symbol.kind)
        assertEquals("public, extends java.lang.Object", symbol.detail)
        assertEquals(listOf("<init>", "<clinit>", "getName", "name", "age"), symbol.childNames)
        assertEquals(
            listOf(SymbolKind.Constructor, SymbolKind.Constructor, SymbolKind.Method, SymbolKind.Field, SymbolKind.Field),
            symbol.children.map { it.kind }
        )
    }

    @Test
    fun `every method is listed, fifteen included`() {
        val methods = (1..15).map { method("public method$it()V") }.toTypedArray()
        val symbol = outline(".class public Ltest/Many;", ".super Ljava/lang/Object;", *methods)

        assertEquals((1..15).map { "method$it" }, symbol.childNames)
    }

    @Test
    fun `method detail is the descriptor`() {
        val symbol = outline(
            ".class public Ltest/Methods;", ".super Ljava/lang/Object;",
            method("public foo()V"), method("public bar(I)Ljava/lang/String;"),
            method("public baz(Ljava/lang/String;I)Z"),
            method("public complex(ILjava/lang/String;Z[BLjava/util/List;Ltest/Callback;JDF)V")
        )

        assertEquals(
            mapOf(
                "foo" to "()V",
                "bar" to "(I)Ljava/lang/String;",
                "baz" to "(Ljava/lang/String;I)Z",
                "complex" to "(ILjava/lang/String;Z[BLjava/util/List;Ltest/Callback;JDF)V"
            ),
            symbol.children.associate { it.name to it.detail }
        )
    }

    @Test
    fun `synthetic, bridge, varargs and abstract methods are listed`() {
        val symbol = outline(
            ".class public abstract Ltest/Special;", ".super Ljava/lang/Object;",
            method("public synthetic access\$000()V"),
            method("public bridge synthetic compare(Ljava/lang/Object;Ljava/lang/Object;)I"),
            method("public varargs format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"),
            method("public abstract doWork()V")
        )

        assertEquals(listOf("access\$000", "compare", "format", "doWork"), symbol.childNames)
        assertEquals("(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;", symbol.child("format").detail)
        assertEquals(SymbolKind.Method, symbol.child("doWork").kind)
    }

    @Test
    fun `primitive and primitive array fields show the Java type name`() {
        val types = listOf("I", "J", "F", "D", "Z", "B", "C", "S", "[B", "[[I")
        val fields = types.mapIndexed { i, type -> ".field public f$i:$type" }.toTypedArray()
        val symbol = outline(".class public Ltest/Primitives;", ".super Ljava/lang/Object;", *fields)

        assertEquals(listOf("int", "long", "float", "double", "boolean", "byte", "char", "short", "byte[]", "int[][]"), symbol.children.map { it.detail })
        assertEquals(List(types.size) { SymbolKind.Field }, symbol.children.map { it.kind })
    }

    @Test
    fun `object fields show the readable class name, object arrays included`() {
        val symbol = outline(
            ".class public Ltest/Fields;", ".super Ljava/lang/Object;",
            ".field public name:Ljava/lang/String;", ".field public list:Ljava/util/List;",
            ".field protected callback:Ltest/Callback;", ".field private strings:[Ljava/lang/String;"
        )

        assertEquals(
            listOf("java.lang.String", "java.util.List", "test.Callback", "java.lang.String[]"),
            symbol.children.map { it.detail }
        )
    }

    @Test
    fun `range covers the declaration and selection range the name token`() {
        val source = """
            .class public Ltest/Ranges;
            .super Ljava/lang/Object;

            .field public value:I

            .method public getValue()I
                const/4 v0, 0x0
                return v0
            .end method
        """.trimIndent()
        val symbol = outline(source)

        assertEquals(Range(Position(0, 0), Position(8, ".end method".length)), symbol.range, "the class encloses its members")
        assertEquals(source.rangeOf(".class", "Ltest/Ranges;"), symbol.selectionRange)
        val field = symbol.child("value")
        assertEquals(source.rangeOf(".field"), field.range)
        assertEquals(source.rangeOf(".field", "value"), field.selectionRange)
        val method = symbol.child("getValue")
        assertEquals(Range(Position(5, 0), Position(8, ".end method".length)), method.range)
        assertEquals(source.rangeOf(".method", "getValue"), method.selectionRange)
    }

    @Test
    fun `class detail is the modifiers then the superclass, or absent`() {
        val abstract = outline(".class public abstract Ltest/Abstract;", ".super Ljava/lang/Object;")
        assertEquals("public abstract, extends java.lang.Object", abstract.detail)
        assertEquals("public, extends test.Base", outline(".class public Ltest/Derived;", ".super Ltest/Base;").detail)
        assertNull(outline(".class Ltest/Bare;").detail)
    }

    @Test
    fun `interface, annotation and enum classes keep their modifiers and members`() {
        val iface = outline(
            ".class public abstract interface Ltest/IClickable;", ".super Ljava/lang/Object;",
            method("public abstract onClick()V")
        )
        assertEquals("public abstract interface, extends java.lang.Object", iface.detail)
        assertEquals(SymbolKind.Method, iface.child("onClick").kind)

        val annotation = outline(
            ".class public interface abstract annotation Ltest/MyAnnotation;", ".super Ljava/lang/Object;",
            ".implements Ljava/lang/annotation/Annotation;", method("public abstract value()Ljava/lang/String;")
        )
        assertEquals("public interface abstract annotation, extends java.lang.Object", annotation.detail)
        assertEquals("()Ljava/lang/String;", annotation.child("value").detail)

        val color = outline(
            ".class public final enum Ltest/Color;", ".super Ljava/lang/Enum;",
            ".field public static final enum RED:Ltest/Color;", ".field public static final enum GREEN:Ltest/Color;",
            method("public static values()[Ltest/Color;")
        )
        assertEquals("public final enum, extends java.lang.Enum", color.detail)
        assertEquals(listOf("values", "RED", "GREEN"), color.childNames)
        assertEquals("()[Ltest/Color;", color.child("values").detail)
        assertEquals("test.Color", color.child("RED").detail)
    }

    @Test
    fun `nested and anonymous class names keep their dollar segment`() {
        val inner = outline(
            ".class public Ltest/Outer\$Inner;", ".super Ljava/lang/Object;", method("public innerMethod()V")
        )
        assertEquals("test.Outer\$Inner", inner.name)
        assertEquals(listOf("innerMethod"), inner.childNames)

        val anonymous = outline(
            ".class Ltest/Outer\$1;", ".super Ljava/lang/Object;", ".source \"Outer.java\"", method("public run()V")
        )
        assertEquals("test.Outer\$1", anonymous.name)
        assertEquals("extends java.lang.Object", anonymous.detail)
        assertEquals(listOf("run"), anonymous.childNames)
    }

    @Test
    fun `empty class is a class symbol with no children`() {
        val symbol = outline(".class public Ltest/Empty;", ".super Ljava/lang/Object;")

        assertEquals("test.Empty", symbol.name)
        assertEquals(SymbolKind.Class, symbol.kind)
        assertEquals(emptyList<DocumentSymbol>(), symbol.children)
    }

    @Test
    fun `member names with dollar, hyphen and unicode are kept verbatim`() {
        val source = listOf(
            ".class public Ltest/Names;", ".super Ljava/lang/Object;",
            ".field private a-b:I", ".field private -c:I", ".field private héllo:Ljava/lang/String;",
            method("public static -deprecated_run(I)I"), method("public lambda\$run\$0()V"), method("public 日本語()V")
        ).joinToString("\n")
        val symbol = outline(source)

        assertEquals(listOf("-deprecated_run", "lambda\$run\$0", "日本語", "a-b", "-c", "héllo"), symbol.childNames)
        assertEquals(source.rangeOf("-deprecated_run(I)I", "-deprecated_run"), symbol.child("-deprecated_run").selectionRange)
        assertEquals(source.rangeOf("-c:I", "-c"), symbol.child("-c").selectionRange)
    }

    @Test
    fun `members around a syntax error are still listed`() {
        val symbol = outline("""
            .class public Ltest/Broken;
            .super Ljava/lang/Object;

            .method public bad()V
                this is not smali
            .end method

            .field public after:I

            .method public good()V
                return-void
            .end method
        """.trimIndent())

        assertEquals("test.Broken", symbol.name)
        assertEquals(listOf("bad", "good", "after"), symbol.childNames)
        assertEquals("()V", symbol.child("bad").detail)
    }
}
