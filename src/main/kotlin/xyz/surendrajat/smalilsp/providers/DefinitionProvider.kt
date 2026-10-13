package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.slf4j.LoggerFactory
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.util.Types

/**
 * Go to definition. The symbol under the caret comes from the AST ([SmaliFile.symbolAt]);
 * declarations are looked up in the workspace index.
 */
class DefinitionProvider(
    private val workspaceIndex: WorkspaceIndex
) {
    private val logger = LoggerFactory.getLogger(DefinitionProvider::class.java)

    fun findDefinition(uri: String, position: Position): List<Location> {
        val file = workspaceIndex.findFileByUri(uri) ?: run {
            logger.debug("Not indexed: {}", uri)
            return emptyList()
        }
        return when (val symbol = file.symbolAt(position)) {
            null -> emptyList()
            is Symbol.ClassDecl -> listOf(Location(uri, symbol.range))
            is Symbol.MethodDecl, is Symbol.FieldDecl -> emptyList()
            is Symbol.ClassRef -> classDefinition(symbol.className)
            is Symbol.MethodRef -> methodDefinition(symbol.instruction)
            is Symbol.FieldRef -> fieldDefinition(symbol.instruction)
            is Symbol.Primitive -> when (val inside = symbol.inside) {
                is InvokeInstruction -> methodDefinition(inside)
                is FieldAccessInstruction -> fieldDefinition(inside)
                else -> emptyList()
            }
            is Symbol.LabelDecl -> listOf(Location(uri, symbol.label.range))
            is Symbol.LabelRef -> labelDefinition(symbol.method, symbol.name, uri)
            // Anywhere else on an instruction: its primary symbol
            is Symbol.OnInstruction -> when (val instruction = symbol.instruction) {
                is InvokeInstruction -> methodDefinition(instruction)
                is FieldAccessInstruction -> fieldDefinition(instruction)
                is TypeInstruction -> classDefinition(instruction.className)
                is JumpInstruction -> labelDefinition(symbol.method, instruction.targetLabel, uri)
                is ConstStringInstruction -> emptyList()
            }
        }
    }

    /**
     * Whatever is indexed is navigable, including bundled copies of SDK-prefixed classes
     * (androidx.*, kotlin.*, desugared java.util.function.*): their smali is in the workspace
     * and is the code that runs. Classes that are not indexed have no source to navigate to.
     */
    private fun classDefinition(className: String): List<Location> {
        val file = workspaceIndex.findClass(Types.elementClass(className)) ?: return emptyList()
        return listOf(Location(file.uri, file.classDefinition.nameSpan.toRange()))
    }

    private fun methodDefinition(instruction: InvokeInstruction): List<Location> =
        workspaceIndex.findMethod(instruction.className, instruction.methodName, instruction.descriptor).toList()

    private fun fieldDefinition(instruction: FieldAccessInstruction): List<Location> =
        listOfNotNull(workspaceIndex.findField(instruction.className, instruction.fieldName, instruction.fieldType))

    /** Labels are method-local and names repeat across methods (`:cond_0` is in nearly every one). */
    private fun labelDefinition(method: MethodDefinition, name: String, uri: String): List<Location> =
        listOfNotNull(method.labels[name]?.let { Location(uri, it.range) })
}
