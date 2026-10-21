package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import java.util.PriorityQueue
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.util.Types

/**
 * Workspace symbol search over classes, methods and fields: case-insensitive, fuzzy, and the
 * best 500 kept while scanning so a large workspace never sorts a full result list.
 */
class WorkspaceSymbolProvider(
    private val index: WorkspaceIndex
) {
    companion object {
        // Increased from 100 to allow more results while still being performant
        private const val MAX_RESULTS = 500
    }
    
    /**
     * Search workspace for symbols matching query.
     * 
     * @param query Search string (case-insensitive, supports fuzzy matching)
     * @return List of matching symbols, ranked by relevance
     */
    fun search(query: String, cancel: CancelChecker? = null): List<SymbolInformation> {
        val normalizedQuery = query.lowercase().trim()
        if (normalizedQuery.isEmpty()) {
            return collectFirstSymbols()
        }

        // Every class, method and field name is compared on every keystroke of the symbol
        // picker; the scan is memory-bound, so it runs in parallel chunks, each keeping its
        // own top matches, merged at the end. The global top set is contained in the union
        // of the per-chunk top sets, so the result equals a sequential scan's.
        val files = index.getFilesView().toList()
        val chunkSize = maxOf(256, files.size / (Runtime.getRuntime().availableProcessors() * 4) + 1)
        val merged = TopSymbolMatches(MAX_RESULTS)
        files.chunked(chunkSize).parallelStream()
            .map { chunk ->
                cancel?.checkCanceled()
                val top = TopSymbolMatches(MAX_RESULTS)
                for (file in chunk) matchFile(file, normalizedQuery, top)
                top
            }
            .toList()
            .forEach { top -> top.forEach(merged::offer) }
        return merged.toSortedSymbolInformation()
    }

    private fun matchFile(file: SmaliFile, normalizedQuery: String, matches: TopSymbolMatches) {
        // Class symbol: match on the simple name, or on the package path at a lower score
        // (query "billingclient" should find "Lcom/android/billingclient/BillingClient;")
        // Nothing is allocated for a candidate until the top set accepts its score: a short query
        // matches most of the workspace's names
        val className = file.classDefinition.name
        val classMatch = matchSymbol(Types.simpleName(className), normalizedQuery)
            ?: if (pathContains(className, normalizedQuery)) 50 else null
        if (classMatch != null) {
            matches.offer(ScoredSymbol(file, SymbolKind.Class, null, file.classDefinition.range, classMatch))
        }
        for (method in file.methods) {
            val score = matchSymbol(method.name, normalizedQuery) ?: continue
            matches.offer(ScoredSymbol(file, SymbolKind.Method, method.name, method.range, score))
        }
        for (field in file.fields) {
            val score = matchSymbol(field.name, normalizedQuery) ?: continue
            matches.offer(ScoredSymbol(file, SymbolKind.Field, field.name, field.range, score))
        }
    }

    private fun collectFirstSymbols(): List<SymbolInformation> {
        val results = ArrayList<SymbolInformation>(MAX_RESULTS)

        for (file in index.getFilesView()) {
            if (results.size >= MAX_RESULTS) break
            results.add(createClassSymbol(file))

            for (method in file.methods) {
                if (results.size >= MAX_RESULTS) break
                results.add(
                    SymbolInformation(
                        "${file.classDefinition.name}.${method.name}",
                        SymbolKind.Method,
                        Location(file.uri, method.range),
                        file.classDefinition.name
                    )
                )
            }

            for (field in file.fields) {
                if (results.size >= MAX_RESULTS) break
                results.add(
                    SymbolInformation(
                        "${file.classDefinition.name}.${field.name}",
                        SymbolKind.Field,
                        Location(file.uri, field.range),
                        file.classDefinition.name
                    )
                )
            }
        }

        return results
    }
    
    /** Match score, higher is better: 1000 exact, 500 prefix, 100 contains (both plus a bonus for shorter names, so `q` beats `queryString`), 10 fuzzy for queries of 3+ characters; null for no match. */
    private fun matchSymbol(symbolName: String, query: String): Int? {
        if (query.isEmpty()) return 10 // Everything matches empty query

        // Case-insensitive comparisons without lowercasing every symbol: a query runs over
        // every method and field name in the workspace, so one allocation per name counted.
        val lengthBonus = maxOf(0, 100 - symbolName.length * 5)
        if (symbolName.equals(query, ignoreCase = true)) return 1000 + lengthBonus
        if (symbolName.startsWith(query, ignoreCase = true)) return 500 + lengthBonus
        if (symbolName.contains(query, ignoreCase = true)) return 100 + lengthBonus
        if (query.length >= 3 && fuzzyMatch(symbolName, query)) return 10
        return null
    }

    /** Does the package path of `Lcom/pkg/Name;` (without `L` and `;`) contain [query]? */
    private fun pathContains(className: String, query: String): Boolean {
        val at = className.indexOf(query, startIndex = 1, ignoreCase = true)
        return at >= 0 && at + query.length <= className.length - 1
    }
    
    /** Every query letter appears in order in the symbol: `onCrt` matches `onCreate`. */
    private fun fuzzyMatch(symbol: String, query: String): Boolean {
        var symbolIdx = 0
        var queryIdx = 0
        while (symbolIdx < symbol.length && queryIdx < query.length) {
            if (symbol[symbolIdx].lowercaseChar() == query[queryIdx]) queryIdx++
            symbolIdx++
        }
        return queryIdx == query.length
    }
    
    /**
     * Create a SymbolInformation for a class.
     */
    private fun createClassSymbol(file: SmaliFile): SymbolInformation {
        return SymbolInformation(
            file.classDefinition.name,
            SymbolKind.Class,
            Location(file.uri, file.classDefinition.range)
        )
    }

    /** A candidate: the file, the member (null for the class itself) and the score; the symbol is built only for the winners. */
    private class ScoredSymbol(val file: SmaliFile, val kind: SymbolKind, val memberName: String?, val range: Range, val score: Int) {
        val className: String get() = file.classDefinition.name
        fun toSymbolInformation(): SymbolInformation =
            if (memberName == null) SymbolInformation(className, kind, Location(file.uri, range))
            else SymbolInformation("$className.$memberName", kind, Location(file.uri, range), className)
    }

    /** Ties broken by class name then member name, which orders like the full `class.member` name without building it. */
    private val byName = Comparator<ScoredSymbol> { left, right ->
        val byClass = left.className.compareTo(right.className)
        if (byClass != 0) byClass else (left.memberName ?: "").compareTo(right.memberName ?: "")
    }

    private inner class TopSymbolMatches(limit: Int) {
        private val worstFirst = Comparator<ScoredSymbol> { left, right ->
            val scoreCompare = left.score.compareTo(right.score)
            if (scoreCompare != 0) scoreCompare else byName.compare(right, left)
        }

        private val maxSize = limit
        private var buffered = ArrayList<ScoredSymbol>(minOf(limit, 64))
        private var heap: PriorityQueue<ScoredSymbol>? = null

        fun offer(symbol: ScoredSymbol) {
            val activeHeap = heap
            if (activeHeap == null) {
                buffered.add(symbol)
                if (buffered.size > maxSize) {
                    val newHeap = PriorityQueue<ScoredSymbol>(maxSize, worstFirst)
                    buffered.forEach { offerIntoHeap(newHeap, it) }
                    buffered = ArrayList(0)
                    heap = newHeap
                }
                return
            }

            offerIntoHeap(activeHeap, symbol)
        }

        fun forEach(action: (ScoredSymbol) -> Unit) {
            (heap?.toList() ?: buffered).forEach(action)
        }

        fun toSortedSymbolInformation(): List<SymbolInformation> {
            val source = heap?.toList() ?: buffered
            return source
                .sortedWith(compareByDescending<ScoredSymbol> { it.score }.then(byName))
                .map { it.toSymbolInformation() }
        }

        private fun offerIntoHeap(target: PriorityQueue<ScoredSymbol>, symbol: ScoredSymbol) {
            if (target.size < maxSize) {
                target.add(symbol)
                return
            }

            val worst = target.peek() ?: return
            if (worstFirst.compare(symbol, worst) > 0) {
                target.poll()
                target.add(symbol)
            }
        }
    }
    }
