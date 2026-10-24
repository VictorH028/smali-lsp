package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

/**
 * baksmali's layout, applied to any smali text: four spaces per block (`.method`, `.annotation`,
 * `.subannotation`, a switch or array payload, an annotation value in braces, a `.field` or
 * `.param` that carries annotations), one space between tokens, `, ` between operands, ` .. ` in
 * a range, ` = ` in an element, ` -> ` in a switch entry, four spaces before a trailing comment,
 * no trailing blanks. Strings and comments keep their text, blank lines stay where they are and
 * no line is joined or split, so baksmali output formats to itself and a formatted file parses
 * to the same model. Blank lines are the author's: baksmali's own vary with the section.
 */
object SmaliFormatter {
    private const val INDENT = "    "
    private const val COMMENT_GAP = "    "

    private enum class Frame { METHOD, ANNOTATION, SUBANNOTATION, PAYLOAD, BRACE, FIELD, PARAM }

    private val payloads = setOf(".packed-switch", ".sparse-switch", ".array-data")
    private val closers = mapOf(
        ".end method" to Frame.METHOD, ".end annotation" to Frame.ANNOTATION, ".end subannotation" to Frame.SUBANNOTATION,
        ".end packed-switch" to Frame.PAYLOAD, ".end sparse-switch" to Frame.PAYLOAD, ".end array-data" to Frame.PAYLOAD,
        ".end field" to Frame.FIELD, ".end param" to Frame.PARAM, "}" to Frame.BRACE
    )

    /** The whole text formatted, with its own line ending. */
    fun format(text: String): String {
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        return formatLines(split(text)).joinToString(newline)
    }

    /** One edit per line that changes, for the lines [from]..[to]; the line count never changes. */
    fun edits(text: String, from: Int = 0, to: Int = Int.MAX_VALUE): List<TextEdit> {
        val lines = split(text)
        val formatted = formatLines(lines)
        val out = ArrayList<TextEdit>()
        for (i in lines.indices) {
            if (i < from || i > to || lines[i] == formatted[i]) continue
            out.add(TextEdit(Range(Position(i, 0), Position(i, lines[i].length)), formatted[i]))
        }
        return out
    }

    private fun split(text: String): List<String> = text.split("\n").map { it.removeSuffix("\r") }

    fun formatLines(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        val stack = ArrayList<Frame>()
        var tentative = false   // the top frame is a .field or .param that stays only if an annotation follows
        for (raw in lines) {
            val commentAt = commentStart(raw)
            val code = if (commentAt < 0) raw else raw.substring(0, commentAt)
            val comment = if (commentAt < 0) null else raw.substring(commentAt).trimEnd()
            val tokens = tokenize(code)
            if (tokens.isEmpty() && comment == null) { out.add(""); continue }
            val first = tokens.firstOrNull()?.removeSuffix(",") ?: ""
            val head = if (first == ".end" && tokens.size > 1) ".end ${tokens[1].removeSuffix(",")}" else first
            if (tentative && head != ".annotation") { stack.removeAt(stack.size - 1); tentative = false }
            closers[head]?.let { frame ->
                val at = stack.lastIndexOf(frame)
                if (at >= 0) while (stack.size > at) stack.removeAt(stack.size - 1)
            }
            out.add(buildString {
                repeat(stack.size) { append(INDENT) }
                append(join(tokens))
                if (comment != null) { if (tokens.isNotEmpty()) append(COMMENT_GAP); append(comment) }
            })
            when (head) {
                ".method" -> stack.add(Frame.METHOD)
                ".annotation" -> { stack.add(Frame.ANNOTATION); tentative = false }
                ".field" -> { stack.add(Frame.FIELD); tentative = true }
                ".param" -> { stack.add(Frame.PARAM); tentative = true }
                in payloads -> stack.add(Frame.PAYLOAD)
            }
            if (".subannotation" in tokens) stack.add(Frame.SUBANNOTATION)
            if (tokens.lastOrNull() == "{") stack.add(Frame.BRACE)
        }
        return out
    }

    /** The index of the `#` that starts a comment, outside any string or character literal; -1 when there is none. */
    private fun commentStart(line: String): Int {
        var i = 0
        while (i < line.length) {
            when (val c = line[i]) {
                '"', '\'' -> i = closingQuote(line, i)
                '#' -> return i
                else -> {}
            }
            i++
        }
        return -1
    }

    /** The index of the quote that closes the literal opened at [open], or the last index when it never closes. */
    private fun closingQuote(line: String, open: Int): Int {
        var i = open + 1
        while (i < line.length) {
            when (line[i]) {
                '\\' -> i++
                line[open] -> return i
            }
            i++
        }
        return line.length - 1
    }

    /**
     * Words, with `,`, `{`, `}`, `..`, `=` and a free-standing `->` as tokens of their own. A string
     * or character literal is part of its word, including whatever is attached after the closing
     * quote (`"name":Ltype;`).
     */
    private fun tokenize(code: String): List<String> {
        val tokens = ArrayList<String>()
        var start = -1
        var i = 0
        fun flush(end: Int) { if (start >= 0) { tokens.add(code.substring(start, end)); start = -1 } }
        while (i < code.length) {
            val c = code[i]
            when {
                c == ' ' || c == '\t' || c == '\r' -> flush(i)
                c == ',' || c == '{' || c == '}' || c == '=' -> { flush(i); tokens.add(c.toString()) }
                c == '.' && i + 1 < code.length && code[i + 1] == '.' && (start < 0 || code[start] in "vp") -> { flush(i); tokens.add(".."); i++ }
                c == '-' && start < 0 && i + 1 < code.length && code[i + 1] == '>' -> { tokens.add("->"); i++ }
                c == '"' || c == '\'' -> { if (start < 0) start = i; i = closingQuote(code, i) }
                else -> if (start < 0) start = i
            }
            i++
        }
        flush(code.length)
        return tokens
    }

    private fun join(tokens: List<String>): String {
        val sb = StringBuilder()
        for ((i, token) in tokens.withIndex()) {
            if (i > 0) {
                val previous = tokens[i - 1]
                val space = when {
                    token == "," -> false
                    previous == "{" || token == "}" -> false
                    else -> true
                }
                if (space) sb.append(' ')
            }
            sb.append(token)
        }
        return sb.toString()
    }
}
