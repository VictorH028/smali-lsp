package xyz.surendrajat.smalilsp.parser

import org.antlr.v4.runtime.*
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.parser.generated.SmaliLexer
import xyz.surendrajat.smalilsp.parser.generated.SmaliParser as GeneratedSmaliParser
import xyz.surendrajat.smalilsp.util.StringPool
import org.slf4j.LoggerFactory

/**
 * The parser entry: runs the ANTLR lexer and parser over a file's text and hands the parse to
 * [ASTBuilder], which listens to it and builds the model, so the tree is never walked twice.
 * Syntax errors are collected, not thrown: a broken method still yields the class around it.
 */
class SmaliParser(private val strings: StringPool = StringPool()) {
    private val logger = LoggerFactory.getLogger(SmaliParser::class.java)
    
    /**
     * Result of parsing with error collection.
     */
    data class ParseResult(
        val smaliFile: SmaliFile?,
        val syntaxErrors: List<SyntaxError>
    )
    
    /** Syntax error at a 1-based line, with the length of the offending token (1 when there is none). */
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
    
    /**
     * The model of a file, or null for empty or class-less text or when the parse itself fails.
     *
     * Prediction is single-stage SLL: the grammar has no ambiguity that needs LL fallback, and
     * every file of the three decompiled apps under test-data (98,686 files) parses this way
     * without a syntax error, at about 0.14 ms per file single-threaded. Call [parseWithErrors]
     * for the syntax errors as well.
     */
    fun parse(uri: String, content: String): SmaliFile? {
        return parseWithErrors(uri, content).smaliFile
    }
    
    /** The model (possibly partial) and every lexer and parser error, each with the offending token's length. */
    fun parseWithErrors(uri: String, content: String): ParseResult {
        // Strip UTF-8 BOM if present (some editors add it)
        val cleanContent = if (content.startsWith('\uFEFF')) content.substring(1) else content

        // Handle empty or whitespace-only files gracefully
        if (cleanContent.isBlank()) {
            logger.debug("Skipping parse of empty file: $uri")
            return ParseResult(null, emptyList())
        }
        
        // Handle comment-only files (no .class directive)
        if (!cleanContent.contains(".class")) {
            logger.debug("Skipping parse of file with no .class directive: $uri")
            return ParseResult(null, emptyList())
        }

        val syntaxErrors = mutableListOf<SyntaxError>()

        val smaliFile = try {
            val charStream = StringCharStream(cleanContent, uri)
            val lexer = SmaliLexer(charStream)
            
            // Collect lexer errors
            lexer.removeErrorListeners()
            lexer.addErrorListener(object : BaseErrorListener() {
                override fun syntaxError(
                    recognizer: Recognizer<*, *>?,
                    offendingSymbol: Any?,
                    line: Int,
                    charPositionInLine: Int,
                    msg: String?,
                    e: RecognitionException?
                ) {
                    syntaxErrors.add(SyntaxError(line, charPositionInLine, msg ?: "Lexer error"))
                }
            })
            
            val tokens = CommonTokenStream(lexer)
            
            val parser = GeneratedSmaliParser(tokens)
            parser.interpreter.predictionMode = org.antlr.v4.runtime.atn.PredictionMode.SLL
            
            // Collect parser errors
            parser.removeErrorListeners()
            parser.addErrorListener(object : BaseErrorListener() {
                override fun syntaxError(
                    recognizer: Recognizer<*, *>?,
                    offendingSymbol: Any?,
                    line: Int,
                    charPositionInLine: Int,
                    msg: String?,
                    e: RecognitionException?
                ) {
                    syntaxErrors.add(SyntaxError(line, charPositionInLine, msg ?: "Parser error", tokenLength(offendingSymbol)))
                }
            })
            
            // The builder listens to the parse itself — no second traversal of the tree.
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
