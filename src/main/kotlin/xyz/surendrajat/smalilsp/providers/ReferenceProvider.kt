package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.util.Types

/**
 * Find references. Classes: subclasses, implementors and every use as a type or owner.
 * Methods: every call site, including calls through subclasses. Fields: every access.
 * Labels: every reference in the method. All answered from the reverse indexes; the symbol
 * under the caret comes from [SmaliFile.symbolAt].
 */
class ReferenceProvider(
    private val workspaceIndex: WorkspaceIndex
) {

    fun findReferences(
        uri: String,
        position: Position,
        includeDeclaration: Boolean = true
    ): List<Location> {
        val file = workspaceIndex.findFileByUri(uri) ?: return emptyList()
        return when (val symbol = file.symbolAt(position)) {
            null -> emptyList()
            is Symbol.ClassDecl -> findClassReferences(symbol.classDef.name, includeDeclaration)
            // A type inside an invoke's descriptor: the user is on a call, so answer with the call's references
            is Symbol.ClassRef -> symbol.descriptorOf?.let { methodReferences(it, includeDeclaration) }
                ?: findClassReferences(symbol.className, includeDeclaration)
            is Symbol.MethodDecl -> findMethodReferences(file.classDefinition.name, symbol.method.name, symbol.method.descriptor, includeDeclaration, symbol.method.range)
            is Symbol.FieldDecl -> findFieldReferences(file.classDefinition.name, symbol.fieldDef.name, symbol.fieldDef.type, includeDeclaration, symbol.fieldDef.range)
            is Symbol.MethodRef -> methodReferences(symbol.instruction, includeDeclaration)
            is Symbol.FieldRef -> fieldReferences(symbol.instruction, includeDeclaration)
            is Symbol.Primitive -> when (val inside = symbol.inside) {
                is InvokeInstruction -> methodReferences(inside, includeDeclaration)
                is FieldAccessInstruction -> fieldReferences(inside, includeDeclaration)
                else -> emptyList()
            }
            is Symbol.LabelDecl -> findLabelReferences(symbol.label.name, uri, symbol.method, includeDeclaration)
            is Symbol.LabelRef -> findLabelReferences(symbol.name, uri, symbol.method, includeDeclaration)
            is Symbol.OnInstruction -> (symbol.instruction as? JumpInstruction)
                ?.let { findLabelReferences(it.targetLabel, uri, symbol.method, includeDeclaration) }
                ?: emptyList()
        }
    }

    /**
     * Declaration (if requested), `.super`/`.implements` lines of subclasses and implementors,
     * then every other use: instructions, field types, method signatures.
     */
    fun findClassReferences(className: String, includeDeclaration: Boolean): List<Location> {
        val locations = mutableListOf<Location>()

        if (includeDeclaration) {
            workspaceIndex.findClass(className)?.let { locations.add(Location(it.uri, it.classDefinition.range)) }
        }

        for (usageUri in workspaceIndex.findClassUsages(className)) {
            val usageFile = workspaceIndex.findClassNameByUri(usageUri)?.let { workspaceIndex.findClass(it) } ?: continue
            val definition = usageFile.classDefinition
            if (definition.superClass == className) {
                locations.add(Location(usageFile.uri, definition.superClassRange ?: definition.range))
            }
            if (className in definition.interfaces) {
                locations.add(Location(usageFile.uri, definition.interfaceRanges[className] ?: definition.range))
            }
        }

        locations.addAll(workspaceIndex.findClassRefLocations(className))

        return locations.distinctBy { "${it.uri}:${it.range.start.line}:${it.range.start.character}" }
    }

    private fun methodReferences(instruction: InvokeInstruction, includeDeclaration: Boolean): List<Location> =
        findMethodReferences(instruction.className, instruction.methodName, instruction.descriptor, includeDeclaration)

    private fun fieldReferences(instruction: FieldAccessInstruction, includeDeclaration: Boolean): List<Location> =
        findFieldReferences(instruction.className, instruction.fieldName, instruction.fieldType, includeDeclaration)

    /** The method's declaration when its class is in the workspace, then every call site. */
    fun findMethodReferences(className: String, methodName: String, descriptor: String, includeDeclaration: Boolean): List<Location> {
        val declaration = workspaceIndex.findClass(className)?.methods?.find { it.name == methodName && it.descriptor == descriptor }
        return findMethodReferences(className, methodName, descriptor, includeDeclaration && declaration != null, declaration?.range)
    }

    /** The field's declaration when its class is in the workspace, then every access. */
    fun findFieldReferences(className: String, fieldName: String, fieldType: String, includeDeclaration: Boolean): List<Location> {
        val declaration = workspaceIndex.findClass(className)?.fields?.find { it.name == fieldName && it.type == fieldType }
        return findFieldReferences(className, fieldName, fieldType, includeDeclaration && declaration != null, declaration?.range)
    }

    /**
     * Direct call sites plus calls through subclasses and implementors. Polymorphic matching is
     * skipped for SDK classes, where it would list every `Object.<init>` in the workspace.
     */
    private fun findMethodReferences(
        className: String,
        methodName: String,
        descriptor: String,
        includeDeclaration: Boolean,
        declarationRange: Range?
    ): List<Location> {
        val locations = mutableListOf<Location>()

        if (includeDeclaration && declarationRange != null) {
            workspaceIndex.findClass(className)?.let { locations.add(Location(it.uri, declarationRange)) }
        }

        locations.addAll(workspaceIndex.findMethodUsages(className, methodName, descriptor))

        if (!Types.isSdkClass(className)) {
            for (subClass in workspaceIndex.getAllSubclasses(className)) {
                locations.addAll(workspaceIndex.findMethodUsages(subClass, methodName, descriptor))
            }
        }

        return locations
    }

    private fun findFieldReferences(
        className: String,
        fieldName: String,
        fieldType: String,
        includeDeclaration: Boolean,
        declarationRange: Range?
    ): List<Location> {
        val locations = mutableListOf<Location>()

        if (includeDeclaration && declarationRange != null) {
            workspaceIndex.findClass(className)?.let { locations.add(Location(it.uri, declarationRange)) }
        }

        locations.addAll(workspaceIndex.findFieldUsages(className, fieldName, fieldType))

        return locations
    }

    /** Labels are method-local: the definition (if requested) and every reference in [method]. */
    private fun findLabelReferences(
        labelName: String,
        uri: String,
        method: MethodDefinition,
        includeDeclaration: Boolean
    ): List<Location> {
        val locations = mutableListOf<Location>()
        if (includeDeclaration) {
            method.labels[labelName]?.let { locations.add(Location(uri, it.range)) }
        }
        for (ref in method.labelRefs) {
            if (ref.name == labelName) locations.add(Location(uri, ref.span.toRange()))
        }
        return locations
    }
}
