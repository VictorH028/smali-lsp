package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import xyz.surendrajat.smalilsp.core.InstructionScanner
import xyz.surendrajat.smalilsp.core.InstructionText
import xyz.surendrajat.smalilsp.index.ResourceTable
import xyz.surendrajat.smalilsp.index.WorkspaceIndex

/**
 * Inlay hints say what a literal is. After a resource id, the resource's name and value
 * (`string/app_name = "Mail"`); after any other hex literal of a `const*` or `*-int/lit*`
 * instruction, its decimal value (`= 1000`). Values -9..9 need no help, a literal baksmali
 * annotated itself (`0x3f800000    # 1.0f`) keeps its comment, and a resource-shaped id the
 * table does not know (an `android.R` one, or a project without `public.xml`) is not a number
 * anyone wants in decimal. The lines are scanned on demand: the AST keeps no literals.
 */
object InlayHints {

    /** The hints for the lines of [lines] within [range], in line order. */
    fun hints(index: WorkspaceIndex, lines: List<String>, range: Range): List<InlayHint> {
        val out = ArrayList<InlayHint>()
        val scanner = InstructionScanner()
        val last = minOf(range.end.line, lines.size - 1)
        for (lineNumber in maxOf(0, range.start.line)..last) {
            val line = lines[lineNumber]
            if (!scanner.scan(line)) continue
            val literal = scanner.literal ?: continue
            if (!isHex(line, literal.start)) continue
            val value = InstructionText.literalValue(literal.text) ?: continue
            val hint = if (ResourceTable.isResourceId(value)) {
                val resource = index.resources[value.toInt()] ?: continue
                ResourceLiterals.hint(index, resource)
            } else {
                if (!carriesNumber(scanner) || value in -9L..9L || looksLikeResourceId(value)) continue
                if (line.indexOf('#', literal.end) >= 0) continue
                Hint("= $value", null)
            }
            out.add(InlayHint(Position(lineNumber, literal.end), Either.forLeft(hint.label)).apply {
                paddingLeft = true
                hint.tooltip?.let { tooltip = Either.forLeft(it) }
            })
        }
        return out
    }

    /** A hint's text and its tooltip, if the text was cut short. */
    class Hint(val label: String, val tooltip: String?)

    private fun isHex(line: String, start: Int): Boolean {
        val at = if (line[start] == '-') start + 1 else start
        return line.startsWith("0x", at) || line.startsWith("0X", at)
    }

    /** The literal is the value the instruction loads or computes with: `const*` (not a string or class) and `*-int/lit8`, `*-int/lit16`. */
    private fun carriesNumber(scanner: InstructionScanner): Boolean {
        val opcode = scanner.opcode
        return opcode == "const" || opcode.startsWith("const/") || opcode.startsWith("const-wide") ||
            opcode.endsWith("/lit8") || opcode.endsWith("/lit16")
    }

    /** The `android.R` id range (`0x01TTEEEE`); app ids are [ResourceTable.isResourceId]. */
    private fun looksLikeResourceId(value: Long): Boolean = value in 0x01010000L..0x01ffffffL
}
