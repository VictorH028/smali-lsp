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
 * Inlay hints say what a literal is. After a resource id, the resource's name and value
 * (`string/app_name = "Mail"`); after any other hex literal, its decimal value (`= 1000`).
 * Literals are wherever baksmali writes them ([Literals]): `const*` and `*-int/lit*` operands,
 * `.field` values, annotation elements, `.array-data` elements and switch keys. Values -9..9
 * need no help, bytes and shorts (`0x41t`, `0x1s`) are data rather than numbers to read, a
 * literal baksmali annotated itself (`0x3f800000    # 1.0f`) keeps its comment, and a
 * resource-shaped id nothing names (a project without `public.xml`, a non-public framework id)
 * is not a number anyone wants in decimal. `android.R` ids come from the bundled framework table. The lines are scanned on demand: the
 * AST keeps no literals.
 */
object InlayHints {

    /** The hints for the lines of [lines] within [range], in line order. */
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

    /** A hint's text and its tooltip, if the text was cut short. */
    class Hint(val label: String, val tooltip: String?)

    private fun isHex(text: String): Boolean {
        val at = if (text.startsWith("-")) 1 else 0
        return text.startsWith("0x", at) || text.startsWith("0X", at)
    }

    /** The literal is the value the instruction loads or computes with: `const*` (not a string or class) and `*-int/lit8`, `*-int/lit16`. */
    private fun carriesNumber(opcode: String): Boolean =
        opcode == "const" || opcode.startsWith("const/") || opcode.startsWith("const-wide") ||
            opcode.endsWith("/lit8") || opcode.endsWith("/lit16")
}
