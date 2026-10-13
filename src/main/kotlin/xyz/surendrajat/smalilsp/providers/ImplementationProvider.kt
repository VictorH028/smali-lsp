package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.core.Symbol
import xyz.surendrajat.smalilsp.core.TypeInstruction
import xyz.surendrajat.smalilsp.core.symbolAt
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.util.Types

/**
 * `textDocument/implementation`: for a method, the concrete declarations of its member family
 * other than the one under the caret (the implementations of an interface method, the
 * overrides of a virtual one); for a class, the classes that extend or implement it.
 */
class ImplementationProvider(private val index: WorkspaceIndex) {

    private val references = ReferenceProvider(index)

    fun findImplementations(uri: String, position: Position): List<Location> {
        val file = index.findFileByUri(uri) ?: return emptyList()
        return when (val symbol = file.symbolAt(position)) {
            is Symbol.ClassDecl -> subtypes(symbol.classDef.name)
            is Symbol.ClassRef -> symbol.descriptorOf?.let { implementationsOf(it) } ?: subtypes(symbol.className)
            is Symbol.MethodDecl -> implementationsOf(file.classDefinition.name, symbol.method.name, symbol.method.descriptor, file.uri to symbol.range)
            is Symbol.MethodRef -> implementationsOf(symbol.instruction)
            is Symbol.Primitive -> (symbol.inside as? InvokeInstruction)?.let { implementationsOf(it) } ?: emptyList()
            is Symbol.OnInstruction -> when (val instruction = symbol.instruction) {
                is InvokeInstruction -> implementationsOf(instruction)
                is TypeInstruction -> subtypes(Types.elementClass(instruction.className))
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun implementationsOf(call: InvokeInstruction) = implementationsOf(call.className, call.methodName, call.descriptor, null)

    private fun implementationsOf(className: String, methodName: String, descriptor: String, caret: Pair<String, Range>?): List<Location> =
        references.methodFamily(className, methodName, descriptor).declarations
            .filter { "abstract" !in it.modifiers }
            .map { Location(it.file.uri, it.nameSpan.toRange()) }
            .filter { caret == null || it.uri != caret.first || it.range != caret.second }

    /** The classes below [className], at their `.class` name tokens, in file order. */
    private fun subtypes(className: String): List<Location> =
        index.getAllSubclasses(className)
            .mapNotNull { index.findClass(it) }
            .map { Location(it.uri, it.classDefinition.nameSpan.toRange()) }
            .sortedBy { it.uri }
}
