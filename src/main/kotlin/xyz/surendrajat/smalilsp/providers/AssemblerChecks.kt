package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import xyz.surendrajat.smalilsp.core.InstructionScanner
import xyz.surendrajat.smalilsp.core.InstructionText
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.core.MethodDefinition
import xyz.surendrajat.smalilsp.core.RegisterFrame
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.data.DalvikOpcodeDatabase
import xyz.surendrajat.smalilsp.util.Types

/**
 * What the assembler or the verifier would reject in a hand-edited method, reported before the
 * rebuild: a register the method does not declare, a register too wide for the instruction's
 * encoding (`move` addresses v0..v15, `invoke-*` five registers below v16 unless `/range`), a
 * wide pair whose second half is past the frame, a literal that does not fit (`const/4` holds
 * -8..7), a call passing more or fewer registers than its descriptor takes, a `return` or
 * `move-result` of the wrong kind for the type it carries, a `move-result` not right after a
 * call, and a second `.registers` or `.locals`. baksmali output never trips these, so they only
 * speak up about edits; the pass runs over every line of the edited file on each change, so it
 * allocates nothing per line until it has something to report. A diagnostic with one right
 * answer carries it as a [Fixes] entry in its data, which [CodeActionProvider] turns into an edit.
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

    /** Opcode name → format, wide-operand mask and kind bits, probed by the opcode's span in its line so a lookup cuts no string. */
    private val formatKeys: Array<String?>
    private val formatValues: Array<String?>
    private val wideMasks: IntArray
    private val kinds: IntArray

    private const val MOVE_RESULT = 1
    private const val RETURN = 2
    private const val INVOKE = 4
    private const val STATIC_INVOKE = 8
    private const val FILLED_NEW_ARRAY = 16
    /** `invoke-polymorphic` and `invoke-custom`: their arity and result come from a proto or call site the checks do not read. */
    private const val UNCHECKED_INVOKE = 32

    init {
        val names = DalvikOpcodeDatabase.allInstructionNames()
        var size = 64
        while (size < names.size * 4) size = size shl 1
        formatKeys = arrayOfNulls(size)
        formatValues = arrayOfNulls(size)
        wideMasks = IntArray(size)
        kinds = IntArray(size)
        for (name in names) {
            var slot = name.hashCode() and (size - 1)
            while (formatKeys[slot] != null) slot = (slot + 1) and (size - 1)
            formatKeys[slot] = name
            formatValues[slot] = DalvikOpcodeDatabase.lookup(name)?.formatId
            wideMasks[slot] = wideOperands(name)
            kinds[slot] = kindOf(name)
        }
    }

    private fun kindOf(name: String): Int {
        var kind = 0
        if (name.startsWith("move-result")) kind = kind or MOVE_RESULT
        if (name.startsWith("return")) kind = kind or RETURN
        if (name.startsWith("invoke-")) kind = kind or INVOKE
        if (name.startsWith("invoke-static")) kind = kind or STATIC_INVOKE
        if (name.startsWith("filled-new-array")) kind = kind or FILLED_NEW_ARRAY
        if (name.startsWith("invoke-polymorphic") || name.startsWith("invoke-custom")) kind = kind or UNCHECKED_INVOKE
        return kind
    }

    /** The table slot of the opcode spelled by `line[start, end)`, or -1 when it is no opcode. */
    private fun slotOf(line: String, start: Int, end: Int): Int {
        var hash = 0
        for (i in start until end) hash = 31 * hash + line[i].code
        val mask = formatKeys.size - 1
        var slot = hash and mask
        while (true) {
            val key = formatKeys[slot] ?: return -1
            if (key.length == end - start && line.regionMatches(start, key, 0, key.length)) return slot
            slot = (slot + 1) and mask
        }
    }

    /**
     * Which register operands hold the low half of a 64-bit value, one bit per operand position:
     * `const-wide vA` the first, `add-long vA, vB, vC` all three, `shl-long vA, vB, vC` the first
     * two (the shift count is 32-bit), `cmp-long vA, vB, vC` the last two, `long-to-int vA, vB`
     * the second. Each of them needs the next register as well.
     */
    private fun wideOperands(opcode: String): Int {
        val shift = opcode.startsWith("shl-") || opcode.startsWith("shr-") || opcode.startsWith("ushr-")
        return when {
            opcode.startsWith("const-wide") || opcode == "return-wide" || opcode == "move-result-wide" -> 1
            opcode.startsWith("move-wide") -> 3
            opcode.endsWith("-wide") -> 1   // iget, iput, sget, sput, aget, aput
            opcode == "neg-long" || opcode == "not-long" || opcode == "neg-double" || opcode == "long-to-double" || opcode == "double-to-long" -> 3
            opcode == "int-to-long" || opcode == "int-to-double" || opcode == "float-to-long" || opcode == "float-to-double" -> 1
            opcode == "long-to-int" || opcode == "long-to-float" || opcode == "double-to-int" || opcode == "double-to-float" -> 2
            opcode == "cmp-long" || opcode == "cmpl-double" || opcode == "cmpg-double" -> 6
            opcode.endsWith("-long/2addr") || opcode.endsWith("-double/2addr") -> if (shift) 1 else 3
            opcode.endsWith("-long") || opcode.endsWith("-double") -> if (shift) 3 else 7
            else -> 0
        }
    }

    fun diagnostics(file: SmaliFile, lines: List<String>): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val scanner = InstructionScanner()
        for (method in file.methods) {
            val frame = RegisterFrame.of(method, lines)
            val instructions = method.instructions
            var next = 0                    // the first AST instruction not yet reached; they are walked alongside the lines
            var previous = false            // the previous line was an instruction
            var produced: String? = null    // what it left for move-result: a call's descriptor, "[" for filled-new-array, "?" when unknown
            var frameDirectives = 0
            val last = minOf(method.range.end.line, lines.size - 1)
            for (lineNumber in method.range.start.line + 1 until last) {
                val line = lines[lineNumber]
                if (!scanner.scan(line)) {
                    if (!carriesNoCode(line)) previous = false
                    val directive = frameDirectiveStart(line)
                    if (directive >= 0 && ++frameDirectives == 2) {
                        val end = line.indexOf(' ', directive).let { if (it < 0) line.length else it }
                        out.add(error(lineNumber, directive, end, "There can only be one .registers or .locals directive in a method", "frame-directive", Fixes.deleteLine))
                    }
                    continue
                }
                val slot = slotOf(line, scanner.opcodeStart, scanner.opcodeEnd)
                val kind = if (slot < 0) 0 else kinds[slot]
                var call: InvokeInstruction? = null
                if (kind and INVOKE != 0) {
                    while (next < instructions.size && instructions[next].startLine < lineNumber) next++
                    call = instructions.getOrNull(next)?.takeIf { it.startLine == lineNumber } as? InvokeInstruction
                }
                if (slot >= 0) check(scanner, slot, kind, lineNumber, method, frame, call, if (previous) produced else null, out)
                previous = true
                produced = when {
                    kind and FILLED_NEW_ARRAY != 0 -> "["
                    kind and UNCHECKED_INVOKE != 0 -> "?"
                    kind and INVOKE != 0 -> call?.descriptor ?: "?"
                    else -> null
                }
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

    /** The column of a `.registers` or `.locals` directive on [line], or -1. */
    private fun frameDirectiveStart(line: String): Int {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        return if (line.startsWith(".registers", i) || line.startsWith(".locals", i)) i else -1
    }

    private fun check(s: InstructionScanner, slot: Int, kind: Int, line: Int, method: MethodDefinition, frame: RegisterFrame?, call: InvokeInstruction?, produced: String?, out: MutableList<Diagnostic>) {
        val format = formatValues[slot] ?: return

        if (kind and MOVE_RESULT != 0) {
            if (produced == null) {
                out.add(error(line, s.opcodeStart, s.opcodeEnd, "${s.opcode} must directly follow an invoke or filled-new-array", "move-result-placement", Fixes.deleteLine))
            } else if (produced == "[") {
                if (!s.opcodeIs("move-result-object")) out.add(error(line, s.opcodeStart, s.opcodeEnd, "${s.opcode} after filled-new-array, which leaves an array; use move-result-object", "move-result-kind", Fixes.opcode("move-result-object")))
            } else if (produced != "?") {
                val returned = returnTypeOf(produced)
                val expected = moveResultFor(returned[0])
                if (expected == null) out.add(error(line, s.opcodeStart, s.opcodeEnd, "${s.opcode} after a call that returns nothing", "move-result-kind", Fixes.deleteLine))
                else if (!s.opcodeIs(expected)) out.add(error(line, s.opcodeStart, s.opcodeEnd, "${s.opcode} after a call that returns ${Types.readableName(returned)}; use $expected", "move-result-kind", Fixes.opcode(expected)))
            }
        }

        if (kind and RETURN != 0) {
            val expected = returnFor(method.returnType)
            if (!s.opcodeIs(expected)) {
                // return-void needs no register; the other way round the fix cannot guess one
                val fix = if (expected == "return-void") Fixes.opcode(expected, operands = false) else if (s.opcodeIs("return-void")) null else Fixes.opcode(expected)
                out.add(error(line, s.opcodeStart, s.opcodeEnd, "${s.opcode} in a method that returns ${Types.readableName(method.returnType)}; use $expected", "return-kind", fix))
            }
        }

        if (call != null && kind and UNCHECKED_INVOKE == 0) {
            val receiver = kind and STATIC_INVOKE == 0
            val expected = parameterSlots(call.descriptor) + if (receiver) 1 else 0
            val passed = passedRegisters(s, frame)
            if (passed == BACKWARDS) {
                out.add(error(line, s.registerStart(0), s.registerEnd(1), "The range runs backwards: ${s.registerText(1)} is below ${s.registerText(0)}", "call-arity", Fixes.swapRange))
            } else if (passed >= 0 && passed != expected) {
                val open = s.line.indexOf('{', s.opcodeEnd)
                val close = if (open < 0) -1 else s.line.indexOf('}', open)
                val start = if (open >= 0) open else s.opcodeStart
                val end = if (close >= 0) close + 1 else s.opcodeEnd
                out.add(error(line, start, end, "$passed register${if (passed == 1) "" else "s"} passed to ${call.methodName}${call.descriptor}, which takes $expected: ${slotsText(call.descriptor, receiver)}", "call-arity"))
            }
        }

        if (frame != null) {
            for (i in 0 until s.registerCount) {
                if (!frame.declares(s.isParameter(i), s.registerNumber(i))) {
                    val limit = if (s.isParameter(i)) "${frame.parameters} parameter register(s)" else frameText(frame)
                    val fix = if (s.isParameter(i)) null else Fixes.frame(frame, s.registerNumber(i) + 1)
                    out.add(error(line, s.registerStart(i), s.registerEnd(i), "Register ${s.registerText(i)} is beyond the $limit this method declares", "register-out-of-range", fix))
                }
            }
            val bits = registerBits[format]
            if (bits != null && !s.isRange) {
                if ((format == "35c" || format == "45cc") && s.registerCount > 5) {
                    val opcode = s.opcode
                    out.add(error(line, s.opcodeStart, s.opcodeEnd, "$opcode takes at most five registers; use $opcode/range", "register-width", Fixes.rangeForm))
                }
                for (i in 0 until minOf(s.registerCount, bits.size)) {
                    if (!frame.declares(s.isParameter(i), s.registerNumber(i))) continue
                    val absolute = frame.absolute(s.isParameter(i), s.registerNumber(i))
                    val max = (1 shl bits[i]) - 1
                    if (absolute > max) {
                        val opcode = s.opcode
                        val shown = if (s.isParameter(i)) "${s.registerText(i)} (v$absolute)" else s.registerText(i)
                        val fix = when {
                            format == "35c" || format == "45cc" -> Fixes.rangeForm
                            opcode.startsWith("move") -> Fixes.opcode(wider(opcode, format))
                            else -> null
                        }
                        out.add(error(line, s.registerStart(i), s.registerEnd(i), "$shown does not fit $opcode, whose operand addresses v0..v$max; use the ${wider(opcode, format)} form", "register-width", fix))
                    }
                }
            }
            val wide = wideMasks[slot]
            if (wide != 0) {
                for (i in 0 until s.registerCount) {
                    if (wide and (1 shl i) == 0 || !frame.declares(s.isParameter(i), s.registerNumber(i))) continue
                    val absolute = frame.absolute(s.isParameter(i), s.registerNumber(i))
                    if (absolute + 1 >= frame.total) {
                        out.add(error(line, s.registerStart(i), s.registerEnd(i), "${s.registerText(i)} starts a wide pair, and its second half v${absolute + 1} is beyond the ${frameText(frame)} this method declares", "register-out-of-range", Fixes.frame(frame, absolute + 2)))
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
            out.add(error(line, literal.start, literal.end, "Literal ${literal.text} does not fit $opcode, which $problem; use ${wider(opcode, format)}", "literal-out-of-range", widerLiteral(opcode, value)?.let { Fixes.opcode(it) }))
        }
    }

    private val literalFormats = setOf("11n", "22b", "21s", "22s", "21h")

    private const val UNKNOWN = -1
    private const val BACKWARDS = -2

    /** Registers a call passes: the list's count, or the span of a `{vA .. vB}` range; [UNKNOWN] for a mixed range without a frame to place it, [BACKWARDS] when vB is below vA. */
    private fun passedRegisters(s: InstructionScanner, frame: RegisterFrame?): Int {
        if (!s.isRange || s.registerCount < 2) return s.registerCount
        val first: Int
        val last: Int
        when {
            s.isParameter(0) == s.isParameter(1) -> { first = s.registerNumber(0); last = s.registerNumber(1) }
            frame != null -> { first = frame.absolute(s.isParameter(0), s.registerNumber(0)); last = frame.absolute(s.isParameter(1), s.registerNumber(1)) }
            else -> return UNKNOWN
        }
        return if (last < first) BACKWARDS else last - first + 1
    }

    /** The register slots the parameters of [descriptor] take: two for `J` and `D`, one for anything else, arrays included. */
    private fun parameterSlots(descriptor: String): Int {
        var i = 1
        var slots = 0
        while (i < descriptor.length && descriptor[i] != ')') {
            var c = descriptor[i]
            var array = false
            while (c == '[') {
                array = true
                if (++i >= descriptor.length) return slots
                c = descriptor[i]
            }
            if (c == 'L') {
                val semi = descriptor.indexOf(';', i)
                if (semi < 0) return slots
                i = semi + 1
            } else i++
            slots += if (!array && (c == 'J' || c == 'D')) 2 else 1
        }
        return slots
    }

    /** `this and 3 for the parameters, J and D taking two each`. */
    private fun slotsText(descriptor: String, receiver: Boolean): String {
        val slots = parameterSlots(descriptor)
        val close = descriptor.indexOf(')').let { if (it < 0) descriptor.length else it }
        val wide = Types.parseTypeStrings(descriptor.substring(1, close)).any { it == "J" || it == "D" }
        return buildString {
            if (receiver) append("this")
            if (slots > 0) {
                if (receiver) append(" and ")
                append(slots).append(if (slots == 1) " for the parameter" else " for the parameters")
                if (wide) append(", J and D taking two each")
            } else if (!receiver) append("none")
        }
    }

    /** The return type of a call's descriptor; `V` when it has none. */
    private fun returnTypeOf(descriptor: String): String {
        val close = descriptor.indexOf(')')
        return if (close < 0 || close + 1 >= descriptor.length) "V" else descriptor.substring(close + 1)
    }

    private fun moveResultFor(type: Char): String? = when (type) {
        'V' -> null
        'J', 'D' -> "move-result-wide"
        'L', '[' -> "move-result-object"
        else -> "move-result"
    }

    private fun returnFor(type: String): String = when (type.firstOrNull()) {
        'V', null -> "return-void"
        'J', 'D' -> "return-wide"
        'L', '[' -> "return-object"
        else -> "return"
    }

    private fun InstructionScanner.opcodeIs(name: String): Boolean = opcodeEnd - opcodeStart == name.length && line.startsWith(name, opcodeStart)

    private fun frameText(frame: RegisterFrame): String = "${frame.total} registers (${frame.directive} ${if (frame.directive == ".locals") frame.locals else frame.total})"

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

    /** The one instruction that holds the literal: `const/16` for `const/4`, `const-wide/32` when the value fits 32 bits else `const-wide`; none for `/lit16`, which needs a register. */
    private fun widerLiteral(opcode: String, value: Long): String? = when {
        opcode == "const/4" -> "const/16"
        opcode == "const/16" || opcode == "const/high16" -> "const"
        opcode == "const-wide/16" || opcode == "const-wide/high16" -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) "const-wide/32" else "const-wide"
        opcode.endsWith("/lit8") -> opcode.replace("/lit8", "/lit16")
        else -> null
    }

    private fun error(line: Int, start: Int, end: Int, message: String, code: String, fix: Map<String, Any>? = null) =
        Diagnostic(Range(Position(line, start), Position(line, end)), message, DiagnosticSeverity.Error, "smali-lsp", code).apply { if (fix != null) data = fix }
}
