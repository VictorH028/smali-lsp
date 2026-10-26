package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.data.DalvikOpcodeDatabase
import xyz.surendrajat.smalilsp.providers.CompletionProvider
import xyz.surendrajat.smalilsp.shared.openSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompletionProviderTest {

    private lateinit var index: WorkspaceIndex
    private lateinit var parser: SmaliParser
    private lateinit var provider: CompletionProvider

    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
        parser = SmaliParser()
        provider = CompletionProvider(index)
    }

    private fun indexContent(uri: String, content: String) {
        val file = parser.parse(uri, content)
        assertNotNull(file, "Failed to parse $uri")
        index.indexFile(file)
    }

    // --- Opcode completion ---

    @Test
    fun `completes opcodes at line start`() {
        val result = provider.provideCompletions("file:///test.smali", Position(0, 6), "invoke")
        assertTrue(result.items.isNotEmpty(), "Should have opcode completions")
        assertTrue(result.items.any { it.label.startsWith("invoke-") })
    }

    @Test
    fun `completes partial opcodes`() {
        val result = provider.provideCompletions("file:///test.smali", Position(0, 3), "ige")
        assertTrue(result.items.isNotEmpty())
        assertTrue(result.items.any { it.label.startsWith("iget") })
    }

    @Test
    fun `a directive prefix completes directives, not opcodes`() {
        val result = provider.provideCompletions("file:///test.smali", Position(0, 6), ".class")
        assertEquals(listOf(".class"), result.items.map { it.label })
    }

    @Test
    fun `no opcode completion for comments`() {
        val result = provider.provideCompletions("file:///test.smali", Position(0, 10), "# some text")
        assertTrue(result.items.isEmpty(), "Comments should not trigger completion")
    }

    // --- Class name completion ---

    @Test
    fun `completes class names with L-prefix`() {
        val content = """
            .class public Lcom/example/MyActivity;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/MyActivity.smali", content)

        val lineText = "    new-instance v0, Lcom/exam"
        val result = provider.provideCompletions("file:///test.smali", Position(0, lineText.length), lineText)
        assertTrue(result.items.isNotEmpty(), "Should complete partial class name")
        assertTrue(result.items.any {
            it.textEdit?.left?.newText == "Lcom/example/MyActivity;"
        })
    }

    @Test
    fun `completes class names in super directive`() {
        val content = """
            .class public Lcom/example/Base;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Base.smali", content)

        val result = provider.provideCompletions("file:///test.smali", Position(0, 10), ".super Bas")
        assertTrue(result.items.isNotEmpty(), "Should complete class names in .super")
    }

    @Test
    fun `completes class names in implements directive`() {
        val content = """
            .class public interface abstract Lcom/example/MyInterface;
            .super Ljava/lang/Object;
        """.trimIndent()
        indexContent("file:///test/MyInterface.smali", content)

        val result = provider.provideCompletions("file:///test.smali", Position(0, 17), ".implements MyIn")
        assertTrue(result.items.isNotEmpty(), "Should complete class names in .implements")
    }

    // --- Member completion (after ->) ---

    @Test
    fun `completes methods after arrow`() {
        val content = """
            .class public Lcom/example/Service;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public start()V
                .registers 1
                return-void
            .end method
            .method public stop()V
                .registers 1
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Service.smali", content)

        val result = provider.provideCompletions(
            "file:///test.smali",
            Position(0, 52),
            "    invoke-virtual {v0}, Lcom/example/Service;->st"
        )
        assertTrue(result.items.isNotEmpty(), "Should complete method names")
        assertTrue(result.items.any { it.label.contains("start") })
        assertTrue(result.items.any { it.label.contains("stop") })
    }

    @Test
    fun `completes fields after arrow`() {
        val content = """
            .class public Lcom/example/Data;
            .super Ljava/lang/Object;
            .field public name:Ljava/lang/String;
            .field public count:I
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Data.smali", content)

        val result = provider.provideCompletions(
            "file:///test.smali",
            Position(0, 44),
            "    iget-object v0, p0, Lcom/example/Data;->n"
        )
        assertTrue(result.items.isNotEmpty(), "Should complete field names")
        assertTrue(result.items.any { it.label.contains("name") })
    }

    @Test
    fun `no completions for unknown class after arrow`() {
        val result = provider.provideCompletions(
            "file:///test.smali",
            Position(0, 52),
            "    invoke-virtual {v0}, Lcom/nonexistent/Foo;->bar"
        )
        assertTrue(result.items.isEmpty(), "Unknown class should return empty completions")
    }

    // --- Edge cases ---

    @Test
    fun `an empty line in an unknown file offers every opcode`() {
        val result = provider.provideCompletions("file:///test.smali", Position(0, 0), "")
        assertTrue(result.items.size > 200 && result.items.all { DalvikOpcodeDatabase.lookup(it.label) != null })
    }

    @Test
    fun `position beyond line length handled gracefully`() {
        val result = provider.provideCompletions("file:///test.smali", Position(0, 100), "short")
        // Should not throw, should return something (opcodes in this case)
        assertNotNull(result)
    }

    @Test
    fun `completion list limited to 50 class results`() {
        // Index many classes
        for (i in 1..60) {
            val content = """
                .class public Lcom/example/Gen$i;
                .super Ljava/lang/Object;
            """.trimIndent()
            indexContent("file:///test/Gen$i.smali", content)
        }

        val result = provider.provideCompletions(
            "file:///test.smali",
            Position(0, 19),
            "    new-instance v0, LGen"
        )
        assertTrue(result.items.size <= 50, "Class completions should be capped at 50")
    }

    // --- textEdit replacement range ---

    @Test
    fun `opcode completion replaces typed prefix via textEdit`() {
        // User typed "invoke" at column 4 (after spaces)
        val line = "    invoke"
        val result = provider.provideCompletions("file:///test.smali", Position(5, 10), line)
        assertTrue(result.items.isNotEmpty())

        val item = result.items.first { it.label == "invoke-virtual" }
        val textEdit = item.textEdit?.left
        assertNotNull(textEdit, "CompletionItem must have a textEdit")
        // The textEdit should replace from column 4 (start of "invoke") to column 10 (cursor)
        assertEquals(4, textEdit.range.start.character)
        assertEquals(10, textEdit.range.end.character)
        assertEquals("invoke-virtual", textEdit.newText)
    }

    @Test
    fun `member completion replaces typed prefix via textEdit`() {
        val content = """
            .class public Lcom/example/Service;
            .super Ljava/lang/Object;
            .method public start()V
                .registers 1
                return-void
            .end method
            .method public stop()V
                .registers 1
                return-void
            .end method
        """.trimIndent()
        indexContent("file:///test/Service.smali", content)

        val line = "    invoke-virtual {v0}, Lcom/example/Service;->st"
        val result = provider.provideCompletions("file:///test.smali", Position(0, line.length), line)
        assertTrue(result.items.isNotEmpty())

        val item = result.items.first { it.label.contains("start") }
        val textEdit = item.textEdit?.left
        assertNotNull(textEdit, "Member CompletionItem must have a textEdit")
        // Should replace "st" (2 chars before cursor)
        assertEquals(line.length - 2, textEdit.range.start.character)
        assertEquals(line.length, textEdit.range.end.character)
    }

    // --- Specific opcode completion tests ---

    @Test
    fun `invoke-static appears in completions for invoke-st prefix`() {
        val line = "    invoke-st"
        val result = provider.provideCompletions("file:///test.smali", Position(0, line.length), line)
        assertTrue(result.items.isNotEmpty(), "Should have completions for 'invoke-st'")
        assertTrue(result.items.any { it.label == "invoke-static" }, "Should include invoke-static")
        assertTrue(result.items.any { it.label == "invoke-static/range" }, "Should include invoke-static/range")
    }

    @Test
    fun `return prefix completes return-void and variants`() {
        val line = "    return"
        val result = provider.provideCompletions("file:///test.smali", Position(0, line.length), line)
        assertTrue(result.items.isNotEmpty(), "Should have completions for 'return'")
        val labels = result.items.map { it.label }
        assertTrue(labels.contains("return-void"), "Should include return-void, got: $labels")
        assertTrue(labels.contains("return"), "Should include return, got: $labels")
        assertTrue(labels.contains("return-wide"), "Should include return-wide, got: $labels")
        assertTrue(labels.contains("return-object"), "Should include return-object, got: $labels")
    }

    @Test
    fun `const prefix completes const-4 and variants`() {
        val line = "    const"
        val result = provider.provideCompletions("file:///test.smali", Position(0, line.length), line)
        assertTrue(result.items.isNotEmpty(), "Should have completions for 'const'")
        val labels = result.items.map { it.label }
        assertTrue(labels.contains("const/4"), "Should include const/4, got: $labels")
        assertTrue(labels.contains("const/16"), "Should include const/16, got: $labels")
        assertTrue(labels.contains("const-string"), "Should include const-string, got: $labels")
    }

    @Test
    fun `aput prefix completes aput-object and variants`() {
        val line = "    aput"
        val result = provider.provideCompletions("file:///test.smali", Position(0, line.length), line)
        assertTrue(result.items.isNotEmpty(), "Should have completions for 'aput'")
        val labels = result.items.map { it.label }
        assertTrue(labels.contains("aput"), "Should include aput, got: $labels")
        assertTrue(labels.contains("aput-object"), "Should include aput-object, got: $labels")
        assertTrue(labels.contains("aput-wide"), "Should include aput-wide, got: $labels")
    }

    @Test
    fun `move prefix completes move variants`() {
        val line = "    move"
        val result = provider.provideCompletions("file:///test.smali", Position(0, line.length), line)
        assertTrue(result.items.isNotEmpty(), "Should have completions for 'move'")
        val labels = result.items.map { it.label }
        assertTrue(labels.contains("move"), "Should include move, got: $labels")
        assertTrue(labels.contains("move-object"), "Should include move-object, got: $labels")
        assertTrue(labels.contains("move-result"), "Should include move-result, got: $labels")
    }

    @Test
    fun `add-int prefix completes add-int variants`() {
        val line = "    add-int"
        val result = provider.provideCompletions("file:///test.smali", Position(0, line.length), line)
        assertTrue(result.items.isNotEmpty(), "Should have completions for 'add-int'")
        val labels = result.items.map { it.label }
        assertTrue(labels.contains("add-int"), "Should include add-int, got: $labels")
        assertTrue(labels.contains("add-int/2addr"), "Should include add-int/2addr, got: $labels")
        assertTrue(labels.contains("add-int/lit8"), "Should include add-int/lit8, got: $labels")
        assertTrue(labels.contains("add-int/lit16"), "Should include add-int/lit16, got: $labels")
    }

    @Test
    fun `single char n completes nop and neg variants`() {
        val line = "    n"
        val result = provider.provideCompletions("file:///test.smali", Position(0, line.length), line)
        assertTrue(result.items.isNotEmpty(), "Should have completions for 'n'")
        val labels = result.items.map { it.label }
        assertTrue(labels.contains("nop"), "Should include nop, got: $labels")
        assertTrue(labels.any { it.startsWith("neg-") }, "Should include neg variants, got: $labels")
        assertTrue(labels.any { it.startsWith("new-") }, "Should include new variants, got: $labels")
    }

    @Test
    fun `a capital L inside a word or a string is not the start of a class name`() {
        indexContent("file:///test/Later.smali", ".class public Lapp/Later;\n.super Ljava/lang/Object;")
        for (line in listOf(".method public onLa", "    const-string v0, \"XML", "    const-string v0, \"La", "    move-object v0, pL")) {
            val items = provider.provideCompletions("file:///test.smali", Position(3, line.length), line).items
            assertTrue(items.none { it.kind == org.eclipse.lsp4j.CompletionItemKind.Class }, "$line offered ${items.map { it.label }}")
        }
        for (line in listOf("    new-instance v0, La", ".method public run(ILa", ".method public run(Ljava/lang/String;La", ".field public data:[La", "    check-cast v0, La")) {
            val items = provider.provideCompletions("file:///test.smali", Position(3, line.length), line).items
            assertEquals(listOf("Lapp/Later;"), items.map { it.textEdit.left.newText }, line)
        }
    }

    @Test
    fun `an unfinished L-type in a declaration completes class names`() {
        indexContent("file:///test/MyClass.smali", ".class public Lcom/example/MyClass;\n.super Ljava/lang/Object;")
        for (line in listOf(".field public data:Lcom/ex", ".method public run(Lcom/ex")) {
            val items = provider.provideCompletions("file:///test.smali", Position(3, line.length), line).items
            val edit = items.single().textEdit.left
            assertEquals("Lcom/example/MyClass;", edit.newText, line)
            assertEquals(line.indexOf("Lcom/ex"), edit.range.start.character, line)
            assertEquals(line.length, edit.range.end.character, line)
        }
        val finished = ".field public data:Lcom/example/MyClass;"
        assertTrue(provider.provideCompletions("file:///test.smali", Position(3, finished.length), finished).items.isEmpty(), "a finished type offers nothing")
    }

    @Test
    fun `members inherited from the workspace hierarchy complete after the arrow, own members first`() {
        val index = WorkspaceIndex()
        index.openSource("file:///Base.smali", ".class public Lcom/Base;\n.super Ljava/lang/Object;\n.field public baseField:I\n.method public baseMethod()V\n    .registers 1\n    return-void\n.end method\n.method private hidden()V\n    .registers 1\n    return-void\n.end method\n.method public shared()V\n    .registers 1\n    return-void\n.end method")
        index.openSource("file:///Child.smali", ".class public Lcom/Child;\n.super Lcom/Base;\n.method public shared()V\n    .registers 1\n    return-void\n.end method")
        val line = "    invoke-virtual {p0}, Lcom/Child;->"
        val items = CompletionProvider(index).provideCompletions("file:///Any.smali", Position(0, line.length), line).items
        assertEquals(listOf("shared()V" to "Lcom/Child;", "baseMethod()V" to "Lcom/Base;"), items.map { it.label to it.detail }, "the override hides Base.shared, the private method is not inherited")
        assertEquals(items.map { it.sortText }.sorted(), items.map { it.sortText }, "sort order keeps own members first")
    }

    @Test
    fun `class completion ranks shorter names first and keeps a stable order`() {
        val index = WorkspaceIndex()
        for (name in listOf("Lcom/app/ui/Zebra;", "Lcom/app/A;", "Lcom/app/B;", "Lcom/app/ui/Apple;")) index.openSource("file:///${name.hashCode()}.smali", ".class public $name\n.super Ljava/lang/Object;")
        val line = "    new-instance v0, Lcom/app"
        val items = CompletionProvider(index).provideCompletions("file:///Any.smali", Position(0, line.length), line).items
        assertEquals(listOf("com/app/A", "com/app/B", "com/app/ui/Apple", "com/app/ui/Zebra"), items.map { it.label })
        assertEquals(listOf("000", "001", "002", "003"), items.map { it.sortText })
    }

    // --- Context-aware completion ---

    private val util = """
        .class public Lcom/app/Util;
        .super Lcom/app/Base;
        .implements Lcom/app/Listener;

        .field public static COUNT:I
        .field public static NAME:Ljava/lang/String;
        .field public value:I
        .field public wide:J
        .field public flag:Z
        .field public ref:Ljava/lang/Object;
        .field private secretField:I

        .method public constructor <init>()V
            .registers 1
            return-void
        .end method

        .method public static go()V
            .registers 0
            return-void
        .end method

        .method public static go(I)V
            .registers 1
            return-void
        .end method

        .method public static two(IJ)V
            .registers 3
            return-void
        .end method

        .method public run()V
            .registers 1
            return-void
        .end method

        .method public run(I)V
            .registers 2
            return-void
        .end method

        .method private secret()V
            .registers 1
            return-void
        .end method
    """.trimIndent()

    private fun workspace(): WorkspaceIndex = WorkspaceIndex().apply {
        openSource("file:///Util.smali", util)
        openSource("file:///Base.smali", ".class public Lcom/app/Base;\n.super Ljava/lang/Object;\n\n.method public base()V\n    .registers 1\n    return-void\n.end method\n")
        openSource("file:///Listener.smali", ".class public interface abstract Lcom/app/Listener;\n.super Ljava/lang/Object;\n\n.method public abstract onEvent()V\n.end method\n")
        openSource("file:///Boom.smali", ".class public Lcom/app/Boom;\n.super Ljava/lang/RuntimeException;\n")
        openSource("file:///Shape.smali", ".class public abstract Lcom/app/Shape;\n.super Ljava/lang/Object;\n")
    }

    private fun labels(index: WorkspaceIndex, line: String, uri: String = "file:///Any.smali", at: Int = 0): List<String> =
        CompletionProvider(index).provideCompletions(uri, Position(at, line.length), line).items.map { it.label }

    @Test
    fun `a call offers the methods its opcode and register count can bind`() {
        val index = workspace()
        assertEquals(listOf("go()V"), labels(index, "    invoke-static {}, Lcom/app/Util;->"))
        assertEquals(listOf("go(I)V"), labels(index, "    invoke-static {v0}, Lcom/app/Util;->"))
        assertEquals(listOf("two(IJ)V"), labels(index, "    invoke-static {v0, v1, v2}, Lcom/app/Util;->"), "J takes two registers")
        assertEquals(emptyList(), labels(index, "    invoke-static {v0, v1}, Lcom/app/Util;->"))
        assertEquals(listOf("go()V", "go(I)V", "two(IJ)V"), labels(index, "    invoke-static Lcom/app/Util;->"), "no braces yet: every static method")
        assertEquals(listOf("run()V", "base()V", "onEvent()V"), labels(index, "    invoke-virtual {p0}, Lcom/app/Util;->"), "instance methods, own first then inherited, nothing private, no constructor")
        assertEquals(listOf("run(I)V"), labels(index, "    invoke-virtual {p0, v1}, Lcom/app/Util;->"))
        assertEquals(listOf("<init>()V", "secret()V"), labels(index, "    invoke-direct {p0}, Lcom/app/Util;->"), "the class's own constructors and private methods")
        assertEquals(listOf("onEvent()V"), labels(index, "    invoke-interface {p0}, Lcom/app/Listener;->"))
        assertEquals(listOf("base()V"), labels(index, "    invoke-super {p0}, Lcom/app/Base;->"))
        index.openSource("file:///Caller.smali", ".class public Lcom/app/Caller;\n.super Ljava/lang/Object;\n\n.method public call(I)V\n    .registers 3\n    nop\n    return-void\n.end method\n")
        assertEquals(listOf("run(I)V"), labels(index, "    invoke-virtual/range {p0 .. p1}, Lcom/app/Util;->", "file:///Caller.smali", 5), "a range counts through the frame")
        assertEquals(listOf("c()[B"), labels(WorkspaceIndex().apply { openSource("file:///G.smali", ".class public Lo/\u03b5\u03b5\u03b4;\n.super Ljava/lang/Object;\n\n.method public c()[B\n    .registers 1\n    return-void\n.end method\n") }, "    invoke-virtual {p3}, Lo/\u03b5\u03b5\u03b4;->"), "an obfuscated owner with non-ASCII letters")
    }

    @Test
    fun `a field access offers the fields its opcode moves`() {
        val index = workspace()
        assertEquals(listOf("COUNT:I"), labels(index, "    sget v0, Lcom/app/Util;->"))
        assertEquals(listOf("NAME:Ljava/lang/String;"), labels(index, "    sget-object v0, Lcom/app/Util;->"))
        assertEquals(listOf("value:I", "secretField:I"), labels(index, "    iget v0, p0, Lcom/app/Util;->"), "32-bit instance fields; the private one is the class's own")
        assertEquals(listOf("wide:J"), labels(index, "    iget-wide v0, p0, Lcom/app/Util;->"))
        assertEquals(listOf("flag:Z"), labels(index, "    iget-boolean v0, p0, Lcom/app/Util;->"))
        assertEquals(listOf("ref:Ljava/lang/Object;"), labels(index, "    iput-object v0, p0, Lcom/app/Util;->"))
        assertEquals(listOf("COUNT:I", "NAME:Ljava/lang/String;"), labels(index, "        .enum Lcom/app/Util;->"))
    }

    @Test
    fun `type operands offer the kind of class they can take`() {
        val index = workspace()
        fun classes(line: String) = CompletionProvider(index).provideCompletions("file:///Any.smali", Position(0, line.length), line).items.map { it.textEdit.left.newText }
        assertEquals(listOf("Lcom/app/Base;", "Lcom/app/Boom;", "Lcom/app/Util;"), classes("    new-instance v0, Lcom/app/"), "no interface, no abstract class")
        assertEquals(listOf("Lcom/app/Listener;"), classes(".implements Lcom/app/"))
        assertEquals(listOf("Lcom/app/Base;", "Lcom/app/Boom;", "Lcom/app/Util;", "Lcom/app/Shape;"), classes(".super Lcom/app/"))
        assertEquals(listOf("Lcom/app/Boom;"), classes("    .catch Lcom/app/"), "extends RuntimeException through the workspace")
        assertEquals(listOf("Lcom/app/Listener;"), classes("    invoke-interface {p0}, Lcom/app/"))
        assertEquals(5, classes("    check-cast v0, Lcom/app/").size)
    }

    @Test
    fun `a colon offers the method's labels`() {
        val index = WorkspaceIndex()
        index.openSource("file:///Flow.smali", ".class public Lcom/app/Flow;\n.super Ljava/lang/Object;\n\n.method public run(I)V\n    .registers 2\n    if-eqz p1, :cond_0\n    :try_start_0\n    nop\n    :try_end_0\n    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :cond_0\n    :cond_0\n    return-void\n.end method\n")
        assertEquals(listOf(":cond_0", ":try_end_0", ":try_start_0"), labels(index, "    goto :", "file:///Flow.smali", 7))
        assertEquals(listOf(":try_end_0", ":try_start_0"), labels(index, "    if-eqz p1, :t", "file:///Flow.smali", 7))
        assertEquals(listOf(":cond_0", ":try_end_0", ":try_start_0"), labels(index, "    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :", "file:///Flow.smali", 9))
        assertEquals(emptyList(), labels(index, "    goto :", "file:///Flow.smali", 1), "outside a method")
    }

    @Test
    fun `a register prefix offers the frame's registers with what they hold`() {
        val index = WorkspaceIndex()
        index.openSource("file:///Reg.smali", ".class public Lcom/app/Reg;\n.super Ljava/lang/Object;\n\n.method public run(Ljava/lang/String;J)V\n    .registers 6\n    const/4 v0, 0x0\n    return-void\n.end method\n")
        val provider = CompletionProvider(index)
        val items = provider.provideCompletions("file:///Reg.smali", Position(5, "    move-object v1, p".length), "    move-object v1, p").items
        assertEquals(listOf("p0", "p1", "p2", "p3"), items.map { it.label })
        assertEquals(listOf("this (com.app.Reg)", "java.lang.String", "long", "second half of long"), items.map { it.detail })
        assertEquals(listOf("v0", "v1"), labels(index, "    move-object v1, v", "file:///Reg.smali", 5))
        assertEquals(listOf("v0", "v1", "p0", "p1", "p2", "p3"), labels(index, "    invoke-virtual {", "file:///Reg.smali", 5), "every register right after the brace")
        assertEquals(emptyList(), labels(index, "    const/4 v0, ", "file:///Reg.smali", 5), "a literal may follow: nothing is offered")
    }

    @Test
    fun `a dot offers directives for where the caret is, the open block's end first`() {
        val index = WorkspaceIndex()
        index.openSource("file:///Dir.smali", ".class public Lcom/app/Dir;\n.super Ljava/lang/Object;\n\n.method public run()V\n    .registers 1\n    .annotation runtime Lcom/app/A;\n        value = 0x1\n    \n    return-void\n.end method\n\n")
        val provider = CompletionProvider(index)
        val inAnnotation = provider.provideCompletions("file:///Dir.smali", Position(7, 5), "    .").items
        assertEquals(".end annotation", inAnnotation.first().label)
        assertEquals(true, inAnnotation.first().preselect)
        assertTrue(inAnnotation.any { it.label == ".line" } && inAnnotation.none { it.label == ".field" }, inAnnotation.map { it.label }.toString())
        val atClassLevel = provider.provideCompletions("file:///Dir.smali", Position(10, 1), ".").items
        assertTrue(atClassLevel.any { it.label == ".method" } && atClassLevel.none { it.label == ".registers" }, atClassLevel.map { it.label }.toString())
        assertEquals(listOf("method"), labels(index, "    .end m", "file:///Dir.smali", 8))
        assertEquals(listOf("annotation", "method", "subannotation", "packed-switch", "sparse-switch", "array-data", "field", "param", "local"), labels(index, "    .end ", "file:///Dir.smali", 7), "the open annotation first")
        assertTrue(provider.provideCompletions("file:///Dir.smali", Position(2, 0), "").items.all { it.label.startsWith(".") }, "an empty line at class level: directives")
    }

    @Test
    fun `an opcode completes as a snippet of its operand shape when the client takes snippets`() {
        val provider = CompletionProvider(WorkspaceIndex())
        val plain = provider.provideCompletions("file:///Any.smali", Position(0, 13), "    invoke-vi").items.first { it.label == "invoke-virtual" }
        assertEquals("invoke-virtual", plain.textEdit.left.newText)
        assertTrue(plain.insertTextFormat != InsertTextFormat.Snippet)
        provider.snippetSupport = true
        val snippet = provider.provideCompletions("file:///Any.smali", Position(0, 13), "    invoke-vi").items.first { it.label == "invoke-virtual" }
        assertEquals(InsertTextFormat.Snippet, snippet.insertTextFormat)
        assertEquals("invoke-virtual {\${1:p0}}, \${2:Lpkg/Class;->method()V}", snippet.textEdit.left.newText)
        assertEquals("const/4 \${1:v0}, \${2:0x0}", provider.provideCompletions("file:///Any.smali", Position(0, 7), "const/4").items.first { it.label == "const/4" }.textEdit.left.newText)
        assertEquals("return-void", provider.provideCompletions("file:///Any.smali", Position(0, 11), "return-void").items.first { it.label == "return-void" }.textEdit.left.newText)
        assertEquals("if-eqz \${1:v0}, :\${2:label}", provider.provideCompletions("file:///Any.smali", Position(0, 6), "if-eqz").items.first { it.label == "if-eqz" }.textEdit.left.newText)
    }

    @Test
    fun `an empty line in a method offers every opcode, the move-result a call needs first`() {
        val index = WorkspaceIndex()
        index.openSource("file:///Mv.smali", ".class public Lcom/app/Mv;\n.super Ljava/lang/Object;\n\n.method public run()V\n    .registers 2\n    invoke-virtual {p0}, Lcom/app/Mv;->name()Ljava/lang/String;\n\n    invoke-virtual {p0}, Lcom/app/Mv;->run()V\n\n    return-void\n.end method\n")
        val provider = CompletionProvider(index)
        val afterCall = provider.provideCompletions("file:///Mv.smali", Position(6, 4), "    ").items
        assertEquals("move-result-object", afterCall.first().label)
        assertTrue(afterCall.first().preselect == true && afterCall.size > 200)
        val afterVoid = provider.provideCompletions("file:///Mv.smali", Position(8, 4), "    ").items
        assertTrue(afterVoid.size > 200 && afterVoid.none { it.preselect == true })
        assertEquals("move-result-object", provider.provideCompletions("file:///Mv.smali", Position(6, 7), "    mov").items.first().label)
    }
}
