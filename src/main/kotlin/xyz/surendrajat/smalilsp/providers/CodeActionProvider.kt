package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import xyz.surendrajat.smalilsp.core.InstructionText
import xyz.surendrajat.smalilsp.core.RegisterFrame
import xyz.surendrajat.smalilsp.core.methodAt
import xyz.surendrajat.smalilsp.index.WorkspaceIndex

/**
 * The answers a diagnostic can carry in its `data`, for the diagnostics with exactly one right
 * fix: which opcode to use, that the line goes, that a call wants its `/range` form, what the
 * frame directive should say, which label was meant. [CodeActionProvider] turns them into edits
 * against the current text.
 */
object Fixes {
    fun opcode(to: String, operands: Boolean = true): Map<String, Any> =
        if (operands) mapOf("fix" to "opcode", "to" to to) else mapOf("fix" to "opcode", "to" to to, "operands" to false)
    val deleteLine: Map<String, Any> = mapOf("fix" to "delete-line")
    val removeLabel: Map<String, Any> = mapOf("fix" to "remove-label")
    val rangeForm: Map<String, Any> = mapOf("fix" to "range-form")
    val swapRange: Map<String, Any> = mapOf("fix" to "swap-range")
    fun label(to: String): Map<String, Any> = mapOf("fix" to "label", "to" to to)

    /** The frame directive's new count for [total] registers in all: `.locals` counts the parameters out. */
    fun frame(frame: RegisterFrame, total: Int): Map<String, Any> =
        mapOf("fix" to "frame", "to" to if (frame.directive == ".locals") total - frame.parameters else total)
}

/** Quick fixes: one code action per diagnostic in the requested range that carries a [Fixes] answer. */
class CodeActionProvider(private val index: WorkspaceIndex, private val diagnostics: DiagnosticProvider) {

    /** The diagnostics are computed from the current text, so an edit cannot land on a line the client's copy of the diagnostic no longer describes. */
    fun actions(uri: String, range: Range): List<CodeAction> {
        val content = index.getDocumentContent(uri) ?: return emptyList()
        val lines = index.getLines(uri) ?: return emptyList()
        val out = ArrayList<CodeAction>()
        for (diagnostic in diagnostics.computeDiagnostics(uri, content)) {
            if (!touches(diagnostic.range, range)) continue
            val fix = diagnostic.data as? Map<*, *> ?: continue
            val (title, edits) = edits(uri, lines, diagnostic, fix) ?: continue
            out.add(CodeAction(title).apply {
                kind = CodeActionKind.QuickFix
                this.diagnostics = listOf(diagnostic)
                edit = WorkspaceEdit(mapOf(uri to edits))
                isPreferred = true
            })
        }
        return out
    }

    /** The diagnostic shares a line with the request: a caret anywhere on the line gets the line's fixes. */
    private fun touches(a: Range, b: Range): Boolean = a.start.line <= b.end.line && b.start.line <= a.end.line

    private fun edits(uri: String, lines: List<String>, diagnostic: Diagnostic, fix: Map<*, *>): Pair<String, List<TextEdit>>? {
        val lineNumber = diagnostic.range.start.line
        val line = lines.getOrNull(lineNumber) ?: return null
        return when (fix["fix"]) {
            "opcode" -> {
                val to = fix["to"] as? String ?: return null
                val decoded = InstructionText.decode(line) ?: return null
                val end = if (fix["operands"] == false) codeEnd(line) else decoded.opcodeEnd
                "Use $to" to listOf(TextEdit(Range(Position(lineNumber, decoded.opcodeStart), Position(lineNumber, end)), to))
            }
            "delete-line" -> "Remove this ${line.trim().substringBefore(' ')}" to listOf(deleteLine(lines, lineNumber))
            "remove-label" -> {
                val rest = line.removeRange(diagnostic.range.start.character, diagnostic.range.end.character)
                val edit = if (rest.substring(0, codeEnd(rest)).isBlank()) deleteLine(lines, lineNumber) else TextEdit(diagnostic.range, "")
                "Remove the duplicate label" to listOf(edit)
            }
            "range-form" -> rangeForm(uri, lines, lineNumber, line)
            "swap-range" -> {
                val decoded = InstructionText.decode(line) ?: return null
                if (decoded.registers.size < 2) return null
                val (first, last) = decoded.registers
                "Swap the range ends" to listOf(TextEdit(Range(Position(lineNumber, first.start), Position(lineNumber, last.end)), "${last.text} .. ${first.text}"))
            }
            "frame" -> frame(uri, lines, lineNumber, (fix["to"] as? Number)?.toInt() ?: return null)
            "label" -> {
                val to = fix["to"] as? String ?: return null
                "Rename to $to" to listOf(TextEdit(diagnostic.range, to))
            }
            else -> null
        }
    }

    /** `{v0, v1, v2}` to `{v0 .. v2}` and the opcode to its `/range` form, when the registers are consecutive in the frame. */
    private fun rangeForm(uri: String, lines: List<String>, lineNumber: Int, line: String): Pair<String, List<TextEdit>>? {
        val decoded = InstructionText.decode(line) ?: return null
        if (decoded.isRange || decoded.registers.isEmpty()) return null
        val method = index.findFileByUri(uri)?.methodAt(lineNumber) ?: return null
        val frame = RegisterFrame.of(method, lines) ?: return null
        val registers = decoded.registers
        for (i in 1 until registers.size) if (frame.absolute(registers[i]) != frame.absolute(registers[i - 1]) + 1) return null
        val open = line.indexOf('{', decoded.opcodeEnd)
        val close = if (open < 0) -1 else line.indexOf('}', open)
        if (close < 0) return null
        val opcode = decoded.opcode
        return "Use $opcode/range" to listOf(
            TextEdit(Range(Position(lineNumber, decoded.opcodeStart), Position(lineNumber, decoded.opcodeEnd)), "$opcode/range"),
            TextEdit(Range(Position(lineNumber, open), Position(lineNumber, close + 1)), "{${registers.first().text} .. ${registers.last().text}}")
        )
    }

    /** The count of the method's `.registers` or `.locals` directive set to [to]. */
    private fun frame(uri: String, lines: List<String>, lineNumber: Int, to: Int): Pair<String, List<TextEdit>>? {
        val method = index.findFileByUri(uri)?.methodAt(lineNumber) ?: return null
        for (l in method.startLine + 1..minOf(method.endLine, lines.size - 1)) {
            val directive = RegisterFrame.frameDirective(lines[l]) ?: continue
            if (directive.countEnd == directive.countStart) return null
            return "Set ${directive.name} $to" to listOf(TextEdit(Range(Position(l, directive.countStart), Position(l, directive.countEnd)), to.toString()))
        }
        return null
    }

    /** Where the code on [line] ends: before a trailing comment and the blanks ahead of it. */
    private fun codeEnd(line: String): Int {
        var end = line.indexOf('#').let { if (it < 0) line.length else it }
        while (end > 0 && line[end - 1].isWhitespace()) end--
        return end
    }

    /** The whole line with its line break; the last line takes the break before it. */
    private fun deleteLine(lines: List<String>, lineNumber: Int): TextEdit =
        if (lineNumber + 1 < lines.size) TextEdit(Range(Position(lineNumber, 0), Position(lineNumber + 1, 0)), "")
        else TextEdit(Range(Position(lineNumber - 1, lines[lineNumber - 1].length), Position(lineNumber, lines[lineNumber].length)), "")
}
