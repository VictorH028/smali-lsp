package xyz.surendrajat.smalilsp.core

/** A token of an instruction line: its UTF-16 columns `[start, end)` in [line]; the text is cut only when asked for. */
open class TextToken(val line: String, val start: Int, val end: Int) {
    val text: String get() = line.substring(start, end)

    /** The caret is on the token or right after it. */
    fun touches(character: Int): Boolean = character in start..end

    /** The token spells [other]. */
    fun matches(other: String): Boolean = end - start == other.length && line.regionMatches(start, other, 0, other.length)
}

/** A register operand: `v12` or `p0`, with its kind and number read while scanning. */
class RegisterToken(line: String, start: Int, end: Int, val isParameter: Boolean, val number: Int) : TextToken(line, start, end)

/**
 * An instruction line decoded the way an assembler reads it: the opcode and every register
 * operand with its position, and whether the registers form a `{vA .. vB}` range. The AST keeps
 * only navigable instructions and no registers, so register-level features read the text.
 */
class DecodedInstruction(val line: String, val opcodeStart: Int, val opcodeEnd: Int, val registers: List<RegisterToken>, val isRange: Boolean) {
    val opcode: String get() = line.substring(opcodeStart, opcodeEnd)

    /** The register token under [character], if any. */
    fun registerAt(character: Int): RegisterToken? = registers.firstOrNull { it.touches(character) }
}

/**
 * Scans one instruction line into reusable fields. The diagnostics pass runs it over every line
 * of the edited file on each change, so a scan allocates nothing: the opcode, registers and
 * literal are spans into the line until something asks for their text. One scanner serves one
 * thread.
 */
class InstructionScanner {
    var line: String = ""
        private set
    var opcodeStart = 0
        private set
    var opcodeEnd = 0
        private set
    var registerCount = 0
        private set
    var isRange = false
        private set
    private var literalStart = -1
    private var literalEnd = -1
    private var starts = IntArray(8)
    private var ends = IntArray(8)
    private var numbers = IntArray(8)
    private var parameters = BooleanArray(8)
    private var depth = 0
    private var tokenStart = -1
    private var lastStart = -1
    private var lastEnd = -1

    val opcode: String get() = line.substring(opcodeStart, opcodeEnd)
    val hasLiteral: Boolean get() = literalStart >= 0
    val literal: TextToken? get() = if (literalStart >= 0) TextToken(line, literalStart, literalEnd) else null
    fun registerStart(i: Int): Int = starts[i]
    fun registerEnd(i: Int): Int = ends[i]
    fun registerNumber(i: Int): Int = numbers[i]
    fun isParameter(i: Int): Boolean = parameters[i]
    fun registerText(i: Int): String = line.substring(starts[i], ends[i])
    fun register(i: Int): RegisterToken = RegisterToken(line, starts[i], ends[i], parameters[i], numbers[i])

    /** The scanned line as an instruction object. */
    fun toInstruction(): DecodedInstruction =
        DecodedInstruction(line, opcodeStart, opcodeEnd, List(registerCount) { register(it) }, isRange)

    /**
     * Scans [line]; false for a blank line, a comment, a directive or a label, after which the
     * fields are stale. Operands are comma-separated at brace depth 0; a `{...}` group holds
     * registers separated by commas or `..`; a string operand ends the scan (a quoted string may
     * hold anything); the last operand is the literal when it is a number.
     */
    fun scan(line: String): Boolean {
        this.line = line
        var i = 0
        while (i < line.length && isBlank(line[i])) i++
        if (i >= line.length) return false
        val first = line[i]
        if (first == '.' || first == ':' || first == '#' || !first.isLetter()) return false
        opcodeStart = i
        while (i < line.length && !isBlank(line[i])) i++
        opcodeEnd = i
        registerCount = 0
        isRange = opcodeEnd - opcodeStart > 6 && line.startsWith("/range", opcodeEnd - 6)
        literalStart = -1
        depth = 0
        tokenStart = -1
        lastStart = -1
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> { flush(i); break }
                c == '{' -> { flush(i); depth++ }
                c == '}' -> { flush(i); depth-- }
                c == ',' -> flush(i)
                isBlank(c) -> flush(i)
                c == '.' && depth > 0 && i + 1 < line.length && line[i + 1] == '.' -> { flush(i); isRange = true; i++ }
                c == '#' && depth == 0 -> { flush(i); break }
                else -> if (tokenStart < 0) tokenStart = i
            }
            i++
        }
        flush(i)
        if (lastStart >= 0 && isLiteral(lastStart, lastEnd)) { literalStart = lastStart; literalEnd = lastEnd }
        return true
    }

    private fun flush(end: Int) {
        if (tokenStart < 0) return
        addRegister(tokenStart, end)
        if (depth == 0) { lastStart = tokenStart; lastEnd = end }
        tokenStart = -1
    }

    /** Records `[start, end)` when it spells `v<n>` or `p<n>`. */
    private fun addRegister(start: Int, end: Int) {
        if (end - start < 2 || end - start > 8) return
        val kind = line[start]
        if (kind != 'v' && kind != 'p') return
        var number = 0
        for (k in start + 1 until end) {
            val digit = line[k] - '0'
            if (digit !in 0..9) return
            number = number * 10 + digit
        }
        if (registerCount == starts.size) {
            starts = starts.copyOf(registerCount * 2); ends = ends.copyOf(registerCount * 2)
            numbers = numbers.copyOf(registerCount * 2); parameters = parameters.copyOf(registerCount * 2)
        }
        starts[registerCount] = start; ends[registerCount] = end; numbers[registerCount] = number; parameters[registerCount] = kind == 'p'
        registerCount++
    }

    private fun isBlank(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r'

    /** `[start, end)` starts like a number: an optional sign, then a digit. */
    private fun isLiteral(start: Int, end: Int): Boolean {
        val digit = if (line[start] == '-') start + 1 else start
        return digit < end && line[digit] in '0'..'9'
    }
}

object InstructionText {

    /** The decoded instruction, or null for a blank line, a comment, a directive or a label. */
    fun decode(line: String): DecodedInstruction? {
        val scanner = InstructionScanner()
        return if (scanner.scan(line)) scanner.toInstruction() else null
    }

    /**
     * The value of an integer literal as baksmali writes it: hex or decimal, optional sign, an
     * optional `L` (long), `t` (byte) or `s` (short) suffix. Null for anything else (floats, chars).
     */
    fun literalValue(text: String): Long? {
        var body = text
        val negative = body.startsWith("-")
        if (negative) body = body.substring(1)
        body = body.removeSuffix("L").removeSuffix("t").removeSuffix("s").removeSuffix("S")
        val value = try {
            if (body.startsWith("0x") || body.startsWith("0X")) body.substring(2).toULong(16).toLong() else body.toULong(10).toLong()
        } catch (_: NumberFormatException) {
            return null
        }
        return if (negative) -value else value
    }

    /** The `move-result` form a call returning [type] needs; null for `V`. */
    fun moveResultFor(type: String): String? = when (type.firstOrNull()) {
        null, 'V' -> null
        'J', 'D' -> "move-result-wide"
        'L', '[' -> "move-result-object"
        else -> "move-result"
    }

    /** The `return` form of a method returning [type]. */
    fun returnFor(type: String): String = when (type.firstOrNull()) {
        null, 'V' -> "return-void"
        'J', 'D' -> "return-wide"
        'L', '[' -> "return-object"
        else -> "return"
    }
}

/**
 * A method's register frame: how many registers it declares and how many of them are parameters
 * (which sit at the top of the frame). `pN` is register `total - parameters + N`.
 */
class RegisterFrame(val total: Int, val parameters: Int, val directive: String) {
    val locals: Int get() = total - parameters

    /** The frame index of a register: `v3` is 3, `p1` is `total - parameters + 1`. */
    fun absolute(isParameter: Boolean, number: Int): Int = if (isParameter) locals + number else number
    fun absolute(register: RegisterToken): Int = absolute(register.isParameter, register.number)

    /** Whether the method declares the register. */
    fun declares(isParameter: Boolean, number: Int): Boolean = if (isParameter) number < parameters else number < total
    fun declares(register: RegisterToken): Boolean = declares(register.isParameter, register.number)

    companion object {
        /** Slots a parameter type occupies: two for `J` and `D`, one otherwise. */
        fun slots(type: String): Int = if (type == "J" || type == "D") 2 else 1

        /** The parameter slot count of [method]: `this` unless static, then each parameter's slots. */
        fun parameterSlots(method: MethodDefinition): Int =
            (if ("static" in method.modifiers) 0 else 1) + method.parameters.sumOf { slots(it.type) }

        /** The register slots the parameters of a descriptor take, read from its text: two for `J` and `D`, one for anything else, arrays included. */
        fun parameterSlots(descriptor: String): Int {
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

        /** The frame declared by the `.registers` or `.locals` directive in [method]'s lines, or null when there is none. */
        fun of(method: MethodDefinition, lines: List<String>): RegisterFrame? {
            val parameters = parameterSlots(method)
            for (lineNumber in method.startLine..minOf(method.endLine, lines.size - 1)) {
                val directive = frameDirective(lines[lineNumber]) ?: continue
                val count = directive.count ?: continue
                return RegisterFrame(if (directive.name == ".locals") count + parameters else count, parameters, directive.name)
            }
            return null
        }

        /**
         * The `.registers` or `.locals` directive on [line], indented or not, a tab or spaces before
         * the count, a comment after it; null for any other line. The one reader of the directive:
         * the frame, the assembler checks, the frame quick fix and the frame hover all go through it.
         */
        fun frameDirective(line: String): FrameDirective? {
            var i = 0
            while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
            val name = when {
                line.startsWith(".registers", i) -> ".registers"
                line.startsWith(".locals", i) -> ".locals"
                else -> return null
            }
            var start = i + name.length
            if (start < line.length && line[start] != ' ' && line[start] != '\t' && line[start] != '\r') return null
            while (start < line.length && (line[start] == ' ' || line[start] == '\t')) start++
            var end = start
            while (end < line.length && line[end] != ' ' && line[end] != '\t' && line[end] != '\r' && line[end] != '#') end++
            return FrameDirective(name, i, start, end, line)
        }
    }
}

/** A `.registers` or `.locals` line: the directive's column and the count's columns, empty when there is none. */
class FrameDirective(val name: String, val start: Int, val countStart: Int, val countEnd: Int, private val line: String) {
    /** The declared count, or null when what follows the directive is not a number. */
    val count: Int? get() = if (countEnd > countStart) InstructionText.literalValue(line.substring(countStart, countEnd))?.toInt() else null
}
