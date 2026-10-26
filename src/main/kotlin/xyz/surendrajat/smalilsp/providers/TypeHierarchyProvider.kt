package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.*
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.core.Symbol
import xyz.surendrajat.smalilsp.core.symbolAt
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.util.Types

/** Type hierarchy from a class declaration or any class reference: supertypes and subtypes. */
class TypeHierarchyProvider(
    private val index: WorkspaceIndex
) {

    /**
     * The class under the caret: any class reference that is in the workspace (`.super`,
     * `.implements`, an owner, a type operand, a descriptor type), else the file's own class.
     */
    fun prepare(uri: String, position: Position): List<TypeHierarchyItem> {
        val file = index.findFileByUri(uri) ?: return emptyList()
        val referenced = (file.symbolAt(position) as? Symbol.ClassRef)?.let { index.findClass(it.className) }
        return listOf(createItem(referenced ?: file))
    }

    /**
     * Find all supertypes (parent class + implemented interfaces).
     */
    fun supertypes(item: TypeHierarchyItem): List<TypeHierarchyItem> {
        val className = item.detail ?: return emptyList()
        val file = index.findClass(className) ?: return emptyList()
        val results = mutableListOf<TypeHierarchyItem>()

        // Add superclass
        file.classDefinition.superClass?.let { superName ->
            val superFile = index.findClass(superName)
            if (superFile != null) {
                results.add(createItem(superFile))
            } else if (superName != "Ljava/lang/Object;") {
                results.add(createExternalItem(superName, file, file.classDefinition.superClassRange))
            }
        }

        // Add interfaces
        for (ifaceName in file.classDefinition.interfaces) {
            val ifaceFile = index.findClass(ifaceName)
            if (ifaceFile != null) {
                results.add(createItem(ifaceFile))
            } else {
                results.add(createExternalItem(ifaceName, file, file.classDefinition.interfaceRanges[ifaceName]))
            }
        }

        return results
    }

    /**
     * Find all subtypes (direct subclasses and implementors).
     */
    fun subtypes(item: TypeHierarchyItem): List<TypeHierarchyItem> {
        val className = item.detail ?: return emptyList()
        val results = mutableListOf<TypeHierarchyItem>()

        // Direct subclasses (O(1) lookup)
        for (sub in index.getDirectSubclasses(className)) {
            val file = index.findClass(sub) ?: continue
            results.add(createItem(file))
        }

        // Direct implementors (O(1) lookup)
        for (impl in index.getDirectImplementors(className)) {
            val file = index.findClass(impl) ?: continue
            results.add(createItem(file))
        }

        return results
    }

    private fun createItem(file: SmaliFile): TypeHierarchyItem {
        val classDef = file.classDefinition
        val kind = if (classDef.modifiers.contains("interface")) SymbolKind.Interface else SymbolKind.Class
        val item = TypeHierarchyItem(
            Types.simpleName(classDef.name),
            kind,
            file.uri,
            classDef.range,
            classDef.nameSpan.toRange(),
            classDef.name
        )
        return item
    }

    /** A class outside the workspace has no file to open: the item points at the `.super` or `.implements` token that names it. */
    private fun createExternalItem(className: String, from: SmaliFile, token: Range?): TypeHierarchyItem {
        val range = token ?: from.classDefinition.nameSpan.toRange()
        return TypeHierarchyItem(Types.simpleName(className), SymbolKind.Class, from.uri, range, range, className)
    }
}
