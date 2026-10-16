package xyz.surendrajat.smalilsp.core

/**
 * The one numeric literal a smali line can carry, found where baksmali writes it: the last
 * operand of an instruction (`const v0, 0x7f0a0012`), the value of a `.field … = 0x…` or of an
 * annotation element (`value = 0x…`), an `.array-data` element, a `.sparse-switch` key
 * (`0x… -> :label`) or the first key of `.packed-switch`. Literals are not in the AST, so
 * resource hover and inlay hints read them from the line.
 */
object Literals {

    enum class Kind { Instruction, Field, Annotation, Element, SwitchKey, PackedSwitch }

    /** A literal's token, where it sits, and the opcode when it is an instruction's operand. */
    class Literal(val token: TextToken, val kind: Kind, val opcode: String? = null)

    /** The literal of [line], or null when the line has none; [scanner] is reused across lines. */
    fun find(line: String, scanner: InstructionScanner = InstructionScanner()): Literal? {
        var i = 0
        while (i < line.length && isBlank(line[i])) i++
        if (i >= line.length) return null
        val c = line[i]
        return when {
            c == '#' || c == ':' -> null
            c == '.' -> directive(line, i)
            c == '-' || c in '0'..'9' -> bare(line, i)
            c.isLetter() -> assignment(line, i) ?: instruction(line, scanner)
            else -> null
        }
    }

    private fun directive(line: String, start: Int): Literal? = when {
        line.startsWith(".field ", start) -> line.indexOf(" = ", start).let { if (it < 0) null else token(line, it + 3, Kind.Field) }
        line.startsWith(".packed-switch ", start) -> token(line, start + ".packed-switch ".length, Kind.PackedSwitch)
        else -> null
    }

    /** `name = 0x…` of an annotation element; null when the second token is not `=`, which leaves the line to the instruction scanner. */
    private fun assignment(line: String, start: Int): Literal? {
        var i = start
        while (i < line.length && !isBlank(line[i])) i++
        while (i < line.length && isBlank(line[i])) i++
        return if (i < line.length && line[i] == '=') token(line, i + 1, Kind.Annotation) else null
    }

    /** An `.array-data` element, or a `.sparse-switch` key when `->` follows it. */
    private fun bare(line: String, start: Int): Literal? {
        val literal = token(line, start, Kind.Element) ?: return null
        var i = literal.token.end
        while (i < line.length && isBlank(line[i])) i++
        return if (line.startsWith("->", i)) Literal(literal.token, Kind.SwitchKey) else literal
    }

    private fun instruction(line: String, scanner: InstructionScanner): Literal? {
        if (!scanner.scan(line)) return null
        val literal = scanner.literal ?: return null
        return Literal(literal, Kind.Instruction, scanner.opcode)
    }

    /** The token at or after [from] (blanks skipped) up to the next blank, comma or comment, when it starts like a number: an optional `-`, then a digit. */
    private fun token(line: String, from: Int, kind: Kind): Literal? {
        var start = from
        while (start < line.length && isBlank(line[start])) start++
        var end = start
        while (end < line.length && !isBlank(line[end]) && line[end] != '#' && line[end] != ',') end++
        val digit = if (start < end && line[start] == '-') start + 1 else start
        if (digit >= end || line[digit] !in '0'..'9') return null
        return Literal(TextToken(line, start, end), kind)
    }

    private fun isBlank(c: Char) = c == ' ' || c == '\t' || c == '\r'
}
