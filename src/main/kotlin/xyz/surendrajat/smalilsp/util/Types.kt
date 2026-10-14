package xyz.surendrajat.smalilsp.util

/**
 * Dalvik type descriptors and class names.
 *
 * Descriptors are primitives (`Z B C S I J F D V`), objects (`Lpkg/Class;`) and arrays
 * (`[type`). A method descriptor is `(paramTypes)returnType`.
 */
object Types {

    /** Android framework and Java standard library packages, which never exist in a decompiled workspace. */
    private val SDK_PREFIXES = listOf(
        "Ljava/",
        "Ljavax/",
        "Landroid/",
        "Landroidx/",
        "Lkotlin/",
        "Lkotlinx/",
        "Ldalvik/",
        "Lcom/google/android/",
        "Lcom/android/",
        "Lsun/",
        "Ljdk/",
        "Lorg/json/",
        "Lorg/xml/",
        "Lorg/w3c/",
        "Lorg/apache/"
    )

    private val PRIMITIVES = charArrayOf('Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D', 'V')

    /**
     * True for SDK classes and primitives, neither of which can be navigated to in the
     * workspace. Array brackets are stripped first: `[Ljava/lang/String;` is an SDK class.
     */
    fun isSdkClass(className: String): Boolean {
        val baseType = className.trimStart('[')
        if (!baseType.startsWith("L")) return true
        return SDK_PREFIXES.any { baseType.startsWith(it) }
    }

    /** The package part of a class name, slash-terminated: `Lcom/app/Foo;` → `Lcom/app/`, `LFoo;` → `L`. */
    fun packageOf(className: String): String = className.trimStart('[').substring(0, className.trimStart('[').lastIndexOf('/') + 1).ifEmpty { "L" }

    /** `[Lcom/example/Outer$Inner;` → `Inner`. */
    fun simpleName(className: String): String {
        val cleaned = className.trimStart('[').removePrefix("L").removeSuffix(";")
        return cleaned.substringAfterLast('/').substringAfterLast('$')
    }

    /**
     * The class a descriptor refers to, with array brackets stripped:
     * `[[Ljava/lang/String;` → `Ljava/lang/String;`. Null for primitives and malformed input.
     */
    fun className(descriptor: String): String? {
        var i = 0
        while (i < descriptor.length && descriptor[i] == '[') i++
        if (i < descriptor.length && descriptor[i] == 'L') {
            val end = descriptor.indexOf(';', i)
            if (end > i) return descriptor.substring(i, end + 1)
        }
        return null
    }

    /** [className] with array brackets stripped; `[Lcom/Foo;` → `Lcom/Foo;`, `Lcom/Foo;` unchanged. */
    fun elementClass(className: String): String = className.trimStart('[')

    /** Every class referenced by a type sequence or method descriptor; arrays contribute their element class. */
    fun classNames(sequence: String): Set<String> =
        parseTypeSequence(sequence).mapNotNull { it.className }.toSet()

    /** True for a primitive or an array of primitives. */
    fun isPrimitive(descriptor: String): Boolean {
        val base = descriptor.trimStart('[')
        return base.length == 1 && base[0] in PRIMITIVES
    }

    /** `[[Ljava/lang/String;` → `java.lang.String[][]`, `I` → `int`; `unknown` for malformed input. */
    fun readableName(descriptor: String): String {
        var i = 0
        while (i < descriptor.length && descriptor[i] == '[') i++
        val brackets = "[]".repeat(i)
        val base = when {
            i >= descriptor.length -> "unknown"
            descriptor[i] == 'L' -> {
                val end = descriptor.indexOf(';', i)
                if (end > i) descriptor.substring(i + 1, end).replace('/', '.') else "unknown"
            }
            descriptor[i] == 'Z' -> "boolean"
            descriptor[i] == 'B' -> "byte"
            descriptor[i] == 'C' -> "char"
            descriptor[i] == 'S' -> "short"
            descriptor[i] == 'I' -> "int"
            descriptor[i] == 'J' -> "long"
            descriptor[i] == 'F' -> "float"
            descriptor[i] == 'D' -> "double"
            descriptor[i] == 'V' -> "void"
            else -> "unknown"
        }
        return base + brackets
    }

    /** A type inside a sequence, with its offsets. */
    data class TypeSpan(
        /** Full type, e.g. `Ljava/lang/String;`, `[I`, `I`. */
        val type: String,
        /** Start offset in the sequence (inclusive). */
        val start: Int,
        /** End offset in the sequence (exclusive). */
        val end: Int,
        /** An object type or an array of objects. */
        val isClassType: Boolean,
        /** A primitive or an array of primitives. */
        val isPrimitive: Boolean,
        val isArray: Boolean,
        /** For object types and object arrays: the class including `L` and `;`. */
        val className: String? = null
    )

    /**
     * Parse every type in a type sequence, which may be the parameter part of a descriptor
     * (`ILjava/lang/String;[I`) or a whole descriptor; `(`, `)` and spaces are skipped.
     * Truncated types (a missing `;` or a trailing `[`) are skipped.
     */
    fun parseTypeSequence(sequence: String): List<TypeSpan> {
        val types = mutableListOf<TypeSpan>()
        var i = 0
        while (i < sequence.length) {
            when (sequence[i]) {
                '(', ')', ' ' -> i++
                in PRIMITIVES -> {
                    types.add(TypeSpan(sequence[i].toString(), i, i + 1, isClassType = false, isPrimitive = true, isArray = false))
                    i++
                }
                'L' -> {
                    val semi = sequence.indexOf(';', i)
                    if (semi > i) {
                        val type = sequence.substring(i, semi + 1)
                        types.add(TypeSpan(type, i, semi + 1, isClassType = true, isPrimitive = false, isArray = false, className = type))
                        i = semi + 1
                    } else {
                        i++
                    }
                }
                '[' -> {
                    val arrayStart = i
                    while (i < sequence.length && sequence[i] == '[') i++
                    if (i >= sequence.length) break
                    when (sequence[i]) {
                        'L' -> {
                            val semi = sequence.indexOf(';', i)
                            if (semi > i) {
                                types.add(TypeSpan(sequence.substring(arrayStart, semi + 1), arrayStart, semi + 1, isClassType = true, isPrimitive = false, isArray = true, className = sequence.substring(i, semi + 1)))
                                i = semi + 1
                            } else {
                                i++
                            }
                        }
                        in PRIMITIVES -> {
                            types.add(TypeSpan(sequence.substring(arrayStart, i + 1), arrayStart, i + 1, isClassType = false, isPrimitive = true, isArray = true))
                            i++
                        }
                        else -> i++
                    }
                }
                else -> i++
            }
        }
        return types
    }

    /**
     * The type strings of a sequence, in order. Same walk as [parseTypeSequence] without the
     * span objects; used on the indexing hot path.
     */
    fun parseTypeStrings(sequence: String): List<String> {
        val types = mutableListOf<String>()
        var i = 0
        while (i < sequence.length) {
            when (sequence[i]) {
                '(', ')', ' ' -> i++
                in PRIMITIVES -> {
                    types.add(sequence[i].toString())
                    i++
                }
                'L' -> {
                    val semi = sequence.indexOf(';', i)
                    if (semi > i) {
                        types.add(sequence.substring(i, semi + 1))
                        i = semi + 1
                    } else {
                        i++
                    }
                }
                '[' -> {
                    val arrayStart = i
                    while (i < sequence.length && sequence[i] == '[') i++
                    if (i >= sequence.length) break
                    when (sequence[i]) {
                        'L' -> {
                            val semi = sequence.indexOf(';', i)
                            if (semi > i) {
                                types.add(sequence.substring(arrayStart, semi + 1))
                                i = semi + 1
                            } else {
                                i++
                            }
                        }
                        in PRIMITIVES -> {
                            types.add(sequence.substring(arrayStart, i + 1))
                            i++
                        }
                        else -> i++
                    }
                }
                else -> i++
            }
        }
        return types
    }

    /** The type that covers [offset] in the sequence, or null when the offset is on a delimiter. */
    fun typeAtOffset(sequence: String, offset: Int): TypeSpan? =
        parseTypeSequence(sequence).find { offset >= it.start && offset < it.end }
}
