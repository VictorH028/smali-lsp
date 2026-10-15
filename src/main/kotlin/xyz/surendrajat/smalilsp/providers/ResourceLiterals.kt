package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import xyz.surendrajat.smalilsp.core.InstructionScanner
import xyz.surendrajat.smalilsp.core.InstructionText
import xyz.surendrajat.smalilsp.index.Resource
import xyz.surendrajat.smalilsp.index.ResourceTable
import xyz.surendrajat.smalilsp.index.WorkspaceIndex

/**
 * Resource ids where they appear in code: the literal of a `const`, `const/high16` or
 * `const/16` under the caret, when the workspace's `public.xml` names it. Literals are not in
 * the AST, so the line is decoded on demand, as registers are.
 */
object ResourceLiterals {

    /** The resource the literal under the caret names, if any. */
    fun at(index: WorkspaceIndex, uri: String, position: Position): Resource? {
        if (index.resources.size == 0) return null
        val line = index.getLineContent(uri, position.line) ?: return null
        val scanner = InstructionScanner()
        if (!scanner.scan(line)) return null
        val literal = scanner.literal ?: return null
        if (!literal.touches(position.character)) return null
        val value = InstructionText.literalValue(literal.text) ?: return null
        if (!ResourceTable.isResourceId(value)) return null
        return index.resources[value.toInt()]
    }

    fun hover(resource: Resource): Hover =
        Hover(MarkupContent(MarkupKind.MARKDOWN, "**Resource:** `${resource.qualifiedName}` (`0x${Integer.toHexString(resource.id)}`)\n\n*From* `res/values/public.xml`"))
}
