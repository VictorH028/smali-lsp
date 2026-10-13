package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import java.util.PriorityQueue
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.util.Types

/**
 * Provides workspace-wide symbol search.
 * 
 * Features:
 * - Fuzzy matching on symbol names
 * - Case-insensitive search
 * - Searches classes, methods, and fields
 * - Fast performance (< 500ms for 4000+ files)
 * 
 * LSP Request: workspace/symbol
 * 
 * Search Algorithm:
 * 1. If query is empty, return all symbols (up to limit)
 * 2. Normalize query to lowercase for case-insensitive matching
 * 3. Check if symbol name contains query substring
 * 4. Support fuzzy matching: "FBAdapter" matches "FeedbackAdapter"
 * 5. Rank results by match quality (exact > prefix > contains)
 * 6. Return up to 500 results for performance
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
        val className = file.classDefinition.name
        val classMatch = matchSymbol(Types.simpleName(className), normalizedQuery)
            ?: if (pathContains(className, normalizedQuery)) 50 else null
        if (classMatch != null) {
            matches.offer(ScoredSymbol(className, SymbolKind.Class, Location(file.uri, file.classDefinition.range), null, classMatch))
        }
        for (method in file.methods) {
            val score = matchSymbol(method.name, normalizedQuery) ?: continue
            matches.offer(ScoredSymbol("$className.${method.name}", SymbolKind.Method, Location(file.uri, method.range), className, score))
        }
        for (field in file.fields) {
            val score = matchSymbol(field.name, normalizedQuery) ?: continue
            matches.offer(ScoredSymbol("$className.${field.name}", SymbolKind.Field, Location(file.uri, field.range), className, score))
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
    
    /**
     * Match a symbol name against a query.
     * 
     * Returns match score (higher is better):
     * - 1000: Exact match (case-insensitive)
     * - 500 + length bonus: Prefix match (shorter symbols score higher)
     * - 100 + length bonus: Contains match (shorter symbols score higher)
     * - 10: Fuzzy match (only for queries >= 3 chars)
     * - null: No match
     * 
     * The length bonus ensures shorter symbol names rank higher:
     * - Searching "q" returns class "q" before "queryString"
     */
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
    
    /**
     * Fuzzy match: check if all query letters appear in order in symbol.
     * 
     * Examples:
     * - "FBA" matches "FeedBackAdapter"
     * - "onCrt" matches "onCreate"
     * - "str" matches "StringBuilder"
     */
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

    private data class ScoredSymbol(
        val name: String,
        val kind: SymbolKind,
        val location: Location,
        val containerName: String?,
        val score: Int
    )

    private class TopSymbolMatches(limit: Int) {
        private val worstFirst = Comparator<ScoredSymbol> { left, right ->
            val scoreCompare = left.score.compareTo(right.score)
            if (scoreCompare != 0) {
                scoreCompare
            } else {
                right.name.compareTo(left.name)
            }
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
                .sortedWith(compareByDescending<ScoredSymbol> { it.score }.thenBy { it.name })
                .map { symbol ->
                    SymbolInformation(
                        symbol.name,
                        symbol.kind,
                        symbol.location,
                        symbol.containerName
                    )
                }
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
