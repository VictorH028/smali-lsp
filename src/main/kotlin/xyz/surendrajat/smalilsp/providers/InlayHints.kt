package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import xyz.surendrajat.smalilsp.core.InstructionScanner
import xyz.surendrajat.smalilsp.core.InstructionText
import xyz.surendrajat.smalilsp.core.Literals
import xyz.surendrajat.smalilsp.index.ResourceTable
import xyz.surendrajat.smalilsp.index.WorkspaceIndex

/**
 * Hints after literals ([Literals]): what a resource id names, else the decimal of a hex number.
 * None for -9..9 (obvious), bytes and shorts (array data, not numbers to read), lines baksmali
 * already commented (`# 1.0f`), or resource-shaped ids nothing names (decimal would be noise).
 */
object InlayHints {

    /** Hints for [lines] within [range]. */
    fun hints(index: WorkspaceIndex, lines: List<String>, range: Range): List<InlayHint> {
        val out = ArrayList<InlayHint>()
        val scanner = InstructionScanner()
        val last = minOf(range.end.line, lines.size - 1)
        for (lineNumber in maxOf(0, range.start.line)..last) {
            val line = lines[lineNumber]
            val literal = Literals.find(line, scanner) ?: continue
            val text = literal.token.text
            if (!isHex(text) || text.endsWith('t') || text.endsWith('s') || text.endsWith('S')) continue
            val value = InstructionText.literalValue(text) ?: continue
            val hint = if (ResourceTable.isAnyResourceId(value)) {
                val resource = index.resources[value.toInt()] ?: continue
                ResourceLiterals.hint(index, resource)
            } else {
                if (literal.kind == Literals.Kind.Instruction && !carriesNumber(literal.opcode!!)) continue
                if (value in -9L..9L) continue
                if (line.indexOf('#', literal.token.end) >= 0) continue
                Hint("= $value", null)
            }
            out.add(InlayHint(Position(lineNumber, literal.token.end), Either.forLeft(hint.label)).apply {
                paddingLeft = true
                hint.tooltip?.let { tooltip = Either.forLeft(it) }
            })
        }
        return out
    }

    /** Label and optional tooltip. */
    class Hint(val label: String, val tooltip: String?)

    private fun isHex(text: String): Boolean {
        val at = if (text.startsWith("-")) 1 else 0
        return text.startsWith("0x", at) || text.startsWith("0X", at)
    }

    /** `const*` (not string or class) and `*-int/lit8`/`lit16`: the literal is the number used. */
    private fun carriesNumber(opcode: String): Boolean =
        opcode == "const" || opcode.startsWith("const/") || opcode.startsWith("const-wide") ||
            opcode.endsWith("/lit8") || opcode.endsWith("/lit16")
}
