package xyz.surendrajat.smalilsp.unit.providers

import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.core.ConstStringInstruction
import xyz.surendrajat.smalilsp.core.FieldAccessInstruction
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.core.JumpInstruction
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.core.TypeInstruction
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.SmaliFormatter
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The formatter gives any smali text baksmali's layout without touching its meaning, and leaves baksmali's own output alone. */
class SmaliFormatterTest {

    private val canonical = """
        .class public final Lcom/app/Fmt;
        .super Ljava/lang/Object;
        .source "Fmt.java"

        # interfaces
        .implements Ljava/lang/Runnable;


        # annotations
        .annotation system Ldalvik/annotation/MemberClasses;
            value = {
                Lcom/app/Fmt${'$'}Inner;,
                Lcom/app/Fmt${'$'}Other;
            }
        .end annotation

        .annotation system Ldalvik/annotation/AnnotationDefault;
            value = .subannotation Lcom/app/Fmt;
                analyze = {}
                converter = Lc/g;
                describeContents = 0x0
            .end subannotation
        .end annotation


        # static fields
        .field private static final CREATOR:Landroid/os/Parcelable${'$'}Creator;
            .annotation system Ldalvik/annotation/Signature;
                value = {
                    "Landroid/os/Parcelable${'$'}Creator<",
                    "Lcom/app/Fmt;",
                    ">;"
                }
            .end annotation
        .end field

        .field private count:I = 0x2a


        # direct methods
        .method public constructor <init>(Ljava/lang/String;J)V
            .locals 3
            .param p1, "text"    # Ljava/lang/String;
            .param p2    # J
                .annotation build Landroidx/annotation/NonNull;
                .end annotation
            .end param
            .annotation system Ldalvik/annotation/Signature;
                value = {
                    "(",
                    "Ljava/lang/String;J)V"
                }
            .end annotation

            .line 12
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V

            const-string v0, "hi, # there  {x}"

            const-wide/high16 v1, 0x3ff0000000000000L    # 1.0

            const/16 v0, 'a'

            .local v0, "c":C
            if-eqz p1, :cond_0

            invoke-virtual/range {p0 .. p2}, Lcom/app/Fmt;->run(Ljava/lang/String;J)V

            :cond_0
            packed-switch v0, :pswitch_data_0

            :try_start_0
            iget v0, p0, Lcom/app/Fmt;->count:I
            :try_end_0
            .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0
            .catchall {:try_start_0 .. :try_end_0} :catchall_0

            .end local v0    # "c":C
            :catch_0
            :catchall_0
            return-void

            nop

            :pswitch_data_0
            .packed-switch 0x0
                :cond_0
                :catch_0
            .end packed-switch

            :sswitch_data_0
            .sparse-switch
                0x1 -> :cond_0
                0x1000 -> :catch_0
            .end sparse-switch

            :array_0
            .array-data 4
                0x1
                -0x7fffffff    # Integer.MIN_VALUE + 1
            .end array-data
        .end method
    """.trimIndent() + "\n"

    @Test
    fun `baksmali output is left exactly as it is, and formatting is idempotent`() {
        assertEquals(canonical, SmaliFormatter.format(canonical))
        assertEquals(emptyList(), SmaliFormatter.edits(canonical))
    }

    @Test
    fun `indentation, spacing and comment gaps are restored, strings and blank lines untouched`() {
        val mangled = canonical
            .replace("    .locals 3", "\t.locals   3")
            .replace("            invoke-direct {p0}, Ljava/lang/Object;-><init>()V", "invoke-direct { p0 } ,Ljava/lang/Object;-><init>()V   ")
            .replace("    const-string v0, \"hi, # there  {x}\"", "const-string v0,\"hi, # there  {x}\"")
            .replace("    const-wide/high16 v1, 0x3ff0000000000000L    # 1.0", "  const-wide/high16 v1 , 0x3ff0000000000000L # 1.0")
            .replace("    invoke-virtual/range {p0 .. p2}, Lcom/app/Fmt;->run(Ljava/lang/String;J)V", "invoke-virtual/range {p0..p2},Lcom/app/Fmt;->run(Ljava/lang/String;J)V")
            .replace("        0x1 -> :cond_0", "0x1 -> :cond_0")
            .replace("            \"Landroid/os/Parcelable\$Creator<\",", "\"Landroid/os/Parcelable\$Creator<\" ,")
            .replace("        value = .subannotation Lcom/app/Fmt;", "value=.subannotation Lcom/app/Fmt;")
            .replace("        analyze = {}", "analyze={ }")
            .replace("    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0", ".catch Ljava/lang/Exception; { :try_start_0 .. :try_end_0 }:catch_0")
            .replace("    .param p1, \"text\"    # Ljava/lang/String;", ".param p1,\"text\" # Ljava/lang/String;")
            .replace("    :cond_0\n    packed-switch", ":cond_0\n            packed-switch")
            .replace("\n\n    .line 12", "\n\n\n    .line 12")
        val formatted = SmaliFormatter.format(mangled)
        assertEquals(canonical.replace("\n\n    .line 12", "\n\n\n    .line 12"), formatted, "every line back in place; the extra blank line stays, blank lines are the author's")
        assertEquals(formatted, SmaliFormatter.format(formatted))
        assertEquals(model(canonical), model(formatted), "the parser reads the same program")
    }

    @Test
    fun `edits cover only the lines that change, within the range asked for`() {
        val text = canonical.replace("    .locals 3", "  .locals 3").replace("    return-void", "return-void")
        val all = SmaliFormatter.edits(text)
        assertEquals(2, all.size)
        assertEquals("    .locals 3", all[0].newText)
        assertEquals(0, all[0].range.start.character); assertEquals("  .locals 3".length, all[0].range.end.character)
        val returnLine = text.lines().indexOfFirst { it == "return-void" }
        assertEquals(listOf(returnLine), SmaliFormatter.edits(text, returnLine, returnLine).map { it.range.start.line })
        assertEquals("    return-void", SmaliFormatter.edits(text, returnLine, returnLine)[0].newText)
    }

    @Test
    fun `windows line endings are kept and unclosed blocks do not throw`() {
        val crlf = ".class public Lcom/A;\r\n.super Ljava/lang/Object;\r\n.method public run()V\r\n.registers 1\r\nreturn-void\r\n.end method\r\n"
        assertEquals(".class public Lcom/A;\r\n.super Ljava/lang/Object;\r\n.method public run()V\r\n    .registers 1\r\n    return-void\r\n.end method\r\n", SmaliFormatter.format(crlf))
        val broken = ".class public Lcom/B;\n.method public run()V\n.annotation runtime Lx;\nvalue = {\n\"a\"\n.end method\n.end annotation\n}\n.method public other()V\nreturn-void\n.end method\n"
        val formatted = SmaliFormatter.format(broken)
        assertEquals(formatted, SmaliFormatter.format(formatted))
        assertEquals(".method public other()V\n    return-void\n.end method\n", formatted.substringAfter("}\n"), "after the stray closers the depth is back at the class level")
    }

    /** Everything the parser reads except positions. */
    private fun model(text: String): String {
        val result = SmaliParser().parseWithErrors("file:///Fmt.smali", text)
        val file: SmaliFile = assertNotNull(result.smaliFile)
        return buildString {
            append(result.syntaxErrors.map { it.message }).append('\n')
            with(file.classDefinition) { append(name).append(modifiers).append(superClass).append(interfaces).append('\n') }
            for (f in file.fields) append(f.name).append(':').append(f.type).append(f.modifiers).append('\n')
            for (m in file.methods) {
                append(m.name).append(m.descriptor).append(m.modifiers).append(m.parameters.map { it.type }).append(m.returnType).append('\n')
                append(m.labels.keys.sorted()).append(m.labelRefs.map { it.name }).append('\n')
                for (i in m.instructions) append(when (i) {
                    is InvokeInstruction -> "${i.opcode} ${i.className}->${i.methodName}${i.descriptor}"
                    is FieldAccessInstruction -> "${i.opcode} ${i.className}->${i.fieldName}:${i.fieldType}"
                    is TypeInstruction -> "${i.opcode} ${i.className}"
                    is JumpInstruction -> "${i.opcode} :${i.targetLabel}"
                    is ConstStringInstruction -> "const-string ${i.value}"
                }).append('\n')
            }
            append(file.typeRefs.map { it.className })
        }
    }
}
