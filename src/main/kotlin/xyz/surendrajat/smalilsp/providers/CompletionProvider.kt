package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import xyz.surendrajat.smalilsp.data.DalvikOpcodeDatabase
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.util.Types

/**
 * Provides completion suggestions for smali files.
 *
 * Completion contexts:
 * - Class names (L-prefix types in any context)
 * - Method names (after ->)
 * - Field names (after ->)
 * - Opcodes (at instruction start)
 *
 * LSP Request: textDocument/completion
 */
class CompletionProvider(
    private val index: WorkspaceIndex
) {
    private companion object {
        val CLASS_REFERENCE = Regex("""(L[\w/$]+;)""")
        const val MAX_CLASS_ITEMS = 50
    }

    /**
     * Provide completion items at the given position.
     */
    fun provideCompletions(uri: String, position: Position, lineText: String): CompletionList {
        val textBeforeCursor = if (position.character <= lineText.length) {
            lineText.substring(0, position.character)
        } else {
            lineText
        }
        val trimmed = textBeforeCursor.trimStart()

        return when {
            // After -> : complete method or field names on a class
            trimmed.contains("->") -> completeMembers(trimmed, position)

            // Directive context: .super, .implements, .field type, method descriptor
            trimmed.startsWith(".super ") -> completeClassNames(trimmed.removePrefix(".super ").trim(), position)
            trimmed.startsWith(".implements ") -> completeClassNames(trimmed.removePrefix(".implements ").trim(), position)

            // L-prefix type being typed anywhere (method descriptors, field types, instructions)
            else -> extractPartialClassName(trimmed)?.let { partial ->
                // partial includes the 'L' prefix, replace range covers "Lpartial..."
                completeClassNames(partial, position)
            } ?: if (!trimmed.startsWith(".") && !trimmed.startsWith("#")) {
                // Instruction opcode at start of line (inside method body)
                completeOpcodes(trimmed, position)
            } else {
                CompletionList(false, emptyList())
            }
        }
    }

    private fun completeMembers(textBeforeCursor: String, position: Position): CompletionList {
        // Parse: "invoke-virtual {p0}, Lcom/example/MyClass;->meth"
        // or:    "iget v0, p0, Lcom/example/MyClass;->fie"
        val arrowIndex = textBeforeCursor.lastIndexOf("->")
        if (arrowIndex < 0) return CompletionList(false, emptyList())

        val prefix = textBeforeCursor.substring(arrowIndex + 2) // partial member name
        val beforeArrow = textBeforeCursor.substring(0, arrowIndex)

        // Extract class name (last L...;  before ->)
        val className = CLASS_REFERENCE.findAll(beforeArrow).lastOrNull()?.value ?: return CompletionList(false, emptyList())
        if (!index.hasClass(className)) return CompletionList(false, emptyList())
        val items = mutableListOf<CompletionItem>()
        val replaceRange = Range(
            Position(position.line, position.character - prefix.length),
            position
        )

        // The class's own members, then the inherited ones up the hierarchy; an override hides what it overrides
        val seen = HashSet<String>()
        val visited = HashSet<String>()
        val pending = ArrayDeque<String>().apply { add(className) }
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            val file = index.findClass(current) ?: continue
            for (method in file.methods) {
                if (!method.name.startsWith(prefix, ignoreCase = true)) continue
                if (current != className && (method.isConstructor || "private" in method.modifiers)) continue
                val text = "${method.name}${method.descriptor}"
                if (!seen.add(text)) continue
                items.add(CompletionItem(text).apply {
                    kind = CompletionItemKind.Method
                    detail = current
                    textEdit = Either.forLeft(TextEdit(replaceRange, text))
                    sortText = "%s%03d".format(if (current == className) "0" else "1", items.size)
                })
            }
            for (field in file.fields) {
                if (!field.name.startsWith(prefix, ignoreCase = true)) continue
                if (current != className && "private" in field.modifiers) continue
                val text = "${field.name}:${field.type}"
                if (!seen.add(text)) continue
                items.add(CompletionItem(text).apply {
                    kind = CompletionItemKind.Field
                    detail = current
                    textEdit = Either.forLeft(TextEdit(replaceRange, text))
                    sortText = "%s%03d".format(if (current == className) "0" else "1", items.size)
                })
            }
            file.classDefinition.superClass?.let { pending.add(it) }
            pending.addAll(file.classDefinition.interfaces)
        }

        return CompletionList(false, items)
    }

    /**
     * Class names matching what was typed, best first: with an `L` the typed text is a prefix of
     * the full name; in a directive it may also be a prefix of the simple name or appear in the
     * path. Shorter names rank higher, then alphabetical, so the answer is the same on every
     * keystroke instead of whatever the index iterates first.
     */
    private fun completeClassNames(partial: String, position: Position): CompletionList {
        val hasLPrefix = partial.startsWith("L")
        val replaceRange = Range(
            Position(position.line, position.character - partial.length),
            position
        )

        val matches = ArrayList<Pair<Int, String>>()   // (rank, class name): lower is better
        for (className in index.getClassNames()) {
            val rank = when {
                hasLPrefix -> if (className.startsWith(partial, ignoreCase = true)) 0 else continue
                Types.simpleName(className).startsWith(partial, ignoreCase = true) -> 0
                className.contains(partial, ignoreCase = true) -> 1
                else -> continue
            }
            matches.add(rank to className)
        }
        matches.sortWith(compareBy({ it.first }, { it.second.length }, { it.second }))

        val items = matches.take(MAX_CLASS_ITEMS).mapIndexed { i, (_, className) ->
            val simpleName = Types.simpleName(className)
            val readablePath = className.removePrefix("L").removeSuffix(";")
            CompletionItem(readablePath).apply {
                kind = CompletionItemKind.Class
                detail = simpleName
                textEdit = Either.forLeft(TextEdit(replaceRange, className))
                filterText = "$readablePath $simpleName"
                sortText = "%03d".format(i)
            }
        }
        return CompletionList(matches.size > MAX_CLASS_ITEMS, items)
    }

    private fun completeOpcodes(partial: String, position: Position): CompletionList {
        if (partial.isEmpty()) return CompletionList(false, emptyList())
        val replaceRange = Range(
            Position(position.line, position.character - partial.length),
            position
        )
        val items = DalvikOpcodeDatabase.allInstructionNames()
            .filter { it.startsWith(partial, ignoreCase = true) }
            .take(30)
            .mapNotNull { opcodeName ->
                val info = DalvikOpcodeDatabase.lookup(opcodeName) ?: return@mapNotNull null
                val item = CompletionItem(opcodeName)
                item.kind = CompletionItemKind.Keyword
                item.detail = "0x${info.opcode} ${info.formatId}"
                item.documentation = Either.forRight(
                    MarkupContent(MarkupKind.MARKDOWN, info.toCompletionDoc())
                )
                item.textEdit = Either.forLeft(TextEdit(replaceRange, opcodeName))
                item
            }
        return CompletionList(false, items)
    }

    /**
     * Extract partial class name being typed, including the leading 'L'.
     * Looks for an unclosed L-prefix type: "Lcom/example/My" (no trailing semicolon).
     * Returns the full partial including 'L', e.g., "Lcom/example/My".
     */
    private fun extractPartialClassName(text: String): String? {
        // Find last 'L' that starts a type reference and isn't closed with ';'
        val lastL = text.lastIndexOf('L')
        if (lastL < 0) return null
        val afterL = text.substring(lastL + 1)
        // If there's a semicolon, the type is complete — no completion needed
        if (afterL.contains(';')) return null
        // Must look like a partial class path (letters, digits, /, $)
        if (afterL.isEmpty() || afterL.all { it.isLetterOrDigit() || it == '/' || it == '$' || it == '_' }) {
            return text.substring(lastL) // Include the 'L' prefix
        }
        return null
    }

}
