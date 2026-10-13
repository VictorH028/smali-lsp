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
 * Hover: class hierarchy, member signatures, field types, primitive types, labels, and Dalvik
 * opcode documentation, as Markdown. The symbol under the caret comes from [SmaliFile.symbolAt];
 * only the opcode lookup reads the line text, because unmodelled instructions have no AST node.
 */
class HoverProvider(
    private val workspaceIndex: WorkspaceIndex
) {

    fun provideHover(uri: String, position: Position): Hover? {
        val file = workspaceIndex.findFileByUri(uri) ?: return null
        return when (val symbol = file.symbolAt(position)) {
            null -> hoverForOpcodeKeyword(uri, position)
            is Symbol.ClassDecl -> hoverForClass(symbol.classDef)
            is Symbol.ClassRef -> hoverForClassReference(symbol.type)
            is Symbol.MethodDecl -> hoverForMethod(symbol.method)
            is Symbol.FieldDecl -> hoverForField(symbol.fieldDef)
            is Symbol.MethodRef -> hoverForMethodReference(symbol.instruction.className, symbol.instruction.methodName, symbol.instruction.descriptor)
            is Symbol.FieldRef -> hoverForFieldReference(symbol.instruction.className, symbol.instruction.fieldName, symbol.instruction.fieldType)
            is Symbol.Primitive -> hoverForPrimitiveType(symbol.type)
            is Symbol.LabelDecl -> hoverForLabel(symbol.label, symbol.method)
            is Symbol.LabelRef -> symbol.method.labels[symbol.name]?.let { hoverForLabel(it, symbol.method) }
                ?: Hover(MarkupContent(MarkupKind.MARKDOWN, "**Jump to label:** `:${symbol.name}`"))
            is Symbol.OnInstruction -> hoverForOpcodeKeyword(uri, position)
        }
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

    private fun hoverForMethod(method: MethodDefinition): Hover {
        val md = buildString {
            append("**Method:** `${method.name}`\n\n")
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
            append("**Returns:** `$returnType`")
        }
        return Hover(MarkupContent(MarkupKind.MARKDOWN, md))
    }

    private fun hoverForField(field: FieldDefinition): Hover {
        val md = buildString {
            append("**Field:** `${field.name}`\n\n")
            if (field.modifiers.isNotEmpty()) {
                append("**Modifiers:** ${field.modifiers.joinToString(", ")}\n\n")
            }
            append("**Type:** `${Types.readableName(field.type)}`")
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

        val md = buildString {
            append("**Class:** `${Types.readableName(type)}`\n\n")
            if (Types.isSdkClass(type)) {
                append("*SDK Class* - Part of Android SDK or Java standard library")
            } else {
                append("*Not found in workspace*")
            }
        }
        return Hover(MarkupContent(MarkupKind.MARKDOWN, md))
    }

    fun hoverForMethodReference(className: String, methodName: String, descriptor: String): Hover? {
        workspaceIndex.findClass(className)?.methods
            ?.find { it.name == methodName && it.descriptor == descriptor }
            ?.let { return hoverForMethod(it) }

        val md = buildString {
            append("**Method:** `${className}.${methodName}`\n\n")
            append("**Descriptor:** `${descriptor}`\n\n")
            if (Types.isSdkClass(className)) {
                append("*SDK Method* - Part of Android SDK or Java standard library")
            } else {
                append("*Not found in workspace*")
            }
        }
        return Hover(MarkupContent(MarkupKind.MARKDOWN, md))
    }

    fun hoverForFieldReference(className: String, fieldName: String, fieldType: String): Hover? {
        workspaceIndex.findClass(className)?.fields
            ?.find { it.name == fieldName }
            ?.let { return hoverForField(it) }

        val md = buildString {
            append("**Field:** `${className}.${fieldName}`\n\n")
            append("**Type:** `${Types.readableName(fieldType)}`\n\n")
            if (Types.isSdkClass(className)) {
                append("*SDK Field* - Part of Android SDK or Java standard library")
            } else {
                append("*Not found in workspace*")
            }
        }
        return Hover(MarkupContent(MarkupKind.MARKDOWN, md))
    }

    private fun hoverForPrimitiveType(type: String): Hover {
        val arrayDim = type.takeWhile { it == '[' }.length
        val (typeName, description) = when (type.trimStart('[').firstOrNull()) {
            'I' -> "int" to "32-bit signed integer"
            'J' -> "long" to "64-bit signed integer"
            'Z' -> "boolean" to "true or false"
            'B' -> "byte" to "8-bit signed integer"
            'C' -> "char" to "16-bit Unicode character"
            'S' -> "short" to "16-bit signed integer"
            'F' -> "float" to "32-bit floating point"
            'D' -> "double" to "64-bit floating point"
            'V' -> "void" to "no return value"
            else -> "unknown" to "unknown primitive type"
        }
        val md = buildString {
            if (arrayDim > 0) {
                append("**Primitive Array:** `${typeName}${"[]".repeat(arrayDim)}`\n\n")
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
