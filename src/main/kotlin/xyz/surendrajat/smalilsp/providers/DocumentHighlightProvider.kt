package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import xyz.surendrajat.smalilsp.core.FieldAccessInstruction
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.core.Span
import xyz.surendrajat.smalilsp.core.Symbol
import xyz.surendrajat.smalilsp.core.TypeInstruction
import xyz.surendrajat.smalilsp.core.symbolAt
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.util.Types

/**
 * `textDocument/documentHighlight`: every occurrence in the file of the symbol under the caret.
 * The file is scanned directly (the request fires on every caret move, and a class such as
 * `Object` has hundreds of thousands of references elsewhere); the member family decides which
 * owners count. Field accesses are marked as reads or writes (`iput`/`sput` write).
 */
class DocumentHighlightProvider(private val index: WorkspaceIndex) {

    private val references = ReferenceProvider(index)

    fun highlights(uri: String, position: Position): List<DocumentHighlight> {
        val file = index.findFileByUri(uri) ?: return emptyList()
        val symbol = file.symbolAt(position)
        // A caret on a register, which the AST does not model, highlights the register's uses in its method
        if (symbol == null || symbol is Symbol.OnInstruction) {
            val lines = index.getLines(uri)
            if (lines != null) Registers.at(file, lines, position)?.let { (method, register) -> return Registers.highlights(method, lines, register.text) }
        }
        val highlights = when (symbol) {
            null -> emptyList()
            is Symbol.ClassDecl -> classOccurrences(file, symbol.classDef.name)
            is Symbol.ClassRef -> symbol.descriptorOf?.let { methodOccurrences(file, it.className, it.methodName, it.descriptor) } ?: classOccurrences(file, symbol.className)
            is Symbol.MethodDecl -> methodOccurrences(file, file.classDefinition.name, symbol.method.name, symbol.method.descriptor)
            is Symbol.FieldDecl -> fieldOccurrences(file, file.classDefinition.name, symbol.fieldDef.name, symbol.fieldDef.type)
            is Symbol.MethodRef -> methodOccurrences(file, symbol.instruction)
            is Symbol.FieldRef -> fieldOccurrences(file, symbol.instruction)
            is Symbol.Primitive -> when (val inside = symbol.inside) {
                is InvokeInstruction -> methodOccurrences(file, inside)
                is FieldAccessInstruction -> fieldOccurrences(file, inside)
                else -> emptyList()
            }
            is Symbol.LabelDecl, is Symbol.LabelRef -> labelOccurrences(uri, position)
            is Symbol.OnInstruction -> emptyList()   // the opcode or a string (a register returned above)
        }
        return highlights
            .distinctBy { it.range.start.line to it.range.start.character }
            .sortedWith(compareBy({ it.range.start.line }, { it.range.start.character }))
    }

    private fun text(span: Span) = DocumentHighlight(span.toRange(), DocumentHighlightKind.Text)
    private fun text(range: Range) = DocumentHighlight(range, DocumentHighlightKind.Text)

    /** Every token in the file naming [className]: the header, field types, descriptor types, owners, operands, directive references. */
    private fun classOccurrences(file: SmaliFile, className: String): List<DocumentHighlight> {
        val out = ArrayList<DocumentHighlight>()
        val c = file.classDefinition
        if (c.name == className) out.add(text(c.nameSpan))
        if (c.superClass == className) c.superClassRange?.let { out.add(text(it)) }
        c.interfaceRanges[className]?.let { out.add(text(it)) }
        for (field in file.fields) if (Types.className(field.type) == className) out.add(text(field.typeSpan))
        for (method in file.methods) {
            val descriptor = method.descriptorSpan
            if (descriptor.length == method.descriptor.length) {
                for (type in Types.parseTypeSequence(method.descriptor)) {
                    if (type.className == className) out.add(text(Span(descriptor.line, descriptor.start + type.start, descriptor.start + type.end)))
                }
            }
            for (instruction in method.instructions) {
                when (instruction) {
                    is InvokeInstruction -> if (Types.className(instruction.className) == className) instruction.ownerSpan?.let { out.add(text(it)) }
                    is FieldAccessInstruction -> {
                        if (Types.className(instruction.className) == className) instruction.ownerSpan?.let { out.add(text(it)) }
                        if (Types.className(instruction.fieldType) == className) instruction.typeSpan?.let { out.add(text(it)) }
                    }
                    is TypeInstruction -> if (Types.className(instruction.className) == className) instruction.typeSpan?.let { out.add(text(it)) }
                    else -> {}
                }
            }
        }
        for (ref in file.typeRefs) if (ref.className == className) out.add(text(ref.span))
        return out
    }

    private fun methodOccurrences(file: SmaliFile, call: InvokeInstruction) = methodOccurrences(file, call.className, call.methodName, call.descriptor)

    private fun methodOccurrences(file: SmaliFile, className: String, methodName: String, descriptor: String): List<DocumentHighlight> {
        val family = references.methodFamily(className, methodName, descriptor)
        val out = ArrayList<DocumentHighlight>()
        for (declaration in family.declarations) if (declaration.file.uri == file.uri) out.add(text(declaration.nameSpan))
        for (method in file.methods) for (instruction in method.instructions) {
            if (instruction is InvokeInstruction && instruction.methodName == methodName && instruction.descriptor == descriptor && family.includes(instruction.className)) {
                instruction.nameSpan?.let { out.add(text(it)) }
            }
        }
        return out
    }

    private fun fieldOccurrences(file: SmaliFile, access: FieldAccessInstruction) = fieldOccurrences(file, access.className, access.fieldName, access.fieldType)

    private fun fieldOccurrences(file: SmaliFile, className: String, fieldName: String, fieldType: String): List<DocumentHighlight> {
        val family = references.fieldFamily(className, fieldName, fieldType)
        val out = ArrayList<DocumentHighlight>()
        for (declaration in family.declarations) if (declaration.file.uri == file.uri) out.add(DocumentHighlight(declaration.nameSpan.toRange(), DocumentHighlightKind.Read))
        for (method in file.methods) for (instruction in method.instructions) {
            if (instruction is FieldAccessInstruction && instruction.fieldName == fieldName && instruction.fieldType == fieldType && family.includes(instruction.className)) {
                val kind = if (instruction.opcode.startsWith("iput") || instruction.opcode.startsWith("sput")) DocumentHighlightKind.Write else DocumentHighlightKind.Read
                instruction.nameSpan?.let { out.add(DocumentHighlight(it.toRange(), kind)) }
            }
        }
        return out
    }

    /** Labels are method-local, so their references are the occurrences. */
    private fun labelOccurrences(uri: String, position: Position): List<DocumentHighlight> =
        references.findReferences(uri, position, includeDeclaration = true).map { text(it.range) }
}
