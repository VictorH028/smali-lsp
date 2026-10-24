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
import xyz.surendrajat.smalilsp.core.TypeInstruction
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.util.Types
import org.slf4j.LoggerFactory

/**
 * Diagnostics: syntax errors from the parser, label and assembler checks, and, once the index can
 * tell, undefined classes and calls or field accesses whose kind contradicts the member's declaration.
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
                    diagnostics.add(createDiagnostic(ref.span.toRange(), "Label ':${ref.name}' is not defined in this method", DiagnosticSeverity.Error, "undefined-label"))
                }
            }
            for (label in method.duplicateLabels) {
                diagnostics.add(createDiagnostic(label.range, "Label ':${label.name}' is defined more than once in this method", DiagnosticSeverity.Error, "duplicate-label"))
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
                    is InvokeInstruction -> { className = instruction.className; range = instruction.ownerSpan?.toRange() ?: instruction.range; invokeKind(instruction)?.let(diagnostics::add) }
                    is FieldAccessInstruction -> { className = instruction.className; range = instruction.ownerSpan?.toRange() ?: instruction.range; fieldAccessKind(instruction)?.let(diagnostics::add) }
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
     * `invoke-static` on an instance method, or an instance call on a static one, decidable when
     * a workspace class declares the method. The fix named is the call the member needs:
     * `invoke-direct` for a private method or a constructor, `invoke-interface` for an
     * interface's, `invoke-virtual` otherwise, keeping the `/range` form.
     */
    private fun invokeKind(call: InvokeInstruction): Diagnostic? {
        val static = call.opcode.startsWith("invoke-static")
        val target = index.resolveMethod(call.className, call.methodName, call.descriptor) ?: return null
        if (("static" in target.method.modifiers) == static) return null
        val use = when {
            !static -> "invoke-static"
            target.method.isConstructor || "private" in target.method.modifiers -> "invoke-direct"
            "interface" in target.file.classDefinition.modifiers -> "invoke-interface"
            else -> "invoke-virtual"
        } + if (call.opcode.endsWith("/range")) "/range" else ""
        val member = if (static) "an instance method" else "a static method"
        return createDiagnostic(opcodeRange(call.startLine, call.startChar, call.opcode), "${call.opcode} calls ${call.methodName}${call.descriptor}, $member; use $use", DiagnosticSeverity.Error, "invoke-kind")
    }

    /** `sget`/`sput` on an instance field or `iget`/`iput` on a static one, decidable when a workspace class declares the field. */
    private fun fieldAccessKind(access: FieldAccessInstruction): Diagnostic? {
        val static = access.opcode[0] == 's'
        val target = index.resolveField(access.className, access.fieldName, access.fieldType) ?: return null
        if (("static" in target.field.modifiers) == static) return null
        val use = (if (static) "i" else "s") + access.opcode.substring(1)
        val member = if (static) "an instance field" else "a static field"
        return createDiagnostic(opcodeRange(access.startLine, access.startChar, access.opcode), "${access.opcode} on ${access.fieldName}, $member; use $use", DiagnosticSeverity.Error, "field-access-kind")
    }

    /** An instruction's range starts at its opcode. */
    private fun opcodeRange(line: Int, start: Int, opcode: String) = Range(Position(line, start), Position(line, start + opcode.length))

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

    private fun createDiagnostic(
        range: Range,
        message: String,
        severity: DiagnosticSeverity,
        code: String
    ): Diagnostic {
        return Diagnostic(range, message, severity, "smali-lsp", code)
    }
}
