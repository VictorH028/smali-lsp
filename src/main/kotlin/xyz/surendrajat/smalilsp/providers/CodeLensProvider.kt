package xyz.surendrajat.smalilsp.providers

import com.google.gson.JsonObject
import org.eclipse.lsp4j.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex

/**
 * Provides code lens (inline reference counts) for smali classes.
 *
 * Shows "N references" above each method and field definition.
 * Uses lazy resolution: provideCodeLenses returns unresolved lenses (cheap),
 * resolveCodeLens computes the actual count only when a lens becomes visible.
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

        for (method in file.methods) {
            val data = JsonObject()
            data.addProperty("className", className)
            data.addProperty("memberName", method.name)
            data.addProperty("memberType", "method")
            data.addProperty("descriptor", method.descriptor)
            lenses.add(CodeLens(method.range, null, data))
        }

        for (field in file.fields) {
            val data = JsonObject()
            data.addProperty("className", className)
            data.addProperty("memberName", field.name)
            data.addProperty("memberType", "field")
            data.addProperty("fieldType", field.type)
            lenses.add(CodeLens(field.range, null, data))
        }

        return lenses
    }

    /**
     * Resolve a single code lens by computing its reference count.
     * Called lazily by the client when the lens scrolls into view.
     */
    fun resolveCodeLens(lens: CodeLens): CodeLens {
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

    /** Calls that dispatch to this declaration or to an override below it; the lens on an override counts its own callers, not the whole hierarchy's. */
    private fun countMethodReferences(className: String, methodName: String, descriptor: String): Int =
        references.methodFamily(className, methodName, descriptor, climb = false).usages.size

    private fun countFieldReferences(className: String, fieldName: String, fieldType: String): Int =
        references.fieldFamily(className, fieldName, fieldType).usages.size
}
