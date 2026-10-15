package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.data.DalvikOpcodeDatabase
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.util.Types

/**
 * Hover: class hierarchy, member signatures, field types, primitive types, labels, string
 * literals, and Dalvik opcode documentation, as Markdown. The symbol under the caret comes from
 * [SmaliFile.symbolAt]; registers, resource ids and the opcode lookup read the line text,
 * because they have no AST node.
 */
class HoverProvider(
    private val workspaceIndex: WorkspaceIndex
) {
    private val references = ReferenceProvider(workspaceIndex)

    fun provideHover(uri: String, position: Position): Hover? {
        val file = workspaceIndex.findFileByUri(uri) ?: return null
        return when (val symbol = file.symbolAt(position)) {
            null -> hoverForRegister(uri, file, position) ?: hoverForResource(uri, position) ?: hoverForOpcodeKeyword(uri, position)
            is Symbol.ClassDecl -> hoverForClass(symbol.classDef)
            is Symbol.ClassRef -> hoverForClassReference(symbol.type)
            is Symbol.MethodDecl -> hoverForMethod(symbol.method, file.classDefinition.name)
            is Symbol.FieldDecl -> hoverForField(symbol.fieldDef, file.classDefinition.name)
            is Symbol.MethodRef -> hoverForMethodReference(symbol.instruction.className, symbol.instruction.methodName, symbol.instruction.descriptor)
            is Symbol.FieldRef -> hoverForFieldReference(symbol.instruction.className, symbol.instruction.fieldName, symbol.instruction.fieldType)
            is Symbol.Primitive -> hoverForPrimitiveType(symbol.type)
            is Symbol.LabelDecl -> hoverForLabel(symbol.label, symbol.method)
            is Symbol.LabelRef -> symbol.method.labels[symbol.name]?.let { hoverForLabel(it, symbol.method) }
                ?: Hover(MarkupContent(MarkupKind.MARKDOWN, "**Jump to label:** `:${symbol.name}`"))
            is Symbol.OnInstruction -> hoverForRegister(uri, file, position) ?: hoverForOpcodeKeyword(uri, position) ?: hoverForString(symbol.instruction)
        }
    }

    /** A string literal: its text and how many `const-string` instructions in the workspace load the same value. */
    private fun hoverForString(instruction: Instruction): Hover? {
        if (instruction !is ConstStringInstruction) return null
        val value = instruction.value
        val shown = if (value.length > 200) value.substring(0, 199) + "\u2026" else value
        val md = "**String:** ${codeSpan("\"$shown\"")}\n\n**References:** ${workspaceIndex.findStringUsages(value).size}"
        return Hover(MarkupContent(MarkupKind.MARKDOWN, md))
    }

    /** [text] as a Markdown code span, fenced with one more backtick than its longest run of them. */
    private fun codeSpan(text: String): String {
        var longest = 0
        var run = 0
        for (c in text) { run = if (c == '`') run + 1 else 0; longest = maxOf(longest, run) }
        val fence = "`".repeat(longest + 1)
        return "$fence$text$fence"
    }

    /** The resource id under the caret, named by the workspace's `public.xml`. */
    private fun hoverForResource(uri: String, position: Position): Hover? =
        ResourceLiterals.at(workspaceIndex, uri, position)?.let { ResourceLiterals.hover(workspaceIndex, it) }

    /** The register under the caret: which parameter it carries, or its local slot and debug name. */
    private fun hoverForRegister(uri: String, file: SmaliFile, position: Position): Hover? {
        val lines = workspaceIndex.getLines(uri) ?: return null
        val (method, register) = Registers.at(file, lines, position) ?: return null
        return Registers.hover(method, file.classDefinition.name, lines, register, position.line)
    }

    private fun hoverForLabel(labelDef: LabelDefinition, method: MethodDefinition): Hover {
        val referenceCount = method.labelRefs.count { it.name == labelDef.name }
        val md = buildString {
            append("**Label:** `:${labelDef.name}`\n\n")
            append("**References:** $referenceCount\n\n")
            val labelType = when {
                labelDef.name.startsWith("cond_") -> "Conditional branch target"
                labelDef.name.startsWith("goto_") -> "Unconditional branch target"
                labelDef.name.startsWith("try_start") -> "Try block start"
                labelDef.name.startsWith("try_end") -> "Try block end"
                labelDef.name.startsWith("catch_") -> "Exception handler"
                labelDef.name.startsWith("catchall_") -> "Catch-all exception handler"
                labelDef.name.startsWith("switch_") -> "Switch branch target"
                labelDef.name.startsWith("pswitch_") -> "Packed switch target"
                labelDef.name.startsWith("sswitch_") -> "Sparse switch target"
                labelDef.name.startsWith("array_") -> "Array data target"
                else -> "Branch target"
            }
            append("**Type:** $labelType")
        }
        return Hover(MarkupContent(MarkupKind.MARKDOWN, md))
    }

    private fun hoverForClass(classDef: ClassDefinition): Hover? {
        val file = workspaceIndex.findClass(classDef.name) ?: return null
        val md = buildString {
            append("**Class:** `${Types.readableName(file.classDefinition.name)}`\n\n")
            if (file.classDefinition.modifiers.isNotEmpty()) {
                append("**Modifiers:** ${file.classDefinition.modifiers.joinToString(", ")}\n\n")
            }
            file.classDefinition.superClass?.let {
                append("**Extends:** `${Types.readableName(it)}`\n\n")
            }
            if (file.classDefinition.interfaces.isNotEmpty()) {
                append("**Implements:**\n")
                file.classDefinition.interfaces.forEach { iface ->
                    append("- `${Types.readableName(iface)}`\n")
                }
                append("\n")
            }
            append("**Methods:** ${file.methods.size}  ")
            append("**Fields:** ${file.fields.size}")
        }
        return Hover(MarkupContent(MarkupKind.MARKDOWN, md))
    }

    /** The declaration's signature, where it is declared, and how many calls bind to it (through subclasses and overrides). */
    private fun hoverForMethod(method: MethodDefinition, className: String): Hover {
        val md = buildString {
            append("**Method:** `${method.name}`\n\n")
            append("**Declared in:** `${Types.readableName(className)}`\n\n")
            if (method.modifiers.isNotEmpty()) {
                append("**Modifiers:** ${method.modifiers.joinToString(", ")}\n\n")
            }
            val params = method.parameters.joinToString(", ") { Types.readableName(it.type) }
            val returnType = Types.readableName(method.returnType)
            append("**Signature:** `($params)$returnType`\n\n")
            if (method.parameters.isNotEmpty()) {
                append("**Parameters:**\n")
                method.parameters.forEach { param ->
                    append("- `${Types.readableName(param.type)}`")
                    param.name?.let { append(" $it") }
                    append("\n")
                }
                append("\n")
            }
            append("**Returns:** `$returnType`\n\n")
            append("**References:** ${references.methodFamily(className, method.name, method.descriptor).usages.size}")
        }
        return Hover(MarkupContent(MarkupKind.MARKDOWN, md))
    }

    /** The field's type, where it is declared, and how many accesses bind to it. */
    private fun hoverForField(field: FieldDefinition, className: String): Hover {
        val md = buildString {
            append("**Field:** `${field.name}`\n\n")
            append("**Declared in:** `${Types.readableName(className)}`\n\n")
            if (field.modifiers.isNotEmpty()) {
                append("**Modifiers:** ${field.modifiers.joinToString(", ")}\n\n")
            }
            append("**Type:** `${Types.readableName(field.type)}`\n\n")
            append("**References:** ${references.fieldFamily(className, field.name, field.type).usages.size}")
        }
        return Hover(MarkupContent(MarkupKind.MARKDOWN, md))
    }

    /** Opcode documentation when the caret is on the first word of an instruction line. */
    private fun hoverForOpcodeKeyword(uri: String, position: Position): Hover? {
        val lineContent = workspaceIndex.getLineContent(uri, position.line) ?: return null
        val trimmed = lineContent.trimStart()
        if (trimmed.isEmpty() || trimmed.startsWith(".") || trimmed.startsWith("#") || trimmed.startsWith(":")) {
            return null
        }
        val opcodeStart = lineContent.length - trimmed.length
        val opcodeEnd = opcodeStart + trimmed.indexOf(' ').let { if (it == -1) trimmed.length else it }
        if (position.character < opcodeStart || position.character >= opcodeEnd) return null
        val info = DalvikOpcodeDatabase.lookup(lineContent.substring(opcodeStart, opcodeEnd)) ?: return null
        return Hover(MarkupContent(MarkupKind.MARKDOWN, info.toMarkdown()))
    }

    /** [type] may be an array; the hover describes its element class. */
    fun hoverForClassReference(type: String): Hover? {
        if (Types.isPrimitive(type)) return hoverForPrimitiveType(type)
        val file = workspaceIndex.findClass(Types.className(type) ?: type)
        if (file != null) return hoverForClass(file.classDefinition)
        return unresolved("Class", type, "**Class:** `${Types.readableName(type)}`")
    }

    fun hoverForMethodReference(className: String, methodName: String, descriptor: String): Hover? {
        workspaceIndex.resolveMethod(className, methodName, descriptor)?.let { return hoverForMethod(it.method, it.file.classDefinition.name) }
        return unresolved("Method", className, "**Method:** `${className}.${methodName}`", "**Descriptor:** `${descriptor}`")
    }

    fun hoverForFieldReference(className: String, fieldName: String, fieldType: String): Hover? {
        workspaceIndex.resolveField(className, fieldName, fieldType)?.let { return hoverForField(it.field, it.file.classDefinition.name) }
        return unresolved("Field", className, "**Field:** `${className}.${fieldName}`", "**Type:** `${Types.readableName(fieldType)}`")
    }

    /** The hover for a class or member the workspace does not define: a platform one, or one the app does not ship. */
    private fun unresolved(kind: String, className: String, vararg lines: String): Hover {
        val md = buildString {
            for (line in lines) append(line).append("\n\n")
            append(if (Types.isSdkClass(className)) "*SDK $kind* - Part of Android SDK or Java standard library" else "*Not found in workspace*")
        }
        return Hover(MarkupContent(MarkupKind.MARKDOWN, md))
    }

    private fun hoverForPrimitiveType(type: String): Hover {
        val arrayDim = type.takeWhile { it == '[' }.length
        val base = type.trimStart('[')
        val typeName = Types.readableName(base)
        val description = when (base.firstOrNull()) {
            'I' -> "32-bit signed integer"
            'J' -> "64-bit signed integer"
            'Z' -> "true or false"
            'B' -> "8-bit signed integer"
            'C' -> "16-bit Unicode character"
            'S' -> "16-bit signed integer"
            'F' -> "32-bit floating point"
            'D' -> "64-bit floating point"
            'V' -> "no return value"
            else -> "unknown primitive type"
        }
        val md = buildString {
            if (arrayDim > 0) {
                append("**Primitive Array:** `${Types.readableName(type)}`\n\n")
                append("**Base Type:** `${typeName}` ($description)\n\n")
                append("**Dimensions:** $arrayDim\n\n")
            } else {
                append("**Primitive Type:** `${typeName}`\n\n")
                append("**Description:** $description\n\n")
            }
            append("*Built-in type* - Part of the Dalvik/Java type system")
        }
        return Hover(MarkupContent(MarkupKind.MARKDOWN, md))
    }
}
