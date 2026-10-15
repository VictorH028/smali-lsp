package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Position
import xyz.surendrajat.smalilsp.core.ConstStringInstruction
import xyz.surendrajat.smalilsp.core.Symbol
import xyz.surendrajat.smalilsp.index.WorkspaceIndex

/**
 * The string operand of a `const-string`. The AST keeps the value but not the literal's
 * columns, so whether the caret is on it is read from the line: at or after the opening quote.
 */
object StringLiterals {

    /** The instruction whose string literal the caret is on, if [symbol] is on a `const-string` and the caret is not on its opcode or register. */
    fun at(index: WorkspaceIndex, uri: String, position: Position, symbol: Symbol?): ConstStringInstruction? {
        val instruction = (symbol as? Symbol.OnInstruction)?.instruction as? ConstStringInstruction ?: return null
        val line = index.getLineContent(uri, position.line) ?: return null
        val quote = line.indexOf('"')
        return if (quote >= 0 && position.character >= quote) instruction else null
    }
}
