package xyz.surendrajat.smalilsp.parser

import org.eclipse.lsp4j.Range
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.util.StringPool
import xyz.surendrajat.smalilsp.util.Types

/**
 * The smali front end: a hand-written lexer and statement parser that build the [SmaliFile]
 * model straight from the text.
 *
 * Smali is a list of statements, one per line in everything baksmali writes, so the parser
 * reads a statement at a time: a directive, a `:label`, or an instruction whose operands are
 * fixed by its opcode ([InstructionShapes], derived from the grammar). Whitespace, including
 * newlines, separates tokens as the assembler's lexer treats it, so a hand-written statement
 * may still wrap. A syntax error is reported on the offending token and parsing resumes at the
 * next line, so one bad line costs one diagnostic, not the rest of the file.
 *
 * Tokens are spans into the source, never objects: the lexer advances a cursor and leaves the
 * current token's kind and bounds in fields. Positions are UTF-16 columns, as LSP wants them.
 * Names, descriptors and modifiers are interned through [strings].
 */
class SmaliTextParser(private val uri: String, private val source: String, private val strings: StringPool) {

    // ======================================================================
    // Tokens
    // ======================================================================

    enum class T {
        EOF, IDENT, TYPE, PRIM, VOID, PRIMSEQ, COMPOUND, STRING, CHAR, INT, FLOAT, FLOAT_SPECIAL, BOOL, NULL,
        DIRECTIVE, LBRACK, RBRACK, LPAREN, RPAREN, LBRACE, RBRACE, COLON, ASSIGN, DOT, SUB, COMMA, SLASH, LT, GT, ARROW, SEMI, AT, BAD
    }

    private var pos = 0
    private var line = 0
    private var lineStart = 0

    /** The current token. */
    private var kind = T.EOF
    private var tokStart = 0
    private var tokEnd = 0
    private var tokLine = 0
    private var tokCol = 0

    /** The last consumed token's end, for the ranges of the statements that just finished. */
    private var lastLine = 0
    private var lastEnd = 0
    private var lastIndex = 0

    private val tokText: String get() = source.substring(tokStart, tokEnd)
    private fun tokIs(text: String): Boolean = tokEnd - tokStart == text.length && source.startsWith(text, tokStart)

    private fun advance() {
        lastLine = tokLine
        lastEnd = tokCol + (tokEnd - tokStart)
        lastIndex = tokEnd
        lex()
    }

    private fun lex() {
        skipBlank()
        tokStart = pos
        tokLine = line
        tokCol = pos - lineStart
        if (pos >= source.length) { kind = T.EOF; tokEnd = pos; return }
        val c = source[pos]
        when {
            c == '"' -> lexString()
            c == '\'' -> lexChar()
            c == '.' -> lexDot()
            c == '-' -> { if (pos + 1 < source.length && source[pos + 1] == '>') { pos += 2; kind = T.ARROW } else { pos++; kind = T.SUB } }
            c == '[' -> { pos++; kind = T.LBRACK }
            c == ']' -> { pos++; kind = T.RBRACK }
            c == '(' -> { pos++; kind = T.LPAREN }
            c == ')' -> { pos++; kind = T.RPAREN }
            c == '{' -> { pos++; kind = T.LBRACE }
            c == '}' -> { pos++; kind = T.RBRACE }
            c == ':' -> { pos++; kind = T.COLON }
            c == '=' -> { pos++; kind = T.ASSIGN }
            c == ',' -> { pos++; kind = T.COMMA }
            c == '/' -> { pos++; kind = T.SLASH }
            c == '<' -> { pos++; kind = T.LT }
            c == '>' -> { pos++; kind = T.GT }
            c == ';' -> { pos++; kind = T.SEMI }
            c == '@' -> { pos++; kind = T.AT }
            c == 'L' && lexQualifiedType(pos) -> kind = T.TYPE
            isWordChar(c) -> lexWord()
            else -> { pos++; kind = T.BAD }
        }
        tokEnd = pos
    }

    private fun skipBlank() {
        while (pos < source.length) {
            val c = source[pos]
            when {
                c == '\n' -> { pos++; line++; lineStart = pos }
                c == ' ' || c == '\t' || c == '\r' || c == '\u000C' -> pos++
                c == '#' -> { while (pos < source.length && source[pos] != '\n') pos++ }
                else -> return
            }
        }
    }

    /** Skips the rest of the current line: where parsing resumes after a syntax error. */
    private fun skipToNextLine() {
        pos = tokStart
        while (pos < source.length && source[pos] != '\n') pos++
        lex()
    }

    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '$' || c == '_' || c == '-' || c.code > 0x7F

    private fun lexString() {
        pos++
        while (pos < source.length) {
            val c = source[pos]
            if (c == '\\') { pos += 2; continue }
            if (c == '\n' || c == '\r') { kind = T.BAD; return }   // unterminated: the quote and what follows are the error
            pos++
            if (c == '"') { kind = T.STRING; return }
        }
        kind = T.BAD
    }

    /** `'x'`, `'\\''`, `'\\u0041'`: one character or one escape sequence between the quotes. */
    private fun lexChar() {
        val start = pos
        pos++
        if (pos < source.length && source[pos] == '\\') {
            pos++
            when {
                pos < source.length && source[pos] == 'u' -> { while (pos < source.length && source[pos] == 'u') pos++; repeat(4) { if (pos < source.length && source[pos].isHexDigit()) pos++ } }
                pos < source.length && source[pos] in '0'..'7' -> { var n = 0; while (n < 3 && pos < source.length && source[pos] in '0'..'7') { pos++; n++ } }
                pos < source.length && source[pos] != '\n' -> pos++
            }
        } else if (pos < source.length && source[pos] != '\n' && source[pos] != '\'') {
            pos++
        }
        if (pos < source.length && source[pos] == '\'') { pos++; kind = T.CHAR } else { pos = start + 1; kind = T.BAD }
    }

    /** `.5` is a number; `.end method` and `.restart local` are one directive; `.name` is a directive. */
    private fun lexDot() {
        if (pos + 1 < source.length && source[pos + 1].isDigit()) { lexNumber(); return }
        var end = pos + 1
        while (end < source.length && (source[end].isLetterOrDigit() || source[end] == '-' || source[end] == '_')) end++
        if (end == pos + 1) { pos++; kind = T.DOT; return }
        val word = source.substring(pos + 1, end)
        if (word == "end" || word == "restart") {
            var ws = end
            while (ws < source.length && (source[ws] == ' ' || source[ws] == '\t')) ws++
            var wend = ws
            while (wend < source.length && (source[wend].isLetterOrDigit() || source[wend] == '-' || source[wend] == '_')) wend++
            val second = source.substring(ws, wend)
            if (wend > ws && second in END_WORDS && (word == "end" || second == "local")) { pos = wend; kind = T.DIRECTIVE; return }
        }
        pos = end
        kind = T.DIRECTIVE
    }

    /** `Lpkg/Name;` as one token: components of letters, digits and the usual name characters, joined by `/`. */
    private fun lexQualifiedType(from: Int): Boolean {
        var i = from + 1
        var componentStart = i
        while (i < source.length) {
            val c = source[i]
            when {
                c == ';' -> { if (i == componentStart) return false; pos = i + 1; return true }
                c == '/' -> { if (i == componentStart) return false; i++; componentStart = i }
                isWordChar(c) -> i++
                else -> return false
            }
        }
        return false
    }

    /**
     * A run of name characters, classified as the lexer would: a number when the whole run is
     * one (ties go to the number, as in the grammar), else a primitive letter, a primitive
     * sequence, a compound `ILjava/lang/String;` argument literal, a keyword or a name.
     */
    private fun lexWord() {
        val start = pos
        var end = pos
        while (end < source.length && isWordChar(source[end])) end++
        while (end > start + 1 && source[end - 1] == '-') end--   // a name cannot end with a hyphen: `I->` is `I` then `->`
        // an opcode with a slash (`const/4`, `invoke-static/range`) is one word, as the assembler's keywords are
        if (end < source.length && source[end] == '/') {
            var e2 = end + 1
            while (e2 < source.length && isWordChar(source[e2])) e2++
            if (e2 > end + 1 && InstructionShapes[source.substring(start, e2)] != null) { pos = e2; kind = T.IDENT; return }
        }
        if (source[start].isDigit()) {
            val numberEnd = scanNumber(start)
            if (numberEnd >= end) { pos = numberEnd; return }
        }
        // primitives, possibly followed by a qualified type (`ILjava/lang/String;` is one token)
        var p = start
        while (p < end && source[p] in PRIMITIVES) p++
        if (p == end) {
            pos = end
            kind = if (end - start == 1) T.PRIM else T.PRIMSEQ
            return
        }
        if (p > start && source[p] == 'L' && lexQualifiedType(p)) { kind = T.COMPOUND; return }
        pos = end
        kind = when {
            end - start == 1 && source[start] == 'V' -> T.VOID
            matches(start, end, "true") || matches(start, end, "false") -> T.BOOL
            matches(start, end, "null") -> T.NULL
            isFloatSpecial(start, end) -> T.FLOAT_SPECIAL
            else -> T.IDENT
        }
    }

    private fun matches(start: Int, end: Int, word: String) = end - start == word.length && source.startsWith(word, start)

    private fun isFloatSpecial(start: Int, end: Int): Boolean {
        var e = end
        if (e > start && (source[e - 1] == 'f' || source[e - 1] == 'F')) e--
        return matches(start, e, "Infinity") || matches(start, e, "NaN")
    }

    /** The end of the longest integer or float literal starting at [from], setting [kind]; [from] when there is none. */
    private fun scanNumber(from: Int): Int {
        var i = from
        val n = source.length
        fun digits(hex: Boolean): Int {   // Digits: digit ([digit_]* digit)?
            val s = i
            while (i < n && (if (hex) source[i].isHexDigit() else source[i].isDigit())) {
                i++
                while (i < n && source[i] == '_') { if (i + 1 < n && (if (hex) source[i + 1].isHexDigit() else source[i + 1].isDigit())) i++ else break }
            }
            return i - s
        }
        if (source[i] == '0' && i + 1 < n && (source[i + 1] == 'x' || source[i + 1] == 'X')) {
            i += 2
            val digitsStart = i
            digits(true)
            // hex float: 0x1.8p3
            var j = i
            if (j < n && source[j] == '.') { j++; val save = i; i = j; digits(true); j = i; i = save }
            if (j < n && (source[j] == 'p' || source[j] == 'P') && j + 1 < n) {
                var k = j + 1
                if (source[k] == '+' || source[k] == '-') k++
                if (k < n && source[k].isDigit()) { i = k; digits(false); if (i < n && source[i] in "fFdD") i++; kind = T.FLOAT; return i }
            }
            if (i == digitsStart) return from
            if (i < n && source[i] in "lLsStT") i++
            kind = T.INT; return i
        }
        if (source[i] == '0' && i + 1 < n && (source[i + 1] == 'b' || source[i + 1] == 'B') && i + 2 < n && (source[i + 2] == '0' || source[i + 2] == '1')) {
            i += 2
            while (i < n && (source[i] == '0' || source[i] == '1' || (source[i] == '_' && i + 1 < n && (source[i + 1] == '0' || source[i + 1] == '1')))) i++
            if (i < n && source[i] in "lLsStT") i++
            kind = T.INT; return i
        }
        val intStart = i
        digits(false)
        if (i == intStart && !(source[i] == '.' && i + 1 < n && source[i + 1].isDigit())) return from
        // float forms
        var isFloat = false
        if (i < n && source[i] == '.' && (i + 1 >= n || source[i + 1].isDigit() || i > intStart)) {
            if (i + 1 < n && source[i + 1].isDigit()) { i++; digits(false); isFloat = true }
            else if (i > intStart) { i++; isFloat = true }   // `1.`
        }
        if (i < n && (source[i] == 'e' || source[i] == 'E')) {
            var k = i + 1
            if (k < n && (source[k] == '+' || source[k] == '-')) k++
            if (k < n && source[k].isDigit()) { i = k; digits(false); isFloat = true }
        }
        if (i < n && source[i] in "fFdD") { i++; isFloat = true }
        if (isFloat) { kind = T.FLOAT; return i }
        if (i < n && source[i] in "lLsStT") i++
        kind = T.INT
        return i
    }

    private fun lexNumber() {
        val end = scanNumber(pos)
        if (end == pos) { pos++; kind = T.DOT } else pos = end
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    // ======================================================================
    // Errors and recovery
    // ======================================================================

    val syntaxErrors = ArrayList<SmaliParser.SyntaxError>()

    private object Recover : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Where the statement being parsed began; a failure on its first token is the statement's own, later ones may be truncation. */
    private var statementStart = -1

    /** Set when the failed statement was cut short at a line end: parsing resumes at the token that followed it. */
    private var resumeAtToken = false

    /**
     * Records [message] and abandons the statement; the caller calls [recover]. The error sits on
     * the offending token, except when a statement ran out at the end of its line and the
     * offending token is the start of the next one: then it sits where the statement stopped,
     * one character wide, and parsing resumes at that token, which may well be a good statement.
     */
    private fun fail(message: String): Nothing {
        val truncated = kind != T.EOF && tokStart != statementStart && tokLine > lastLine && tokStart == lineStart + firstNonBlank(lineStart)
        if (truncated) {
            syntaxErrors.add(SmaliParser.SyntaxError(lastLine + 1, lastEnd, message, 1))
            resumeAtToken = true
        } else {
            val length = if (kind == T.EOF) 1 else maxOf(1, tokEnd - tokStart)
            syntaxErrors.add(SmaliParser.SyntaxError(tokLine + 1, tokCol, message, length))
            resumeAtToken = false
        }
        throw Recover
    }

    /** Where parsing goes on after a failed statement: the next line, or the token a truncated statement stopped before. */
    private fun recover() {
        if (resumeAtToken && kind != T.EOF) {
            resumeAtToken = false
            return
        }
        skipToNextLine()
    }

    private fun firstNonBlank(from: Int): Int {
        var i = from
        while (i < source.length && (source[i] == ' ' || source[i] == '\t')) i++
        return i - from
    }

    private fun expect(t: T, what: String) {
        if (kind != t) fail("Expected $what")
        advance()
    }

    private fun describe(): String = when (kind) {
        T.EOF -> "end of file"
        else -> "'$tokText'"
    }

    // ======================================================================
    // The model under construction
    // ======================================================================

    private var className: String? = null
    private var classRange: Range? = null
    private var classNameSpan: Span? = null
    private val classModifiers = ArrayList<String>()
    private var superClass: String? = null
    private var superClassRange: Range? = null
    private val interfaces = ArrayList<String>()
    private val interfaceRanges = LinkedHashMap<String, Range>()
    private val methods = ArrayList<MethodDefinition>()
    private val fields = ArrayList<FieldDefinition>()
    private val typeRefs = ArrayList<TypeRef>()
    private val blocks = ArrayList<Block>()

    private var inMethod = false
    private val instructions = ArrayList<Instruction>()
    private val labels = LinkedHashMap<String, LabelDefinition>()
    private val duplicateLabels = ArrayList<LabelDefinition>()
    private val labelRefs = ArrayList<LabelRef>()

    /** > 0 while inside a directive whose class name tokens are recorded as [TypeRef]s. */
    private var typeRefDepth = 0

    // ======================================================================
    // Entry
    // ======================================================================

    /** The model, or null when no `.class` directive parsed; syntax errors are in [syntaxErrors]. */
    fun parse(): SmaliFile? {
        lex()
        while (kind != T.EOF) {
            statementStart = tokStart
            try {
                topLevelStatement()
            } catch (_: Recover) {
                recover()
            }
        }
        val name = className ?: return null
        val range = classRange ?: return null
        return SmaliFile(
            uri = uri,
            classDefinition = ClassDefinition(
                name = name,
                range = range,
                modifiers = strings.internModifiers(classModifiers),
                superClass = superClass,
                interfaces = interfaces.toList(),   // exact-size copies: the accumulators keep growth slack
                superClassRange = superClassRange,
                interfaceRanges = interfaceRanges,
                nameSpan = classNameSpan ?: Span(range.start.line, range.start.character, range.start.character)
            ),
            methods = methods.toList(),
            fields = fields.toList(),
            typeRefs = TypeRefList.of(typeRefs),
            blocks = BlockList.of(blocks)
        )
    }

    private fun topLevelStatement() {
        if (kind != T.DIRECTIVE) fail("Expected a directive, not ${describe()}")
        when {
            tokIs(".class") -> classDirective()
            tokIs(".super") -> superDirective()
            tokIs(".source") -> { advance(); expect(T.STRING, "the source file name") }
            tokIs(".implements") -> implementsDirective()
            tokIs(".field") -> fieldDirective()
            tokIs(".method") -> methodDirective()
            tokIs(".annotation") -> annotationDirective()
            else -> fail("Unknown directive ${describe()} here")
        }
    }

    // ======================================================================
    // Class header
    // ======================================================================

    private fun classDirective() {
        val startLine = tokLine
        val startCol = tokCol
        advance()
        classModifiers.clear()
        while (kind != T.TYPE) {
            if (isWord() && tokText in CLASS_MODIFIERS) { classModifiers.add(strings.intern(tokText)); advance() }
            else fail("Expected a class name, not ${describe()}")
        }
        className = strings.intern(tokText)
        classNameSpan = Span(tokLine, tokCol, tokCol + (tokEnd - tokStart))
        advance()
        classRange = range(startLine, startCol, lastLine, lastEnd)
    }

    private fun superDirective() {
        advance()
        if (kind != T.TYPE) fail("Expected the superclass name, not ${describe()}")
        superClass = strings.intern(tokText)
        superClassRange = range(tokLine, tokCol, tokLine, tokCol + (tokEnd - tokStart))
        advance()
    }

    private fun implementsDirective() {
        advance()
        if (kind != T.TYPE) fail("Expected the interface name, not ${describe()}")
        val name = strings.intern(tokText)
        interfaces.add(name)
        interfaceRanges[name] = range(tokLine, tokCol, tokLine, tokCol + (tokEnd - tokStart))
        advance()
    }

    // ======================================================================
    // Fields
    // ======================================================================

    private fun fieldDirective() {
        val startLine = tokLine
        val startCol = tokCol
        advance()
        val modifiers = ArrayList<String>()
        while (isWord() && tokText in FIELD_MODIFIERS && !nameIsNext()) { modifiers.add(strings.intern(tokText)); advance() }
        // name
        val nameLine = tokLine
        val nameStart = tokCol
        val nameTextStart = tokStart
        if (kind == T.SUB) advance()
        if (!isIdentifier()) fail("Expected the field name, not ${describe()}")
        advance()
        val nameText = source.substring(nameTextStart, tokStart).trimEnd()
        if (kind != T.COLON) fail("Expected ':' and the field type after the field name")
        val colonLine = tokLine
        val colonCol = tokCol
        advance()
        val typeLine = tokLine
        val typeStart = tokCol
        val typeFirstEnd = tokCol + (tokEnd - tokStart)
        val typeText = anyType()
        val typeEnd = if (lastLine == typeLine) lastEnd else typeFirstEnd
        if (kind == T.ASSIGN) { advance(); assignableValue() }
        while (kind == T.DIRECTIVE && tokIs(".annotation")) annotationDirective()
        if (kind == T.DIRECTIVE && tokIs(".end field")) advance()
        val name = strings.intern(nameText)
        fields.add(FieldDefinition(
            name = name,
            type = strings.intern(typeText),
            startLine = startLine, startChar = startCol, endLine = lastLine, endChar = lastEnd,
            modifiers = strings.internModifiers(modifiers),
            nameLine = if (colonLine == nameLine) nameLine else startLine,
            nameStart = if (colonLine == nameLine) nameStart else startCol,
            nameEnd = if (colonLine == nameLine) colonCol else startCol,
            typeLine = typeLine, typeStart = typeStart, typeEnd = typeEnd
        ))
    }

    /** A modifier word that is followed by `:` is the field's name, as in `.field private static:I`. */
    private fun nameIsNext(): Boolean {
        var i = tokEnd
        while (i < source.length && (source[i] == ' ' || source[i] == '\t')) i++
        return i < source.length && source[i] == ':'
    }

    private fun assignableValue() {
        when (kind) {
            T.STRING, T.CHAR, T.NULL, T.BOOL -> advance()
            T.SUB, T.INT, T.FLOAT, T.FLOAT_SPECIAL -> numericLiteral()
            T.TYPE, T.PRIM, T.VOID, T.LBRACK -> anyType()
            else -> fail("Expected a value, not ${describe()}")
        }
    }

    private fun numericLiteral() {
        if (kind == T.SUB) advance()
        if (kind != T.INT && kind != T.FLOAT && kind != T.FLOAT_SPECIAL) fail("Expected a number, not ${describe()}")
        advance()
    }

    // ======================================================================
    // Types
    // ======================================================================

    private fun isWord(): Boolean = kind == T.IDENT || kind == T.PRIM || kind == T.VOID || kind == T.PRIMSEQ || kind == T.BOOL || kind == T.NULL || kind == T.FLOAT_SPECIAL

    /** The grammar's `identifier`: any word, a primitive letter or sequence, a keyword, or an integer literal. */
    private fun isIdentifier(): Boolean = isWord() || kind == T.INT

    /** Consumes a type token, recording it as a reference when inside an annotation, `.local` or prototype. */
    private fun consumeType() {
        if (typeRefDepth > 0) {
            if (kind == T.TYPE) typeRefs.add(TypeRef(strings.intern(tokText), tokLine, tokCol, tokCol + (tokEnd - tokStart)))
            else if (kind == T.COMPOUND) {
                val text = tokText
                for (type in Types.parseTypeSequence(text)) {
                    val cls = type.className ?: continue
                    typeRefs.add(TypeRef(strings.intern(cls), tokLine, tokCol + type.start, tokCol + type.end))
                }
            }
        }
        advance()
    }

    /** `anyType`: a class, a primitive, `V` or an array; returns its text. */
    private fun anyType(): String {
        val start = tokStart
        when (kind) {
            T.TYPE, T.PRIM, T.VOID -> consumeType()
            T.LBRACK -> arrayType()
            else -> fail("Expected a type, not ${describe()}")
        }
        return source.substring(start, lastIndex)
    }

    private fun nonVoidType(): String {
        if (kind == T.VOID) fail("Expected a non-void type")
        return anyType()
    }

    private fun referenceOrArrayType(): String {
        if (kind != T.TYPE && kind != T.LBRACK) fail("Expected a class or array type, not ${describe()}")
        return anyType()
    }

    /** `[` (any number of them) then a class, primitive, primitive sequence or compound literal. */
    private fun arrayType() {
        while (kind == T.LBRACK) advance()
        when (kind) {
            T.TYPE, T.PRIM, T.PRIMSEQ, T.COMPOUND -> consumeType()
            else -> fail("Expected the element type after '[', not ${describe()}")
        }
    }

    /** `(types)return`, inside a signature or prototype; the parameter list text and the return type text. */
    private fun descriptorBody(): Pair<String, String> {
        expect(T.LPAREN, "'('")
        val paramsStart = tokStart
        while (kind != T.RPAREN) {
            when (kind) {
                T.TYPE, T.PRIM, T.PRIMSEQ, T.COMPOUND -> consumeType()
                T.LBRACK -> arrayType()
                T.EOF -> fail("Unterminated parameter list")
                else -> fail("Expected a parameter type, not ${describe()}")
            }
        }
        val params = source.substring(paramsStart, tokStart).trim()
        advance()
        val returnStart = tokStart
        when (kind) {
            T.TYPE, T.PRIM, T.VOID -> consumeType()
            T.LBRACK -> arrayType()
            else -> fail("Expected the return type, not ${describe()}")
        }
        return params to source.substring(returnStart, lastIndex)
    }

    // ======================================================================
    // Methods
    // ======================================================================

    private fun methodDirective() {
        val startLine = tokLine
        val startCol = tokCol
        advance()
        val modifiers = ArrayList<String>()
        while (isWord() && tokText in METHOD_MODIFIERS && !signatureIsNext()) { modifiers.add(strings.intern(tokText)); advance() }
        // signature: (SUB? identifier | LT identifier GT) ( params ) return
        val sigLine = tokLine
        val sigCol = tokCol
        val sigStart = tokStart
        when (kind) {
            T.LT -> { advance(); if (!isIdentifier()) fail("Expected the method name after '<'"); advance(); expect(T.GT, "'>'") }
            T.SUB -> { advance(); if (!isIdentifier()) fail("Expected the method name, not ${describe()}"); advance() }
            else -> { if (!isIdentifier()) fail("Expected the method name, not ${describe()}"); advance() }
        }
        val nameText = source.substring(sigStart, tokStart).trimEnd()
        if (kind != T.LPAREN) fail("Expected '(' after the method name, not ${describe()}")
        val lparenLine = tokLine
        val lparenCol = tokCol
        val (params, returnType) = descriptorBody()
        val descriptor = strings.intern("($params)$returnType")
        val nameSpan = if (lparenLine == sigLine) Span(sigLine, sigCol, lparenCol) else null
        val descriptorSpan = if (lastLine == lparenLine) Span(lparenLine, lparenCol, lastEnd) else null

        inMethod = true
        instructions.clear(); labels.clear(); duplicateLabels.clear(); labelRefs.clear()
        var ended = false
        while (!ended) {
            statementStart = tokStart
            try {
                when {
                    kind == T.EOF -> fail("Missing '.end method'")
                    kind == T.DIRECTIVE && tokIs(".end method") -> { advance(); ended = true }
                    kind == T.DIRECTIVE && (tokIs(".class") || tokIs(".super") || tokIs(".method") || tokIs(".field") || tokIs(".implements") || tokIs(".source")) ->
                        fail("Missing '.end method' before ${describe()}")
                    else -> methodStatement()
                }
            } catch (_: Recover) {
                if (kind == T.EOF) break
                if (kind == T.DIRECTIVE && (tokIs(".class") || tokIs(".super") || tokIs(".method") || tokIs(".field") || tokIs(".implements") || tokIs(".source"))) break
                recover()
            }
        }
        inMethod = false
        val startChar = startCol
        methods.add(MethodDefinition(
            name = strings.intern(nameText),
            descriptor = descriptor,
            startLine = startLine, startChar = startChar, endLine = lastLine, endChar = lastEnd,
            modifiers = strings.internModifiers(modifiers),
            parameters = if (params.isEmpty()) emptyList() else ParameterList.of(Types.parseTypeStrings(params).map { strings.intern(it) }),
            returnType = strings.intern(returnType),
            instructions = instructions.toList(),
            labels = LabelMap.of(labels),
            nameLine = nameSpan?.line ?: startLine, nameStart = nameSpan?.start ?: startChar, nameEnd = nameSpan?.end ?: startChar,
            descriptorLine = descriptorSpan?.line ?: startLine, descriptorStart = descriptorSpan?.start ?: startChar, descriptorEnd = descriptorSpan?.end ?: startChar,
            labelRefs = LabelRefList.of(labelRefs),
            duplicateLabels = duplicateLabels.toList()
        ))
    }

    /** A modifier word directly followed by `(` or `<` is the method's name, as in `.method public static(I)V`. */
    private fun signatureIsNext(): Boolean {
        var i = tokEnd
        while (i < source.length && (source[i] == ' ' || source[i] == '\t')) i++
        return i < source.length && source[i] == '('
    }

    private fun methodStatement() {
        when (kind) {
            T.COLON -> lineLabel()
            T.DIRECTIVE -> methodDirectiveStatement()
            else -> instruction()
        }
    }

    private fun lineLabel() {
        val startLine = tokLine
        val startCol = tokCol
        advance()
        if (!isIdentifier()) fail("Expected a label name after ':'")
        val name = strings.intern(tokText)
        val end = tokCol + (tokEnd - tokStart)
        advance()
        val definition = LabelDefinition(name, startLine, startCol, end)
        if (labels.putIfAbsent(name, definition) != null) duplicateLabels.add(definition)
    }

    /** A `:label` operand; its span covers the colon and the name. */
    private fun labelOperand(): LabelRef {
        if (kind != T.COLON) fail("Expected a label, not ${describe()}")
        val startLine = tokLine
        val startCol = tokCol
        advance()
        if (!isIdentifier() || tokLine != startLine) fail("Expected a label name after ':'")
        val ref = LabelRef(strings.intern(tokText), startLine, startCol, tokCol + (tokEnd - tokStart))
        advance()
        return ref
    }

    private fun methodDirectiveStatement() {
        when {
            tokIs(".registers") || tokIs(".locals") || tokIs(".line") -> { advance(); numericLiteral() }
            tokIs(".prologue") || tokIs(".epilogue") -> advance()
            tokIs(".param") -> paramDirective()
            tokIs(".local") -> localDirective()
            tokIs(".end local") || tokIs(".restart local") -> { advance(); if (kind != T.IDENT) fail("Expected a register, not ${describe()}"); advance() }
            tokIs(".catch") -> catchDirective(true)
            tokIs(".catchall") -> catchDirective(false)
            tokIs(".annotation") -> annotationDirective()
            tokIs(".packed-switch") -> packedSwitchDirective()
            tokIs(".sparse-switch") -> sparseSwitchDirective()
            tokIs(".array-data") -> arrayDataDirective()
            else -> fail("Unknown directive ${describe()} in a method body")
        }
    }

    private fun paramDirective() {
        advance()
        if (kind != T.IDENT) fail("Expected the parameter register, not ${describe()}")
        advance()
        if (kind == T.COMMA) { advance(); expect(T.STRING, "the parameter name") }
        // annotations may follow, closed by `.end param`; without it they are the method's own
        while (kind == T.DIRECTIVE && tokIs(".annotation")) annotationDirective()
        if (kind == T.DIRECTIVE && tokIs(".end param")) advance()
    }

    private fun localDirective() {
        typeRefDepth++
        try {
            advance()
            if (kind != T.IDENT) fail("Expected the local's register, not ${describe()}")
            advance()
            if (kind == T.COMMA) {
                advance()
                if (kind == T.STRING || kind == T.NULL) advance() else fail("Expected the local's name, not ${describe()}")
                if (kind == T.COLON) { advance(); nonVoidType() }
                if (kind == T.COMMA) { advance(); expect(T.STRING, "the generic signature") }
            }
        } finally {
            typeRefDepth--
        }
    }

    private fun catchDirective(typed: Boolean) {
        advance()
        if (typed) {
            if (kind != T.TYPE) fail("Expected the exception class, not ${describe()}")
            typeRefs.add(TypeRef(strings.intern(tokText), tokLine, tokCol, tokCol + (tokEnd - tokStart)))
            advance()
        }
        expect(T.LBRACE, "'{'")
        val from = labelOperand()
        expect(T.DOT, "'..'"); expect(T.DOT, "'..'")
        val to = labelOperand()
        expect(T.RBRACE, "'}'")
        val handler = labelOperand()
        labelRefs.add(from); labelRefs.add(to); labelRefs.add(handler)
    }

    private fun packedSwitchDirective() {
        val startLine = tokLine
        advance()
        numericLiteral()
        while (kind == T.COLON) labelRefs.add(labelOperand())
        if (!(kind == T.DIRECTIVE && tokIs(".end packed-switch"))) fail("Expected a label or '.end packed-switch', not ${describe()}")
        blocks.add(Block(startLine, tokLine))
        advance()
    }

    private fun sparseSwitchDirective() {
        val startLine = tokLine
        advance()
        while (kind == T.SUB || kind == T.INT || kind == T.FLOAT || kind == T.FLOAT_SPECIAL) {
            numericLiteral()
            expect(T.ARROW, "'->'")
            labelRefs.add(labelOperand())
        }
        if (!(kind == T.DIRECTIVE && tokIs(".end sparse-switch"))) fail("Expected a switch entry or '.end sparse-switch', not ${describe()}")
        blocks.add(Block(startLine, tokLine))
        advance()
    }

    private fun arrayDataDirective() {
        val startLine = tokLine
        advance()
        numericLiteral()
        while (kind == T.SUB || kind == T.INT || kind == T.FLOAT || kind == T.FLOAT_SPECIAL) numericLiteral()
        if (!(kind == T.DIRECTIVE && tokIs(".end array-data"))) fail("Expected an array element or '.end array-data', not ${describe()}")
        blocks.add(Block(startLine, tokLine))
        advance()
    }

    // ======================================================================
    // Annotations
    // ======================================================================

    /** Annotations nest through their values; deeper than this is not smali but an attack on the stack. */
    private var nesting = 0

    private fun annotationDirective() {
        val startLine = tokLine
        if (++nesting > MAX_NESTING) { nesting--; fail("Annotations nested too deep") }
        typeRefDepth++
        try {
            advance()
            if (kind != T.IDENT) fail("Expected the annotation scope (build, runtime or system), not ${describe()}")
            advance()
            if (kind != T.TYPE) fail("Expected the annotation class, not ${describe()}")
            consumeType()
            annotationFields(".end annotation")
            blocks.add(Block(startLine, tokLine))
            advance()
        } finally {
            typeRefDepth--
            nesting--
        }
    }

    private fun subannotation() {
        if (++nesting > MAX_NESTING) { nesting--; fail("Annotations nested too deep") }
        try {
            advance()
            if (kind != T.TYPE) fail("Expected the annotation class, not ${describe()}")
            consumeType()
            annotationFields(".end subannotation")
            advance()
        } finally {
            nesting--
        }
    }

    /** `name = value` entries up to [terminator], which is left as the current token. */
    private fun annotationFields(terminator: String) {
        while (true) {
            when {
                kind == T.DIRECTIVE && tokIs(terminator) -> return
                kind == T.EOF -> fail("Missing '$terminator'")
                else -> {
                    if (kind == T.SUB) advance()
                    if (!isIdentifier()) fail("Expected an annotation element name or '$terminator', not ${describe()}")
                    advance()
                    expect(T.ASSIGN, "'=' after the element name")
                    if (kind == T.LBRACE) {
                        advance()
                        while (kind != T.RBRACE) {
                            annotationValue()
                            if (kind == T.COMMA) advance() else if (kind != T.RBRACE) fail("Expected ',' or '}' in the value list, not ${describe()}")
                        }
                        advance()
                    } else {
                        annotationValue()
                    }
                }
            }
        }
    }

    private fun annotationValue() {
        when (kind) {
            T.STRING, T.CHAR, T.NULL, T.BOOL -> advance()
            T.SUB, T.INT, T.FLOAT, T.FLOAT_SPECIAL -> numericLiteral()
            T.LBRACK, T.PRIM, T.VOID -> anyType()
            T.TYPE -> { consumeType(); if (kind == T.ARROW) { advance(); memberSignatureAfterArrow() } }
            T.DIRECTIVE -> when {
                tokIs(".enum") -> { advance(); fieldTarget() }
                tokIs(".subannotation") -> subannotation()
                tokIs(".annotation") -> annotationDirective()
                else -> fail("Unexpected ${describe()} as an annotation value")
            }
            else -> fail("Expected an annotation value, not ${describe()}")
        }
    }

    /** After `Lowner;->`: a method signature `name(params)ret` as a reference inside an annotation. */
    private fun memberSignatureAfterArrow() {
        when (kind) {
            T.LT -> { advance(); if (!isIdentifier()) fail("Expected the method name after '<'"); advance(); expect(T.GT, "'>'") }
            T.SUB -> { advance(); if (!isIdentifier()) fail("Expected a member name"); advance() }
            else -> { if (!isIdentifier()) fail("Expected a member name, not ${describe()}"); advance() }
        }
        descriptorBody()
    }

    // ======================================================================
    // Instructions
    // ======================================================================

    /** A reference operand's text, position and whether it was one contiguous run on one line (as the builder recorded). */
    private class Target(val text: String, val line: Int, val contiguousStart: Int)

    private fun instruction() {
        if (!isWord()) fail("Expected an instruction, a label or a directive, not ${describe()}")
        val opcodeText = tokText
        val shape = InstructionShapes[opcodeText] ?: fail("Unknown opcode '$opcodeText'")
        val startLine = tokLine
        val startChar = tokCol
        val opcode = strings.intern(opcodeText)
        advance()
        when (shape) {
            OperandShape.NONE -> {}
            OperandShape.R -> register()
            OperandShape.RR -> { register(); comma(); register() }
            OperandShape.RRR -> { register(); comma(); register(); comma(); register() }
            OperandShape.RR_LITERAL -> { register(); comma(); register(); comma(); numericLiteral() }
            OperandShape.RR_REGISTER_OR_LITERAL -> { register(); comma(); register(); comma(); if (kind == T.IDENT) advance() else numericLiteral() }
            OperandShape.R_LITERAL -> { register(); comma(); numericLiteral() }
            OperandShape.R_STRING -> {
                register(); comma()
                if (kind != T.STRING) fail("Expected a string literal, not ${describe()}")
                val value = strings.internLiteral(source.substring(tokStart + 1, tokEnd - 1))
                advance()
                instructions.add(ConstStringInstruction(value, startLine, startChar, lastLine, lastEnd))
            }
            OperandShape.R_FIELD -> { register(); comma(); val t = fieldTarget(); addFieldAccess(opcode, t, startLine, startChar) }
            OperandShape.RR_FIELD -> { register(); comma(); register(); comma(); val t = fieldTarget(); addFieldAccess(opcode, t, startLine, startChar) }
            OperandShape.LABEL -> jump(opcode, startLine, startChar)
            OperandShape.R_LABEL -> { register(); comma(); if (opcodeText.startsWith("if-")) jump(opcode, startLine, startChar) else labelRefs.add(labelOperand()) }
            OperandShape.RR_LABEL -> { register(); comma(); register(); comma(); jump(opcode, startLine, startChar) }
            OperandShape.LIST_METHOD -> { registerList(); comma(); val t = methodTarget(); addInvoke(opcode, t, startLine, startChar) }
            OperandShape.RANGE_METHOD -> { registerRange(); comma(); val t = methodTarget(); addInvoke(opcode, t, startLine, startChar) }
            OperandShape.LIST_METHOD_PROTO -> { registerList(); comma(); val t = methodTarget(); comma(); methodProto(); addInvoke(opcode, t, startLine, startChar) }
            OperandShape.RANGE_METHOD_PROTO -> { registerRange(); comma(); val t = methodTarget(); comma(); methodProto(); addInvoke(opcode, t, startLine, startChar) }
            OperandShape.LIST_CALL_SITE -> { registerList(); comma(); val t = callSite(); addInvoke(opcode, t, startLine, startChar) }
            OperandShape.RANGE_CALL_SITE -> { registerRange(); comma(); val t = callSite(); addInvoke(opcode, t, startLine, startChar) }
            OperandShape.LIST_TYPE -> { registerList(); comma(); val t = typeOperand(false); addType(opcode, t, startLine, startChar) }
            OperandShape.RANGE_TYPE -> { registerRange(); comma(); val t = typeOperand(false); addType(opcode, t, startLine, startChar) }
            OperandShape.R_REFERENCE_TYPE -> { register(); comma(); if (kind != T.TYPE) fail("Expected a class name, not ${describe()}"); val t = typeOperand(false); addType(opcode, t, startLine, startChar) }
            OperandShape.R_REFERENCE_OR_ARRAY_TYPE -> { register(); comma(); if (kind != T.TYPE && kind != T.LBRACK) fail("Expected a class or array type, not ${describe()}"); val t = typeOperand(false); addType(opcode, t, startLine, startChar) }
            OperandShape.RR_NON_VOID_TYPE -> { register(); comma(); register(); comma(); val t = typeOperand(false); addType(opcode, t, startLine, startChar) }
            OperandShape.R_METHOD_HANDLE -> { register(); comma(); methodHandle(opcode, startLine, startChar) }
            OperandShape.R_PROTO -> { register(); comma(); methodProto() }
        }
    }

    private fun comma() { if (kind != T.COMMA) fail("Expected ',' not ${describe()}"); advance() }

    private fun register() {
        if (kind != T.IDENT) fail("Expected a register, not ${describe()}")
        advance()
    }

    private fun registerList() {
        expect(T.LBRACE, "'{'")
        if (kind != T.RBRACE) {
            register()
            while (kind == T.COMMA) { advance(); register() }
        }
        expect(T.RBRACE, "'}'")
    }

    private fun registerRange() {
        expect(T.LBRACE, "'{'")
        register()
        expect(T.DOT, "'..'"); expect(T.DOT, "'..'")
        register()
        expect(T.RBRACE, "'}'")
    }

    private fun jump(opcode: String, startLine: Int, startChar: Int) {
        val ref = labelOperand()
        instructions.add(JumpInstruction(opcode, ref.name, startLine, startChar, lastLine, lastEnd, ref.line, ref.start, ref.end))
        labelRefs.add(ref)
    }

    /** `Lowner;->name(params)ret`, or an array owner such as `[I->clone()`. */
    private fun methodTarget(): Target {
        val startIndex = tokStart
        val startLine = tokLine
        val startCol = tokCol
        ownerType()
        expect(T.ARROW, "'->' after the class name")
        memberSignatureAfterArrow()
        return target(startIndex, startLine, startCol)
    }

    /** `Lowner;->name:type`. */
    private fun fieldTarget(): Target {
        val startIndex = tokStart
        val startLine = tokLine
        val startCol = tokCol
        ownerType()
        expect(T.ARROW, "'->' after the class name")
        if (kind == T.SUB) advance()
        if (!isIdentifier()) fail("Expected the field name, not ${describe()}")
        advance()
        expect(T.COLON, "':' and the field type")
        anyType()
        return target(startIndex, startLine, startCol)
    }

    private fun ownerType() {
        when (kind) {
            T.TYPE -> consumeType()
            T.LBRACK -> arrayType()
            else -> fail("Expected a class name, not ${describe()}")
        }
    }

    /** The text of the operand just consumed: the source slice when it holds no blank or comment, else the tokens joined. */
    private fun target(startIndex: Int, startLine: Int, startCol: Int): Target {
        val endIndex = lastIndex
        val slice = source.substring(startIndex, endIndex)
        var contiguous = true
        for (ch in slice) if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n' || ch == '#') { contiguous = false; break }
        if (contiguous) return Target(slice, startLine, startCol)
        return Target(joinedTokens(startIndex, endIndex), startLine, -1)
    }

    /** The tokens of a span without the blanks and comments between them. */
    private fun joinedTokens(start: Int, end: Int): String {
        val sb = StringBuilder(end - start)
        var i = start
        while (i < end) {
            val c = source[i]
            when {
                c == '#' -> while (i < end && source[i] != '\n') i++
                c == ' ' || c == '\t' || c == '\r' || c == '\n' -> i++
                c == '"' -> { val s = i; i++; while (i < end && source[i] != '"') { if (source[i] == '\\') i++; i++ }; i++; sb.append(source, s, minOf(i, end)) }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun typeOperand(allowVoid: Boolean): Target {
        val startIndex = tokStart
        val startLine = tokLine
        val startCol = tokCol
        if (allowVoid) anyType() else nonVoidType()
        return target(startIndex, startLine, startCol)
    }

    private fun methodProto() {
        if (kind != T.LPAREN) fail("Expected a prototype '(types)type', not ${describe()}")
        typeRefDepth++
        try { descriptorBody() } finally { typeRefDepth-- }
    }

    /** `name("site", (proto)ret, extra...)@Lbsm;->method(...)ret`: the bootstrap method is the navigable target. */
    private fun callSite(): Target {
        if (!isIdentifier()) fail("Expected the call site name, not ${describe()}")
        advance()
        expect(T.LPAREN, "'('")
        expect(T.STRING, "the call site method name")
        comma()
        methodProto()
        while (kind == T.COMMA) { advance(); callSiteArgument() }
        expect(T.RPAREN, "')'")
        expect(T.AT, "'@' and the bootstrap method")
        return methodTarget()
    }

    private fun callSiteArgument() {
        when (kind) {
            T.STRING, T.CHAR, T.NULL, T.BOOL -> advance()
            T.SUB, T.INT, T.FLOAT, T.FLOAT_SPECIAL -> numericLiteral()
            T.LPAREN -> methodProto()
            T.LBRACK -> anyType()
            T.TYPE -> { consumeType(); if (kind == T.ARROW) { advance(); memberOrField() } }
            T.DIRECTIVE -> if (tokIs(".enum")) { advance(); fieldTarget() } else fail("Unexpected ${describe()} in the call site")
            else -> {
                if (!isIdentifier()) fail("Unexpected ${describe()} in the call site")
                advance()
                if (kind == T.AT) { advance(); ownerType(); expect(T.ARROW, "'->'"); memberOrField() }
            }
        }
    }

    /** After `->` where either a method signature or `name:type` may follow. */
    private fun memberOrField() {
        val afterName = peekAfterName()
        if (afterName == ':') {
            if (kind == T.SUB) advance()
            if (!isIdentifier()) fail("Expected the field name, not ${describe()}")
            advance(); expect(T.COLON, "':'"); anyType()
        } else {
            memberSignatureAfterArrow()
        }
    }

    /** The first non-blank character after the member name that starts at the current token. */
    private fun peekAfterName(): Char {
        var i = tokEnd
        if (kind == T.SUB || kind == T.LT) {
            while (i < source.length && isWordChar(source[i])) i++
            if (kind == T.LT) { if (i < source.length && source[i] == '>') i++ }
        }
        while (i < source.length && (source[i] == ' ' || source[i] == '\t')) i++
        return if (i < source.length) source[i] else '\u0000'
    }

    private fun methodHandle(opcode: String, startLine: Int, startChar: Int) {
        if (!isIdentifier()) fail("Expected the method handle kind, not ${describe()}")
        advance()
        expect(T.AT, "'@' and the member reference")
        val startIndex = tokStart
        val targetLine = tokLine
        val targetCol = tokCol
        ownerType()
        expect(T.ARROW, "'->' after the class name")
        if (peekAfterName() == ':') {
            if (kind == T.SUB) advance()
            if (!isIdentifier()) fail("Expected the field name, not ${describe()}")
            advance(); expect(T.COLON, "':'"); anyType()
            addFieldAccess(opcode, target(startIndex, targetLine, targetCol), startLine, startChar)
        } else {
            memberSignatureAfterArrow()
            addInvoke(opcode, target(startIndex, targetLine, targetCol), startLine, startChar)
        }
    }

    private fun addInvoke(opcode: String, target: Target, startLine: Int, startChar: Int) {
        val text = target.text
        val arrow = text.indexOf("->")
        if (arrow < 0) return
        val signature = text.substring(arrow + 2)
        val paren = signature.indexOf('(')
        if (paren < 0) return
        instructions.add(InvokeInstruction(
            opcode = opcode,
            className = strings.intern(text.substring(0, arrow)),
            methodName = strings.intern(signature.substring(0, paren)),
            descriptor = strings.intern(signature.substring(paren)),
            startLine = startLine, startChar = startChar, endLine = lastLine, endChar = lastEnd,
            targetLine = target.line, targetStart = target.contiguousStart
        ))
    }

    private fun addFieldAccess(opcode: String, target: Target, startLine: Int, startChar: Int) {
        val text = target.text
        val arrow = text.indexOf("->")
        if (arrow < 0) return
        val member = text.substring(arrow + 2)
        val colon = member.indexOf(':')
        if (colon < 0) return
        instructions.add(FieldAccessInstruction(
            opcode = opcode,
            className = strings.intern(text.substring(0, arrow)),
            fieldName = strings.intern(member.substring(0, colon)),
            fieldType = strings.intern(member.substring(colon + 1)),
            startLine = startLine, startChar = startChar, endLine = lastLine, endChar = lastEnd,
            targetLine = target.line, targetStart = target.contiguousStart
        ))
    }

    private fun addType(opcode: String, target: Target, startLine: Int, startChar: Int) {
        instructions.add(TypeInstruction(
            opcode = opcode,
            className = strings.intern(target.text),
            startLine = startLine, startChar = startChar, endLine = lastLine, endChar = lastEnd,
            typeLine = target.line, typeStart = target.contiguousStart
        ))
    }

    companion object {
        private const val PRIMITIVES = "ZBSCIJFD"
        private const val MAX_NESTING = 64
        private val END_WORDS = setOf("method", "field", "annotation", "local", "packed-switch", "sparse-switch", "array-data", "param", "subannotation")
        private val CLASS_MODIFIERS = setOf("public", "private", "protected", "final", "annotation", "synthetic", "static", "abstract", "enum", "interface")
        private val METHOD_MODIFIERS = setOf("public", "private", "protected", "final", "synthetic", "static", "abstract", "constructor", "bridge", "declared-synchronized", "synchronized", "strictfp", "varargs", "native")
        private val FIELD_MODIFIERS = setOf("public", "private", "protected", "final", "synthetic", "static", "transient", "volatile", "enum")
    }
}
