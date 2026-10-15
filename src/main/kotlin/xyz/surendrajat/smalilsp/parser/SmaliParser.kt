package xyz.surendrajat.smalilsp.parser

import org.antlr.v4.runtime.*
import org.slf4j.LoggerFactory
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.parser.generated.SmaliLexer
import xyz.surendrajat.smalilsp.parser.generated.SmaliParser as GeneratedSmaliParser
import xyz.surendrajat.smalilsp.util.StringPool

/**
 * The parser entry. The hand-written [SmaliTextParser] is the front end; the ANTLR grammar it
 * replaces stays reachable through [parseWithAntlr] for the parity check over the corpus.
 * Syntax errors are collected, not thrown: a broken method still yields the class around it.
 */
class SmaliParser(private val strings: StringPool = StringPool(), private val engine: Engine = Engine.TEXT) {

    /** Which front end parses: the hand-written text parser, or the ANTLR grammar it replaces. */
    enum class Engine { TEXT, ANTLR }

    private val logger = LoggerFactory.getLogger(SmaliParser::class.java)

    /** The model (possibly partial) and the syntax errors, each on its offending token. */
    data class ParseResult(
        val smaliFile: SmaliFile?,
        val syntaxErrors: List<SyntaxError>
    )

    /** Syntax error at a 1-based line and 0-based UTF-16 column, with the length of the offending token (1 at the end of the file). */
    data class SyntaxError(
        val line: Int,
        val charPositionInLine: Int,
        val message: String,
        val length: Int = 1
    )

    private fun tokenLength(offendingSymbol: Any?): Int {
        val token = offendingSymbol as? Token ?: return 1
        if (token.type == Token.EOF) return 1
        return token.text?.length?.coerceAtLeast(1) ?: 1
    }

    /** The model of a file, or null for empty or class-less text. Call [parseWithErrors] for the syntax errors as well. */
    fun parse(uri: String, content: String): SmaliFile? = parseWithErrors(uri, content).smaliFile

    /** The model (possibly partial) and every syntax error, each with the offending token's length. */
    fun parseWithErrors(uri: String, content: String): ParseResult =
        if (engine == Engine.TEXT) parseWithText(uri, content) else parseWithAntlr(uri, content)

    /** The hand-written front end. */
    fun parseWithText(uri: String, content: String): ParseResult {
        val cleanContent = if (content.startsWith('﻿')) content.substring(1) else content
        if (cleanContent.isBlank() || !cleanContent.contains(".class")) return ParseResult(null, emptyList())
        val parser = SmaliTextParser(uri, cleanContent, strings)
        val file = try {
            parser.parse()
        } catch (e: Exception) {
            logger.error("Failed to parse $uri", e)
            return ParseResult(null, parser.syntaxErrors + SyntaxError(1, 0, "Parse error: ${e.message}"))
        }
        return ParseResult(file, parser.syntaxErrors)
    }

    /** The ANTLR front end: SLL prediction, the AST built by [ASTBuilder] as a parse listener. */
    fun parseWithAntlr(uri: String, content: String): ParseResult {
        val cleanContent = if (content.startsWith('﻿')) content.substring(1) else content
        if (cleanContent.isBlank() || !cleanContent.contains(".class")) return ParseResult(null, emptyList())
        val syntaxErrors = mutableListOf<SyntaxError>()
        val smaliFile = try {
            val lexer = SmaliLexer(StringCharStream(cleanContent, uri))
            lexer.removeErrorListeners()
            lexer.addErrorListener(object : BaseErrorListener() {
                override fun syntaxError(recognizer: Recognizer<*, *>?, offendingSymbol: Any?, line: Int, charPositionInLine: Int, msg: String?, e: RecognitionException?) {
                    syntaxErrors.add(SyntaxError(line, charPositionInLine, msg ?: "Lexer error"))
                }
            })
            val tokens = CommonTokenStream(lexer)
            val parser = GeneratedSmaliParser(tokens)
            parser.interpreter.predictionMode = org.antlr.v4.runtime.atn.PredictionMode.SLL
            parser.removeErrorListeners()
            parser.addErrorListener(object : BaseErrorListener() {
                override fun syntaxError(recognizer: Recognizer<*, *>?, offendingSymbol: Any?, line: Int, charPositionInLine: Int, msg: String?, e: RecognitionException?) {
                    syntaxErrors.add(SyntaxError(line, charPositionInLine, msg ?: "Parser error", tokenLength(offendingSymbol)))
                }
            })
            val builder = ASTBuilder(uri, tokens, cleanContent, strings)
            parser.addParseListener(builder)
            parser.parse()
            builder.build()
        } catch (e: Exception) {
            logger.error("Failed to parse $uri", e)
            syntaxErrors.add(SyntaxError(1, 0, "Parse error: ${e.message}"))
            null
        }
        return ParseResult(smaliFile, syntaxErrors)
    }
}
