package xyz.surendrajat.smalilsp.unit.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.core.Span
import xyz.surendrajat.smalilsp.parser.SmaliParser

/** `ILjava/lang/String;` is one lexer token; the class inside it is still a type reference. */
class CompoundTypeRefTest {

    @Test
    fun `class names inside compound method-arg tokens are type references`() {
        val source = """
            .class public Lcom/Inner;
            .super Ljava/lang/Object;

            .annotation system Ldalvik/annotation/EnclosingMethod;
                value = Lcom/Outer;->run(ILjava/lang/String;ZLcom/Arg;)V
            .end annotation
        """.trimIndent()
        val file = SmaliParser().parse("file:///Inner.smali", source)!!
        val line = source.lines().indexOfFirst { it.contains("value =") }
        val text = source.lines()[line]
        fun at(name: String) = Span(line, text.indexOf(name), text.indexOf(name) + name.length)
        val refs = file.typeRefs.map { it.className to it.span }
        assertEquals(listOf(
            "Ldalvik/annotation/EnclosingMethod;" to Span(line - 1, ".annotation system ".length, ".annotation system Ldalvik/annotation/EnclosingMethod;".length),
            "Lcom/Outer;" to at("Lcom/Outer;"),
            "Ljava/lang/String;" to at("Ljava/lang/String;"),
            "Lcom/Arg;" to at("Lcom/Arg;")
        ), refs)
    }
}
