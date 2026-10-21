package xyz.surendrajat.smalilsp.core

import java.util.AbstractMap.SimpleImmutableEntry
import java.util.Arrays
import java.util.Collections

/**
 * A method's labels by name, behind the `Map` interface: a sorted array of names and an int
 * array of positions, with a [LabelDefinition] built when one is asked for. A large app has
 * over half a million labels, which made the maps the second-largest item of the AST.
 */
class LabelMap private constructor(
    private val names: Array<String>,
    private val positions: IntArray
) : AbstractMap<String, LabelDefinition>() {

    override val size: Int get() = names.size

    override fun containsKey(key: String): Boolean = Arrays.binarySearch(names, key) >= 0

    override fun get(key: String): LabelDefinition? {
        val i = Arrays.binarySearch(names, key)
        return if (i >= 0) at(i) else null
    }

    private fun at(i: Int) = LabelDefinition(names[i], positions[3 * i], positions[3 * i + 1], positions[3 * i + 2])

    override val values: Collection<LabelDefinition>
        get() = object : AbstractList<LabelDefinition>(), RandomAccess {
            override val size: Int get() = names.size
            override fun get(index: Int): LabelDefinition = at(index)
        }

    override val entries: Set<Map.Entry<String, LabelDefinition>>
        get() = object : AbstractSet<Map.Entry<String, LabelDefinition>>() {
            override val size: Int get() = names.size
            override fun iterator(): Iterator<Map.Entry<String, LabelDefinition>> =
                names.indices.asSequence().map { SimpleImmutableEntry(names[it], at(it)) }.iterator()
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
            val positions = IntArray(names.size * 3)
            for ((i, name) in names.withIndex()) {
                val label = labels.getValue(name)
                positions[3 * i] = label.line; positions[3 * i + 1] = label.start; positions[3 * i + 2] = label.end
            }
            return LabelMap(names, positions)
        }
    }
}
