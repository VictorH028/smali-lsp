package xyz.surendrajat.smalilsp.core

import java.util.AbstractMap.SimpleImmutableEntry
import java.util.Arrays
import java.util.Collections

/**
 * A method's labels by name, behind the `Map` interface: two sorted arrays and a binary
 * search instead of a `LinkedHashMap` with a table and an entry per label. A large app has
 * over half a million labels, which made the maps the second-largest item of the AST.
 */
class LabelMap private constructor(
    private val names: Array<String>,
    private val labels: Array<LabelDefinition>
) : AbstractMap<String, LabelDefinition>() {

    override val size: Int get() = names.size

    override fun containsKey(key: String): Boolean = Arrays.binarySearch(names, key) >= 0

    override fun get(key: String): LabelDefinition? {
        val i = Arrays.binarySearch(names, key)
        return if (i >= 0) labels[i] else null
    }

    override val values: Collection<LabelDefinition> get() = labels.asList()

    override val entries: Set<Map.Entry<String, LabelDefinition>>
        get() = object : AbstractSet<Map.Entry<String, LabelDefinition>>() {
            override val size: Int get() = names.size
            override fun iterator(): Iterator<Map.Entry<String, LabelDefinition>> =
                names.indices.asSequence().map { SimpleImmutableEntry(names[it], labels[it]) }.iterator()
        }

    companion object {
        /** [labels] as the smallest map for its size: empty, a singleton, or sorted arrays. */
        fun of(labels: Map<String, LabelDefinition>): Map<String, LabelDefinition> {
            when (labels.size) {
                0 -> return emptyMap()
                1 -> return labels.entries.first().let { Collections.singletonMap(it.key, it.value) }
            }
            val names = labels.keys.toTypedArray()
            Arrays.sort(names)
            return LabelMap(names, Array(names.size) { labels.getValue(names[it]) })
        }
    }
}
