package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import xyz.surendrajat.smalilsp.core.InstructionScanner
import xyz.surendrajat.smalilsp.core.InstructionText
import xyz.surendrajat.smalilsp.core.RegisterFrame
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.data.DalvikOpcodeDatabase

/**
 * What the assembler would reject in a hand-edited method, reported before the rebuild: a
 * register the method does not declare, a register too wide for the instruction's encoding
 * (`move` addresses v0..v15, `invoke-*` five registers below v16 unless `/range`), a literal
 * that does not fit (`const/4` holds -8..7), and a `move-result` not right after a call.
 * baksmali output never trips these, so they only speak up about edits; the pass runs over
 * every line of the edited file on each change, so it allocates nothing per line.
 */
object AssemblerChecks {

    /** Bits available to each register operand, by instruction format; absent formats have no register operands. */
    private val registerBits: Map<String, IntArray> = mapOf(
        "11n" to intArrayOf(4), "11x" to intArrayOf(8), "12x" to intArrayOf(4, 4),
        "21c" to intArrayOf(8), "21h" to intArrayOf(8), "21s" to intArrayOf(8), "21t" to intArrayOf(8),
        "22b" to intArrayOf(8, 8), "22c" to intArrayOf(4, 4), "22s" to intArrayOf(4, 4), "22t" to intArrayOf(4, 4),
        "22x" to intArrayOf(8, 16), "23x" to intArrayOf(8, 8, 8),
        "31c" to intArrayOf(8), "31i" to intArrayOf(8), "31t" to intArrayOf(8), "32x" to intArrayOf(16, 16), "51l" to intArrayOf(8),
        "35c" to intArrayOf(4, 4, 4, 4, 4), "45cc" to intArrayOf(4, 4, 4, 4, 4)
    )

    /** Opcode name → format, probed by the opcode's span in its line so a lookup cuts no string. */
    private val formatKeys: Array<String?>
    private val formatValues: Array<String?>

    init {
        val names = DalvikOpcodeDatabase.allInstructionNames()
        var size = 64
        while (size < names.size * 4) size = size shl 1
        formatKeys = arrayOfNulls(size)
        formatValues = arrayOfNulls(size)
        for (name in names) {
            var slot = name.hashCode() and (size - 1)
            while (formatKeys[slot] != null) slot = (slot + 1) and (size - 1)
            formatKeys[slot] = name
            formatValues[slot] = DalvikOpcodeDatabase.lookup(name)?.formatId
        }
    }

    private fun formatOf(line: String, start: Int, end: Int): String? {
        var hash = 0
        for (i in start until end) hash = 31 * hash + line[i].code
        val mask = formatKeys.size - 1
        var slot = hash and mask
        while (true) {
            val key = formatKeys[slot] ?: return null
            if (key.length == end - start && line.regionMatches(start, key, 0, key.length)) return formatValues[slot]
            slot = (slot + 1) and mask
        }
    }

    fun diagnostics(file: SmaliFile, lines: List<String>): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val scanner = InstructionScanner()
        for (method in file.methods) {
            val frame = RegisterFrame.of(method, lines)
            var previousProducesValue = false   // the last instruction was an invoke or filled-new-array
            var previous = false                // there is a last instruction at all
            val last = minOf(method.range.end.line, lines.size - 1)
            for (lineNumber in method.range.start.line + 1 until last) {
                val line = lines[lineNumber]
                if (!scanner.scan(line)) {
                    if (!carriesNoCode(line)) previous = false
                    continue
                }
                check(scanner, lineNumber, frame, previous && previousProducesValue, out)
                previous = true
                previousProducesValue = scanner.opcodeStartsWith("invoke-") || scanner.opcodeStartsWith("filled-new-array")
            }
        }
        return out
    }

    /** Blank lines, comments and the debug directives keep the previous instruction current for the move-result check. */
    private fun carriesNoCode(line: String): Boolean {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        if (i == line.length || line[i] == '#' || line[i] == '\r') return true
        return line.startsWith(".line", i) || line.startsWith(".local", i) || line.startsWith(".end local", i) || line.startsWith(".restart local", i)
    }

    private fun check(s: InstructionScanner, line: Int, frame: RegisterFrame?, afterCall: Boolean, out: MutableList<Diagnostic>) {
        val format = formatOf(s.line, s.opcodeStart, s.opcodeEnd) ?: return

        if (!afterCall && s.opcodeStartsWith("move-result")) {
            out.add(error(line, s.opcodeStart, s.opcodeEnd, "${s.opcode} must directly follow an invoke or filled-new-array", "move-result-placement"))
        }

        if (frame != null) {
            for (i in 0 until s.registerCount) {
                if (!frame.declares(s.isParameter(i), s.registerNumber(i))) {
                    val limit = if (s.isParameter(i)) "${frame.parameters} parameter register(s)" else "${frame.total} registers (${frame.directive} ${if (frame.directive == ".locals") frame.locals else frame.total})"
                    out.add(error(line, s.registerStart(i), s.registerEnd(i), "Register ${s.registerText(i)} is beyond the $limit this method declares", "register-out-of-range"))
                }
            }
            val bits = registerBits[format]
            if (bits != null && !s.isRange) {
                if ((format == "35c" || format == "45cc") && s.registerCount > 5) {
                    val opcode = s.opcode
                    out.add(error(line, s.opcodeStart, s.opcodeEnd, "$opcode takes at most five registers; use $opcode/range", "register-width"))
                }
                for (i in 0 until minOf(s.registerCount, bits.size)) {
                    if (!frame.declares(s.isParameter(i), s.registerNumber(i))) continue
                    val absolute = frame.absolute(s.isParameter(i), s.registerNumber(i))
                    val max = (1 shl bits[i]) - 1
                    if (absolute > max) {
                        val opcode = s.opcode
                        val shown = if (s.isParameter(i)) "${s.registerText(i)} (v$absolute)" else s.registerText(i)
                        out.add(error(line, s.registerStart(i), s.registerEnd(i), "$shown does not fit $opcode, whose operand addresses v0..v$max; use the ${wider(opcode, format)} form", "register-width"))
                    }
                }
            }
        }

        if (s.hasLiteral && format in literalFormats) {
            val literal = s.literal ?: return
            val opcode = s.opcode
            val value = InstructionText.literalValue(literal.text) ?: return
            val problem = when (format) {
                "11n" -> if (value !in -8L..7L) "fits -8..7" else null
                "22b" -> if (value !in -128L..127L) "fits -128..127" else null
                "21s", "22s" -> if (value !in -32768L..32767L) "fits -32768..32767" else null
                "21h" -> if (opcode.startsWith("const-wide")) {
                    if (value and 0xFFFFFFFFFFFFL != 0L || (value shr 48) !in -32768L..32767L) "holds a 16-bit value shifted into the top of 64 bits" else null
                } else {
                    if (value and 0xFFFFL != 0L || (value shr 16) !in -32768L..32767L) "holds a 16-bit value shifted into the top of 32 bits" else null
                }
                else -> null
            } ?: return
            out.add(error(line, literal.start, literal.end, "Literal ${literal.text} does not fit $opcode, which $problem; use ${wider(opcode, format)}", "literal-out-of-range"))
        }
    }

    private val literalFormats = setOf("11n", "22b", "21s", "22s", "21h")

    /** The instruction form with room for more: `/range` for calls, `/from16` or `/16` for moves, the wider const. */
    private fun wider(opcode: String, format: String): String = when {
        format == "35c" || format == "45cc" -> "$opcode/range"
        opcode.startsWith("move") && format == "12x" -> "$opcode/from16"
        opcode.startsWith("move") && format == "22x" -> opcode.replace("/from16", "/16")
        opcode == "const/4" -> "const/16"
        opcode == "const/16" || opcode == "const/high16" -> "const"
        opcode == "const-wide/16" || opcode == "const-wide/high16" -> "const-wide/32 or const-wide"
        opcode.endsWith("/lit8") -> opcode.replace("/lit8", "/lit16")
        format == "22s" -> "a register operand"
        else -> "a wider form"
    }

    private fun error(line: Int, start: Int, end: Int, message: String, code: String) =
        Diagnostic(Range(Position(line, start), Position(line, end)), message, DiagnosticSeverity.Error, "smali-lsp", code)
}
