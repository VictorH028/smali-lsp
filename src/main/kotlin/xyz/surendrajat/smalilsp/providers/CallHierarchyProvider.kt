package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.*
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.core.MethodDefinition
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.core.Symbol
import xyz.surendrajat.smalilsp.core.symbolAt
import xyz.surendrajat.smalilsp.core.methodAt
import xyz.surendrajat.smalilsp.core.touches
import xyz.surendrajat.smalilsp.index.WorkspaceIndex

/**
 * Provides call hierarchy (incoming/outgoing calls) for smali methods.
 *
 * LSP Requests:
 * - textDocument/prepareCallHierarchy
 * - callHierarchy/incomingCalls
 * - callHierarchy/outgoingCalls
 */
class CallHierarchyProvider(
    private val index: WorkspaceIndex
) {
    private val references = ReferenceProvider(index)

    /**
     * The method under the caret: the declaration a call resolves to when the caret is on a
     * call (as an IDE shows the hierarchy of the called method), else the method containing the
     * caret.
     */
    fun prepare(uri: String, position: Position): List<CallHierarchyItem> {
        val file = index.findFileByUri(uri) ?: return emptyList()
        val called = (file.symbolAt(position) as? Symbol.MethodRef)?.instruction
            ?.let { index.resolveMethod(it.className, it.methodName, it.descriptor) }
        if (called != null) return listOf(createItem(called.file, called.method))
        val method = file.methods.find { it.range.touches(position) } ?: return emptyList()
        return listOf(createItem(file, method))
    }

    /**
     * The methods that call this one: every call site that binds to it, including calls through
     * subclasses and calls on the supertype it overrides, grouped by the containing method.
     */
    fun incomingCalls(item: CallHierarchyItem): List<CallHierarchyIncomingCall> {
        val (methodName, descriptor) = parseItemName(item.name)
        val targetClass = item.detail ?: return emptyList()
        val callSites = references.findMethodReferences(targetClass, methodName, descriptor, includeDeclaration = false)
        if (callSites.isEmpty()) return emptyList()

        val callers = LinkedHashMap<Pair<String, Int>, Pair<MethodDefinition, MutableList<Range>>>()
        for (site in callSites) {
            val file = index.findFileByUri(site.uri) ?: continue
            val method = file.methodAt(site.range.start.line) ?: continue
            callers.getOrPut(file.uri to method.startLine) { method to mutableListOf() }.second.add(site.range)
        }
        return callers.map { (key, value) ->
            CallHierarchyIncomingCall().apply {
                from = createItem(index.findFileByUri(key.first)!!, value.first)
                fromRanges = value.second
            }
        }
    }

    /**
     * Find all methods called from the given method (outgoing calls).
     * Scans invoke instructions within the method.
     */
    fun outgoingCalls(item: CallHierarchyItem): List<CallHierarchyOutgoingCall> {
        val itemClass = item.detail ?: return emptyList()
        val file = index.findClass(itemClass) ?: return emptyList()

        val method = file.methodAt(item.range.start.line)?.takeIf { it.range == item.range }
            ?: return emptyList()

        // Group invoke instructions by target method to consolidate calls
        val callsByTarget = method.instructions
            .filterIsInstance<InvokeInstruction>()
            .groupBy { Triple(it.className, it.methodName, it.descriptor) }

        val results = mutableListOf<CallHierarchyOutgoingCall>()

        for ((target, callSites) in callsByTarget) {
            val (className, methodName, descriptor) = target
            val toItem = index.resolveMethod(className, methodName, descriptor)?.let { createItem(it.file, it.method) }
                ?: createExternalItem(className, methodName, descriptor)

            val call = CallHierarchyOutgoingCall()
            call.to = toItem
            call.fromRanges = callSites.map { it.nameSpan?.toRange() ?: it.range }
            results.add(call)
        }

        return results
    }

    private fun createItem(file: SmaliFile, method: MethodDefinition): CallHierarchyItem {
        val item = CallHierarchyItem()
        item.name = "${method.name}${method.descriptor}"
        item.kind = SymbolKind.Method
        item.uri = file.uri
        item.range = method.range
        item.selectionRange = method.nameSpan.toRange()
        item.detail = file.classDefinition.name
        return item
    }

    private fun createExternalItem(className: String, methodName: String, descriptor: String): CallHierarchyItem {
        val item = CallHierarchyItem()
        item.name = "$methodName$descriptor"
        item.kind = SymbolKind.Method
        item.detail = className
        // External methods have no real URI — use the class name as identifier
        val uri = index.getUri(className) ?: "external:$className"
        item.uri = uri
        item.range = Range(Position(0, 0), Position(0, 0))
        item.selectionRange = Range(Position(0, 0), Position(0, 0))
        return item
    }

    /**
     * Parse "methodName(descriptor)returnType" back into (name, descriptor).
     */
    private fun parseItemName(name: String): Pair<String, String> {
        val parenIndex = name.indexOf('(')
        return if (parenIndex >= 0) {
            Pair(name.substring(0, parenIndex), name.substring(parenIndex))
        } else {
            Pair(name, "")
        }
    }
}
