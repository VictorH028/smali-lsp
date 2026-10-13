package xyz.surendrajat.smalilsp.index

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.util.StringPool
import xyz.surendrajat.smalilsp.util.Types
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong

/**
 * The workspace index: every parsed file by URI, the class → file mapping, the class hierarchy,
 * and reverse indexes from each method, field and class to the sites that use it.
 *
 * Files are keyed by canonical URI (`file:///…`), so a file the scanner found and the same file
 * sent by the client are one entry, and a file whose class name changes on edit replaces its
 * old entry. Declarations are answered from the file itself; only usages have tables of their
 * own.
 *
 * Usage tables are [UsageList]s: packed ints, no object per site. Every file has an id and a
 * generation. Re-indexing a file bumps its generation, which retires all of its earlier
 * entries at once instead of walking every symbol the file touched. Retired entries are
 * skipped on read and dropped by a compaction once they make up a quarter of the tables.
 *
 * Thread-safe: the scanner indexes files concurrently while requests are answered.
 */
class WorkspaceIndex {

    /** The pool every parser feeding this index should intern through (`SmaliParser(index.strings)`). */
    val strings = StringPool()

    // ---- files ----
    private val filesByUri = ConcurrentHashMap<String, SmaliFile>()
    private val uriByClass = ConcurrentHashMap<String, String>()
    private val simpleNameIndex = ConcurrentHashMap<String, MutableSet<String>>()
    private val methodCount = AtomicInteger(0)
    private val fieldCount = AtomicInteger(0)

    // ---- file ids and generations (grown under fileLock; readers bounds-check their snapshot) ----
    private val fileLock = Any()
    private val fileIds = ConcurrentHashMap<String, Int>()
    @Volatile private var urisById = arrayOfNulls<String>(1024)
    @Volatile private var generation = AtomicIntegerArray(1024)
    @Volatile private var contributions = AtomicIntegerArray(1024)

    // ---- hierarchy ----
    private val subclassIndex = ConcurrentHashMap<String, MutableSet<String>>()
    private val implementorsIndex = ConcurrentHashMap<String, MutableSet<String>>()

    // ---- usages ----
    private val methodUsages = ConcurrentHashMap<String, UsageList>()
    private val fieldUsages = ConcurrentHashMap<String, UsageList>()
    private val classRefs = ConcurrentHashMap<String, UsageList>()
    private val totalEntries = AtomicLong(0)
    private val staleEntries = AtomicLong(0)
    private val compacting = AtomicBoolean(false)

    // ---- string literals: value → sites, built on first search, rebuilt after any change ----
    private var stringIndex = HashMap<String, UsageList>()
    @Volatile private var stringIndexDirty = true
    private val stringIndexLock = Any()
    private val stringValueCounts = ConcurrentHashMap<String, Int>()

    // ---- open documents ----
    private val documentContents = ConcurrentHashMap<String, String>()
    private val documentLines = ConcurrentHashMap<String, List<String>>()

    // ======================================================================
    // Indexing
    // ======================================================================

    /** Indexes a file; a file already indexed under the same URI is replaced. Thread-safe. */
    fun indexFile(file: SmaliFile) {
        val uri = canonicalUri(file.uri)
        val id = fileId(uri)
        filesByUri.put(uri, file)?.let { retract(it, uri) }
        val gen = retireUsages(id)

        val className = file.classDefinition.name
        uriByClass[className] = uri
        simpleNameIndex.computeIfAbsent(simpleKey(className)) { ConcurrentHashMap.newKeySet() }.add(className)
        file.classDefinition.superClass?.let { subclassIndex.computeIfAbsent(it) { ConcurrentHashMap.newKeySet() }.add(className) }
        for (iface in file.classDefinition.interfaces) {
            implementorsIndex.computeIfAbsent(iface) { ConcurrentHashMap.newKeySet() }.add(className)
        }
        methodCount.addAndGet(file.methods.size)
        fieldCount.addAndGet(file.fields.size)
        updateStringValueCounts(file, delta = 1)

        var added = 0
        forEachUsage(file) { table, key, span ->
            table.computeIfAbsent(key) { UsageList() }.add(id, gen, span.line, span.start, span.end)
            added++
        }
        contributions.set(id, added)
        totalEntries.addAndGet(added.toLong())
        stringIndexDirty = true
        maybeCompact()
    }

    /** Removes a deleted file and everything it contributed. Returns false if it was not indexed. */
    fun removeFile(uri: String): Boolean {
        val key = canonicalUri(uri)
        val file = filesByUri.remove(key) ?: return false
        retract(file, key)
        retireUsages(fileId(key))
        stringIndexDirty = true
        return true
    }

    /** Undoes what [indexFile] recorded for [file] outside the usage tables. */
    private fun retract(file: SmaliFile, uri: String) {
        val className = file.classDefinition.name
        uriByClass.remove(className, uri)
        simpleNameIndex[simpleKey(className)]?.remove(className)
        file.classDefinition.superClass?.let { subclassIndex[it]?.remove(className) }
        for (iface in file.classDefinition.interfaces) implementorsIndex[iface]?.remove(className)
        methodCount.addAndGet(-file.methods.size)
        fieldCount.addAndGet(-file.fields.size)
        updateStringValueCounts(file, delta = -1)
    }

    /** Moves the file to a new generation, retiring every usage entry it added so far. */
    private fun retireUsages(id: Int): Int = synchronized(fileLock) {
        staleEntries.addAndGet(contributions.getAndSet(id, 0).toLong())
        generation.incrementAndGet(id)
    }

    private fun fileId(uri: String): Int {
        fileIds[uri]?.let { return it }
        synchronized(fileLock) {
            fileIds[uri]?.let { return it }
            val id = fileIds.size
            if (id >= urisById.size) {
                val n = urisById.size * 2
                urisById = urisById.copyOf(n)
                generation = grow(generation, n)
                contributions = grow(contributions, n)
            }
            urisById[id] = uri
            generation.set(id, 1)
            fileIds[uri] = id
            return id
        }
    }

    private fun grow(old: AtomicIntegerArray, n: Int): AtomicIntegerArray {
        val fresh = AtomicIntegerArray(n)
        for (i in 0 until old.length()) fresh.set(i, old.get(i))
        return fresh
    }

    /**
     * Every usage a file contributes: call sites, field accesses and class references
     * (owners, type operands, field types, descriptor types), each at its token. Array types are
     * filed under their element class.
     */
    private inline fun forEachUsage(
        file: SmaliFile,
        sink: (table: ConcurrentHashMap<String, UsageList>, key: String, span: Span) -> Unit
    ) {
        for (method in file.methods) {
            for (instr in method.instructions) {
                when (instr) {
                    is InvokeInstruction -> {
                        sink(methodUsages, methodKey(instr.className, instr.methodName, instr.descriptor), instr.nameSpan ?: instr.range.toSpan())
                        sink(classRefs, Types.elementClass(instr.className), instr.ownerSpan ?: instr.range.toSpan())
                    }
                    is FieldAccessInstruction -> {
                        sink(fieldUsages, fieldKey(instr.className, instr.fieldName, instr.fieldType), instr.nameSpan ?: instr.range.toSpan())
                        sink(classRefs, Types.elementClass(instr.className), instr.ownerSpan ?: instr.range.toSpan())
                        Types.className(instr.fieldType)?.let { sink(classRefs, it, instr.typeSpan ?: instr.range.toSpan()) }
                    }
                    is TypeInstruction -> {
                        Types.className(instr.className)?.let { sink(classRefs, it, instr.typeSpan ?: instr.range.toSpan()) }
                    }
                    is JumpInstruction, is ConstStringInstruction -> {}
                }
            }
            val descriptor = method.descriptorSpan
            if (descriptor.length == method.descriptor.length) {
                for (type in Types.parseTypeSequence(method.descriptor)) {
                    val className = type.className ?: continue
                    sink(classRefs, className, Span(descriptor.line, descriptor.start + type.start, descriptor.start + type.end))
                }
            } else {
                // No usable span (a hand-built definition): point at the declaration
                Types.className(method.returnType)?.let { sink(classRefs, it, method.range.toSpan()) }
                for (param in method.parameters) {
                    Types.className(param.type)?.let { sink(classRefs, it, method.range.toSpan()) }
                }
            }
        }
        for (field in file.fields) {
            Types.className(field.type)?.let { sink(classRefs, it, field.typeSpan) }
        }
        // Annotations, .catch exception types, .local types, method prototypes
        for (ref in file.typeRefs) sink(classRefs, ref.className, ref.span)
    }

    private fun Range.toSpan() = Span(start.line, start.character, if (end.line == start.line) end.character else start.character)

    /** Drops retired entries once they make up a quarter of the tables. Runs on the indexing thread. */
    private fun maybeCompact() {
        val stale = staleEntries.get()
        if (stale < 100_000 || stale * 4 < totalEntries.get()) return
        if (!compacting.compareAndSet(false, true)) return
        try {
            val gen = generation
            var dropped = 0L
            for (table in listOf(methodUsages, fieldUsages, classRefs)) {
                for (list in table.values) dropped += list.compact(gen)
            }
            totalEntries.addAndGet(-dropped)
            staleEntries.set(0)
        } finally {
            compacting.set(false)
        }
    }

    private fun updateStringValueCounts(file: SmaliFile, delta: Int) {
        for (method in file.methods) {
            for (instr in method.instructions) {
                if (instr is ConstStringInstruction) {
                    stringValueCounts.compute(instr.value) { _, existing ->
                        val next = (existing ?: 0) + delta
                        if (next > 0) next else null
                    }
                }
            }
        }
    }

    // ======================================================================
    // Files and classes
    // ======================================================================

    /** The file defining [className], or null when the class is not in the workspace. */
    fun findClass(className: String): SmaliFile? = uriByClass[className]?.let { filesByUri[it] }

    fun hasClass(className: String): Boolean = uriByClass.containsKey(className)

    /** URI of the file defining [className]. */
    fun getUri(className: String): String? = uriByClass[className]

    fun findFileByUri(uri: String): SmaliFile? = filesByUri[canonicalUri(uri)]

    fun findClassNameByUri(uri: String): String? = findFileByUri(uri)?.classDefinition?.name

    /** Classes whose last path segment is [simpleName] (`Outer$Inner` for nested classes). */
    fun findClassesBySimpleName(simpleName: String): List<SmaliFile> =
        simpleNameIndex[simpleName]?.mapNotNull { findClass(it) } ?: emptyList()

    /** Live key-set view of every indexed class name; no copy. */
    fun getClassNames(): Set<String> = uriByClass.keys

    /** Live view of every indexed file; no copy. */
    fun getFilesView(): Collection<SmaliFile> = filesByUri.values

    fun getStats(): IndexStats = IndexStats(
        classes = uriByClass.size,
        methods = methodCount.get(),
        fields = fieldCount.get(),
        strings = stringValueCounts.size
    )

    // ======================================================================
    // Declarations
    // ======================================================================

    /** Where the method is declared: in [className] or, failing that, up its superclasses and interfaces. */
    fun findMethod(className: String, methodName: String, descriptor: String): List<Location> =
        findMethodIn(className, methodName, descriptor, HashSet())

    private fun findMethodIn(className: String, methodName: String, descriptor: String, visited: MutableSet<String>): List<Location> {
        if (!visited.add(className)) return emptyList()
        val file = findClass(className) ?: return emptyList()
        file.methods.firstOrNull { it.name == methodName && it.descriptor == descriptor }
            ?.let { return listOf(Location(file.uri, it.nameSpan.toRange())) }
        file.classDefinition.superClass?.takeIf { it != "Ljava/lang/Object;" }?.let { superClass ->
            findMethodIn(superClass, methodName, descriptor, visited).takeIf { it.isNotEmpty() }?.let { return it }
        }
        for (iface in file.classDefinition.interfaces) {
            findMethodIn(iface, methodName, descriptor, visited).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    /** Where the field is declared: in [className] or up its superclasses. The type is part of a field's identity. */
    fun findField(className: String, fieldName: String, fieldType: String): Location? =
        findFieldIn(className, fieldName, fieldType, HashSet())

    private fun findFieldIn(className: String, fieldName: String, fieldType: String, visited: MutableSet<String>): Location? {
        if (!visited.add(className)) return null
        val file = findClass(className) ?: return null
        file.fields.firstOrNull { it.name == fieldName && it.type == fieldType }
            ?.let { return Location(file.uri, it.nameSpan.toRange()) }
        val superClass = file.classDefinition.superClass?.takeIf { it != "Ljava/lang/Object;" } ?: return null
        return findFieldIn(superClass, fieldName, fieldType, visited)
    }

    // ======================================================================
    // Usages
    // ======================================================================

    /** Every call site of the method, at the method-name token. */
    fun findMethodUsages(className: String, methodName: String, descriptor: String): List<Location> =
        methodUsages[methodKey(className, methodName, descriptor)].toLocations()

    /** Every access of the field, at the field-name token. */
    fun findFieldUsages(className: String, fieldName: String, fieldType: String): List<Location> =
        fieldUsages[fieldKey(className, fieldName, fieldType)].toLocations()

    /** Every reference to the class as an owner, operand, field type or descriptor type. */
    fun findClassRefLocations(className: String): List<Location> = classRefs[className].toLocations()

    /** URIs of the files that extend or implement [className]. */
    fun findClassUsages(className: String): Set<String> {
        val uris = LinkedHashSet<String>()
        subclassIndex[className]?.forEach { sub -> uriByClass[sub]?.let(uris::add) }
        implementorsIndex[className]?.forEach { impl -> uriByClass[impl]?.let(uris::add) }
        return uris
    }

    private fun UsageList?.toLocations(): List<Location> {
        if (this == null) return emptyList()
        val entries = snapshot()
        val gen = generation
        val uris = urisById
        val out = ArrayList<Location>(entries.size / UsageList.STRIDE)
        var i = 0
        while (i < entries.size) {
            val fileId = entries[i]
            if (fileId < gen.length() && gen.get(fileId) == entries[i + 1]) {
                val line = entries[i + 2]
                out.add(Location(uris[fileId], Range(Position(line, entries[i + 3]), Position(line, entries[i + 4]))))
            }
            i += UsageList.STRIDE
        }
        return out
    }

    // ======================================================================
    // Hierarchy
    // ======================================================================

    fun getDirectSubclasses(className: String): Set<String> = subclassIndex[className] ?: emptySet()

    fun getDirectImplementors(className: String): Set<String> = implementorsIndex[className] ?: emptySet()

    /** Every transitive subclass and implementor, breadth first. */
    fun getAllSubclasses(className: String): Set<String> {
        val result = LinkedHashSet<String>()
        val visited = hashSetOf(className)
        val queue = ArrayDeque<String>()
        queue.add(className)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (next in getDirectSubclasses(current) + getDirectImplementors(current)) {
                if (visited.add(next)) {
                    result.add(next)
                    queue.add(next)
                }
            }
        }
        return result
    }

    // ======================================================================
    // String literals
    // ======================================================================

    private fun ensureStringIndex() {
        if (!stringIndexDirty) return
        synchronized(stringIndexLock) {
            if (!stringIndexDirty) return
            val fresh = HashMap<String, UsageList>()
            for ((uri, file) in filesByUri) {
                val id = fileId(uri)
                val gen = generation.get(id)
                for (method in file.methods) {
                    for (instr in method.instructions) {
                        if (instr is ConstStringInstruction) {
                            val end = if (instr.endLine == instr.startLine) instr.endChar else instr.startChar
                            fresh.getOrPut(instr.value) { UsageList() }.add(id, gen, instr.startLine, instr.startChar, end)
                        }
                    }
                }
            }
            stringIndex = fresh
            stringIndexDirty = false
        }
    }

    /** String literals containing [query] (case-insensitive), with where they occur. */
    fun searchStrings(query: String, maxResults: Int = 500): List<StringSearchResult> {
        ensureStringIndex()
        val normalizedQuery = query.lowercase()
        val results = ArrayList<StringSearchResult>(minOf(maxResults, 64))
        for ((value, sites) in stringIndex) {
            if (normalizedQuery.isNotEmpty() && !value.lowercase().contains(normalizedQuery)) continue
            for (location in sites.toLocations()) {
                results.add(StringSearchResult(value, location))
                if (results.size >= maxResults) return results
            }
        }
        return results
    }

    // ======================================================================
    // Open documents
    // ======================================================================

    /** Keeps the editor's text for an open file (didOpen/didChange). */
    fun setDocumentContent(uri: String, content: String) {
        documentContents[uri] = content
        documentLines.remove(uri)
    }

    fun removeDocumentContent(uri: String) {
        documentContents.remove(uri)
        documentLines.remove(uri)
    }

    fun getDocumentContent(uri: String): String? = documentContents[uri]

    /** A line of an open document, or of the file on disk when it is not open. */
    fun getLineContent(uri: String, lineIndex: Int): String? {
        val content = documentContents[uri]
        if (content != null) {
            val lines = documentLines.getOrPut(uri) { content.lines() }
            return lines.getOrNull(lineIndex)
        }
        return try {
            File(URI(uri)).readLines().getOrNull(lineIndex)
        } catch (_: Exception) {
            null
        }
    }

    // ======================================================================
    // Housekeeping
    // ======================================================================

    fun clear() {
        synchronized(fileLock) {
            filesByUri.clear()
            uriByClass.clear()
            simpleNameIndex.clear()
            fileIds.clear()
            urisById = arrayOfNulls(1024)
            generation = AtomicIntegerArray(1024)
            contributions = AtomicIntegerArray(1024)
            subclassIndex.clear()
            implementorsIndex.clear()
            methodUsages.clear()
            fieldUsages.clear()
            classRefs.clear()
            totalEntries.set(0)
            staleEntries.set(0)
            stringIndexDirty = true
            stringValueCounts.clear()
            methodCount.set(0)
            fieldCount.set(0)
            documentContents.clear()
            documentLines.clear()
        }
    }

    private fun methodKey(className: String, methodName: String, descriptor: String) = "$className->$methodName$descriptor"

    private fun fieldKey(className: String, fieldName: String, fieldType: String) = "$className->$fieldName:$fieldType"

    /** Last path segment of a class name, keeping the Outer$Inner chain (unlike Types.simpleName): "Lcom/a/Outer$Inner;" → "Outer$Inner". */
    private fun simpleKey(className: String): String = className.removePrefix("L").removeSuffix(";").substringAfterLast('/')

    companion object {
        /** `file:/x` (java.io.File) and `file://x` become `file:///x`, the form LSP clients send. */
        fun canonicalUri(uri: String): String = when {
            uri.startsWith("file:///") -> uri
            uri.startsWith("file://") -> "file:///" + uri.substring("file://".length)
            uri.startsWith("file:/") -> "file:///" + uri.substring("file:/".length)
            else -> uri
        }
    }
}

data class IndexStats(
    val classes: Int,
    val methods: Int,
    val fields: Int,
    val strings: Int = 0
)

data class StringSearchResult(
    val value: String,
    val location: Location
)
