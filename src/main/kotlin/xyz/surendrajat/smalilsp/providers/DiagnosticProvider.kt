package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.util.Types
import org.slf4j.LoggerFactory

/**
 * Provides diagnostic information for Smali files.
 * 
 * Two types of diagnostics:
 * 1. Syntax errors from parser (ANTLR errors)
 * 2. Semantic errors from analysis (undefined references, etc.)
 */
class DiagnosticProvider(
    private val parser: SmaliParser,
    private val index: WorkspaceIndex
) {
    private val logger = LoggerFactory.getLogger(DiagnosticProvider::class.java)
    
    /** Every diagnostic for [content]: syntax errors from the parser, then the semantic checks on whatever AST it produced. */
    fun computeDiagnostics(uri: String, content: String): List<Diagnostic> =
        computeDiagnosticsFromParseResult(uri, parser.parseWithErrors(uri, content), content)

    /**
     * Compute diagnostics from an already-parsed result.
     * Avoids re-parsing when the caller already has a ParseResult (e.g. didChange).
     */
    fun computeDiagnosticsFromParseResult(uri: String, parseResult: SmaliParser.ParseResult, content: String? = null): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        diagnostics.addAll(convertSyntaxErrors(parseResult.syntaxErrors))
        parseResult.smaliFile?.let { file ->
            diagnostics.addAll(localDiagnostics(file, content?.lines()))
            diagnostics.addAll(computeSemanticDiagnostics(uri, file))
        }
        return diagnostics
    }

    /**
     * Syntax errors and the file-local checks only: what can be reported while the workspace is
     * still being indexed, when a check against the index would flag classes not yet seen.
     */
    fun computeSyntaxDiagnosticsFromParseResult(parseResult: SmaliParser.ParseResult, content: String? = null): List<Diagnostic> =
        convertSyntaxErrors(parseResult.syntaxErrors) + (parseResult.smaliFile?.let { localDiagnostics(it, content?.lines()) } ?: emptyList())

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
    
    /**
     * Compute semantic diagnostics for a parsed file.
     * Checks for undefined references, missing classes, etc.
     */
    private fun computeSemanticDiagnostics(uri: String, file: SmaliFile): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        
        // Check superclass exists
        file.classDefinition.superClass?.let { superClass ->
            if (!index.hasClass(superClass) && !Types.isSdkClass(superClass)) {
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
            if (!index.hasClass(interfaceName) && !Types.isSdkClass(interfaceName)) {
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
        
        // Check method invocations
        file.methods.forEach { method ->
            method.instructions.forEach { instruction ->
                when (instruction) {
                    is xyz.surendrajat.smalilsp.core.InvokeInstruction -> {
                        if (!isKnownClass(instruction.className)) {
                            diagnostics.add(
                                createDiagnostic(
                                    range = instruction.ownerSpan?.toRange() ?: instruction.range,
                                    message = "Class '${instruction.className}' not found in workspace or SDK",
                                    severity = DiagnosticSeverity.Warning,
                                    code = "undefined-class"
                                )
                            )
                        }
                    }
                    is xyz.surendrajat.smalilsp.core.FieldAccessInstruction -> {
                        if (!isKnownClass(instruction.className)) {
                            diagnostics.add(
                                createDiagnostic(
                                    range = instruction.ownerSpan?.toRange() ?: instruction.range,
                                    message = "Class '${instruction.className}' not found in workspace or SDK",
                                    severity = DiagnosticSeverity.Warning,
                                    code = "undefined-class"
                                )
                            )
                        }
                    }
                    is xyz.surendrajat.smalilsp.core.TypeInstruction -> {
                        if (!isKnownClass(instruction.className)) {
                            diagnostics.add(
                                createDiagnostic(
                                    range = instruction.typeSpan?.toRange() ?: instruction.range,
                                    message = "Class '${instruction.className}' not found in workspace or SDK",
                                    severity = DiagnosticSeverity.Warning,
                                    code = "undefined-class"
                                )
                            )
                        }
                    }
                    is xyz.surendrajat.smalilsp.core.JumpInstruction -> {
                        // JumpInstructions reference labels within the same method
                        // Label validation could be added here if needed
                    }
                    is xyz.surendrajat.smalilsp.core.ConstStringInstruction -> {
                        // String literals don't need diagnostics
                    }
                }
            }
        }
        
        // Check field references
        file.fields.forEach { field ->
            val typeClass = Types.className(field.type)
            if (typeClass != null && !index.hasClass(typeClass) && !Types.isSdkClass(typeClass)) {
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
     * A class reference resolves if its base type (array brackets stripped) is defined
     * in the workspace, or it is an SDK/primitive type. check-cast/instance-of/new-array
     * operands like "[Lcom/app/Foo;" must be checked against the index key "Lcom/app/Foo;"
     * (isSDKClass already strips brackets itself).
     */
    private fun isKnownClass(className: String): Boolean {
        return Types.isSdkClass(className) || index.hasClass(className.trimStart('['))
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
