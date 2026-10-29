package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.slf4j.LoggerFactory
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser

/**
 * Rename of methods (every declaration in the family — overrides included — and every call
 * site that binds to one of them), fields (declaration and every access) and labels (definition and every reference in the method,
 * including `.catch` blocks and switch tables). Classes are not renamed: that would mean
 * renaming the file and rewriting the package path everywhere. `<init>`/`<clinit>` are fixed
 * names. Members of classes that are not in the workspace are not renamed either, since their
 * declaration is unreachable and call sites would stop matching it. A new name is checked by
 * parsing it as the assembler would, and one it would not read is answered with an error naming
 * it, so the editor can say why nothing happened.
 *
 * Every edit is a token span from the AST, so no file text is read.
 */
class RenameProvider(
    private val index: WorkspaceIndex
) {
    private val logger = LoggerFactory.getLogger(RenameProvider::class.java)
    private val references = ReferenceProvider(index)

    /** The current name and its range when the symbol at the caret can be renamed; null otherwise. */
    fun prepareRename(uri: String, position: Position): Either<Range, PrepareRenameResult>? {
        val file = index.findFileByUri(uri) ?: return null
        val target = resolveRenameTarget(file, uri, position) ?: return null
        return Either.forRight(PrepareRenameResult(target.range, target.currentName))
    }

    /** The edit, or null when nothing at the caret can be renamed; a name the assembler would not read is a [ResponseErrorException]. */
    fun rename(uri: String, position: Position, newName: String): WorkspaceEdit? {
        val file = index.findFileByUri(uri) ?: return null
        val target = resolveRenameTarget(file, uri, position) ?: return null

        if (newName.isBlank()) throw invalid("A name is required")
        val valid = when (target) {
            is RenameTarget.Method, is RenameTarget.Field -> isValidMemberName(newName)
            is RenameTarget.Label -> isValidLabelName(newName)
        }
        if (!valid) throw invalid("'$newName' is not a smali ${if (target is RenameTarget.Label) "label" else "member"} name")

        val edits = mutableMapOf<String, MutableList<TextEdit>>()
        when (target) {
            is RenameTarget.Method -> collectMethodRenameEdits(target, newName, edits)
            is RenameTarget.Field -> collectFieldRenameEdits(target, newName, edits)
            is RenameTarget.Label -> collectLabelRenameEdits(target, newName, edits)
        }
        return if (edits.isNotEmpty()) WorkspaceEdit(edits) else null
    }

    private sealed class RenameTarget(val currentName: String, val range: Range) {
        class Method(name: String, range: Range, val className: String, val descriptor: String) : RenameTarget(name, range)
        class Field(name: String, range: Range, val className: String, val type: String) : RenameTarget(name, range)
        class Label(name: String, range: Range, val uri: String, val method: MethodDefinition) : RenameTarget(name, range)
    }

    private fun resolveRenameTarget(file: SmaliFile, uri: String, position: Position): RenameTarget? =
        when (val symbol = file.symbolAt(position)) {
            is Symbol.MethodDecl -> symbol.method
                .takeUnless { it.name == "<init>" || it.name == "<clinit>" }
                ?.let { RenameTarget.Method(it.name, symbol.range, file.classDefinition.name, it.descriptor) }
            is Symbol.FieldDecl -> RenameTarget.Field(symbol.fieldDef.name, symbol.range, file.classDefinition.name, symbol.fieldDef.type)
            // A reference renames the member it resolves to, which may be declared in a superclass
            is Symbol.MethodRef -> symbol.instruction
                .takeIf { it.methodName != "<init>" && it.methodName != "<clinit>" }
                ?.let { ref -> index.resolveMethod(ref.className, ref.methodName, ref.descriptor)?.let { RenameTarget.Method(ref.methodName, symbol.range, it.file.classDefinition.name, ref.descriptor) } }
            is Symbol.FieldRef -> symbol.instruction
                .let { ref -> index.resolveField(ref.className, ref.fieldName, ref.fieldType)?.let { RenameTarget.Field(ref.fieldName, symbol.range, it.file.classDefinition.name, ref.fieldType) } }
            // Labels are method-local; a reference to a label the method does not define is left alone
            is Symbol.LabelDecl -> RenameTarget.Label(symbol.label.name, withoutColon(symbol.label.range), uri, symbol.method)
            is Symbol.LabelRef -> symbol.method.labels[symbol.name]?.let { RenameTarget.Label(symbol.name, withoutColon(symbol.range), uri, symbol.method) }
            else -> null
        }

    /** A label range covers `:name`; the edit replaces only the name. */
    private fun withoutColon(range: Range) = Range(Position(range.start.line, range.start.character + 1), range.end)

    private fun collectMethodRenameEdits(target: RenameTarget.Method, newName: String, edits: MutableMap<String, MutableList<TextEdit>>) =
        collectFamilyEdits(references.methodFamily(target.className, target.currentName, target.descriptor), target.currentName, newName, edits)

    private fun collectFieldRenameEdits(target: RenameTarget.Field, newName: String, edits: MutableMap<String, MutableList<TextEdit>>) =
        collectFamilyEdits(references.fieldFamily(target.className, target.currentName, target.type), target.currentName, newName, edits)

    /** Every declaration of the member (overrides included) and every site that binds to one of them. */
    private fun collectFamilyEdits(family: MemberFamily, name: String, newName: String, edits: MutableMap<String, MutableList<TextEdit>>) {
        for (declaration in family.declarations) edits.add(declaration.file.uri, TextEdit(declaration.nameSpan.toRange(), newName))
        for (usage in family.usages) addUsageEdit(usage, name, newName, edits)
    }

    /** Usage locations are the member-name tokens; anything else is a reference the AST could not place and is skipped. */
    private fun addUsageEdit(usage: Location, name: String, newName: String, edits: MutableMap<String, MutableList<TextEdit>>) {
        val range = usage.range
        if (range.start.line != range.end.line || range.end.character - range.start.character != name.length) {
            logger.warn("Skipping a reference to $name at ${usage.uri}:${range.start.line + 1} without a name token")
            return
        }
        edits.add(usage.uri, TextEdit(range, newName))
    }

    private fun collectLabelRenameEdits(target: RenameTarget.Label, newName: String, edits: MutableMap<String, MutableList<TextEdit>>) {
        target.method.labels[target.currentName]?.let { edits.add(target.uri, TextEdit(withoutColon(it.range), newName)) }
        for (ref in target.method.labelRefs) {
            if (ref.name == target.currentName) edits.add(target.uri, TextEdit(ref.nameSpan.toRange(), newName))
        }
    }

    private fun MutableMap<String, MutableList<TextEdit>>.add(uri: String, edit: TextEdit) {
        getOrPut(uri) { mutableListOf() }.add(edit)
    }

    private fun invalid(message: String) = ResponseErrorException(ResponseError(ResponseErrorCode.InvalidParams, message, null))

    companion object {
        /**
         * Whether the parser reads [name] as a member name, which is what matters: letters, digits,
         * `$`, `_`, `-` and anything beyond ASCII, a leading digit included, as the assembler and
         * obfuscators have it. `<init>` and `<clinit>` are not names to rename to.
         */
        fun isValidMemberName(name: String): Boolean {
            if (name.startsWith("<")) return false
            val parsed = SmaliParser().parseWithErrors("file:///rename.smali", ".class Lx;\n.method public $name()V\n.end method\n")
            return parsed.syntaxErrors.isEmpty() && parsed.smaliFile?.methods?.singleOrNull()?.name == name
        }

        /** Whether the parser reads `:name` as a label definition. */
        fun isValidLabelName(name: String): Boolean {
            val parsed = SmaliParser().parseWithErrors("file:///rename.smali", ".class Lx;\n.method public a()V\n:$name\n.end method\n")
            return parsed.syntaxErrors.isEmpty() && parsed.smaliFile?.methods?.singleOrNull()?.labels?.containsKey(name) == true
        }
    }
}
