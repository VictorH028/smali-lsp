package xyz.surendrajat.smalilsp.util

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

    val size: Int get() = pool.size

    companion object {
        private const val MAX_SIZE = 500_000

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
