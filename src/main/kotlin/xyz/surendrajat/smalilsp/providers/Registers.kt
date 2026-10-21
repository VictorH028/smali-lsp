package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import xyz.surendrajat.smalilsp.core.InstructionText
import xyz.surendrajat.smalilsp.core.MethodDefinition
import xyz.surendrajat.smalilsp.core.RegisterFrame
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.core.RegisterToken
import xyz.surendrajat.smalilsp.core.methodAt
import xyz.surendrajat.smalilsp.util.Types

/**
 * Registers for hover and document highlight. Registers are not in the AST (they would double
 * it for nothing navigable), so these read the method's lines: the token under the caret, every
 * use of it in the method with whether the instruction writes it, what `pN` is a parameter of,
 * and the whole frame for the `.registers` line.
 */
object Registers {

    /** The register token under the caret, with the method it belongs to; null when the caret is not on one. */
    fun at(file: SmaliFile, lines: List<String>, position: Position): Pair<MethodDefinition, RegisterToken>? {
        val method = file.methodAt(position.line) ?: return null
        val line = lines.getOrNull(position.line) ?: return null
        val token = InstructionText.decode(line)?.registerAt(position.character) ?: return null
        return method to token
    }

    /** Every use of [register] in [method], writes marked: the destination is the first operand of instructions that produce a value. */
    fun highlights(method: MethodDefinition, lines: List<String>, register: String): List<DocumentHighlight> {
        val out = ArrayList<DocumentHighlight>()
        for (lineNumber in method.range.start.line..minOf(method.range.end.line, lines.size - 1)) {
            val instruction = InstructionText.decode(lines[lineNumber]) ?: continue
            for ((i, token) in instruction.registers.withIndex()) {
                if (!token.matches(register)) continue
                val kind = if (i == 0 && writesFirstRegister(instruction.opcode)) DocumentHighlightKind.Write else DocumentHighlightKind.Read
                out.add(DocumentHighlight(Range(Position(lineNumber, token.start), Position(lineNumber, token.end)), kind))
            }
        }
        return out
    }

    /** Markdown for a register: the parameter it carries, or the local slot, plus any `.param`/`.local` debug name. */
    fun hover(method: MethodDefinition, className: String, lines: List<String>, token: RegisterToken, line: Int): Hover {
        val frame = RegisterFrame.of(method, lines)
        val register = token.text
        val md = buildString {
            val parameter = frame?.let { parameterOf(method, it, token) }
            if (parameter != null) {
                val (index, type, secondHalf) = parameter
                when {
                    index < 0 -> append("**Parameter** `$register`: `this` (`${Types.readableName(className)}`)\n\n")
                    secondHalf -> append("**Parameter** `$register`: second register of the `${Types.readableName(type)}` parameter ${index + 1}\n\n")
                    else -> {
                        append("**Parameter** `$register`: parameter ${index + 1} of ${method.parameters.size}, `${Types.readableName(type)}`\n\n")
                        method.parameters[index].name?.let { append("**Name:** $it\n\n") }
                    }
                }
                debugName(lines, method, register, line, ".param")?.let { append("**Name:** $it\n\n") }
            } else {
                append("**Register** `$register`")
                if (frame != null) {
                    val absolute = frame.absolute(token)
                    append(if (absolute < frame.locals) ": local ${absolute + 1} of ${frame.locals}" else ": frame slot $absolute")
                }
                append("\n\n")
                debugName(lines, method, register, line, ".local")?.let { append("**Local:** $it\n\n") }
            }
            if (frame != null) {
                append("**Frame:** ${frame.total} registers (${frame.locals} local, ${frame.parameters} parameter) in `${method.name}`")
            } else {
                append("**Method:** `${method.name}` (no `.registers` or `.locals` directive)")
            }
        }
        return Hover(MarkupContent(MarkupKind.MARKDOWN, md))
    }

    /** Markdown for a `.registers`/`.locals` line: the frame's size, then which registers the locals, `this` and each parameter occupy, with the `.param` names. */
    fun frameHover(method: MethodDefinition, className: String, lines: List<String>): Hover? {
        val frame = RegisterFrame.of(method, lines) ?: return null
        val md = buildString {
            append("**Frame:** ${frame.total} registers (${frame.locals} local, ${frame.parameters} parameter) in `${method.name}`\n\n")
            if (frame.locals > 0) append("- ${span("v", 0, frame.locals - 1)}: local${if (frame.locals > 1) "s" else ""}\n")
            var slot = 0
            if ("static" !in method.modifiers) {
                append("- `p0` = `v${frame.locals}`: `this` (`${Types.readableName(className)}`)\n")
                slot = 1
            }
            for (parameter in method.parameters) {
                val width = RegisterFrame.slots(parameter.type)
                append("- ${span("p", slot, slot + width - 1)} = ${span("v", frame.locals + slot, frame.locals + slot + width - 1)}: `${Types.readableName(parameter.type)}`")
                (parameter.name ?: debugName(lines, method, "p$slot", method.range.end.line, ".param"))?.let { append(" $it") }
                append('\n')
                slot += width
            }
        }
        return Hover(MarkupContent(MarkupKind.MARKDOWN, md.trimEnd()))
    }

    /** `v3` or `v3`–`v4`: the registers [from]..[to] of kind [kind] as code. */
    fun span(kind: String, from: Int, to: Int): String = if (from == to) "`$kind$from`" else "`$kind$from`\u2013`$kind$to`"

    /** `(parameter index, type, second half of a wide parameter)` for a parameter register, index -1 for `this`; null for a local. */
    fun parameterOf(method: MethodDefinition, frame: RegisterFrame, register: RegisterToken): Triple<Int, String, Boolean>? {
        val absolute = frame.absolute(register)
        if (absolute < frame.locals) return null
        var slot = absolute - frame.locals
        if ("static" !in method.modifiers) {
            if (slot == 0) return Triple(-1, "", false)
            slot--
        }
        for ((index, parameter) in method.parameters.withIndex()) {
            val width = RegisterFrame.slots(parameter.type)
            if (slot < width) return Triple(index, parameter.type, slot == 1)
            slot -= width
        }
        return null
    }

    /** The name a `.param pN, "name"` or the nearest preceding `.local vN, "name":T` gives the register, as `name` or `name: T`. */
    private fun debugName(lines: List<String>, method: MethodDefinition, register: String, before: Int, directive: String): String? {
        var found: String? = null
        for (lineNumber in method.range.start.line..minOf(before, lines.size - 1)) {
            val text = lines[lineNumber].trim()
            if (!text.startsWith("$directive $register,") && !text.startsWith("$directive $register ")) continue
            val quote = text.indexOf('"')
            if (quote < 0) continue
            val close = text.indexOf('"', quote + 1)
            if (close < 0) continue
            val name = text.substring(quote + 1, close)
            val type = text.substring(close + 1).trim().removePrefix(":").substringBefore(' ').substringBefore('#').trim()
            found = if (type.isNotEmpty()) "$name: `${Types.readableName(type)}`" else name
        }
        return found
    }

    /** Instructions whose first register operand receives a value. */
    fun writesFirstRegister(opcode: String): Boolean =
        WRITE_PREFIXES.any { opcode.startsWith(it) }

    private val WRITE_PREFIXES = listOf(
        "move", "const", "new-instance", "new-array", "check-cast", "instance-of", "array-length",
        "iget", "sget", "aget", "cmp", "neg-", "not-", "int-to", "long-to", "float-to", "double-to",
        "add-", "sub-", "mul-", "div-", "rem-", "and-", "or-", "xor-", "shl-", "shr-", "ushr-", "rsub-"
    )
}
