package xyz.surendrajat.smalilsp.index

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.util.SourceFiles
import xyz.surendrajat.smalilsp.util.StringPool
import xyz.surendrajat.smalilsp.util.Types
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
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
 * Usage tables are [UsageList]s: packed ints, no object per site. Every indexing of a file gets
 * a fresh id and retires the previous one, which retires all of the file's earlier entries at
 * once instead of walking every symbol the file touched. Retired entries are skipped on read
 * and dropped by a compaction once they make up a quarter of the tables.
 *
 * Thread-safe: the scanner indexes files concurrently while requests are answered.
 */
class WorkspaceIndex {

    /** The pool every parser feeding this index should intern through (`SmaliParser(index.strings)`). */
    val strings = StringPool()

    /** Resource ids by value, from each workspace root's `res/values/public.xml`. */
    val resources = ResourceTable()

    // ---- files ----
    private val filesByUri = ConcurrentHashMap<String, SmaliFile>()
    private val uriByClass = ConcurrentHashMap<String, String>()
    private val simpleNameIndex = ConcurrentHashMap<String, MutableSet<String>>()
    private val packageCounts = ConcurrentHashMap<String, Int>()
    private val methodCount = AtomicInteger(0)
    private val fieldCount = AtomicInteger(0)

    // ---- file ids (grown under fileLock; readers bounds-check their snapshot) ----
    // A file gets a fresh id each time it is indexed; retiring the old id retires every usage
    // site it added, so a site carries no generation. Ids are never reused.
    private val fileLock = Any()
    private val fileIds = ConcurrentHashMap<String, Int>()
    private var nextId = 0
    @Volatile private var urisById = arrayOfNulls<String>(1024)
    @Volatile private var alive = AtomicIntegerArray(1024)
    @Volatile private var contributions = AtomicIntegerArray(1024)

    // ---- hierarchy ----
    private val subclassIndex = ConcurrentHashMap<String, MutableSet<String>>()
    private val implementorsIndex = ConcurrentHashMap<String, MutableSet<String>>()

    // ---- usages ----
    private val methodUsages = ConcurrentHashMap<MemberKey, UsageList>()
    private val fieldUsages = ConcurrentHashMap<MemberKey, UsageList>()
    private val classRefs = ConcurrentHashMap<String, UsageList>()
    private val totalEntries = AtomicLong(0)
    private val staleEntries = AtomicLong(0)

    // ---- string literals: value → sites, maintained like the member tables ----
    private val stringUsages = ConcurrentHashMap<String, UsageList>()
    private val stringValueCounts = ConcurrentHashMap<String, Int>()

    // ---- open documents ----
    private val documentContents = ConcurrentHashMap<String, String>()
    private val documentLines = ConcurrentHashMap<String, List<String>>()

    // ---- bodies: files stored without instructions, re-parsed on demand and kept for a while ----
    private val bodiless = ConcurrentHashMap.newKeySet<String>()
    private val bodies = object : LinkedHashMap<String, SmaliFile>(BODY_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SmaliFile>): Boolean = size > BODY_CACHE
    }
    /** The distinct string literals each file loads, kept apart from the body so a shell can be retracted. */
    private val fileStrings = ConcurrentHashMap<String, Array<String>>()

    // ======================================================================
    // Indexing
    // ======================================================================

    /**
     * Indexes a file; a file already indexed under the same URI is replaced. With [keepBody] false
     * the index stores the declarations only and parses the file again when a request needs its
     * body: the instruction lists are most of the AST's memory and matter only for open files
     * and the occasional call-hierarchy lookup. Thread-safe.
     */
    fun indexFile(file: SmaliFile, keepBody: Boolean = true) {
        val uri = canonicalUri(file.uri)
        val id = freshId(uri)
        val stored = if (keepBody) file else file.withoutBody()
        if (keepBody) bodiless.remove(uri) else bodiless.add(uri)
        synchronized(bodies) { bodies.remove(uri) }
        val strings = distinctStrings(file)
        filesByUri.put(uri, stored)?.let { retract(it, uri) }   // retract takes the old file's strings out of the side map
        fileStrings[uri] = strings

        val className = file.classDefinition.name
        uriByClass[className] = uri
        simpleNameIndex.computeIfAbsent(simpleKey(className)) { ConcurrentHashMap.newKeySet() }.add(className)
        packageCounts.merge(Types.packageOf(className), 1, Int::plus)
        file.classDefinition.superClass?.let { subclassIndex.computeIfAbsent(it) { ConcurrentHashMap.newKeySet() }.add(className) }
        for (iface in file.classDefinition.interfaces) {
            implementorsIndex.computeIfAbsent(iface) { ConcurrentHashMap.newKeySet() }.add(className)
        }
        methodCount.addAndGet(file.methods.size)
        fieldCount.addAndGet(file.fields.size)
        updateStringValueCounts(strings, delta = 1)

        var added = 0
        forEachUsage(file) { list, line, start, end ->
            list.add(id, line, start, end)
            added++
        }
        totalEntries.addAndGet(added.toLong())
        synchronized(fileLock) {
            // Under the lock: fileId() may replace the arrays while growing them
            contributions.set(id, added)
            maybeCompact()
        }
    }

    /** Removes a deleted file and everything it contributed. Returns false if it was not indexed. */
    fun removeFile(uri: String): Boolean {
        val key = canonicalUri(uri)
        val file = filesByUri.remove(key) ?: return false
        retract(file, key)
        synchronized(fileLock) { fileIds.remove(key)?.let { retire(it) } }
        bodiless.remove(key)
        synchronized(bodies) { bodies.remove(key) }
        return true
    }

    /** Drops the stored body of a file that is no longer open; the next request for it parses the file again. */
    fun dropBody(uri: String) {
        val key = canonicalUri(uri)
        if (key in bodiless) return
        val file = filesByUri[key] ?: return
        if (filesByUri.replace(key, file, file.withoutBody())) bodiless.add(key)
    }

    /** Removes every indexed file under [folderUri] (a workspace folder the client closed); returns how many. */
    fun removeFilesUnder(folderUri: String): Int {
        val prefix = canonicalUri(folderUri).trimEnd('/') + "/"
        val uris = filesByUri.keys.filter { it.startsWith(prefix) }
        for (uri in uris) removeFile(uri)
        return uris.size
    }

    /** Undoes what [indexFile] recorded for [file] outside the usage tables. */
    private fun retract(file: SmaliFile, uri: String) {
        val className = file.classDefinition.name
        uriByClass.remove(className, uri)
        simpleNameIndex[simpleKey(className)]?.remove(className)
        packageCounts.computeIfPresent(Types.packageOf(className)) { _, n -> if (n > 1) n - 1 else null }
        file.classDefinition.superClass?.let { subclassIndex[it]?.remove(className) }
        for (iface in file.classDefinition.interfaces) implementorsIndex[iface]?.remove(className)
        methodCount.addAndGet(-file.methods.size)
        fieldCount.addAndGet(-file.fields.size)
        updateStringValueCounts(fileStrings.remove(uri) ?: distinctStrings(file), delta = -1)
    }

    /** A new id for [uri]; the id it had before, if any, is retired with every usage site it added. */
    private fun freshId(uri: String): Int = synchronized(fileLock) {
        val id = nextId++
        if (id >= urisById.size) {
            val n = urisById.size * 2
            urisById = urisById.copyOf(n)
            alive = grow(alive, n)
            contributions = grow(contributions, n)
        }
        urisById[id] = uri
        alive.set(id, 1)
        fileIds.put(uri, id)?.let { retire(it) }
        id
    }

    /** Called with [fileLock] held: the id's sites become stale and its URI reference is dropped. */
    private fun retire(id: Int) {
        alive.set(id, 0)
        urisById[id] = null
        staleEntries.addAndGet(contributions.getAndSet(id, 0).toLong())
    }

    private fun grow(old: AtomicIntegerArray, n: Int): AtomicIntegerArray {
        val fresh = AtomicIntegerArray(n)
        for (i in 0 until old.length()) fresh.set(i, old.get(i))
        return fresh
    }

    /**
     * Every usage a file contributes: call sites, field accesses, class references (owners, type
     * operands, field types, descriptor types) and string literals, each at its token. Array
     * types are filed under their element class; a primitive-array owner (`[I->clone()`) names
     * no class.
     */
    private inline fun forEachUsage(
        file: SmaliFile,
        sink: (list: UsageList, line: Int, start: Int, end: Int) -> Unit
    ) {
        for (method in file.methods) {
            for (instr in method.instructions) {
                when (instr) {
                    is InvokeInstruction -> {
                        at(instr.nameSpan ?: instr.range.toSpan()) { l, a, b -> sink(listFor(methodUsages, MemberKey(instr.className, instr.methodName, instr.descriptor)), l, a, b) }
                        Types.className(instr.className)?.let { owner -> at(instr.ownerSpan ?: instr.range.toSpan()) { l, a, b -> sink(listFor(classRefs, owner), l, a, b) } }
                    }
                    is FieldAccessInstruction -> {
                        at(instr.nameSpan ?: instr.range.toSpan()) { l, a, b -> sink(listFor(fieldUsages, MemberKey(instr.className, instr.fieldName, instr.fieldType)), l, a, b) }
                        Types.className(instr.className)?.let { owner -> at(instr.ownerSpan ?: instr.range.toSpan()) { l, a, b -> sink(listFor(classRefs, owner), l, a, b) } }
                        Types.className(instr.fieldType)?.let { type -> at(instr.typeSpan ?: instr.range.toSpan()) { l, a, b -> sink(listFor(classRefs, type), l, a, b) } }
                    }
                    is TypeInstruction -> {
                        Types.className(instr.className)?.let { type -> at(instr.typeSpan ?: instr.range.toSpan()) { l, a, b -> sink(listFor(classRefs, type), l, a, b) } }
                    }
                    is ConstStringInstruction -> sink(listFor(stringUsages, instr.value), instr.startLine, instr.startChar, if (instr.endLine == instr.startLine) instr.endChar else instr.startChar)
                    is JumpInstruction -> {}
                }
            }
            if (method.descriptorEnd - method.descriptorStart == method.descriptor.length) {
                for (type in Types.parseTypeSequence(method.descriptor)) {
                    val className = type.className ?: continue
                    sink(listFor(classRefs, className), method.descriptorLine, method.descriptorStart + type.start, method.descriptorStart + type.end)
                }
            } else {
                // No usable span (a hand-built definition): point at the declaration
                Types.className(method.returnType)?.let { type -> at(method.range.toSpan()) { l, a, b -> sink(listFor(classRefs, type), l, a, b) } }
                for (param in method.parameters) {
                    Types.className(param.type)?.let { type -> at(method.range.toSpan()) { l, a, b -> sink(listFor(classRefs, type), l, a, b) } }
                }
            }
        }
        for (field in file.fields) {
            Types.className(field.type)?.let { type -> sink(listFor(classRefs, type), field.typeLine, field.typeStart, field.typeEnd) }
        }
        // Annotations, .catch exception types, .local types, method prototypes
        for (ref in file.typeRefs) sink(listFor(classRefs, ref.className), ref.line, ref.start, ref.end)
    }

    private inline fun at(span: Span, use: (line: Int, start: Int, end: Int) -> Unit) = use(span.line, span.start, span.end)

    private fun <K : Any> listFor(table: ConcurrentHashMap<K, UsageList>, key: K): UsageList =
        table[key] ?: table.computeIfAbsent(key) { UsageList() }

    /** Drops the spare capacity of every usage list; called once the initial scan is over. */
    fun trimUsages() {
        for (table in listOf(methodUsages, fieldUsages, classRefs, stringUsages)) {
            for (list in table.values) list.trim()
        }
    }

    private fun Range.toSpan() = Span(start.line, start.character, if (end.line == start.line) end.character else start.character)

    /**
     * Drops retired entries once they make up a quarter of the tables. Called with [fileLock]
     * held, so the alive array cannot be replaced (grown) while entries are judged against it:
     * an entry of a file id beyond the captured array would look retired.
     */
    private fun maybeCompact() {
        val stale = staleEntries.get()
        if (stale < 100_000 || stale * 4 < totalEntries.get()) return
        val live = alive
        var dropped = 0L
        for (table in listOf(methodUsages, fieldUsages, classRefs, stringUsages)) {
            for (list in table.values) dropped += list.compact(live)
        }
        totalEntries.addAndGet(-dropped)
        staleEntries.set(0)
    }

    private fun distinctStrings(file: SmaliFile): Array<String> {
        val values = LinkedHashSet<String>()
        for (method in file.methods) for (instr in method.instructions) if (instr is ConstStringInstruction) values.add(instr.value)
        return values.toTypedArray()
    }

    private fun updateStringValueCounts(values: Array<String>, delta: Int) {
        for (value in values) {
            stringValueCounts.compute(value) { _, existing ->
                val next = (existing ?: 0) + delta
                if (next > 0) next else null
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

    /** The file with its body: as stored when it is open or was indexed with its body, else parsed again from the open text or the disk. */
    fun findFileByUri(uri: String): SmaliFile? {
        var key = uri
        var stored = filesByUri[key]
        if (stored == null) {
            key = canonicalUri(uri)
            stored = filesByUri[key] ?: return null
        }
        if (key !in bodiless) return stored
        return body(key) ?: stored
    }

    /** The file as stored, which for a file that is not open is its declarations only. Enough for anything that does not read instructions or labels. */
    fun findDeclarations(uri: String): SmaliFile? = filesByUri[uri] ?: filesByUri[canonicalUri(uri)]

    private fun body(key: String): SmaliFile? {
        synchronized(bodies) { bodies[key]?.let { return it } }
        val text = documentContents[key] ?: try { SourceFiles.read(File(URI(key))) } catch (_: Exception) { return null }
        val parsed = SmaliParser(strings).parse(key, text) ?: return null
        synchronized(bodies) { bodies[key] = parsed }
        return parsed
    }

    fun findClassNameByUri(uri: String): String? = findDeclarations(uri)?.classDefinition?.name

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

    /** A method and the file that declares it. */
    data class MethodMatch(val file: SmaliFile, val method: MethodDefinition)

    /** A field and the file that declares it. */
    data class FieldMatch(val file: SmaliFile, val field: FieldDefinition)

    /**
     * The declaration a reference `className->methodName descriptor` resolves to: in [className]
     * itself or, failing that, up its superclasses and interfaces. Null when no workspace class
     * on that path declares it.
     */
    fun resolveMethod(className: String, methodName: String, descriptor: String): MethodMatch? =
        resolveMethodIn(className, methodName, descriptor, HashSet())

    private fun resolveMethodIn(className: String, methodName: String, descriptor: String, visited: MutableSet<String>): MethodMatch? {
        if (!visited.add(className)) return null
        val file = findClass(className) ?: return null
        file.methods.firstOrNull { it.name == methodName && it.descriptor == descriptor }?.let { return MethodMatch(file, it) }
        file.classDefinition.superClass?.takeIf { it != "Ljava/lang/Object;" }?.let { superClass ->
            resolveMethodIn(superClass, methodName, descriptor, visited)?.let { return it }
        }
        for (iface in file.classDefinition.interfaces) {
            resolveMethodIn(iface, methodName, descriptor, visited)?.let { return it }
        }
        return null
    }

    /**
     * The topmost declarations of the virtual method [start] overrides: walking superclasses and
     * interfaces, the declarations whose own supertypes in the workspace do not declare the
     * method. [start] itself when nothing above declares it; several when an interface and a
     * superclass both do.
     */
    fun rootMethodDeclarations(start: MethodMatch, methodName: String, descriptor: String): List<MethodMatch> {
        val roots = LinkedHashMap<String, MethodMatch>()
        val visited = HashSet<String>()
        fun climb(match: MethodMatch) {
            val definition = match.file.classDefinition
            if (!visited.add(definition.name)) return
            var overridesSomething = false
            for (parent in listOfNotNull(definition.superClass) + definition.interfaces) {
                val above = resolveMethod(parent, methodName, descriptor) ?: continue
                if (!above.method.isVirtual) continue
                overridesSomething = true
                climb(above)
            }
            if (!overridesSomething) roots[definition.name] = match
        }
        climb(start)
        return roots.values.toList()
    }

    /** Where the method is declared, as a location of its name token. */
    fun findMethod(className: String, methodName: String, descriptor: String): List<Location> =
        listOfNotNull(resolveMethod(className, methodName, descriptor)?.let { Location(it.file.uri, it.method.nameSpan.toRange()) })

    /**
     * The declaration a field reference resolves to: in [className], else in its superinterfaces
     * (interface constants are accessed through the implementing class), else up its superclass.
     * The type is part of a field's identity.
     */
    fun resolveField(className: String, fieldName: String, fieldType: String): FieldMatch? =
        resolveFieldIn(className, fieldName, fieldType, HashSet())

    private fun resolveFieldIn(className: String, fieldName: String, fieldType: String, visited: MutableSet<String>): FieldMatch? {
        if (!visited.add(className)) return null
        val file = findClass(className) ?: return null
        file.fields.firstOrNull { it.name == fieldName && it.type == fieldType }?.let { return FieldMatch(file, it) }
        for (iface in file.classDefinition.interfaces) {
            resolveFieldIn(iface, fieldName, fieldType, visited)?.let { return it }
        }
        val superClass = file.classDefinition.superClass?.takeIf { it != "Ljava/lang/Object;" } ?: return null
        return resolveFieldIn(superClass, fieldName, fieldType, visited)
    }

    /** Where the field is declared, as a location of its name token. */
    fun findField(className: String, fieldName: String, fieldType: String): Location? =
        resolveField(className, fieldName, fieldType)?.let { Location(it.file.uri, it.field.nameSpan.toRange()) }

    // ======================================================================
    // Usages
    // ======================================================================

    /** Every call site of the method, at the method-name token. */
    fun findMethodUsages(className: String, methodName: String, descriptor: String): List<Location> =
        methodUsages[MemberKey(className, methodName, descriptor)].toLocations()

    /** Every access of the field, at the field-name token. */
    fun findFieldUsages(className: String, fieldName: String, fieldType: String): List<Location> =
        fieldUsages[MemberKey(className, fieldName, fieldType)].toLocations()

    /** Every `const-string` that loads exactly [value], at the instruction. */
    fun findStringUsages(value: String): List<Location> = stringUsages[value].toLocations()

    /** Every reference to the class as an owner, operand, field type or descriptor type; [cancel] is checked along the way for the big ones. */
    fun findClassRefLocations(className: String, cancel: CancelChecker? = null): List<Location> = classRefs[className].toLocations(cancel)

    /** URIs of the files that extend or implement [className]. */
    fun findClassUsages(className: String): Set<String> {
        val uris = LinkedHashSet<String>()
        subclassIndex[className]?.forEach { sub -> uriByClass[sub]?.let(uris::add) }
        implementorsIndex[className]?.forEach { impl -> uriByClass[impl]?.let(uris::add) }
        return uris
    }

    private fun UsageList?.toLocations(cancel: CancelChecker? = null): List<Location> {
        if (this == null) return emptyList()
        val entries = snapshot()
        val live = alive
        val uris = urisById
        val out = ArrayList<Location>(entries.size / UsageList.STRIDE)
        var i = 0
        while (i < entries.size) {
            if (cancel != null && i % (UsageList.STRIDE * 4096) == 0) cancel.checkCanceled()
            val fileId = entries[i]
            if (fileId < live.length() && live.get(fileId) != 0) {
                val line = entries[i + 1]
                out.add(Location(uris[fileId], Range(Position(line, entries[i + 2]), Position(line, entries[i + 3]))))
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

    /** String literals containing [query] (case-insensitive), with where they occur. */
    fun searchStrings(query: String, maxResults: Int = 500): List<StringSearchResult> {
        val results = ArrayList<StringSearchResult>(minOf(maxResults, 64))
        for ((value, sites) in stringUsages) {
            if (query.isNotEmpty() && !value.contains(query, ignoreCase = true)) continue
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

    /** Keeps the editor's text for an open file (didOpen/didChange). Keyed by canonical URI like the files. */
    fun setDocumentContent(uri: String, content: String) {
        val key = canonicalUri(uri)
        documentContents[key] = content
        documentLines.remove(key)
    }

    fun removeDocumentContent(uri: String) {
        val key = canonicalUri(uri)
        documentContents.remove(key)
        documentLines.remove(key)
    }

    fun getDocumentContent(uri: String): String? = documentContents[canonicalUri(uri)]

    /** Whether the workspace defines at least one class in [packagePrefix] (`Lcom/app/`, as [Types.packageOf] spells it). */
    fun hasPackage(packagePrefix: String): Boolean = packageCounts.containsKey(packagePrefix)

    /** Whether an editor buffer is open for the file, under any spelling of its URI. */
    fun isOpen(uri: String): Boolean = documentContents.containsKey(canonicalUri(uri))

    /** The lines of an open document (split once and cached), or of the file on disk when it is not open. */
    fun getLines(uri: String): List<String>? {
        val key = canonicalUri(uri)
        val content = documentContents[key]
        if (content != null) return documentLines.getOrPut(key) { content.lines() }
        return try {
            File(URI(uri)).readLines()
        } catch (_: Exception) {
            null
        }
    }

    /** A line of an open document, or of the file on disk when it is not open. */
    fun getLineContent(uri: String, lineIndex: Int): String? {
        val key = canonicalUri(uri)
        val content = documentContents[key]
        if (content != null) {
            val lines = documentLines.getOrPut(key) { content.lines() }
            return lines.getOrNull(lineIndex)
        }
        return try {
            File(URI(uri)).bufferedReader().useLines { it.elementAtOrNull(lineIndex) }
        } catch (_: Exception) {
            null
        }
    }



    /** Last path segment of a class name, keeping the Outer$Inner chain (unlike Types.simpleName): "Lcom/a/Outer$Inner;" → "Outer$Inner". */
    private fun simpleKey(className: String): String = className.removePrefix("L").removeSuffix(";").substringAfterLast('/')

    companion object {
        /** Re-parsed bodies kept for requests that come in bursts on the same closed file. */
        private const val BODY_CACHE = 32

        /**
         * One spelling per file. `file:/x` (java.io.File) and `file://x` become `file:///x`, and
         * percent-encoding is normalized through the path: VS Code sends `Foo%24Bar.smali` for the
         * file the scanner found as `Foo$Bar.smali`. Non-file URIs are kept as they are.
         */
        fun canonicalUri(uri: String): String {
            if (!uri.startsWith("file:")) return uri
            return try {
                File(URI(uri)).toPath().toUri().toString()
            } catch (_: Exception) {
                when {
                    uri.startsWith("file:///") -> uri
                    uri.startsWith("file://") -> "file:///" + uri.substring("file://".length)
                    else -> "file:///" + uri.substring("file:/".length)
                }
            }
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

/**
 * A method or field as the usage tables key it: owner, name and descriptor (or field type).
 * The parts are the AST's pooled strings, so a key is one small object sharing them instead
 * of a concatenated `Lowner;->name(desc)` string, and equality is usually three identity checks.
 */
internal class MemberKey(@JvmField val owner: String, @JvmField val name: String, @JvmField val descriptor: String) {
    override fun hashCode(): Int = (owner.hashCode() * 31 + name.hashCode()) * 31 + descriptor.hashCode()

    override fun equals(other: Any?): Boolean =
        other is MemberKey && (owner === other.owner || owner == other.owner) && (name === other.name || name == other.name) && (descriptor === other.descriptor || descriptor == other.descriptor)

    override fun toString(): String = "$owner->$name$descriptor"
}
