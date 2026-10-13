package xyz.surendrajat.smalilsp.integration.workspace

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SymbolKind
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import xyz.surendrajat.smalilsp.core.FieldAccessInstruction
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.core.TypeInstruction
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.DiagnosticProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.providers.WorkspaceSymbolProvider
import xyz.surendrajat.smalilsp.shared.TestUtils
import org.eclipse.lsp4j.Location
import xyz.surendrajat.smalilsp.util.Types
import java.io.File
import java.util.Random

/**
 * Properties that must hold for *any* decompiled APK placed under `test-data/`, checked on
 * every fixture directory found there. Nothing here depends on a particular app version:
 * no file names, no absolute counts, no latency thresholds. Fixtures with more files than
 * [maxFiles] are skipped because the test forks run with a 1 GB heap (the parser-only
 * `GrammarCorpusTest` still covers them).
 *
 * Replaces the version-pinned real-APK suites (RealWorldTest, ComprehensiveRealWorldTest,
 * ProtonMailValidationTest, GotoDefinitionCoverageTest, GenericAPKStressTest, the
 * directive/const-class/reference stress tests and friends).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealApkInvariantsTest {

    private val maxFiles = (System.getProperty("smali.invariants.maxFiles") ?: "40000").toInt()
    private val methodDirective = Regex("""^\.method\b""", RegexOption.MULTILINE)
    private val fieldDirective = Regex("""^\.field\b""", RegexOption.MULTILINE)
    private val classDirective = Regex("""^\.class\b.*\s(L[^\s;]+;)\s*$""", RegexOption.MULTILINE)

    private class Fixture(val dir: File, val smaliFiles: List<File>) {
        val index = WorkspaceIndex()
        lateinit var scanFailed: Pair<Int, Int> // succeeded, failed
        val files: List<SmaliFile> get() = index.getFilesView().sortedBy { it.uri }
        fun uriOf(f: File) = f.toURI().toString()
    }

    private fun fixtures(): List<Fixture> {
        return TestUtils.fixtureDirs().map { dir ->
            Fixture(dir, dir.walkTopDown().filter { it.isFile && it.extension == "smali" }.sortedBy { it.path }.toList())
        }
    }

    @TestFactory
    fun `invariants hold on every fixture under test-data`(): List<DynamicNode> {
        val all = fixtures()
        assumeTrue(all.isNotEmpty(), "no decompiled APKs under test-data/ — skipping")
        return all.map { fx ->
            if (fx.smaliFiles.size > maxFiles) {
                DynamicContainer.dynamicContainer(fx.dir.name, listOf(dynamicTest("skipped: ${fx.smaliFiles.size} files exceed maxFiles=$maxFiles") {
                    assumeTrue(false, "${fx.dir.name}: ${fx.smaliFiles.size} files exceed -Dsmali.invariants.maxFiles=$maxFiles")
                }))
            } else {
                DynamicContainer.dynamicContainer("${fx.dir.name} (${fx.smaliFiles.size} files)", checks(fx))
            }
        }
    }

    private fun checks(fx: Fixture): List<DynamicNode> = listOf(
        dynamicTest("indexes every file") { indexesEveryFile(fx) },
        dynamicTest("member counts equal directive counts") { memberCounts(fx) },
        dynamicTest("hierarchy indexes agree with .super/.implements") { hierarchy(fx) },
        dynamicTest("every workspace invoke and field access resolves") { memberResolution(fx) },
        dynamicTest("usage locations point at lines naming the member") { usageLocations(fx) },
        dynamicTest("find-references from a declaration returns its usages") { referencesFromDeclaration(fx) },
        dynamicTest("find-references on a class returns its subclasses and implementors") { classReferences(fx) },
        dynamicTest("goto-definition on .super and .implements resolves") { directiveNavigation(fx) },
        dynamicTest("hover works on declarations and const-class operands") { hover(fx) },
        dynamicTest("concurrent reads give the same answers as sequential ones") { concurrentReads(fx) },
        dynamicTest("workspace symbol search finds classes by simple name") { symbolSearch(fx) },
        dynamicTest("diagnostics report no syntax errors and no undefined workspace classes") { diagnostics(fx) },
        dynamicTest("re-indexing and remove+add are no-ops") { idempotentReindex(fx) },
    )

    // ---------- 1. indexing ----------

    private fun indexesEveryFile(fx: Fixture) {
        val result = runBlocking { WorkspaceScanner(fx.index).scanDirectory(fx.dir) }
        fx.scanFailed = result.filesSucceeded to result.filesFailed
        assertEquals(0, result.filesFailed, "${fx.dir.name}: files that failed to index")
        assertEquals(fx.smaliFiles.size, result.filesSucceeded, "${fx.dir.name}: files indexed")

        val declaredClasses = fx.smaliFiles.mapNotNull { f -> classDirective.find(f.readText())?.groupValues?.get(1) }.toSet()
        assertEquals(declaredClasses.size, fx.index.getStats().classes, "${fx.dir.name}: distinct .class names vs indexed classes")
        for (f in fx.smaliFiles) assertNotNull(fx.index.findFileByUri(fx.uriOf(f)), "${fx.dir.name}: not indexed by URI: ${f.relativeTo(fx.dir)}")
    }

    // ---------- 2. counts ----------

    private fun memberCounts(fx: Fixture) {
        var methods = 0; var fields = 0
        for (f in fx.smaliFiles) { val t = f.readText(); methods += methodDirective.findAll(t).count(); fields += fieldDirective.findAll(t).count() }
        val stats = fx.index.getStats()
        assertEquals(methods, stats.methods, "${fx.dir.name}: .method directives vs indexed methods")
        assertEquals(fields, stats.fields, "${fx.dir.name}: .field directives vs indexed fields")
    }

    // ---------- 3. hierarchy ----------

    private fun hierarchy(fx: Fixture) {
        val index = fx.index
        val problems = mutableListOf<String>()
        var checked = 0
        for (file in fx.files) {
            val name = file.classDefinition.name
            file.classDefinition.superClass?.let { sup ->
                if (index.hasClass(sup)) { checked++; if (name !in index.getDirectSubclasses(sup)) problems.add("$name missing from subclasses of $sup") }
            }
            for (iface in file.classDefinition.interfaces) {
                if (index.hasClass(iface)) { checked++; if (name !in index.getDirectImplementors(iface)) problems.add("$name missing from implementors of $iface") }
            }
        }
        // and the reverse: every subclass entry names a file whose .super is that class
        for (file in fx.files) {
            for (sub in index.getDirectSubclasses(file.classDefinition.name)) {
                val subFile = index.findClass(sub)
                if (subFile == null || subFile.classDefinition.superClass != file.classDefinition.name) problems.add("$sub listed as subclass of ${file.classDefinition.name} but is not")
            }
        }
        assertTrue(checked > 0, "${fx.dir.name}: no workspace-internal inheritance at all?")
        assertEquals(emptyList<String>(), problems.take(10), "${fx.dir.name}: ${problems.size} hierarchy inconsistencies")
    }

    // ---------- 4. member resolution (goto-definition coverage) ----------

    private fun memberResolution(fx: Fixture) {
        val index = fx.index
        var invokes = 0; var fields = 0
        val missing = mutableListOf<String>()
        for (file in fx.files) for (m in file.methods) for (ins in m.instructions) {
            when (ins) {
                is InvokeInstruction -> if (index.hasClass(ins.className)) {
                    invokes++
                    if (index.findMethod(ins.className, ins.methodName, ins.descriptor).isEmpty()) missing.add("${ins.className}->${ins.methodName}${ins.descriptor}")
                }
                is FieldAccessInstruction -> if (index.hasClass(ins.className)) {
                    fields++
                    if (index.findField(ins.className, ins.fieldName, ins.fieldType) == null) missing.add("${ins.className}->${ins.fieldName}")
                }
                else -> {}
            }
        }
        val total = invokes + fields
        assertTrue(total > 0, "${fx.dir.name}: no workspace-internal member references at all?")
        // Bundled stub classes (android.*/androidx.* copies shipped in the APK) can lack the
        // referenced member; that is the only legitimate miss, and it is rare.
        val missRate = missing.size.toDouble() / total
        assertTrue(missRate <= 0.001, "${fx.dir.name}: ${missing.size}/$total workspace member references unresolved; first: ${missing.take(10)}")
    }

    // ---------- 5. usage locations ----------

    private fun sample(n: Int, size: Int, seed: Long): List<Int> = Random(seed).let { r -> List(minOf(n, size)) { r.nextInt(size) }.distinct() }

    private fun usageLocations(fx: Fixture) {
        val index = fx.index
        val files = fx.files
        var checkedLocations = 0
        val bad = mutableListOf<String>()
        for (i in sample(400, files.size, 1)) {
            val file = files[i]
            for (m in file.methods.take(5)) {
                for (loc in index.findMethodUsages(file.classDefinition.name, m.name, m.descriptor).take(5)) {
                    checkedLocations++
                    val line = index.getLineContent(loc.uri, loc.range.start.line)
                    if (line == null || !line.contains("->${m.name}")) bad.add("${loc.uri.substringAfterLast('/')}:${loc.range.start.line + 1} does not call ${m.name}")
                }
            }
            for (f in file.fields.take(3)) {
                for (loc in index.findFieldUsages(file.classDefinition.name, f.name, f.type).take(5)) {
                    checkedLocations++
                    val line = index.getLineContent(loc.uri, loc.range.start.line)
                    if (line == null || !line.contains("->${f.name}:")) bad.add("${loc.uri.substringAfterLast('/')}:${loc.range.start.line + 1} does not access ${f.name}")
                }
            }
        }
        assertTrue(checkedLocations > 100, "${fx.dir.name}: too few usage locations sampled ($checkedLocations)")
        assertEquals(emptyList<String>(), bad.take(10), "${fx.dir.name}: ${bad.size} of $checkedLocations usage locations are wrong")
    }

    // ---------- 6. references from a declaration ----------

    private fun referencesFromDeclaration(fx: Fixture) {
        val index = fx.index
        val refs = ReferenceProvider(index)
        val files = fx.files
        var checked = 0
        val bad = mutableListOf<String>()
        for (i in sample(300, files.size, 2)) {
            val file = files[i]
            val method = file.methods.firstOrNull { m -> m.name != "<init>" && m.name != "<clinit>" && index.findMethodUsages(file.classDefinition.name, m.name, m.descriptor).isNotEmpty() } ?: continue
            val line = index.getLineContent(file.uri, method.range.start.line) ?: continue
            val col = line.indexOf(method.name + method.descriptor); if (col < 0) continue
            checked++
            val expected = index.findMethodUsages(file.classDefinition.name, method.name, method.descriptor)
            val found = refs.findReferences(file.uri, Position(method.range.start.line, col), includeDeclaration = false).toSet()
            if (!found.containsAll(expected)) bad.add("${file.classDefinition.name}->${method.name}: ${expected.size} usages, ${found.size} returned")
        }
        // a call site of an SDK method: references from the call site are the SDK method's usages
        val sdkFile = files.firstOrNull { f -> f.methods.any { m -> m.instructions.any { it is InvokeInstruction && it.className == "Ljava/lang/Object;" && it.methodName == "<init>" } } }
        if (sdkFile != null) {
            val ins = sdkFile.methods.flatMap { it.instructions }.filterIsInstance<InvokeInstruction>().first { it.className == "Ljava/lang/Object;" && it.methodName == "<init>" }
            val line = index.getLineContent(sdkFile.uri, ins.range.start.line)!!
            val found = refs.findReferences(sdkFile.uri, Position(ins.range.start.line, line.indexOf("<init>") + 1), includeDeclaration = false)
            val expected = index.findMethodUsages("Ljava/lang/Object;", "<init>", "()V")
            checked++
            if (found.size != expected.size) bad.add("Object.<init> call site: ${expected.size} usages, ${found.size} returned")
        }
        assertTrue(checked > 50, "${fx.dir.name}: too few declarations checked ($checked)")
        assertEquals(emptyList<String>(), bad.take(10), "${fx.dir.name}: ${bad.size} of $checked declarations returned incomplete references")
    }

    // ---------- 7. class references ----------

    private fun classReferences(fx: Fixture) {
        val index = fx.index
        val refs = ReferenceProvider(index)
        val withSubs = fx.files.filter { index.getDirectSubclasses(it.classDefinition.name).isNotEmpty() || index.getDirectImplementors(it.classDefinition.name).isNotEmpty() }
        assumeTrue(withSubs.isNotEmpty(), "${fx.dir.name}: no class has workspace subclasses")
        var checked = 0
        val bad = mutableListOf<String>()
        for (i in sample(150, withSubs.size, 3)) {
            val file = withSubs[i]
            val name = file.classDefinition.name
            val line = index.getLineContent(file.uri, file.classDefinition.range.start.line) ?: continue
            val col = line.indexOf(name); if (col < 0) continue
            checked++
            val found = refs.findReferences(file.uri, Position(file.classDefinition.range.start.line, col + 1), includeDeclaration = false)
            for (sub in index.getDirectSubclasses(name)) {
                val subFile = index.findClass(sub) ?: continue
                val r = subFile.classDefinition.superClassRange ?: continue
                if (found.none { it.uri == subFile.uri && it.range.start.line == r.start.line }) bad.add("$name: .super line of $sub not returned")
            }
            for (impl in index.getDirectImplementors(name)) {
                val implFile = index.findClass(impl) ?: continue
                val r = implFile.classDefinition.interfaceRanges[name] ?: continue
                if (found.none { it.uri == implFile.uri && it.range.start.line == r.start.line }) bad.add("$name: .implements line of $impl not returned")
            }
            for (loc in found.take(20)) {
                val text = index.getLineContent(loc.uri, loc.range.start.line)
                if (text == null || !text.contains(name)) bad.add("$name: ${loc.uri.substringAfterLast('/')}:${loc.range.start.line + 1} does not mention it")
            }
        }
        assertTrue(checked > 0)
        assertEquals(emptyList<String>(), bad.take(10), "${fx.dir.name}: ${bad.size} class-reference problems over $checked classes")
    }

    // ---------- 8. directive navigation ----------

    private fun directiveNavigation(fx: Fixture) {
        val index = fx.index
        val defs = DefinitionProvider(index)
        val candidates = fx.files.filter { f -> f.classDefinition.superClass?.let { index.hasClass(it) } == true || f.classDefinition.interfaces.any { index.hasClass(it) } }
        assumeTrue(candidates.isNotEmpty(), "${fx.dir.name}: no workspace-internal inheritance")
        var checked = 0
        val bad = mutableListOf<String>()
        for (i in sample(300, candidates.size, 4)) {
            val file = candidates[i]
            file.classDefinition.superClass?.takeIf { index.hasClass(it) }?.let { sup ->
                val r = file.classDefinition.superClassRange!!
                val line = index.getLineContent(file.uri, r.start.line)!!
                val target = defs.findDefinition(file.uri, Position(r.start.line, line.indexOf(sup) + 1))
                checked++
                if (target.singleOrNull()?.uri != index.getUri(sup)) bad.add("${file.classDefinition.name}: .super $sup -> $target")
            }
            for ((iface, r) in file.classDefinition.interfaceRanges) {
                if (!index.hasClass(iface)) continue
                val line = index.getLineContent(file.uri, r.start.line)!!
                val target = defs.findDefinition(file.uri, Position(r.start.line, line.indexOf(iface) + 1))
                checked++
                if (target.singleOrNull()?.uri != index.getUri(iface)) bad.add("${file.classDefinition.name}: .implements $iface -> $target")
            }
        }
        assertTrue(checked > 50, "${fx.dir.name}: too few directives checked ($checked)")
        assertEquals(emptyList<String>(), bad.take(10), "${fx.dir.name}: ${bad.size} of $checked directive navigations failed")
    }

    // ---------- 9. hover ----------

    private fun hover(fx: Fixture) {
        val index = fx.index
        val hover = HoverProvider(index)
        val files = fx.files
        var checked = 0
        val bad = mutableListOf<String>()
        for (i in sample(300, files.size, 5)) {
            val file = files[i]
            val cls = file.classDefinition
            val classLine = index.getLineContent(file.uri, cls.range.start.line) ?: continue
            checked++
            val h = hover.provideHover(file.uri, Position(cls.range.start.line, classLine.indexOf(cls.name) + 1))
            val text = h?.contents?.right?.value ?: ""
            if (!text.contains(Types.simpleName(cls.name))) bad.add("${cls.name}: class hover = ${text.take(60)}")
            file.methods.firstOrNull()?.let { m ->
                val line = index.getLineContent(file.uri, m.range.start.line) ?: return@let
                val col = line.indexOf(m.name + m.descriptor); if (col < 0) return@let
                checked++
                // position on the first character of the name (names may be a single letter)
                val mh = hover.provideHover(file.uri, Position(m.range.start.line, col))?.contents?.right?.value ?: ""
                if (!mh.contains(m.name)) bad.add("${cls.name}->${m.name}: method hover = ${mh.take(60)}")
            }
        }
        // const-class operands naming workspace classes
        var constClass = 0
        outer@ for (file in files) for (m in file.methods) for (ins in m.instructions) {
            if (ins is TypeInstruction && ins.opcode == "const-class" && index.hasClass(ins.className)) {
                val line = index.getLineContent(file.uri, ins.range.start.line) ?: continue
                val col = line.indexOf(ins.className); if (col < 0) continue
                constClass++; checked++
                val text = hover.provideHover(file.uri, Position(ins.range.start.line, col + 1))?.contents?.right?.value ?: ""
                if (!text.contains(Types.simpleName(ins.className))) bad.add("const-class ${ins.className}: hover = ${text.take(60)}")
                if (constClass >= 100) break@outer
            }
        }
        assertTrue(checked > 100, "${fx.dir.name}: too few hovers checked ($checked)")
        assertEquals(emptyList<String>(), bad.take(10), "${fx.dir.name}: ${bad.size} of $checked hovers wrong")
    }

    // ---------- 10. workspace symbols ----------

    private fun symbolSearch(fx: Fixture) {
        val search = WorkspaceSymbolProvider(fx.index)
        val files = fx.files.filter { Types.simpleName(it.classDefinition.name).length >= 4 }
        assumeTrue(files.isNotEmpty())
        var checked = 0
        val bad = mutableListOf<String>()
        for (i in sample(200, files.size, 6)) {
            val name = files[i].classDefinition.name
            // Query with the last path segment including inner-class parts (e.g. `Outer$Companion`):
            // a bare `Companion` legitimately has thousands of equal-scored matches beyond the 500 cap.
            val query = name.removePrefix("L").removeSuffix(";").substringAfterLast('/')
            checked++
            if (search.search(query).none { it.name == name }) bad.add("$name (query '$query')")
        }
        assertEquals(emptyList<String>(), bad.take(10), "${fx.dir.name}: ${bad.size} of $checked classes not found by simple name")
        // An exact name match (a class, or a member such as Kotlin's `Companion` field) outranks everything else
        val notFirst = sample(50, files.size, 7).map { files[it].classDefinition.name }.filter { name ->
            val query = Types.simpleName(name)
            val first = search.search(query).first()
            val firstName = if (first.kind == SymbolKind.Class) Types.simpleName(first.name) else first.name.substringAfterLast('.')
            !firstName.equals(query, ignoreCase = true)
        }
        assertEquals(emptyList<String>(), notFirst.take(10), "${fx.dir.name}: an exact name match must rank first")
        val all = search.search("")
        assertTrue(all.size <= 500, "empty query must be capped at 500")
        assertEquals(500, all.size, "${fx.dir.name}: empty query should fill the cap on a real app")
    }

    private fun concurrentReads(fx: Fixture) {
        val definitions = DefinitionProvider(fx.index)
        val hovers = HoverProvider(fx.index)
        val references = ReferenceProvider(fx.index)
        val probes = sample(60, fx.files.size, 8).mapNotNull { i ->
            val file = fx.files[i]
            val call = file.methods.asSequence().flatMap { it.instructions.asSequence() }.filterIsInstance<InvokeInstruction>().firstOrNull { it.nameSpan != null } ?: return@mapNotNull null
            file.uri to Position(call.targetLine, call.nameSpan!!.start + 1)
        }
        assumeTrue(probes.isNotEmpty())
        fun answer(p: Pair<String, Position>) = Triple(
            definitions.findDefinition(p.first, p.second),
            hovers.provideHover(p.first, p.second)?.contents?.right?.value,
            references.findReferences(p.first, p.second, true).size
        )
        val expected = probes.map(::answer)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val results = (1..4).flatMap { probes.map { p -> pool.submit<Triple<List<Location>, String?, Int>> { answer(p) } } }
                .map { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }
            results.chunked(probes.size).forEachIndexed { round, got -> assertEquals(expected, got, "${fx.dir.name}: round $round of concurrent reads differs") }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---------- 11. diagnostics ----------

    private fun diagnostics(fx: Fixture) {
        val index = fx.index
        val diag = DiagnosticProvider(SmaliParser(), index)
        val files = fx.files
        var checked = 0
        val bad = mutableListOf<String>()
        for (i in sample(300, files.size, 7)) {
            val file = files[i]
            val content = File(java.net.URI(file.uri)).readText()
            checked++
            for (d in diag.computeDiagnostics(file.uri, content)) {
                if (d.code?.left == "syntax-error") bad.add("${file.classDefinition.name}: syntax error: ${d.message.take(60)}")
                val named = Regex("""'(L[^']+;)'""").find(d.message)?.groupValues?.get(1)
                if (named != null && index.hasClass(named.trimStart('['))) bad.add("${file.classDefinition.name}: flags existing class $named")
            }
        }
        assertTrue(checked > 50)
        assertEquals(emptyList<String>(), bad.take(10), "${fx.dir.name}: ${bad.size} false diagnostics over $checked files")
    }

    // ---------- 12. idempotent re-index ----------

    private fun idempotentReindex(fx: Fixture) {
        val index = fx.index
        val before = index.getStats()
        val files = fx.files
        val probe = files.first { f -> f.methods.any { m -> index.findMethodUsages(f.classDefinition.name, m.name, m.descriptor).isNotEmpty() } }
        val probeMethod = probe.methods.first { m -> index.findMethodUsages(probe.classDefinition.name, m.name, m.descriptor).isNotEmpty() }
        val usagesBefore = index.findMethodUsages(probe.classDefinition.name, probeMethod.name, probeMethod.descriptor).toSet()

        for (i in sample(200, files.size, 8)) index.indexFile(files[i])
        index.indexFile(probe)
        assertEquals(before, index.getStats(), "${fx.dir.name}: stats changed after re-indexing files")
        assertEquals(usagesBefore, index.findMethodUsages(probe.classDefinition.name, probeMethod.name, probeMethod.descriptor).toSet(), "${fx.dir.name}: usages changed after re-index")

        // remove a file and add it back
        val victim = files[sample(1, files.size, 9).first()]
        assertTrue(index.removeFile(victim.uri))
        assertEquals(before.classes - 1, index.getStats().classes)
        index.indexFile(victim)
        assertEquals(before, index.getStats(), "${fx.dir.name}: stats changed after remove + re-add")
    }
}
