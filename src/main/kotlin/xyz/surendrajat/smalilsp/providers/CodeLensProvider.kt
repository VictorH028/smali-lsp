package xyz.surendrajat.smalilsp.providers

import com.google.gson.JsonObject
import org.eclipse.lsp4j.*
import xyz.surendrajat.smalilsp.core.Span
import xyz.surendrajat.smalilsp.index.WorkspaceIndex

/**
 * Provides code lens (inline reference counts) for smali classes.
 *
 * Shows "N references" above the class and each method and field definition, and beside the
 * class how many classes extend or implement it; a lens's range is the declaration line, as
 * the protocol asks. Uses lazy resolution: provideCodeLenses returns unresolved lenses
 * (cheap), resolveCodeLens computes the actual count only when a lens becomes visible. The
 * subtype count is one map lookup, so it comes resolved.
 *
 * LSP Requests: textDocument/codeLens, codeLens/resolve
 */
class CodeLensProvider(
    private val index: WorkspaceIndex
) {
    private val references = ReferenceProvider(index)

    /**
     * Return unresolved code lenses (cheap — no reference counting yet).
     * Each lens carries data for resolution: className, memberName, memberType, descriptor.
     */
    fun provideCodeLenses(uri: String): List<CodeLens> {
        val file = index.findFileByUri(uri) ?: return emptyList()
        val className = file.classDefinition.name
        val lenses = mutableListOf<CodeLens>()

        val classDef = file.classDefinition
        val classLine = Span(classDef.nameSpan.line, classDef.range.start.character, classDef.nameSpan.end).toRange()
        lenses.add(CodeLens(classLine, null, classData(className, "class")))
        val isInterface = "interface" in classDef.modifiers
        val subtypes = if (isInterface) index.getDirectImplementors(className).size else index.getDirectSubclasses(className).size
        if (subtypes > 0) {
            val noun = if (isInterface) "implementation" else "subclass"
            lenses.add(CodeLens(classLine, Command("$subtypes $noun${if (subtypes != 1) (if (isInterface) "s" else "es") else ""}", ""), classData(className, "subtypes")))
        }

        for (method in file.methods) {
            val data = JsonObject()
            data.addProperty("className", className)
            data.addProperty("memberName", method.name)
            data.addProperty("memberType", "method")
            data.addProperty("descriptor", method.descriptor)
            lenses.add(CodeLens(Span(method.range.start.line, method.range.start.character, method.descriptorSpan.end).toRange(), null, data))
        }

        for (field in file.fields) {
            val data = JsonObject()
            data.addProperty("className", className)
            data.addProperty("memberName", field.name)
            data.addProperty("memberType", "field")
            data.addProperty("fieldType", field.type)
            lenses.add(CodeLens(Span(field.range.start.line, field.range.start.character, field.typeSpan.end).toRange(), null, data))
        }

        return lenses
    }

    /**
     * Resolve a single code lens by computing its reference count.
     * Called lazily by the client when the lens scrolls into view.
     */
    fun resolveCodeLens(lens: CodeLens): CodeLens {
        if (lens.command != null) return lens
        val data = lens.data
        if (data == null || data !is JsonObject) {
            lens.command = Command("? references", "")
            return lens
        }

        val className = data.get("className")?.asString ?: ""
        val memberName = data.get("memberName")?.asString ?: ""
        val memberType = data.get("memberType")?.asString ?: ""
        val descriptor = data.get("descriptor")?.asString ?: ""

        val count = when (memberType) {
            "class" -> references.findClassReferences(className, includeDeclaration = false).size
            "method" -> countMethodReferences(className, memberName, descriptor)
            "field" -> countFieldReferences(className, memberName, data.get("fieldType")?.asString ?: "")
            else -> 0
        }

        lens.command = Command(
            "$count reference${if (count != 1) "s" else ""}",
            ""
        )
        return lens
    }

    /** The data of a lens on the class line; the class is its own "member" so that clients and tests keying lenses by member name see it. */
    private fun classData(className: String, memberType: String): JsonObject = JsonObject().apply {
        addProperty("className", className)
        addProperty("memberName", className)
        addProperty("memberType", memberType)
    }

    /** Calls that dispatch to this declaration or to an override below it; the lens on an override counts its own callers, not the whole hierarchy's. */
    private fun countMethodReferences(className: String, methodName: String, descriptor: String): Int =
        references.methodFamily(className, methodName, descriptor, climb = false).usages.size

    private fun countFieldReferences(className: String, fieldName: String, fieldType: String): Int =
        references.fieldFamily(className, fieldName, fieldType).usages.size
}
