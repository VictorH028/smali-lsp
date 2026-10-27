package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DiagnosticProvider
import xyz.surendrajat.smalilsp.shared.positionOf
import xyz.surendrajat.smalilsp.shared.text
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A syntax error underlines the offending token; an unresolved class is a Warning on its token
 * (Information for a field type) unless it is indexed, an SDK class, a primitive or an array of
 * one of those.
 */
class DiagnosticProviderTest {

    private val index = WorkspaceIndex()
    private val parser = SmaliParser(index.strings)
    private val provider = DiagnosticProvider(parser, index)
    private val uri = "file:///Test.smali"

    private fun diagnosticsOf(source: String): List<Diagnostic> = provider.computeDiagnostics(uri, source)

    private fun syntaxErrors(diagnostics: List<Diagnostic>) = diagnostics.filter { it.code.left == "syntax-error" }

    /** Indexes [source] under [uri] as the workspace scan would, without opening it. */
    private fun scan(uri: String, source: String) {
        index.indexFile(parser.parse(uri, source) ?: error("failed to parse $uri"))
    }

    /** The range of [needle] on the first line of [source] containing [marker]. */
    private fun rangeOf(source: String, marker: String, needle: String): Range =
        Range(source.positionOf(marker, needle), source.positionOf(marker, needle, needle.length))

    /** The undefined-class diagnostic the provider must emit for [needle] on the first line containing [marker]. */
    private fun undefinedClass(
        source: String, marker: String, needle: String, message: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.Warning
    ) = Diagnostic(rangeOf(source, marker, needle), message, severity, "smali-lsp", "undefined-class")

    @Test
    fun `method handles and call sites name a member without calling it, so their kind is not checked`() {
        scan("file:///Handles.smali", ".class public Lcom/example/Handles;\n.super Ljava/lang/Object;\n\n.field public static X:I\n\n.method public static bar()V\n    .registers 1\n    return-void\n.end method\n\n.method public baz()V\n    .registers 1\n    return-void\n.end method")
        val source = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;

            .method public foo()V
                .registers 2
                const-method-handle v0, invoke-static@Lcom/example/Handles;->bar()V
                const-method-handle v0, invoke-instance@Lcom/example/Handles;->baz()V
                const-method-handle v1, static-get@Lcom/example/Handles;->X:I
                invoke-custom {v0}, call("run", ()V)@Lcom/example/Handles;->bar()V
                return-void
            .end method
        """.trimIndent()
        assertEquals(emptyList<String>(), diagnosticsOf(source).map { it.message.left })
    }

    @Test
    fun `a member no class on the owner's workspace hierarchy declares is a warning with the closest member as its fix`() {
        scan("file:///Base.smali", ".class public Lcom/example/Base;\n.super Ljava/lang/Object;\n\n.field protected count:I\n\n.method public reset()V\n    .registers 1\n    return-void\n.end method")
        scan("file:///Derived.smali", ".class public Lcom/example/Derived;\n.super Lcom/example/Base;\n\n.method public tick(I)V\n    .registers 2\n    return-void\n.end method")
        val source = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;

            .method public foo(Lcom/example/Derived;)V
                .registers 3
                invoke-virtual {p1}, Lcom/example/Derived;->reset()V
                invoke-virtual {p1}, Lcom/example/Derived;->hashCode()I
                invoke-virtual {p1, v0}, Lcom/example/Derived;->tick(J)V
                invoke-virtual {p1}, Lcom/example/Derived;->rest()V
                iget v0, p1, Lcom/example/Derived;->count:I
                iget v0, p1, Lcom/example/Derived;->count:J
                iget v0, p1, Lcom/example/Derived;->cont:I
                return-void
            .end method
        """.trimIndent()
        val diagnostics = diagnosticsOf(source).filter { it.code.left == "undefined-member" }
        assertEquals(listOf(
            "Method tick(J)V is not declared by Lcom/example/Derived; or a supertype in the workspace; the closest is tick(I)V",
            "Method rest()V is not declared by Lcom/example/Derived; or a supertype in the workspace; the closest is reset()V",
            "Field count:J is not declared by Lcom/example/Derived; or a supertype in the workspace; the closest is count:I",
            "Field cont:I is not declared by Lcom/example/Derived; or a supertype in the workspace; the closest is count:I",
        ), diagnostics.map { it.message.left })
        assertEquals(rangeOf(source, "tick(J)V", "tick(J)V"), diagnostics[0].range)
        assertEquals(mapOf("fix" to "member", "to" to "tick(I)V"), diagnostics[0].data)
        assertTrue(diagnostics.all { it.severity == DiagnosticSeverity.Warning })
    }

    @Test
    fun `a member is not questioned when a supertype or the owner is outside the workspace, nor in the file's own new code`() {
        scan("file:///Screen.smali", ".class public Lcom/example/Screen;\n.super Landroid/app/Activity;")
        scan("file:///Listener.smali", ".class public interface abstract Lcom/example/Listener;\n.super Ljava/lang/Object;\n.implements Ljava/lang/Runnable;")
        val source = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;

            .method public foo(Lcom/example/Screen;Lcom/example/Listener;)V
                .registers 4
                invoke-virtual {p1}, Lcom/example/Screen;->findViewById(I)Landroid/view/View;
                invoke-interface {p2}, Lcom/example/Listener;->run()V
                invoke-virtual {p1}, Ljava/lang/Object;->whatever()V
                invoke-virtual {p0}, Lcom/example/Test;->added()V
                sget v0, Lcom/example/Screen;->FLAG:I
                return-void
            .end method

            .method public added()V
                .registers 1
                return-void
            .end method
        """.trimIndent()
        assertEquals(emptyList<String>(), diagnosticsOf(source).filter { it.code.left == "undefined-member" }.map { it.message.left })
    }

    @Test
    fun `a class outside every workspace package is not reported`() {
        // The app references classes it does not ship: platform ones outside the SDK list and optional libraries behind runtime checks
        val source = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            .implements Lorg/xmlpull/v1/XmlPullParser;

            .field private provider:Lorg/conscrypt/Conscrypt;

            .method public foo()V
                invoke-static {}, Lorg/conscrypt/Conscrypt;->isAvailable()Z
                new-instance v0, Lcom/google/firebase/analytics/connector/AnalyticsConnector;
                check-cast v0, [Llibcore/io/Memory;
                invoke-static {}, Lcom/example/Missing;->go()V
                return-void
            .end method
        """.trimIndent()
        assertEquals(
            listOf("Class 'Lcom/example/Missing;' not found in workspace or SDK"),
            diagnosticsOf(source).map { it.text },
            "only the name in the workspace's own package can be a mistake"
        )
    }

    @Test
    fun `a valid file has no diagnostics`() {
        val source = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;

            .method public foo()V
                return-void
            .end method
        """.trimIndent()
        assertEquals(emptyList(), diagnosticsOf(source))
    }

    @Test
    fun `an undefined superclass is a warning on its token`() {
        val source = """
            .class public Lcom/example/Test;
            .super Lcom/example/NonExistent;
        """.trimIndent()
        assertEquals(
            listOf(undefinedClass(source, ".super", "Lcom/example/NonExistent;", "Superclass 'Lcom/example/NonExistent;' not found in workspace or SDK")),
            diagnosticsOf(source)
        )
    }

    @Test
    fun `an undefined interface is a warning on its token`() {
        val source = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;
            .implements Lcom/example/NonExistentInterface;
        """.trimIndent()
        assertEquals(
            listOf(undefinedClass(source, ".implements", "Lcom/example/NonExistentInterface;", "Interface 'Lcom/example/NonExistentInterface;' not found in workspace or SDK")),
            diagnosticsOf(source)
        )
    }

    @Test
    fun `an undefined invoke owner is a warning on its token and an SDK owner is not flagged`() {
        val source = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;

            .method public foo()V
                invoke-virtual {v0}, Ljava/lang/String;->length()I
                invoke-virtual {v0}, Lcom/example/NonExistent;->bar()V
                return-void
            .end method
        """.trimIndent()
        assertEquals(
            listOf(undefinedClass(source, "->bar()V", "Lcom/example/NonExistent;", "Class 'Lcom/example/NonExistent;' not found in workspace or SDK")),
            diagnosticsOf(source)
        )
    }

    @Test
    fun `an undefined field type is information on the type token`() {
        val source = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;

            .field public myField:Lcom/example/CustomType;
        """.trimIndent()
        assertEquals(
            listOf(undefinedClass(source, "myField:", "Lcom/example/CustomType;", "Type 'Lcom/example/CustomType;' not found in workspace or SDK", DiagnosticSeverity.Information)),
            diagnosticsOf(source)
        )
    }

    @Test
    fun `an array field of an undefined class is reported on the whole type and named without the brackets`() {
        val source = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;

            .field public customArray:[Lcom/example/CustomType;
        """.trimIndent()
        assertEquals(
            listOf(undefinedClass(source, "customArray:", "[Lcom/example/CustomType;", "Type 'Lcom/example/CustomType;' not found in workspace or SDK", DiagnosticSeverity.Information)),
            diagnosticsOf(source)
        )
    }

    @Test
    fun `every undefined reference is reported once at its own severity`() {
        val source = """
            .class public Lcom/example/Test;
            .super Lcom/example/Missing1;
            .implements Lcom/example/Missing2;

            .field public field1:Lcom/example/Missing3;

            .method public foo()V
                invoke-virtual {v0}, Lcom/example/Missing4;->bar()V
                invoke-static {}, Lcom/example/Missing5;->baz()I
                return-void
            .end method
        """.trimIndent()
        // Class header first, then instructions, then fields.
        assertEquals(
            listOf(
                DiagnosticSeverity.Warning to "Superclass 'Lcom/example/Missing1;' not found in workspace or SDK",
                DiagnosticSeverity.Warning to "Interface 'Lcom/example/Missing2;' not found in workspace or SDK",
                DiagnosticSeverity.Warning to "Class 'Lcom/example/Missing4;' not found in workspace or SDK",
                DiagnosticSeverity.Warning to "Class 'Lcom/example/Missing5;' not found in workspace or SDK",
                DiagnosticSeverity.Information to "Type 'Lcom/example/Missing3;' not found in workspace or SDK",
            ),
            diagnosticsOf(source).map { it.severity to it.text }
        )
    }

    @Test
    fun `primitive and SDK array types are not flagged in fields or check-casts`() {
        val source = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;

            .field public intField:I
            .field public longField:J
            .field public booleanField:Z
            .field public byteField:B
            .field public intArray:[I
            .field public stringArray:[Ljava/lang/String;

            .method public test()V
                check-cast p1, [I
                check-cast p1, [Ljava/lang/Object;
                check-cast p1, [[I
                check-cast p2, [[[Ljava/lang/String;
                return-void
            .end method
        """.trimIndent()
        assertEquals(emptyList(), diagnosticsOf(source))
    }

    @Test
    fun `an indexed workspace class is not flagged and neither is an array of it`() {
        scan("file:///com/example/Foo.smali", ".class public Lcom/example/Foo;\n.super Ljava/lang/Object;\n\n.method public bar()V\n    .registers 1\n    return-void\n.end method")
        val source = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;

            .method public cast(Ljava/lang/Object;)V
                invoke-virtual {v0}, Lcom/example/Foo;->bar()V
                check-cast p1, [Lcom/example/Foo;
                return-void
            .end method
        """.trimIndent()
        assertEquals(emptyList(), diagnosticsOf(source), "the index stores Lcom/example/Foo; and the operand keeps its bracket")
    }

    @Test
    fun `check-cast to an undefined class or an array of one is a warning on the type token`() {
        val source = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;

            .method public cast(Ljava/lang/Object;)V
                check-cast p1, Lcom/example/UndefinedClass;
                check-cast p1, [Lcom/example/Missing;
                return-void
            .end method
        """.trimIndent()
        assertEquals(
            listOf(
                undefinedClass(source, "Lcom/example/UndefinedClass;", "Lcom/example/UndefinedClass;", "Class 'Lcom/example/UndefinedClass;' not found in workspace or SDK"),
                undefinedClass(source, "[Lcom/example/Missing;", "[Lcom/example/Missing;", "Class '[Lcom/example/Missing;' not found in workspace or SDK"),
            ),
            diagnosticsOf(source)
        )
    }

    @Test
    fun `every undefined-class diagnostic covers exactly the class token`() {
        val source = """
            .class public Lcom/app/Foo;
            .super Lcom/app/Base;
            .implements Lcom/app/Iface;

            .field private data:Lcom/app/Data;

            .method public run()V
                .registers 2
                invoke-static {}, Lcom/app/Util;->go()V
                iget v0, p0, Lcom/app/Holder;->x:I
                new-instance v0, Lcom/app/Thing;
                return-void
            .end method
        """.trimIndent()
        val byClass = diagnosticsOf(source).associate { d ->
            val name = Regex("'(L[^']+;)'").find(d.text)?.groupValues?.get(1) ?: error("no class in ${d.text}")
            name to d.range
        }
        assertEquals(
            mapOf(
                "Lcom/app/Base;" to rangeOf(source, ".super", "Lcom/app/Base;"),
                "Lcom/app/Iface;" to rangeOf(source, ".implements", "Lcom/app/Iface;"),
                "Lcom/app/Data;" to rangeOf(source, "data:", "Lcom/app/Data;"),
                "Lcom/app/Util;" to rangeOf(source, "invoke-static", "Lcom/app/Util;"),
                "Lcom/app/Holder;" to rangeOf(source, "iget", "Lcom/app/Holder;"),
                "Lcom/app/Thing;" to rangeOf(source, "new-instance", "Lcom/app/Thing;"),
            ),
            byClass
        )
    }

    @Test
    fun `a syntax error underlines the offending token`() {
        val line = "    bogus-opcode v0"
        val content = ".class public Lcom/example/Broken;\n.super Ljava/lang/Object;\n.method public run()V\n    .registers 1\n$line\n    return-void\n.end method"
        val error = syntaxErrors(provider.computeDiagnostics("file:///Broken.smali", content)).first()
        assertEquals(4, error.range.start.line)
        assertEquals(line.indexOf("bogus"), error.range.start.character)
        assertEquals(line.indexOf("bogus") + "bogus-opcode".length, error.range.end.character, "the token, not a fixed width: ${error.message}")
    }

    @Test
    fun `a syntax error reported on line zero is clamped to the first line`() {
        val parseResult = SmaliParser.ParseResult(smaliFile = null, syntaxErrors = listOf(SmaliParser.SyntaxError(0, 0, "Parse error")))
        val diagnostic = provider.computeDiagnosticsFromParseResult("file:///Broken.smali", parseResult).single()
        assertEquals(Range(Position(0, 0), Position(0, 1)), diagnostic.range, "line 0 would become -1 without the clamp")
        assertEquals("syntax-error", diagnostic.code.left)
    }

    @Test
    fun `a method without its end directive is a one-character error at end of file`() {
        val source = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;

            .method public test()V
                return-void
        """.trimIndent()
        val errors = syntaxErrors(diagnosticsOf(source))
        assertTrue(errors.isNotEmpty(), "a method that never ends is a syntax error")
        val atEof = errors.last()
        assertEquals(atEof.range.start.line, atEof.range.end.line)
        assertEquals(1, atEof.range.end.character - atEof.range.start.character, "EOF has no token text, so the range is one character wide: ${atEof.text}")
    }

    @Test
    fun `a class directive without a name produces only syntax errors`() {
        val source = """
            .class public
            .super Ljava/lang/Object;
        """.trimIndent()
        val syntaxOnly = provider.computeSyntaxDiagnosticsFromParseResult(parser.parseWithErrors(uri, source))
        assertTrue(syntaxOnly.isNotEmpty(), "a missing class name is a syntax error")
        assertEquals(setOf("syntax-error"), syntaxOnly.map { it.code.left }.toSet())
        assertEquals(syntaxOnly, diagnosticsOf(source), "nothing semantic to add: the superclass is an SDK class")
    }

    @Test
    fun `error recovery reports every syntax error in the file`() {
        val source = """
            .class
            .super
            .method
        """.trimIndent()
        val errors = syntaxErrors(diagnosticsOf(source))
        assertTrue(errors.size >= 2, "three broken directives, not just the first: ${errors.map { it.text }}")
    }

    @Test
    fun `garbage after the class directive is a syntax error`() {
        val source = """
            .class public Lcom/example/Test;
            this is not valid smali code at all
            it should not crash the diagnostic provider
        """.trimIndent()
        assertTrue(syntaxErrors(diagnosticsOf(source)).isNotEmpty(), "garbage after the class directive must produce a syntax-error diagnostic")
    }

    @Test
    fun `syntax-only diagnostics leave out the undefined-class warnings`() {
        val source = """
            .class public Lcom/example/Test;
            .super Lcom/example/NonExistent;

            .method public foo()V
                invoke-virtual {v0}, Lcom/example/Missing;->bar()V
                return-void
            .end method
        """.trimIndent()
        val parseResult = parser.parseWithErrors(uri, source)
        assertEquals(emptyList(), provider.computeSyntaxDiagnosticsFromParseResult(parseResult), "the file is syntactically valid")

        val full = provider.computeDiagnosticsFromParseResult(uri, parseResult)
        assertEquals(
            listOf(
                "Superclass 'Lcom/example/NonExistent;' not found in workspace or SDK",
                "Class 'Lcom/example/Missing;' not found in workspace or SDK",
            ),
            full.map { it.text }
        )
        assertEquals(setOf("undefined-class"), full.map { it.code.left }.toSet())
        assertEquals(full, diagnosticsOf(source), "computeDiagnostics parses and reports the same")
    }

    @Test
    fun `a large valid file has no diagnostics`() {
        val methods = (1..1000).joinToString("\n") { i ->
            """
            .method public method$i()V
                return-void
            .end method
            """.trimIndent()
        }
        val source = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;

            $methods
        """.trimIndent()
        assertEquals(emptyList(), diagnosticsOf(source))
    }

    @Test
    fun `a jump to an undefined label and a label defined twice are errors, reported before indexing too`() {
        val source = """
            .class public Lcom/example/Labels;
            .super Ljava/lang/Object;
            .method public run(I)V
                .registers 2
                if-eqz p1, :nowhere
                :cond_0
                goto :cond_0
                :cond_0
                return-void
            .end method
        """.trimIndent()
        val diagnostics = provider.computeDiagnostics("file:///Labels.smali", source)
        assertEquals(listOf("undefined-label", "duplicate-label"), diagnostics.map { it.code.left })
        assertEquals(source.positionOf("if-eqz", ":nowhere"), diagnostics[0].range.start)
        assertEquals("Label ':nowhere' is not defined in this method", diagnostics[0].text)
        assertEquals(7, diagnostics[1].range.start.line, "the second definition is the duplicate")
        assertEquals(diagnostics, provider.computeSyntaxDiagnosticsFromParseResult(parser.parseWithErrors("file:///Labels.smali", source)), "file-local checks need no index")
    }

    @Test
    fun `labels used by catch blocks, switch tables and array data are defined labels`() {
        val source = """
            .class public Lcom/example/Tables;
            .super Ljava/lang/Object;
            .method public run(I)V
                .registers 2
                :try_start_0
                packed-switch p1, :pswitch_data_0
                fill-array-data p1, :array_0
                :try_end_0
                .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0
                :catch_0
                :pswitch_0
                return-void
                :pswitch_data_0
                .packed-switch 0x0
                    :pswitch_0
                .end packed-switch
                :array_0
                .array-data 4
                    0x1
                .end array-data
            .end method
        """.trimIndent()
        assertEquals(emptyList<String>(), provider.computeDiagnostics("file:///Tables.smali", source).map { it.text })
    }

    @Test
    fun `a call or field access of the wrong kind for the member the workspace declares`() {
        scan("file:///Util.smali", """
            .class public Lcom/example/Util;
            .super Ljava/lang/Object;

            .field public static COUNT:I

            .field public name:Ljava/lang/String;

            .method public static go()V
                .registers 0
                return-void
            .end method

            .method public run()V
                .registers 1
                return-void
            .end method

            .method private secret()V
                .registers 1
                return-void
            .end method
        """.trimIndent())
        val source = """
            .class public Lcom/example/Test;
            .super Ljava/lang/Object;

            .method public foo(Lcom/example/Util;)V
                .registers 3
                invoke-virtual {p1}, Lcom/example/Util;->go()V
                invoke-static {}, Lcom/example/Util;->go()V
                invoke-static {}, Lcom/example/Util;->run()V
                invoke-static {}, Lcom/example/Util;->secret()V
                invoke-virtual/range {p1 .. p1}, Lcom/example/Util;->go()V
                sget v0, Lcom/example/Util;->COUNT:I
                iget v0, p1, Lcom/example/Util;->COUNT:I
                sget-object v0, Lcom/example/Util;->name:Ljava/lang/String;
                iput-object v0, p1, Lcom/example/Util;->name:Ljava/lang/String;
                invoke-static {}, Lcom/example/Unknown;->go()V
                invoke-virtual {p1}, Ljava/lang/Object;->hashCode()I
                return-void
            .end method
        """.trimIndent()
        val diagnostics = diagnosticsOf(source)
        assertEquals(listOf(
            "invoke-virtual calls go()V, a static method; use invoke-static",
            "invoke-static calls run()V, an instance method; use invoke-virtual",
            "invoke-static calls secret()V, an instance method; use invoke-direct",
            "invoke-virtual/range calls go()V, a static method; use invoke-static/range",
            "iget on COUNT, a static field; use sget",
            "sget-object on name, an instance field; use iget-object",
            "Class 'Lcom/example/Unknown;' not found in workspace or SDK"
        ), diagnostics.map { it.text }, "a member outside the workspace cannot be judged")
        assertEquals(rangeOf(source, "invoke-virtual {p1}, Lcom/example/Util;->go()V", "invoke-virtual"), diagnostics[0].range, "the opcode is underlined")
        assertEquals(setOf(DiagnosticSeverity.Error), diagnostics.dropLast(1).map { it.severity }.toSet())
    }
}
