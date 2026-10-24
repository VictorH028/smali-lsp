package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensDelta
import org.eclipse.lsp4j.SemanticTokensEdit
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.jsonrpc.messages.Either
import xyz.surendrajat.smalilsp.core.FieldAccessInstruction
import xyz.surendrajat.smalilsp.core.InstructionScanner
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.core.Literals
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.core.Span
import xyz.surendrajat.smalilsp.core.TypeInstruction
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.util.Types
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Semantic tokens: what each token of a file is. Class, member, type and label tokens come from
 * the AST's spans; directives, modifiers, opcodes, registers, literals, strings and comments
 * from a scan of the lines. An editor without a smali grammar colours from these alone; one
 * with a grammar gets the precise classes on top. Computed from the open file's stored AST,
 * never a re-parse; the last full result per file is kept so an edit answers with a delta.
 */
class SemanticTokensProvider(private val index: WorkspaceIndex) {

    private val previous = ConcurrentHashMap<String, Remembered>()
    private val resultIds = AtomicLong()

    /** The last full result for an open file and the text it was computed from, compared by identity: an unchanged buffer needs no recount. */
    private class Remembered(val resultId: String, val data: IntArray, val text: String?)

    fun full(uri: String): SemanticTokens? {
        val data = encode(uri) ?: return null
        return SemanticTokens(remember(uri, data), data.toList())
    }

    private fun current(uri: String): IntArray? {
        val last = previous[uri]
        val text = index.getDocumentContent(uri)
        if (last != null && text != null && last.text === text) return last.data
        return encode(uri)
    }

    /** One edit covering what changed between the client's result and now, or the full tokens when its result is unknown. */
    fun delta(uri: String, previousResultId: String): Either<SemanticTokens, SemanticTokensDelta>? {
        val data = current(uri) ?: return null
        val last = previous[uri]
        if (last == null || last.resultId != previousResultId) return Either.forLeft(SemanticTokens(remember(uri, data), data.toList()))
        val old = last.data
        var prefix = 0
        while (prefix < old.size && prefix < data.size && old[prefix] == data[prefix]) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < data.size - prefix && old[old.size - 1 - suffix] == data[data.size - 1 - suffix]) suffix++
        prefix -= prefix % 5; suffix -= suffix % 5   // edits are whole tokens
        val edit = SemanticTokensEdit(prefix, old.size - prefix - suffix, data.copyOfRange(prefix, data.size - suffix).toList())
        return Either.forRight(SemanticTokensDelta(if (edit.deleteCount == 0 && edit.data.isEmpty()) emptyList() else listOf(edit), remember(uri, data)))
    }

    /** Only the lines asked for are scanned and only the methods that reach them walked: what an editor asks for first on a huge file. */
    fun range(uri: String, range: Range): SemanticTokens? {
        val tokens = collect(uri, range.start.line, range.end.line) ?: return null
        return SemanticTokens(encode(tokens).toList())
    }

    /** The file is closed: nothing to diff against any more. */
    fun forget(uri: String) { previous.remove(uri) }

    private fun remember(uri: String, data: IntArray): String {
        val id = resultIds.incrementAndGet().toString()
        if (data.size <= MAX_REMEMBERED) previous[uri] = Remembered(id, data, index.getDocumentContent(uri)) else previous.remove(uri)
        return id
    }

    private fun encode(uri: String): IntArray? = collect(uri)?.let { encode(it) }

    /** Every token of the file (or of lines [from]..[to]) in position order, AST tokens winning over line-scan tokens where they overlap. */
    internal fun collect(uri: String, from: Int = 0, to: Int = Int.MAX_VALUE): List<Token>? {
        val file = index.findFileByUri(uri) ?: return null
        val lines = index.getLines(uri) ?: return null
        val out = ArrayList<Token>(if (to == Int.MAX_VALUE) lines.size * 4 else (to - from + 1) * 4)
        astTokens(file, from, to, out)
        lineTokens(lines, from, to, out)
        if (to != Int.MAX_VALUE) out.removeIf { it.line < from || it.line > to }
        out.sortWith(compareBy({ it.line }, { it.col }, { it.priority }))
        val merged = ArrayList<Token>(out.size)
        var lastLine = -1; var lastEnd = 0
        for (t in out) {
            if (t.length <= 0) continue
            if (t.line == lastLine && t.col < lastEnd) continue
            merged.add(t); lastLine = t.line; lastEnd = t.col + t.length
        }
        return merged
    }

    private fun astTokens(file: SmaliFile, from: Int, to: Int, out: MutableList<Token>) {
        val c = file.classDefinition
        out.add(Token(c.nameSpan, CLASS, DECLARATION))
        c.superClassRange?.let { if (it.start.line == it.end.line) out.add(Token(it.start.line, it.start.character, it.end.character - it.start.character, TYPE, sdk(c.superClass))) }
        for ((iface, r) in c.interfaceRanges) if (r.start.line == r.end.line) out.add(Token(r.start.line, r.start.character, r.end.character - r.start.character, TYPE, sdk(iface)))
        for (field in file.fields) {
            out.add(Token(field.nameSpan, PROPERTY, DECLARATION or staticOf(field.modifiers)))
            out.add(Token(field.typeSpan, TYPE, sdk(field.type)))
        }
        for (method in file.methods) {
            if (method.endLine < from || method.startLine > to) continue
            out.add(Token(method.nameSpan, METHOD, DECLARATION or staticOf(method.modifiers)))
            val d = method.descriptorSpan
            if (d.end - d.start == method.descriptor.length) for (type in Types.parseTypeSequence(method.descriptor)) out.add(Token(d.line, d.start + type.start, type.end - type.start, TYPE, sdk(type.type)))
            for (label in method.labels.values) out.add(Token(label.line, label.start, label.end - label.start, LABEL, DECLARATION))
            for (ref in method.labelRefs) out.add(Token(ref.span, LABEL, 0))
            for (instruction in method.instructions) when (instruction) {
                is InvokeInstruction -> {
                    val sdk = sdk(instruction.className)
                    instruction.ownerSpan?.let { out.add(Token(it, TYPE, sdk)) }
                    instruction.nameSpan?.let { out.add(Token(it, METHOD, sdk or (if (instruction.opcode.startsWith("invoke-static")) STATIC else 0))) }
                    instruction.descriptorSpan?.let { d2 -> for (type in Types.parseTypeSequence(instruction.descriptor)) out.add(Token(d2.line, d2.start + type.start, type.end - type.start, TYPE, sdk(type.type))) }
                }
                is FieldAccessInstruction -> {
                    val sdk = sdk(instruction.className)
                    instruction.ownerSpan?.let { out.add(Token(it, TYPE, sdk)) }
                    instruction.nameSpan?.let { out.add(Token(it, PROPERTY, sdk or (if (instruction.opcode.startsWith("sget") || instruction.opcode.startsWith("sput")) STATIC else 0))) }
                    instruction.typeSpan?.let { out.add(Token(it, TYPE, sdk(instruction.fieldType))) }
                }
                is TypeInstruction -> instruction.typeSpan?.let { out.add(Token(it, TYPE, sdk(instruction.className))) }
                else -> {}
            }
        }
        for (ref in file.typeRefs) out.add(Token(ref.span, TYPE, sdk(ref.className)))
    }

    /** Directives, modifiers, opcodes, registers, literals, strings and comments, read from the text; lower priority than the AST's tokens. */
    private fun lineTokens(lines: List<String>, from: Int, to: Int, out: MutableList<Token>) {
        val scanner = InstructionScanner()
        for (lineNumber in maxOf(0, from)..minOf(to, lines.size - 1)) {
            val line = lines[lineNumber]
            var i = 0
            while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
            if (i >= line.length) continue
            // strings and the comment: everything else is scanned between them
            val codeEnd = stringsAndComment(line, lineNumber, i, out)
            val c = line[i]
            when {
                c == '#' -> {}
                c == '.' -> directive(line, lineNumber, i, codeEnd, out)
                c == ':' -> words(line, lineNumber, i, codeEnd, out)
                c == '-' || c in '0'..'9' -> words(line, lineNumber, i, codeEnd, out)
                else -> {
                    val eq = assignment(line, i)
                    if (eq >= 0) { out.add(Token(lineNumber, i, eq - i, PROPERTY, 0, 1)); words(line, lineNumber, eq + 1, codeEnd, out) }
                    else if (scanner.scan(line)) {
                        out.add(Token(lineNumber, scanner.opcodeStart, scanner.opcodeEnd - scanner.opcodeStart, KEYWORD, 0, 1))
                        for (r in 0 until scanner.registerCount) out.add(Token(lineNumber, scanner.registerStart(r), scanner.registerEnd(r) - scanner.registerStart(r), if (scanner.isParameter(r)) PARAMETER else VARIABLE, 0, 1))
                        words(line, lineNumber, scanner.opcodeEnd, codeEnd, out)
                    }
                }
            }
        }
    }

    /** Emits string and comment tokens; returns where the code part of the line ends (the comment's start or the line's end). */
    private fun stringsAndComment(line: String, lineNumber: Int, from: Int, out: MutableList<Token>): Int {
        var i = from
        while (i < line.length) {
            when (line[i]) {
                '"' -> {
                    val start = i; i++
                    while (i < line.length && line[i] != '"') { if (line[i] == '\\') i++; i++ }
                    i = minOf(i + 1, line.length)
                    out.add(Token(lineNumber, start, i - start, STRING, 0, 1))
                }
                '\'' -> {
                    val start = i; i++
                    while (i < line.length && line[i] != '\'') { if (line[i] == '\\') i++; i++ }
                    i = minOf(i + 1, line.length)
                    out.add(Token(lineNumber, start, i - start, STRING, 0, 1))
                }
                '#' -> { out.add(Token(lineNumber, i, line.length - i, COMMENT, 0, 1)); return i }
                else -> i++
            }
        }
        return line.length
    }

    private fun directive(line: String, lineNumber: Int, start: Int, codeEnd: Int, out: MutableList<Token>) {
        var end = start + 1
        while (end < codeEnd && isWordChar(line[end])) end++
        val word = line.substring(start, end)
        if (word == ".end" || word == ".restart") {   // `.end method`, `.restart local`: one keyword
            var ws = end
            while (ws < codeEnd && (line[ws] == ' ' || line[ws] == '\t')) ws++
            var wend = ws
            while (wend < codeEnd && isWordChar(line[wend])) wend++
            if (wend > ws) end = wend
        }
        out.add(Token(lineNumber, start, end - start, KEYWORD, 0, 1))
        words(line, lineNumber, end, codeEnd, out)
    }

    /** `name = …` of an annotation element: the index of `=`, or -1. */
    private fun assignment(line: String, start: Int): Int {
        var i = start
        while (i < line.length && isWordChar(line[i])) i++
        var j = i
        while (j < line.length && (line[j] == ' ' || line[j] == '\t')) j++
        return if (j > i && j < line.length && line[j] == '=' && (j + 1 >= line.length || line[j + 1] != '=')) i else -1
    }

    /** The words of `[from, to)` outside strings: modifiers, registers, numbers, types, labels and inline directives. */
    private fun words(line: String, lineNumber: Int, from: Int, to: Int, out: MutableList<Token>) {
        var i = from
        while (i < to) {
            val c = line[i]
            if (c == '"' || c == '\'') { i = skipQuoted(line, i, to); continue }
            if (c == ' ' || c == '\t' || c == ',' || c == '{' || c == '}' || c == '(' || c == ')' || c == '=') { i++; continue }
            if (c == '-' && i + 1 < to && line[i + 1] == '>') { i += 2; continue }
            if (c == '.' && i + 1 < to && line[i + 1] == '.') { i += 2; continue }
            val start = i
            if (c == '[' ) { while (i < to && line[i] == '[') i++; if (i < to && line[i] == 'L') { while (i < to && line[i] != ';') i++; i = minOf(i + 1, to) } else if (i < to) i++
                out.add(Token(lineNumber, start, i - start, TYPE, 0, 1)); continue }
            if (c == 'L' && i + 1 < to && line[i + 1] != '-' && line.indexOf(';', i) in (i + 1)..<to && !line.substring(i, line.indexOf(';', i)).contains(' ')) {
                val semi = line.indexOf(';', i); i = semi + 1
                out.add(Token(lineNumber, start, i - start, TYPE, sdk(line.substring(start, i)), 1)); continue
            }
            while (i < to && !isSeparator(line[i])) i++
            val word = line.substring(start, i)
            val type = when {
                word.startsWith(":") -> LABEL
                word.startsWith(".") -> KEYWORD
                word in MODIFIERS -> MODIFIER
                isRegister(word) -> if (word[0] == 'p') PARAMETER else VARIABLE
                isNumber(word) -> NUMBER
                word == "true" || word == "false" || word == "null" -> KEYWORD
                start >= 2 && line.startsWith("->", start - 2) && line.contains(".enum") -> ENUM_MEMBER
                else -> -1
            }
            if (type >= 0) out.add(Token(lineNumber, start, i - start, type, 0, 1))
        }
    }

    private fun skipQuoted(line: String, from: Int, to: Int): Int {
        val quote = line[from]; var i = from + 1
        while (i < to && line[i] != quote) { if (line[i] == '\\') i++; i++ }
        return minOf(i + 1, to)
    }

    private fun isSeparator(c: Char) = c == ' ' || c == '\t' || c == ',' || c == '{' || c == '}' || c == '(' || c == ')' || c == '='
    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-' || c == '$' || c == '/' || c == ';' || c == '<' || c == '>' || c.code > 0x7F
    private fun isRegister(word: String) = word.length in 2..8 && (word[0] == 'v' || word[0] == 'p') && word.substring(1).all { it in '0'..'9' }
    private fun isNumber(word: String): Boolean {
        val body = word.removePrefix("-")
        if (body.isEmpty() || body[0] !in '0'..'9') return false
        return Literals.find("    const v0, $word") != null || body.all { it in '0'..'9' || it == '.' || it in "xXabcdefABCDEFfFdDlLsStTeE+-_" }
    }
    private fun sdk(type: String?): Int = if (type != null && Types.className(type)?.let { Types.isSdkClass(it) } == true) DEFAULT_LIBRARY else 0
    private fun staticOf(modifiers: Set<String>): Int = if ("static" in modifiers) STATIC else 0

    /** A token: absolute line and column, length in UTF-16 units, legend indices; AST tokens have priority 0, line tokens 1. */
    internal class Token(val line: Int, val col: Int, val length: Int, val type: Int, val modifiers: Int, val priority: Int = 0) {
        constructor(span: Span, type: Int, modifiers: Int) : this(span.line, span.start, span.end - span.start, type, modifiers)
    }

    companion object {
        val TOKEN_TYPES = listOf("keyword", "modifier", "type", "class", "method", "property", "enumMember", "variable", "parameter", "number", "string", "comment", "label")
        val TOKEN_MODIFIERS = listOf("declaration", "static", "defaultLibrary")
        val legend = SemanticTokensLegend(TOKEN_TYPES, TOKEN_MODIFIERS)
        const val KEYWORD = 0; const val MODIFIER = 1; const val TYPE = 2; const val CLASS = 3; const val METHOD = 4; const val PROPERTY = 5
        const val ENUM_MEMBER = 6; const val VARIABLE = 7; const val PARAMETER = 8; const val NUMBER = 9; const val STRING = 10; const val COMMENT = 11; const val LABEL = 12
        const val DECLARATION = 1; const val STATIC = 2; const val DEFAULT_LIBRARY = 4
        /** Results above this many ints (a 180 K-line file is about 1.6 M) are not kept for deltas. */
        private const val MAX_REMEMBERED = 2_000_000
        private val MODIFIERS = setOf("public", "private", "protected", "final", "annotation", "synthetic", "static", "abstract", "enum", "interface", "constructor", "bridge",
            "declared-synchronized", "synchronized", "strictfp", "varargs", "native", "transient", "volatile", "runtime", "system", "build")

        /** The LSP encoding: five ints per token, line and start relative to the previous token. */
        internal fun encode(tokens: List<Token>): IntArray {
            val data = IntArray(tokens.size * 5)
            var line = 0; var col = 0; var i = 0
            for (t in tokens) {
                val deltaLine = t.line - line
                data[i] = deltaLine; data[i + 1] = if (deltaLine == 0) t.col - col else t.col; data[i + 2] = t.length; data[i + 3] = t.type; data[i + 4] = t.modifiers
                line = t.line; col = t.col; i += 5
            }
            return data
        }
    }
}
