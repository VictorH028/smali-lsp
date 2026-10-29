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

/** Call hierarchy: a method's callers (through subclasses and supertypes too) and the methods it calls. */
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
            val file = index.findDeclarations(site.uri) ?: continue
            val method = file.methodAt(site.range.start.line) ?: continue
            callers.getOrPut(file.uri to method.startLine) { method to mutableListOf() }.second.add(site.range)
        }
        return callers.map { (key, value) ->
            CallHierarchyIncomingCall().apply {
                from = createItem(index.findDeclarations(key.first)!!, value.first)
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
        // The callee's file may be closed: ask for its body
        val file = index.findClass(itemClass)?.let { index.findFileByUri(it.uri) } ?: return emptyList()

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
                ?: externalItem(className, methodName, descriptor, file, callSites.first())

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

    /** A method outside the workspace has no declaration to open: the item points at the first call, which is what there is to see of it. */
    private fun externalItem(className: String, methodName: String, descriptor: String, caller: SmaliFile, call: InvokeInstruction): CallHierarchyItem {
        val item = CallHierarchyItem()
        item.name = "$methodName$descriptor"
        item.kind = SymbolKind.Method
        item.detail = className
        item.uri = caller.uri
        item.range = call.range
        item.selectionRange = call.nameSpan?.toRange() ?: call.range
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
