package xyz.surendrajat.smalilsp.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.shared.TestUtils
import java.io.File

/**
 * Grammar corpus check over every decompiled APK under `test-data/`.
 *
 * baksmali output is valid smali by construction, so every file must parse without a
 * syntax error, and the AST builder must see every `.method` and `.field` directive that
 * is in the source. A shortfall means the parser gave up part-way through a file — the
 * failure that hid 912 methods on ProtonMail until `EOF` was added to the start rule.
 *
 * Skips when no fixture directory is present. Parses each file once.
 */
class GrammarCorpusTest {

    private val methodDirective = Regex("""^\.method\b""", RegexOption.MULTILINE)
    private val fieldDirective = Regex("""^\.field\b""", RegexOption.MULTILINE)

    private fun fixtureDirs(): List<File> {
        val root = File(TestUtils.getProjectRoot(), "test-data")
        return root.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
    }

    @TestFactory
    fun `every file in the corpus parses completely and without syntax errors`(): List<DynamicTest> {
        val dirs = fixtureDirs()
        assumeTrue(dirs.isNotEmpty(), "no decompiled APKs under test-data/ — skipping corpus check")

        return dirs.map { dir ->
            DynamicTest.dynamicTest(dir.name) {
                val parser = SmaliParser()
                val syntaxErrors = mutableListOf<String>()
                val truncated = mutableListOf<String>()
                var files = 0

                dir.walkTopDown().filter { it.isFile && it.extension == "smali" }.forEach { f ->
                    files++
                    val text = f.readText(Charsets.UTF_8)
                    val result = parser.parseWithErrors(f.toURI().toString(), text)
                    result.syntaxErrors.firstOrNull()?.let { e ->
                        val line = text.lines().getOrNull(e.line - 1)?.trim() ?: ""
                        syntaxErrors.add("${f.relativeTo(dir)}:${e.line} ${e.message.take(70)} | ${line.take(90)}")
                    }
                    val smali = result.smaliFile ?: run { truncated.add("${f.relativeTo(dir)}: no AST at all"); return@forEach }
                    val declaredMethods = methodDirective.findAll(text).count()
                    val declaredFields = fieldDirective.findAll(text).count()
                    if (smali.methods.size != declaredMethods || smali.fields.size != declaredFields) {
                        truncated.add("${f.relativeTo(dir)}: methods ${smali.methods.size}/$declaredMethods, fields ${smali.fields.size}/$declaredFields")
                    }
                }

                println("[corpus] ${dir.name}: $files files, ${syntaxErrors.size} with syntax errors, ${truncated.size} with missing members")
                assertEquals(emptyList<String>(), syntaxErrors.take(20), "${syntaxErrors.size} file(s) in ${dir.name} have syntax errors")
                assertEquals(emptyList<String>(), truncated.take(20), "${truncated.size} file(s) in ${dir.name} lost methods/fields during parsing")
            }
        }
    }
}
