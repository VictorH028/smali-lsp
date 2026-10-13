package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.Position
import xyz.surendrajat.smalilsp.core.FieldAccessInstruction
import xyz.surendrajat.smalilsp.core.Symbol
import xyz.surendrajat.smalilsp.core.symbolAt
import xyz.surendrajat.smalilsp.index.WorkspaceIndex

/**
 * `textDocument/documentHighlight`: every occurrence in the file of the symbol under the caret,
 * which is its references restricted to the file. Field accesses are marked as reads or writes
 * (`iput`/`sput` write); everything else is a plain occurrence.
 */
class DocumentHighlightProvider(private val index: WorkspaceIndex) {

    private val references = ReferenceProvider(index)

    fun highlights(uri: String, position: Position): List<DocumentHighlight> {
        val file = index.findFileByUri(uri) ?: return emptyList()
        val symbol = file.symbolAt(position) ?: return emptyList()
        val isField = when (symbol) {
            is Symbol.FieldDecl, is Symbol.FieldRef -> true
            is Symbol.Primitive -> symbol.inside is FieldAccessInstruction
            is Symbol.OnInstruction -> symbol.instruction is FieldAccessInstruction
            else -> false
        }
        val writes = if (!isField) emptySet() else file.methods.asSequence()
            .flatMap { it.instructions.asSequence() }
            .filterIsInstance<FieldAccessInstruction>()
            .filter { it.opcode.startsWith("iput") || it.opcode.startsWith("sput") }
            .mapNotNull { it.nameSpan }
            .map { it.line to it.start }
            .toSet()
        return references.findReferences(uri, position, includeDeclaration = true)
            .filter { WorkspaceIndex.canonicalUri(it.uri) == file.uri || it.uri == file.uri }
            .distinctBy { it.range.start.line to it.range.start.character }
            .sortedWith(compareBy({ it.range.start.line }, { it.range.start.character }))
            .map { location ->
                val kind = when {
                    !isField -> DocumentHighlightKind.Text
                    (location.range.start.line to location.range.start.character) in writes -> DocumentHighlightKind.Write
                    else -> DocumentHighlightKind.Read
                }
                DocumentHighlight(location.range, kind)
            }
    }
}
