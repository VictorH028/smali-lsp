package xyz.surendrajat.smalilsp.unit.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import xyz.surendrajat.smalilsp.core.FieldAccessInstruction
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.parser.SmaliParser

/**
 * `invoke-polymorphic`, `invoke-custom`, `const-method-handle` and `const-method-type`
 * (API 26+) used to be opcode-only stub rules: any operand produced a syntax error and
 * the rest of the method body was lost to error recovery.
 */
class InvokeCustomAndMethodHandleTest {

    private val parser = SmaliParser()

    private fun parseMethodBody(body: String): SmaliFile {
        val content = """
            .class public final Lcom/example/Indy;
            .super Ljava/lang/Object;

            .method public static run(Ljava/lang/invoke/MethodHandle;)V
                .registers 4
                ${body.trimIndent().prependIndent("                ").trimStart()}
                :end
                return-void
            .end method
        """.trimIndent()
        val result = parser.parseWithErrors("file:///Indy.smali", content)
        assertEquals(emptyList<SmaliParser.SyntaxError>(), result.syntaxErrors, "unexpected syntax errors")
        val file = result.smaliFile!!
        // The label after the instruction proves the method body was parsed to the end.
        assertEquals(setOf("end"), file.methods.single().labels.keys)
        return file
    }

    private fun invokes(file: SmaliFile) = file.methods.single().instructions.filterIsInstance<InvokeInstruction>()

    @Test
    fun `invoke-polymorphic carries the method reference and a call-site prototype`() {
        val file = parseMethodBody("""
            const-string v1, "x"
            invoke-polymorphic {p0, v1}, Ljava/lang/invoke/MethodHandle;->invoke([Ljava/lang/Object;)Ljava/lang/Object;, (Ljava/lang/String;)Ljava/lang/String;
            move-result-object v2
            invoke-polymorphic/range {p0 .. v2}, Ljava/lang/invoke/MethodHandle;->invokeExact([Ljava/lang/Object;)Ljava/lang/Object;, (Ljava/lang/String;Ljava/lang/String;)V
        """)
        val calls = invokes(file)
        assertEquals(listOf("invoke-polymorphic", "invoke-polymorphic/range"), calls.map { it.opcode })
        assertEquals(listOf("invoke", "invokeExact"), calls.map { it.methodName })
        assertEquals("Ljava/lang/invoke/MethodHandle;", calls[0].className)
        assertEquals("([Ljava/lang/Object;)Ljava/lang/Object;", calls[0].descriptor)
    }

    @Test
    fun `invoke-custom call site with extra arguments resolves to the bootstrap method`() {
        val file = parseMethodBody("""
            invoke-custom {p0, v1}, call_site_0("runDynamic", (Ljava/lang/invoke/MethodHandle;I)Ljava/lang/String;, 0x7b, "extra", Lcom/example/Indy;, invoke-static@Lcom/example/Indy;->target(I)V, (I)V, null, true, 1.5f, .enum Lcom/example/Kind;->A:Lcom/example/Kind;)@Lcom/example/Bsm;->bootstrap(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;
            move-result-object v2
            invoke-custom/range {p0 .. v2}, call_site_1("apply", ()V)@Ljava/lang/invoke/LambdaMetafactory;->metafactory(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;
        """)
        val calls = invokes(file)
        assertEquals(listOf("invoke-custom", "invoke-custom/range"), calls.map { it.opcode })
        assertEquals("Lcom/example/Bsm;", calls[0].className)
        assertEquals("bootstrap", calls[0].methodName)
        assertEquals("Ljava/lang/invoke/LambdaMetafactory;", calls[1].className)
        assertEquals("metafactory", calls[1].methodName)
    }

    @ParameterizedTest
    @ValueSource(strings = ["invoke-static", "invoke-instance", "invoke-constructor", "invoke-direct", "invoke-interface"])
    fun `const-method-handle for a method kind captures the method reference`(kind: String) {
        val file = parseMethodBody("""
            const-method-handle v0, $kind@Lcom/example/Indy;->target(I)V
        """)
        val call = invokes(file).single()
        assertEquals("const-method-handle", call.opcode)
        assertEquals("Lcom/example/Indy;", call.className)
        assertEquals("target", call.methodName)
        assertEquals("(I)V", call.descriptor)
    }

    @ParameterizedTest
    @ValueSource(strings = ["static-get", "static-put", "instance-get", "instance-put"])
    fun `const-method-handle for a field kind captures the field reference`(kind: String) {
        val file = parseMethodBody("""
            const-method-handle v0, $kind@Lcom/example/Indy;->counter:I
        """)
        val access = file.methods.single().instructions.filterIsInstance<FieldAccessInstruction>().single()
        assertEquals("const-method-handle", access.opcode)
        assertEquals("Lcom/example/Indy;", access.className)
        assertEquals("counter", access.fieldName)
        assertEquals("I", access.fieldType)
    }

    @Test
    fun `const-method-type takes a bare prototype`() {
        val file = parseMethodBody("""
            const-method-type v0, (ILjava/lang/String;[J)Ljava/lang/Object;
            const-method-type v1, ()V
        """)
        assertEquals(emptyList<InvokeInstruction>(), invokes(file))
    }
}
