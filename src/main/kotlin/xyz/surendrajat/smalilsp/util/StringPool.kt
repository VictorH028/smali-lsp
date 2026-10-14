package xyz.surendrajat.smalilsp.util

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Interning pool for the strings a workspace's ASTs share: class names, member names,
 * descriptors, modifiers, labels. Each [xyz.surendrajat.smalilsp.index.WorkspaceIndex] owns
 * one, and every parser feeding that index interns through it, so a class name that appears
 * in thousands of files is one `String`. Bounded: past [MAX_SIZE] entries new strings pass
 * through uninterned, which keeps a pathological input from growing the pool without limit.
 */
class StringPool {
    private val pool = ConcurrentHashMap<String, String>()
    private val modifierSets = ConcurrentHashMap<List<String>, Set<String>>()

    init {
        // Strings that appear in nearly every file skip the computeIfAbsent path
        for (s in COMMON) pool[s] = s
    }

    /** The canonical instance of [s]. Thread-safe. */
    fun intern(s: String): String {
        val existing = pool[s]
        if (existing != null) return existing
        if (pool.size >= MAX_SIZE) return s
        return pool.computeIfAbsent(s) { it }
    }

    /**
     * The canonical set for a member's modifiers, in source order. A workspace has a few dozen
     * distinct combinations, so every member shares one immutable set instead of carrying a
     * `LinkedHashSet` with its table and entries. Bounded like the string pool.
     */
    fun internModifiers(modifiers: List<String>): Set<String> {
        if (modifiers.isEmpty()) return emptySet()
        modifierSets[modifiers]?.let { return it }
        if (modifierSets.size >= MAX_MODIFIER_SETS) return LinkedHashSet(modifiers)
        return modifierSets.computeIfAbsent(ArrayList(modifiers)) { Collections.unmodifiableSet(LinkedHashSet(it)) }
    }

    /** A string literal's value: interned when short, since resource names and keys repeat across files; long ones pass through. */
    fun internLiteral(value: String): String = if (value.length <= MAX_LITERAL_LENGTH) intern(value) else value

    val size: Int get() = pool.size

    companion object {
        private const val MAX_SIZE = 500_000
        private const val MAX_MODIFIER_SETS = 4_096
        private const val MAX_LITERAL_LENGTH = 64

        private val COMMON = listOf(
            // Modifiers
            "public", "private", "protected", "static", "final",
            "abstract", "synthetic", "bridge", "varargs", "native",
            "transient", "volatile", "synchronized", "strictfp", "enum",
            "interface", "annotation", "constructor",
            // Primitive types and void
            "V", "I", "Z", "J", "B", "C", "S", "F", "D",
            // Very common descriptors
            "()V", "(I)V", "(Z)V", "()I", "()Z", "()Ljava/lang/String;",
            // Common method names
            "<init>", "<clinit>", "toString", "hashCode", "equals",
            // Common SDK types
            "Ljava/lang/Object;", "Ljava/lang/String;", "Ljava/lang/Class;",
            "Ljava/lang/Throwable;", "Ljava/lang/Exception;",
            "Ljava/lang/Runnable;", "Ljava/lang/StringBuilder;",
            "Landroid/content/Context;", "Landroid/view/View;",
            "Landroid/os/Bundle;", "Landroid/app/Activity;"
        )
    }
}
