package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import xyz.surendrajat.smalilsp.core.ConstStringInstruction
import xyz.surendrajat.smalilsp.core.FieldAccessInstruction
import xyz.surendrajat.smalilsp.core.InvokeInstruction
import xyz.surendrajat.smalilsp.core.JumpInstruction
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.core.Span
import xyz.surendrajat.smalilsp.core.TypeInstruction
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.util.Types
import org.slf4j.LoggerFactory

/**
 * Diagnostics: syntax errors from the parser, label and assembler checks, and, once the index can
 * tell, undefined classes, members no class on the owner's workspace hierarchy declares, and calls
 * or field accesses whose kind contradicts the member's declaration.
 */
class DiagnosticProvider(
    private val parser: SmaliParser,
    private val index: WorkspaceIndex
) {
    private val logger = LoggerFactory.getLogger(DiagnosticProvider::class.java)
    
    /** Every diagnostic for [content]: syntax errors from the parser, then the semantic checks on whatever AST it produced. */
    fun computeDiagnostics(uri: String, content: String): List<Diagnostic> =
        computeDiagnosticsFromParseResult(uri, parser.parseWithErrors(uri, content), content.lines())

    /**
     * Compute diagnostics from an already-parsed result.
     * Avoids re-parsing when the caller already has a ParseResult (e.g. didChange).
     */
    fun computeDiagnosticsFromParseResult(uri: String, parseResult: SmaliParser.ParseResult, lines: List<String>? = null): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        diagnostics.addAll(convertSyntaxErrors(parseResult.syntaxErrors))
        parseResult.smaliFile?.let { file ->
            diagnostics.addAll(localDiagnostics(file, lines))
            diagnostics.addAll(computeSemanticDiagnostics(uri, file))
        }
        return diagnostics
    }

    /**
     * Syntax errors and the file-local checks only: what can be reported while the workspace is
     * still being indexed, when a check against the index would flag classes not yet seen.
     */
    fun computeSyntaxDiagnosticsFromParseResult(parseResult: SmaliParser.ParseResult, lines: List<String>? = null): List<Diagnostic> =
        convertSyntaxErrors(parseResult.syntaxErrors) + (parseResult.smaliFile?.let { localDiagnostics(it, lines) } ?: emptyList())

    /**
     * Checks that need nothing but the file: a jump to a label the method does not define, a
     * label defined twice, and, when the text is at hand, what the assembler would reject
     * ([AssemblerChecks]).
     */
    fun localDiagnostics(file: SmaliFile, lines: List<String>? = null): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        if (lines != null) diagnostics.addAll(AssemblerChecks.diagnostics(file, lines))
        for (method in file.methods) {
            for (ref in method.labelRefs) {
                if (ref.name !in method.labels) {
                    val fix = closestName(ref.name, method.labels.keys)?.let { Fixes.label(":$it") }
                    diagnostics.add(createDiagnostic(ref.span.toRange(), "Label ':${ref.name}' is not defined in this method", DiagnosticSeverity.Error, "undefined-label", fix))
                }
            }
            for (label in method.duplicateLabels) {
                diagnostics.add(createDiagnostic(label.range, "Label ':${label.name}' is defined more than once in this method", DiagnosticSeverity.Error, "duplicate-label", Fixes.removeLabel))
            }
        }
        return diagnostics
    }

    private fun convertSyntaxErrors(errors: List<SmaliParser.SyntaxError>): List<Diagnostic> {
        return errors.map { error ->
            val line = maxOf(0, error.line - 1)
            createDiagnostic(
                range = Range(
                    Position(line, error.charPositionInLine),
                    Position(line, error.charPositionInLine + error.length)
                ),
                message = error.message,
                severity = DiagnosticSeverity.Error,
                code = "syntax-error"
            )
        }
    }
    
    /** Undefined classes, and a call or field access of the wrong kind for the member the workspace declares. */
    private fun computeSemanticDiagnostics(uri: String, file: SmaliFile): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val ownPackage = Types.packageOf(file.classDefinition.name)
        fun isKnownClass(className: String) = isKnownClass(className, ownPackage)
        
        // Check superclass exists
        file.classDefinition.superClass?.let { superClass ->
            if (!isKnownClass(superClass)) {
                diagnostics.add(
                    createDiagnostic(
                        range = file.classDefinition.superClassRange ?: file.classDefinition.nameSpan.toRange(),
                        message = "Superclass '$superClass' not found in workspace or SDK",
                        severity = DiagnosticSeverity.Warning,
                        code = "undefined-class"
                    )
                )
            }
        }
        
        // Check interfaces exist
        file.classDefinition.interfaces.forEach { interfaceName ->
            if (!isKnownClass(interfaceName)) {
                diagnostics.add(
                    createDiagnostic(
                        range = file.classDefinition.interfaceRanges[interfaceName] ?: file.classDefinition.nameSpan.toRange(),
                        message = "Interface '$interfaceName' not found in workspace or SDK",
                        severity = DiagnosticSeverity.Warning,
                        code = "undefined-class"
                    )
                )
            }
        }
        
        // Check the classes instructions name
        for (method in file.methods) {
            for (instruction in method.instructions) {
                val className: String
                val range: Range
                when (instruction) {
                    is InvokeInstruction -> { className = instruction.className; range = instruction.ownerSpan?.toRange() ?: instruction.range; checkCall(instruction, file)?.let(diagnostics::add) }
                    is FieldAccessInstruction -> { className = instruction.className; range = instruction.ownerSpan?.toRange() ?: instruction.range; checkFieldAccess(instruction, file)?.let(diagnostics::add) }
                    is TypeInstruction -> { className = instruction.className; range = instruction.typeSpan?.toRange() ?: instruction.range }
                    is JumpInstruction, is ConstStringInstruction -> continue   // labels are checked above; string literals need nothing
                }
                if (!isKnownClass(className)) {
                    diagnostics.add(createDiagnostic(range, "Class '$className' not found in workspace or SDK", DiagnosticSeverity.Warning, "undefined-class"))
                }
            }
        }
        
        // Check field references
        file.fields.forEach { field ->
            val typeClass = Types.className(field.type)
            if (typeClass != null && !isKnownClass(typeClass)) {
                diagnostics.add(
                    createDiagnostic(
                        range = field.typeSpan.toRange(),
                        message = "Type '$typeClass' not found in workspace or SDK",
                        severity = DiagnosticSeverity.Information,
                        code = "undefined-class"
                    )
                )
            }
        }
        
        return diagnostics
    }
        
    /**
     * A call: `invoke-static` on an instance method, or an instance call on a static one, decidable
     * when a workspace class declares the method (the fix named is the call the member needs:
     * `invoke-direct` for a private method or a constructor, `invoke-interface` for an interface's,
     * `invoke-virtual` otherwise, keeping the `/range` form); else the method it names, when no
     * class on the owner's workspace hierarchy declares it.
     */
    private fun checkCall(call: InvokeInstruction, file: SmaliFile): Diagnostic? {
        // a method handle or an invoke-custom site names a method without calling it this way
        if (!call.opcode.startsWith("invoke-") || call.opcode.startsWith("invoke-custom") || call.opcode.startsWith("invoke-polymorphic")) return null
        val target = index.resolveMethod(call.className, call.methodName, call.descriptor) ?: return undefinedMethod(call, file)
        val static = call.opcode.startsWith("invoke-static")
        if (("static" in target.method.modifiers) == static) return null
        val use = when {
            !static -> "invoke-static"
            target.method.isConstructor || "private" in target.method.modifiers -> "invoke-direct"
            "interface" in target.file.classDefinition.modifiers -> "invoke-interface"
            else -> "invoke-virtual"
        } + if (call.opcode.endsWith("/range")) "/range" else ""
        val member = if (static) "an instance method" else "a static method"
        return createDiagnostic(opcodeRange(call.startLine, call.startChar, call.opcode), "${call.opcode} calls ${call.methodName}${call.descriptor}, $member; use $use", DiagnosticSeverity.Error, "invoke-kind", Fixes.opcode(use))
    }

    /** A field access: `sget`/`sput` on an instance field or `iget`/`iput` on a static one, decidable when a workspace class declares the field; else the field it names, when none on the owner's workspace hierarchy does. */
    private fun checkFieldAccess(access: FieldAccessInstruction, file: SmaliFile): Diagnostic? {
        if (access.opcode == "const-method-handle") return null   // a field handle, not an access
        val target = index.resolveField(access.className, access.fieldName, access.fieldType) ?: return undefinedField(access, file)
        val static = access.opcode[0] == 's'
        if (("static" in target.field.modifiers) == static) return null
        val use = (if (static) "i" else "s") + access.opcode.substring(1)
        val member = if (static) "an instance field" else "a static field"
        return createDiagnostic(opcodeRange(access.startLine, access.startChar, access.opcode), "${access.opcode} on ${access.fieldName}, $member; use $use", DiagnosticSeverity.Error, "field-access-kind", Fixes.opcode(use))
    }

    /** An instruction's range starts at its opcode. */
    private fun opcodeRange(line: Int, start: Int, opcode: String) = Range(Position(line, start), Position(line, start + opcode.length))

    /**
     * A call to a method that neither the owner nor any supertype declares, a hand-edit typo the
     * assembler accepts and the runtime rejects. Told only when the owner is a workspace class and
     * every supertype up to `Object` is too, since one outside may declare it; a constructor is the
     * owner's alone. The closest declared method, by descriptor or by name, is the quick fix.
     */
    private fun undefinedMethod(call: InvokeInstruction, file: SmaliFile): Diagnostic? {
        val wanted = call.methodName + call.descriptor
        val walked = lookup(call.className, file, ownerOnly = call.methodName.startsWith("<"), inObject = wanted in OBJECT_METHODS) { f -> f.methods.any { it.name == call.methodName && it.descriptor == call.descriptor } }
            ?: return null
        val suggestion = closest(call.methodName, call.descriptor, walked.flatMap { f -> f.methods.map { it.name to it.descriptor } })?.let { it.first + it.second }
        return undefinedMember("Method", wanted, call.className, call.nameSpan, call.range, suggestion)
    }

    /** The field counterpart of [undefinedMethod]. */
    private fun undefinedField(access: FieldAccessInstruction, file: SmaliFile): Diagnostic? {
        val wanted = access.fieldName + ":" + access.fieldType
        val walked = lookup(access.className, file, ownerOnly = false, inObject = false) { f -> f.fields.any { it.name == access.fieldName && it.type == access.fieldType } }
            ?: return null
        val suggestion = closest(access.fieldName, access.fieldType, walked.flatMap { f -> f.fields.map { it.name to it.type } })?.let { it.first + ":" + it.second }
        return undefinedMember("Field", wanted, access.className, access.nameSpan, access.range, suggestion)
    }

    private fun undefinedMember(kind: String, wanted: String, owner: String, nameSpan: Span?, instruction: Range, suggestion: String?): Diagnostic {
        val range = nameSpan?.let { Range(Position(it.line, it.start), Position(it.line, it.start + wanted.length)) } ?: instruction
        val message = "$kind $wanted is not declared by $owner or a supertype in the workspace" + (suggestion?.let { "; the closest is $it" } ?: "")
        return createDiagnostic(range, message, DiagnosticSeverity.Warning, "undefined-member", if (suggestion != null && nameSpan != null) Fixes.member(suggestion) else null)
    }

    /**
     * The workspace classes on [owner]'s hierarchy when none of them [declares] the member and the
     * hierarchy stays in the workspace up to `Object` ([inObject] says whether `Object` declares
     * it); null when the member is declared, when [owner] is an array or outside the workspace, or
     * when a supertype is, since it may declare the member. The file being checked stands in for
     * its own class, which the index may hold in an older form.
     */
    private fun lookup(owner: String, file: SmaliFile, ownerOnly: Boolean, inObject: Boolean, declares: (SmaliFile) -> Boolean): List<SmaliFile>? {
        if (owner != file.classDefinition.name && !index.hasClass(owner)) return null
        val walked = ArrayList<SmaliFile>()
        val seen = HashSet<String>()
        val pending = ArrayDeque<String>().apply { add(owner) }
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (!seen.add(name)) continue
            if (name == "Ljava/lang/Object;") { if (inObject) return null; continue }
            // a platform-named class in the workspace is a stub or a desugared copy: the runtime's own declares more
            if (Types.isSdkClass(name)) return null
            val f = (if (name == file.classDefinition.name) file else index.findClass(name)) ?: return null
            if (declares(f)) return null
            walked.add(f)
            if (ownerOnly) break
            f.classDefinition.superClass?.let(pending::add)
            pending.addAll(f.classDefinition.interfaces)
        }
        return walked
    }

    /** The declared member closest to the one named: the same name with another descriptor, else a name within two edits with the same descriptor; null when none or when two are as close. */
    private fun closest(name: String, descriptor: String, declared: List<Pair<String, String>>): Pair<String, String>? {
        val sameName = declared.filter { it.first == name }.distinct()
        if (sameName.isNotEmpty()) return sameName.singleOrNull()
        val sameDescriptor = declared.filter { it.second == descriptor }.distinct()
        return closestName(name, sameDescriptor.map { it.first })?.let { found -> sameDescriptor.first { it.first == found } }
    }

    /** The name within two edits of [name], for a typo fix; null when none is that close or two are equally close. */
    private fun closestName(name: String, names: Collection<String>): String? {
        var best: String? = null
        var bestDistance = 3
        var tie = false
        for (candidate in names) {
            if (kotlin.math.abs(candidate.length - name.length) > 2) continue
            val d = editDistance(name, candidate)
            if (d < bestDistance) { best = candidate; bestDistance = d; tie = false } else if (d == bestDistance) tie = true
        }
        return if (tie) null else best
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            val swap = previous; previous = current; current = swap
        }
        return previous[b.length]
    }

    /**
     * A class reference is reported only when it looks like a mistake: the class is not in the
     * workspace, not a platform class, and its package is one the workspace defines classes in.
     * A decompiled app also references classes it does not ship, such as `org.xmlpull` from the
     * platform or an optional library like Conscrypt behind a runtime check, and those are not
     * errors; a hand-edited name inside the app's own packages is. The file's own package counts
     * as the workspace's whether or not the file is indexed yet. Array brackets are stripped:
     * a check-cast to `[Lcom/app/Foo;` is checked against the index key `Lcom/app/Foo;`.
     */
    private fun isKnownClass(className: String, ownPackage: String): Boolean {
        if (Types.isSdkClass(className)) return true
        val base = className.trimStart('[')
        if (index.hasClass(base)) return true
        val pkg = Types.packageOf(base)
        return pkg != ownPackage && !index.hasPackage(pkg)
    }

    private fun createDiagnostic(range: Range, message: String, severity: DiagnosticSeverity, code: String, fix: Map<String, Any>? = null): Diagnostic =
        Diagnostic(range, message, severity, "smali-lsp", code).apply { if (fix != null) data = fix }

    private companion object {
        /** What `java.lang.Object` declares, the one class every hierarchy ends in without being in the workspace. */
        val OBJECT_METHODS = setOf("<init>()V", "equals(Ljava/lang/Object;)Z", "hashCode()I", "toString()Ljava/lang/String;", "getClass()Ljava/lang/Class;", "clone()Ljava/lang/Object;", "finalize()V", "notify()V", "notifyAll()V", "wait()V", "wait(J)V", "wait(JI)V")
    }
}
