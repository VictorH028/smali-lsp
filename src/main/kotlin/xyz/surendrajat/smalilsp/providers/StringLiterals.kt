package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Position
import xyz.surendrajat.smalilsp.core.ConstStringInstruction
import xyz.surendrajat.smalilsp.core.Symbol
import xyz.surendrajat.smalilsp.index.WorkspaceIndex

/** The string operand of a `const-string`; the AST has no columns for it, so the caret counts from the opening quote. */
object StringLiterals {

    /** The `const-string` whose literal the caret is on, else null. */
    fun at(index: WorkspaceIndex, uri: String, position: Position, symbol: Symbol?): ConstStringInstruction? {
        val instruction = (symbol as? Symbol.OnInstruction)?.instruction as? ConstStringInstruction ?: return null
        val line = index.getLineContent(uri, position.line) ?: return null
        val quote = line.indexOf('"')
        return if (quote >= 0 && position.character >= quote) instruction else null
    }
}
