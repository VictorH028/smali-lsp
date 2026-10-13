package xyz.surendrajat.smalilsp.unit.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.parser.generated.SmaliLexer
import xyz.surendrajat.smalilsp.parser.generated.SmaliParser
import xyz.surendrajat.smalilsp.shared.TestUtils
import java.io.File

/**
 * The parser's `opcodeKeyword` rule must enumerate every `OP_*` token the lexer defines,
 * otherwise a member named after a newly added opcode silently becomes unparseable again.
 * ANTLR cannot express "any OP_* token", so the list is duplicated and checked here.
 */
class GrammarConsistencyTest {

    private val parserGrammar = File(TestUtils.getProjectRoot(), "src/main/antlr/SmaliParser.g4").readText()

    private fun ruleBody(name: String): String {
        val m = Regex("""(?m)^$name:\s*(.*?)^\s*;""", RegexOption.DOT_MATCHES_ALL).find(parserGrammar)
        requireNotNull(m) { "rule $name not found in SmaliParser.g4" }
        return m.groupValues[1]
    }

    @Test
    fun `opcodeKeyword lists every OP_ token of the lexer`() {
        val lexerOpcodes = SmaliLexer.VOCABULARY.let { v ->
            (0..v.maxTokenType).mapNotNull { v.getSymbolicName(it) }.filter { it.startsWith("OP_") }.toSet()
        }
        val listed = Regex("""OP_\w+""").findAll(ruleBody("opcodeKeyword")).map { it.value }.toSet()

        assertTrue(lexerOpcodes.isNotEmpty())
        assertEquals(emptySet<String>(), lexerOpcodes - listed, "opcode tokens missing from opcodeKeyword")
        assertEquals(emptySet<String>(), listed - lexerOpcodes, "opcodeKeyword lists unknown tokens")
    }

    @Test
    fun `identifier accepts every keyword-like token except directives and punctuation`() {
        // Tokens that legitimately cannot be member names: directives (start with '.'),
        // punctuation, literals with structure, whitespace/comments, and the catch-all identifier.
        val excluded = setOf(
            "QUALIFIED_TYPE_NAME", "COMPOUND_METHOD_ARG_LITERAL", "LBRACK", "RBRACK", "LPAREN", "RPAREN",
            "LBRACE", "RBRACE", "COLON", "ASSIGN", "DOT", "SUB", "COMMA", "SLASH", "LT", "GT", "ARROW", "SEMI",
            "DECIMAL_LITERAL", "HEX_LITERAL", "OCT_LITERAL", "BINARY_LITERAL", "FLOAT_LITERAL", "HEX_FLOAT_LITERAL",
            "CHAR_LITERAL", "STRING_LITERAL", "WS", "LINE_COMMENT", "IDENTIFIER",
        )
        val v = SmaliLexer.VOCABULARY
        val keywordTokens = (1..v.maxTokenType).mapNotNull { v.getSymbolicName(it) }
            .filter { it !in excluded && !it.endsWith("_DIRECTIVE") }
            .toSet()

        val identifierBody = ruleBody("identifier") + ruleBody("literalKeyword") + ruleBody("opcodeKeyword")
        val accepted = Regex("""[A-Z][A-Z0-9_]+""").findAll(identifierBody).map { it.value }.toSet()

        assertEquals(emptySet<String>(), keywordTokens - accepted, "keyword tokens not accepted as member names")
    }

    @Test
    fun `generated parser exposes the rules this test relies on`() {
        // Guards the regexes above against a rename of the rules they parse out of the grammar.
        val ruleNames = SmaliParser.ruleNames.toSet()
        assertTrue("identifier" in ruleNames && "opcodeKeyword" in ruleNames && "literalKeyword" in ruleNames)
    }
}
