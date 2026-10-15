package xyz.surendrajat.smalilsp.integration

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.shared.TestUtils
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals

/**
 * The hand-written front end must produce exactly the model the ANTLR grammar produced, on
 * every file of every decompiled app under `test-data/`: the whole `SmaliFile` tree compares
 * equal (positions, names, labels, references, blocks), and neither reports a syntax error.
 */
class ParserParityTest {

    @TestFactory
    fun `the text parser and the ANTLR parser agree on every corpus file`(): List<DynamicTest> {
        val dirs = TestUtils.fixtureDirs()
        assumeTrue(dirs.isNotEmpty(), "no decompiled APKs under test-data/ — skipping parity check")
        return dirs.map { dir ->
            DynamicTest.dynamicTest(dir.name) {
                val parser = SmaliParser()
                val problems = ConcurrentLinkedQueue<String>()
                val files = dir.walkTopDown().filter { it.isFile && it.extension == "smali" }.toList()
                files.parallelStream().forEach { f ->
                    val text = f.readText(Charsets.UTF_8)
                    val uri = f.toURI().toString()
                    val antlr = parser.parseWithAntlr(uri, text)
                    val hand = parser.parseWithText(uri, text)
                    val where = f.relativeTo(dir).path
                    if (hand.syntaxErrors.isNotEmpty()) problems.add("$where: text parser syntax error ${hand.syntaxErrors.first()}")
                    if (antlr.syntaxErrors.isNotEmpty()) problems.add("$where: ANTLR syntax error ${antlr.syntaxErrors.first()}")
                    if (antlr.smaliFile != hand.smaliFile) problems.add("$where: ${firstDifference(antlr.smaliFile, hand.smaliFile)}")
                }
                assertEquals(emptyList<String>(), problems.sorted().take(25), "${problems.size} of ${files.size} file(s) in ${dir.name} differ")
            }
        }
    }

    /** A short description of where two models first differ, for a failure message that locates the bug. */
    private fun firstDifference(a: SmaliFile?, b: SmaliFile?): String {
        if (a == null || b == null) return "one side has no model: antlr=${a != null} text=${b != null}"
        if (a.classDefinition != b.classDefinition) return "class: ${a.classDefinition} vs ${b.classDefinition}"
        if (a.fields != b.fields) {
            val i = a.fields.indices.firstOrNull { it >= b.fields.size || a.fields[it] != b.fields[it] } ?: a.fields.size
            return "field #$i: ${a.fields.getOrNull(i)} vs ${b.fields.getOrNull(i)}"
        }
        if (a.methods != b.methods) {
            val i = a.methods.indices.firstOrNull { it >= b.methods.size || a.methods[it] != b.methods[it] } ?: a.methods.size
            val am = a.methods.getOrNull(i) ?: return "method #$i missing in text parser (${b.methods.size} vs ${a.methods.size})"
            val bm = b.methods.getOrNull(i) ?: return "method #$i missing in ANTLR (${b.methods.size} vs ${a.methods.size})"
            if (am.instructions != bm.instructions) {
                val j = am.instructions.indices.firstOrNull { it >= bm.instructions.size || am.instructions[it] != bm.instructions[it] } ?: am.instructions.size
                return "method ${am.name}${am.descriptor} instruction #$j: ${am.instructions.getOrNull(j)} vs ${bm.instructions.getOrNull(j)}"
            }
            if (am.labels != bm.labels) return "method ${am.name} labels: ${am.labels} vs ${bm.labels}"
            if (am.labelRefs != bm.labelRefs) return "method ${am.name} labelRefs: ${am.labelRefs.take(5)} vs ${bm.labelRefs.take(5)}"
            return "method #$i: ${am.copy(instructions = emptyList(), labels = emptyMap(), labelRefs = emptyList())} vs ${bm.copy(instructions = emptyList(), labels = emptyMap(), labelRefs = emptyList())}"
        }
        if (a.typeRefs != b.typeRefs) {
            val i = a.typeRefs.indices.firstOrNull { it >= b.typeRefs.size || a.typeRefs[it] != b.typeRefs[it] } ?: a.typeRefs.size
            return "typeRef #$i: ${a.typeRefs.getOrNull(i)} vs ${b.typeRefs.getOrNull(i)} (${a.typeRefs.size} vs ${b.typeRefs.size})"
        }
        if (a.blocks != b.blocks) return "blocks: ${a.blocks.take(5)} vs ${b.blocks.take(5)} (${a.blocks.size} vs ${b.blocks.size})"
        return "equal parts but unequal files?"
    }
}
