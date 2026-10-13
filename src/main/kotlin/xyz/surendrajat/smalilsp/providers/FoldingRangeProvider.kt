package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeKind
import xyz.surendrajat.smalilsp.core.SmaliFile

/** `textDocument/foldingRange`: every method body and every annotation, switch-table and array-data block that spans more than one line. */
class FoldingRangeProvider {

    fun foldingRanges(file: SmaliFile): List<FoldingRange> {
        val ranges = ArrayList<FoldingRange>()
        for (method in file.methods) {
            if (method.range.end.line > method.range.start.line) ranges.add(region(method.range.start.line, method.range.end.line))
        }
        for (block in file.blocks) {
            if (block.endLine > block.startLine) ranges.add(region(block.startLine, block.endLine))
        }
        return ranges.sortedWith(compareBy({ it.startLine }, { it.endLine }))
    }

    private fun region(startLine: Int, endLine: Int) = FoldingRange(startLine, endLine).apply { kind = FoldingRangeKind.Region }
}
