package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import xyz.surendrajat.smalilsp.core.FieldDefinition
import xyz.surendrajat.smalilsp.core.InstructionScanner
import xyz.surendrajat.smalilsp.core.MethodDefinition
import xyz.surendrajat.smalilsp.core.RegisterFrame
import xyz.surendrajat.smalilsp.core.RegisterToken
import xyz.surendrajat.smalilsp.core.methodAt
import xyz.surendrajat.smalilsp.data.DalvikOpcodeDatabase
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.InstructionShapes
import xyz.surendrajat.smalilsp.parser.OperandShape
import xyz.surendrajat.smalilsp.util.Types

/**
 * Completion that knows where the caret is. After `->`, the members the opcode can take: static
 * methods for `invoke-static`, the owner's private methods and constructors for `invoke-direct`,
 * instance methods otherwise (inherited ones too, an override hiding what it overrides), and only
 * those whose register count the braces hold; static or instance fields of the type the field
 * opcode moves. Before it, class names of the kind the operand needs: a concrete class for
 * `new-instance`, an interface for `.implements` or an `invoke-interface` owner, a throwable for
 * `.catch`. After `:`, the method's labels; on a `v` or `p`, the frame's registers with what they
 * hold; after `.`, the directives that belong where the caret is, the open block's `.end` first.
 * At the start of a line, opcodes, the `move-result` a call just made first, each inserting its
 * operand shape as snippet tab stops when the client takes snippets. The class list is scanned
 * once with the best fifty kept, so an answer costs a few hundred lookups at most.
 */
class CompletionProvider(
    private val index: WorkspaceIndex
) {
    private companion object {
        /** An owner before `->`: obfuscators use any letters, so anything but blanks and the `;`. */
        val CLASS_REFERENCE = Regex("""(L[^\s;]+;)""")
        const val MAX_CLASS_ITEMS = 50
        val REGISTER = Regex("""[vp]\d*""")
        val METHOD_DIRECTIVES = listOf(".registers", ".locals", ".param", ".line", ".local", ".end local", ".restart local", ".prologue", ".epilogue", ".catch", ".catchall", ".annotation", ".end annotation", ".subannotation", ".end subannotation", ".packed-switch", ".end packed-switch", ".sparse-switch", ".end sparse-switch", ".array-data", ".end array-data", ".end param", ".end method")
        val CLASS_DIRECTIVES = listOf(".class", ".super", ".source", ".implements", ".field", ".end field", ".method", ".annotation", ".end annotation", ".subannotation", ".end subannotation", ".enum")
        val BLOCK_ENDS = listOf("method", "annotation", "subannotation", "packed-switch", "sparse-switch", "array-data", "field", "param", "local")
    }

    /** The client inserts snippets, so an opcode completion carries its operand shape as tab stops. */
    @Volatile var snippetSupport = false

    private enum class ClassKind { ANY, CLASS, INTERFACE, CONCRETE, THROWABLE }

    /** What the opcode before the caret can take after `->`; [slots] is the register count the braces hold, -1 when they are not there yet. */
    private class Wanted(val methods: Boolean, val fields: Boolean, val static: Boolean?, val direct: Boolean = false, val slots: Int = -1, val fieldType: Char = ' ')

    fun provideCompletions(uri: String, position: Position, lineText: String): CompletionList {
        val before = if (position.character <= lineText.length) lineText.substring(0, position.character) else lineText
        val trimmed = before.trimStart()
        val word = currentWord(before)
        return when {
            word.startsWith(":") -> completeLabels(uri, position, word)
            trimmed.contains("->") -> completeMembers(uri, position, lineText, trimmed)
            trimmed.startsWith(".super ") -> completeClassNames(trimmed.removePrefix(".super ").trim(), position, ClassKind.CLASS)
            trimmed.startsWith(".implements ") -> completeClassNames(trimmed.removePrefix(".implements ").trim(), position, ClassKind.INTERFACE)
            trimmed.startsWith(".end ") -> completeBlockEnd(uri, position, trimmed.removePrefix(".end "))
            trimmed.startsWith(".") && ' ' !in trimmed -> completeDirectives(uri, position, trimmed)
            trimmed.startsWith("#") -> CompletionList(false, emptyList())
            else -> partialClassName(trimmed)?.let { completeClassNames(it, position, classKindFor(trimmed)) }
                ?: when {
                    ' ' !in trimmed -> completeFirstWord(uri, position, trimmed)
                    REGISTER.matches(word) || (word.isEmpty() && before.endsWith("{")) -> completeRegisters(uri, position, word)
                    else -> CompletionList(false, emptyList())
                }
        }
    }

    /** The first word of a line: opcodes inside a method, directives at class level, opcodes when the file is not known. */
    private fun completeFirstWord(uri: String, position: Position, partial: String): CompletionList {
        val file = index.findFileByUri(uri)
        return if (file != null && file.methodAt(position.line) == null) directiveItems(CLASS_DIRECTIVES, partial, position, null) else completeOpcodes(uri, position, partial)
    }

    /** Directives for where the caret is; the innermost open block's `.end` comes first and preselected. */
    private fun completeDirectives(uri: String, position: Position, partial: String): CompletionList {
        val file = index.findFileByUri(uri)
        val inMethod = file?.methodAt(position.line) != null
        val candidates = if (file == null) METHOD_DIRECTIVES + CLASS_DIRECTIVES.filter { it !in METHOD_DIRECTIVES } else if (inMethod) METHOD_DIRECTIVES else CLASS_DIRECTIVES
        val open = if (".end".startsWith(partial.take(4))) openBlock(uri, position.line)?.let { ".end $it" } else null
        return directiveItems(candidates, partial, position, open)
    }

    private fun directiveItems(candidates: List<String>, partial: String, position: Position, first: String?): CompletionList {
        val replaceRange = Range(Position(position.line, position.character - partial.length), position)
        val ordered = (listOfNotNull(first) + candidates.filter { it != first }).filter { it.startsWith(partial, ignoreCase = true) }
        val items = ordered.mapIndexed { i, directive ->
            CompletionItem(directive).apply {
                kind = CompletionItemKind.Keyword
                textEdit = Either.forLeft(TextEdit(replaceRange, directive))
                sortText = "%03d".format(i)
                if (directive == first) preselect = true
            }
        }
        return CompletionList(false, items)
    }

    /** After `.end `: the block kinds, the innermost open one first. */
    private fun completeBlockEnd(uri: String, position: Position, partial: String): CompletionList {
        val open = openBlock(uri, position.line)
        return directiveItems(BLOCK_ENDS, partial, position, open)
    }

    /**
     * The innermost block still open above [line]: `.method`, `.annotation`, `.subannotation`, a
     * switch or array payload, a `.field` or `.param` whose annotations follow; null at the top.
     */
    private fun openBlock(uri: String, line: Int): String? {
        val lines = index.getLines(uri) ?: return null
        val stack = ArrayList<String>()
        var tentative = false
        for (i in 0 until minOf(line, lines.size)) {
            val text = lines[i].trim()
            if (text.isEmpty()) continue
            val first = text.substringBefore(' ').removeSuffix(",")
            val head = if (first == ".end") ".end " + text.substringAfter(' ').substringBefore(' ').removeSuffix(",") else first
            if (tentative && head != ".annotation") { stack.removeAt(stack.size - 1); tentative = false }
            if (head.startsWith(".end ")) {
                val at = stack.lastIndexOf(head.substring(5))
                if (at >= 0) while (stack.size > at) stack.removeAt(stack.size - 1)
                continue
            }
            when (head) {
                ".method", ".annotation", ".packed-switch", ".sparse-switch", ".array-data" -> stack.add(head.substring(1))
                ".field", ".param" -> { stack.add(head.substring(1)); tentative = true }
            }
            if (".subannotation" in text.split(' ')) stack.add("subannotation")
        }
        if (tentative) stack.removeAt(stack.size - 1)
        return stack.lastOrNull()
    }

    /** The registers of the method's frame, each with what it holds: `this`, a parameter's type, a local's slot. */
    private fun completeRegisters(uri: String, position: Position, word: String): CompletionList {
        val file = index.findFileByUri(uri) ?: return CompletionList(false, emptyList())
        val method = file.methodAt(position.line) ?: return CompletionList(false, emptyList())
        val lines = index.getLines(uri) ?: return CompletionList(false, emptyList())
        val frame = RegisterFrame.of(method, lines) ?: return CompletionList(false, emptyList())
        val replaceRange = Range(Position(position.line, position.character - word.length), position)
        val items = ArrayList<CompletionItem>()
        fun add(name: String, detail: String) {
            if (!name.startsWith(word, ignoreCase = true)) return
            items.add(CompletionItem(name).apply {
                kind = CompletionItemKind.Variable
                this.detail = detail
                textEdit = Either.forLeft(TextEdit(replaceRange, name))
                sortText = "%03d".format(items.size)
            })
        }
        for (n in 0 until frame.locals) add("v$n", "local ${n + 1} of ${frame.locals}")
        for (n in 0 until frame.parameters) {
            val (index, type, secondHalf) = Registers.parameterOf(method, frame, RegisterToken("", 0, 0, true, n)) ?: continue
            add("p$n", when {
                index < 0 -> "this (${Types.readableName(file.classDefinition.name)})"
                secondHalf -> "second half of ${Types.readableName(type)}"
                else -> Types.readableName(type) + (method.parameters[index].name?.let { " $it" } ?: "")
            })
        }
        return CompletionList(false, items)
    }

    /** The token being typed: everything after the last blank, comma or brace. */
    private fun currentWord(before: String): String {
        var i = before.length
        while (i > 0 && before[i - 1] !in " \t,{}") i--
        return before.substring(i)
    }

    private fun classKindFor(trimmed: String): ClassKind {
        val opcode = trimmed.substringBefore(' ')
        return when {
            opcode == "new-instance" -> ClassKind.CONCRETE
            opcode == ".catch" -> ClassKind.THROWABLE
            opcode.startsWith("invoke-interface") -> ClassKind.INTERFACE
            else -> ClassKind.ANY
        }
    }

    private fun completeMembers(uri: String, position: Position, lineText: String, trimmed: String): CompletionList {
        val arrowIndex = trimmed.lastIndexOf("->")
        val prefix = trimmed.substring(arrowIndex + 2)
        val beforeArrow = trimmed.substring(0, arrowIndex)
        val className = CLASS_REFERENCE.findAll(beforeArrow).lastOrNull()?.value ?: return CompletionList(false, emptyList())
        if (!index.hasClass(className)) return CompletionList(false, emptyList())
        val wanted = wanted(trimmed.substringBefore(' '), beforeArrow, uri, position.line, lineText)
        val items = mutableListOf<CompletionItem>()
        val replaceRange = Range(Position(position.line, position.character - prefix.length), position)

        // The class's own members, then the inherited ones up the hierarchy; an override hides what it overrides
        val seen = HashSet<String>()
        val visited = HashSet<String>()
        val pending = ArrayDeque<String>().apply { add(className) }
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            val file = index.findClass(current) ?: continue
            val own = current == className
            if (wanted.methods) for (method in file.methods) {
                if (!method.name.startsWith(prefix, ignoreCase = true) || !wanted.takes(method, own)) continue
                val text = "${method.name}${method.descriptor}"
                if (!seen.add(text)) continue
                items.add(CompletionItem(text).apply {
                    kind = CompletionItemKind.Method
                    detail = current
                    textEdit = Either.forLeft(TextEdit(replaceRange, text))
                    sortText = "%s%03d".format(if (own) "0" else "1", items.size)
                })
            }
            if (wanted.fields) for (field in file.fields) {
                if (!field.name.startsWith(prefix, ignoreCase = true) || !wanted.takes(field, own)) continue
                val text = "${field.name}:${field.type}"
                if (!seen.add(text)) continue
                items.add(CompletionItem(text).apply {
                    kind = CompletionItemKind.Field
                    detail = current
                    textEdit = Either.forLeft(TextEdit(replaceRange, text))
                    sortText = "%s%03d".format(if (own) "0" else "1", items.size)
                })
            }
            if (wanted.direct) break   // invoke-direct reaches only the named class
            file.classDefinition.superClass?.let { pending.add(it) }
            pending.addAll(file.classDefinition.interfaces)
        }
        return CompletionList(false, items)
    }

    private fun Wanted.takes(method: MethodDefinition, own: Boolean): Boolean {
        val static = "static" in method.modifiers
        if (direct) return own && (method.isConstructor || "private" in method.modifiers)
        if (this.static != null && static != this.static) return false
        if (this.static == false && (method.isConstructor || "private" in method.modifiers)) return false   // an instance call binds no constructor and no private method
        if (!own && (method.isConstructor || "private" in method.modifiers)) return false
        return slots < 0 || RegisterFrame.parameterSlots(method) == slots
    }

    private fun Wanted.takes(field: FieldDefinition, own: Boolean): Boolean {
        if (!own && "private" in field.modifiers) return false
        if (static != null && ("static" in field.modifiers) != static) return false
        return when (fieldType) {
            ' ' -> true
            'L' -> field.type.startsWith("L") || field.type.startsWith("[")
            'W' -> field.type == "J" || field.type == "D"
            'I' -> field.type == "I" || field.type == "F"
            else -> field.type.length == 1 && field.type[0] == fieldType
        }
    }

    /** The members [opcode] can take, and how many registers its braces hold when they are complete. */
    private fun wanted(opcode: String, beforeArrow: String, uri: String, line: Int, lineText: String): Wanted = when {
        opcode.startsWith("invoke-") -> {
            val slots = if ('{' in beforeArrow && '}' in beforeArrow) passedRegisters(uri, line, lineText) else -1
            when {
                opcode.startsWith("invoke-static") -> Wanted(methods = true, fields = false, static = true, slots = slots)
                opcode.startsWith("invoke-direct") -> Wanted(methods = true, fields = false, static = false, direct = true, slots = slots)
                opcode.startsWith("invoke-polymorphic") || opcode.startsWith("invoke-custom") -> Wanted(methods = true, fields = false, static = null)
                else -> Wanted(methods = true, fields = false, static = false, slots = slots)
            }
        }
        opcode.startsWith("iget") || opcode.startsWith("iput") || opcode.startsWith("sget") || opcode.startsWith("sput") -> {
            val type = when (opcode.substringAfter('-', "")) {
                "object" -> 'L'; "wide" -> 'W'; "boolean" -> 'Z'; "byte" -> 'B'; "char" -> 'C'; "short" -> 'S'; else -> 'I'
            }
            Wanted(methods = false, fields = true, static = opcode[0] == 's', fieldType = type)
        }
        opcode == ".enum" -> Wanted(methods = false, fields = true, static = true)
        else -> Wanted(methods = true, fields = true, static = null)
    }

    /** The registers the braces of the instruction on [lineText] hold; a `{vA .. vB}` range needs the method's frame to count, -1 without it. */
    private fun passedRegisters(uri: String, line: Int, lineText: String): Int {
        val scanner = InstructionScanner()
        if (!scanner.scan(lineText)) return -1
        if (!scanner.isRange) return scanner.registerCount
        if (scanner.registerCount < 2) return scanner.registerCount
        val method = index.findFileByUri(uri)?.methodAt(line) ?: return -1
        val frame = RegisterFrame.of(method, index.getLines(uri) ?: return -1) ?: return -1
        val first = frame.absolute(scanner.isParameter(0), scanner.registerNumber(0))
        val last = frame.absolute(scanner.isParameter(1), scanner.registerNumber(1))
        return if (last < first) -1 else last - first + 1
    }

    /** The method's labels, for a `:` after a jump, in a `.catch` or in a switch payload. */
    private fun completeLabels(uri: String, position: Position, word: String): CompletionList {
        val method = index.findFileByUri(uri)?.methodAt(position.line) ?: return CompletionList(false, emptyList())
        val typed = word.substring(1)
        val replaceRange = Range(Position(position.line, position.character - word.length), position)
        val items = method.labels.keys.sorted().filter { it.startsWith(typed, ignoreCase = true) }.mapIndexed { i, name ->
            CompletionItem(":$name").apply {
                kind = CompletionItemKind.Reference
                textEdit = Either.forLeft(TextEdit(replaceRange, ":$name"))
                sortText = "%03d".format(i)
            }
        }
        return CompletionList(false, items)
    }

    /**
     * Class names matching what was typed, best first: with an `L` the typed text is a prefix of
     * the full name; in a directive it may also be a prefix of the simple name or appear in the
     * path. Shorter names rank higher, then alphabetical, so the answer is the same on every
     * keystroke. The list is scanned once, keeping the best fifty; [kind] is checked only for a
     * name that would make the fifty, so the cost stays with the scan.
     */
    private fun completeClassNames(partial: String, position: Position, kind: ClassKind): CompletionList {
        val hasLPrefix = partial.startsWith("L")
        val replaceRange = Range(Position(position.line, position.character - partial.length), position)
        val best = ArrayList<Pair<Int, String>>(MAX_CLASS_ITEMS + 1)   // (rank, class name), kept sorted
        val order = compareBy<Pair<Int, String>>({ it.first }, { it.second.length }, { it.second })
        val throwables = HashMap<String, Boolean>()
        var matches = 0
        for (className in index.getClassNames()) {
            val rank = when {
                hasLPrefix -> if (className.startsWith(partial, ignoreCase = true)) 0 else continue
                Types.simpleName(className).startsWith(partial, ignoreCase = true) -> 0
                className.contains(partial, ignoreCase = true) -> 1
                else -> continue
            }
            matches++
            val candidate = rank to className
            if (best.size == MAX_CLASS_ITEMS && order.compare(candidate, best[best.size - 1]) >= 0) continue
            if (!accepts(kind, className, throwables)) continue
            val at = best.binarySearch(candidate, order).let { if (it < 0) -it - 1 else it }
            best.add(at, candidate)
            if (best.size > MAX_CLASS_ITEMS) best.removeAt(best.size - 1)
        }
        val items = best.mapIndexed { i, (_, className) ->
            val simpleName = Types.simpleName(className)
            val readablePath = className.removePrefix("L").removeSuffix(";")
            CompletionItem(readablePath).apply {
                this.kind = CompletionItemKind.Class
                detail = simpleName
                textEdit = Either.forLeft(TextEdit(replaceRange, className))
                filterText = "$readablePath $simpleName"
                sortText = "%03d".format(i)
            }
        }
        return CompletionList(matches > MAX_CLASS_ITEMS, items)
    }

    private fun accepts(kind: ClassKind, className: String, throwables: HashMap<String, Boolean>): Boolean {
        if (kind == ClassKind.ANY) return true
        val modifiers = index.findClass(className)?.classDefinition?.modifiers ?: return false
        return when (kind) {
            ClassKind.CLASS -> "interface" !in modifiers
            ClassKind.INTERFACE -> "interface" in modifiers
            ClassKind.CONCRETE -> "interface" !in modifiers && "abstract" !in modifiers
            ClassKind.THROWABLE -> isThrowable(className, throwables)
            ClassKind.ANY -> true
        }
    }

    /** The class extends, through the workspace, a class named `*Throwable`, `*Exception` or `*Error`. */
    private fun isThrowable(className: String, known: HashMap<String, Boolean>): Boolean {
        known[className]?.let { return it }
        var current: String? = className
        val chain = ArrayList<String>()
        var result = false
        while (current != null && current !in chain) {
            known[current]?.let { result = it; break }
            if (current.endsWith("Throwable;") || current.endsWith("Exception;") || current.endsWith("Error;")) { result = true; break }
            chain.add(current)
            current = index.findClass(current)?.classDefinition?.superClass
        }
        for (name in chain) known[name] = result
        return result
    }

    /** Opcodes matching the first word of a line, every one on an empty line; right after a call, the `move-result` its return type needs comes first. */
    private fun completeOpcodes(uri: String, position: Position, partial: String): CompletionList {
        val replaceRange = Range(Position(position.line, position.character - partial.length), position)
        val expected = moveResultAfterCall(uri, position.line)
        val names = DalvikOpcodeDatabase.allInstructionNames().filter { it.startsWith(partial, ignoreCase = true) }
        val ordered = if (expected != null && expected in names) listOf(expected) + names.filter { it != expected } else names
        val items = ordered.mapIndexedNotNull { i, opcodeName ->
            val info = DalvikOpcodeDatabase.lookup(opcodeName) ?: return@mapIndexedNotNull null
            CompletionItem(opcodeName).apply {
                kind = CompletionItemKind.Keyword
                detail = "0x${info.opcode} ${info.formatId}"
                documentation = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, info.toCompletionDoc()))
                filterText = opcodeName
                if (snippetSupport) {
                    insertTextFormat = InsertTextFormat.Snippet
                    textEdit = Either.forLeft(TextEdit(replaceRange, snippet(opcodeName)))
                } else textEdit = Either.forLeft(TextEdit(replaceRange, opcodeName))
                if (opcodeName == expected) preselect = true
                sortText = "%03d".format(i)
            }
        }
        return CompletionList(false, items)
    }

    /** The opcode with its operand shape as snippet tab stops, `const/4` as `const/4 \${1:v0}, \${2:0x0}`. */
    private fun snippet(opcode: String): String {
        val member = "\${2:Lpkg/Class;->method()V}"
        return when (InstructionShapes[opcode]) {
            null, OperandShape.NONE -> opcode
            OperandShape.R -> "$opcode \${1:v0}"
            OperandShape.RR -> "$opcode \${1:v0}, \${2:v1}"
            OperandShape.RRR -> "$opcode \${1:v0}, \${2:v1}, \${3:v2}"
            OperandShape.RR_LITERAL -> "$opcode \${1:v0}, \${2:v1}, \${3:0x0}"
            OperandShape.RR_REGISTER_OR_LITERAL -> "$opcode \${1:v0}, \${2:v1}, \${3:v2}"
            OperandShape.R_LITERAL -> "$opcode \${1:v0}, \${2:0x0}"
            OperandShape.R_STRING -> "$opcode \${1:v0}, \"\${2:text}\""
            OperandShape.R_FIELD -> "$opcode \${1:v0}, \${2:Lpkg/Class;->field:I}"
            OperandShape.RR_FIELD -> "$opcode \${1:v0}, \${2:p0}, \${3:Lpkg/Class;->field:I}"
            OperandShape.LABEL -> "$opcode :\${1:label}"
            OperandShape.R_LABEL -> "$opcode \${1:v0}, :\${2:label}"
            OperandShape.RR_LABEL -> "$opcode \${1:v0}, \${2:v1}, :\${3:label}"
            OperandShape.LIST_METHOD -> "$opcode {\${1:p0}}, $member"
            OperandShape.RANGE_METHOD -> "$opcode {\${1:v0} .. \${2:v1}}, \${3:Lpkg/Class;->method()V}"
            OperandShape.LIST_METHOD_PROTO -> "$opcode {\${1:p0}}, $member, \${3:()V}"
            OperandShape.RANGE_METHOD_PROTO -> "$opcode {\${1:v0} .. \${2:v1}}, \${3:Lpkg/Class;->method()V}, \${4:()V}"
            OperandShape.LIST_CALL_SITE -> "$opcode {\${1:v0}}, \${2:call_site_0}"
            OperandShape.RANGE_CALL_SITE -> "$opcode {\${1:v0} .. \${2:v1}}, \${3:call_site_0}"
            OperandShape.LIST_TYPE -> "$opcode {\${1:v0}}, \${2:[I}"
            OperandShape.RANGE_TYPE -> "$opcode {\${1:v0} .. \${2:v1}}, \${3:[I}"
            OperandShape.R_REFERENCE_TYPE, OperandShape.R_REFERENCE_OR_ARRAY_TYPE -> "$opcode \${1:v0}, \${2:Lpkg/Class;}"
            OperandShape.RR_NON_VOID_TYPE -> "$opcode \${1:v0}, \${2:v1}, \${3:[I}"
            OperandShape.R_METHOD_HANDLE -> "$opcode \${1:v0}, \${2:invoke-static@Lpkg/Class;->method()V}"
            OperandShape.R_PROTO -> "$opcode \${1:v0}, \${2:(I)V}"
        }
    }

    /** `move-result`, `-wide` or `-object` when the previous instruction is a call that returns something, or a `filled-new-array`. */
    private fun moveResultAfterCall(uri: String, line: Int): String? {
        var previous = line - 1
        while (previous >= 0) {
            val text = index.getLineContent(uri, previous)?.trim() ?: return null
            if (text.isEmpty() || text.startsWith("#") || text.startsWith(".line")) { previous--; continue }
            return when {
                text.startsWith("filled-new-array") -> "move-result-object"
                text.startsWith("invoke-") -> when (text.substringAfterLast(')', "").firstOrNull()) {
                    null, 'V' -> null
                    'J', 'D' -> "move-result-wide"
                    'L', '[' -> "move-result-object"
                    else -> "move-result"
                }
                else -> null
            }
        }
        return null
    }

    /** The `L...` being typed, without its `;` yet, including the `L`; null when the caret is not in one. */
    private fun partialClassName(text: String): String? {
        val lastL = text.lastIndexOf('L')
        if (lastL < 0) return null
        val afterL = text.substring(lastL + 1)
        if (afterL.contains(';')) return null
        if (afterL.isEmpty() || afterL.all { it.isLetterOrDigit() || it == '/' || it == '$' || it == '_' }) return text.substring(lastL)
        return null
    }
}
