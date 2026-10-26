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
 * Hover, as Markdown: the declaration as smali in a code block, then one line a reader can
 * take in at a glance, with the Java-style signature, the owner and a count of references. A
 * platform member links to its reference page. The symbol under the caret comes from
 * [SmaliFile.symbolAt]; registers, resource ids, the `.registers` frame and the opcode lookup
 * read the line text, because they have no AST node.
 */
class HoverProvider(
    private val workspaceIndex: WorkspaceIndex
) {
    private val references = ReferenceProvider(workspaceIndex)

    fun provideHover(uri: String, position: Position): Hover? {
        val file = workspaceIndex.findFileByUri(uri) ?: return null
        return when (val symbol = file.symbolAt(position)) {
            null -> hoverForRegister(uri, file, position) ?: hoverForResource(uri, position) ?: hoverForFrame(uri, file, position) ?: hoverForOpcodeKeyword(uri, position)
            is Symbol.ClassDecl -> hoverForClass(symbol.classDef)
            is Symbol.ClassRef -> hoverForClassReference(symbol.type)
            is Symbol.MethodDecl -> hoverForMethod(symbol.method, file.classDefinition.name)
            is Symbol.FieldDecl -> hoverForField(symbol.fieldDef, file.classDefinition.name)
            is Symbol.MethodRef -> hoverForMethodReference(symbol.instruction.className, symbol.instruction.methodName, symbol.instruction.descriptor)
            is Symbol.FieldRef -> hoverForFieldReference(symbol.instruction.className, symbol.instruction.fieldName, symbol.instruction.fieldType)
            is Symbol.Primitive -> hoverForPrimitiveType(symbol.type)
            is Symbol.LabelDecl -> hoverForLabel(symbol.label, symbol.method)
            is Symbol.LabelRef -> symbol.method.labels[symbol.name]?.let { hoverForLabel(it, symbol.method) }
                ?: markdown("`:${symbol.name}` · no such label in this method")
            is Symbol.OnInstruction -> hoverForRegister(uri, file, position) ?: hoverForOpcodeKeyword(uri, position) ?: hoverForString(uri, position, symbol)
        }
    }

    /** A string literal: its text and how many `const-string` instructions in the workspace load the same value. */
    private fun hoverForString(uri: String, position: Position, symbol: Symbol): Hover? {
        val value = StringLiterals.at(workspaceIndex, uri, position, symbol)?.value ?: return null
        val shown = if (value.length > 200) value.substring(0, 199) + "…" else value
        return markdown("${codeSpan("\"$shown\"")} · ${count(workspaceIndex.findStringUsages(value).size, "reference")}")
    }

    /** On a `.registers` or `.locals` line: the method's frame, with the registers the locals, `this` and each parameter occupy. */
    private fun hoverForFrame(uri: String, file: SmaliFile, position: Position): Hover? {
        val line = workspaceIndex.getLineContent(uri, position.line) ?: return null
        if (RegisterFrame.frameDirective(line) == null) return null
        val method = file.methodAt(position.line) ?: return null
        val lines = workspaceIndex.getLines(uri) ?: return null
        return Registers.frameHover(method, file.classDefinition.name, lines)
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

    /** `:cond_0` · Conditional branch target · 2 references · lines 12, 20 */
    private fun hoverForLabel(labelDef: LabelDefinition, method: MethodDefinition): Hover {
        val refs = method.labelRefs.filter { it.name == labelDef.name }
        val kind = when {
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
        val lines = refs.map { it.line + 1 }.sorted().let { if (it.size > 5) it.take(5).joinToString(", ") + ", …" else it.joinToString(", ") }
        val from = if (refs.isEmpty()) "" else " · line${if (refs.size == 1) "" else "s"} $lines"
        return markdown("`:${labelDef.name}` · $kind · ${count(refs.size, "reference")}$from")
    }

    /** The `.class`, `.super` and `.implements` lines, then the readable name with member and subtype counts. */
    private fun hoverForClass(classDef: ClassDefinition): Hover? {
        val file = workspaceIndex.findClass(classDef.name) ?: return null
        val definition = file.classDefinition
        val declaration = buildString {
            append(".class ")
            if (definition.modifiers.isNotEmpty()) append(definition.modifiers.joinToString(" ")).append(' ')
            append(definition.name)
            definition.superClass?.let { append("\n.super ").append(it) }
            for (iface in definition.interfaces) append("\n.implements ").append(iface)
        }
        val facts = mutableListOf("`${Types.readableName(definition.name)}`", count(file.methods.size, "method"), count(file.fields.size, "field"))
        workspaceIndex.getDirectSubclasses(definition.name).size.let { if (it > 0) facts.add(if (it == 1) "1 subclass" else "$it subclasses") }
        workspaceIndex.getDirectImplementors(definition.name).size.let { if (it > 0) facts.add(count(it, "implementor")) }
        return markdown(code(declaration) + "\n" + facts.joinToString(" · "))
    }

    /** The `.method` line, then the Java-style signature with the register each parameter arrives in, the owner and how many calls bind to it (through subclasses and overrides). */
    private fun hoverForMethod(method: MethodDefinition, className: String): Hover {
        val declaration = ".method " + (if (method.modifiers.isEmpty()) "" else method.modifiers.joinToString(" ") + " ") + method.name + method.descriptor
        val usages = references.methodFamily(className, method.name, method.descriptor).usages.size
        return markdown(code(declaration) + "\n`${signature(method)}` in `${Types.readableName(className)}` · ${count(usages, "reference")}")
    }

    /** `String[] A(m p0, int p1)`: short type names, each parameter with its register, a `.param` name in quotes after it. */
    private fun signature(method: MethodDefinition): String {
        var slot = if ("static" in method.modifiers) 0 else 1
        val params = method.parameters.joinToString(", ") { param ->
            val width = RegisterFrame.slots(param.type)
            val register = if (width == 1) "p$slot" else "p$slot..p${slot + width - 1}"
            slot += width
            "${short(param.type)} $register" + (param.name?.let { " \"$it\"" } ?: "")
        }
        return "${short(method.returnType)} ${method.name}($params)"
    }

    /** The `.field` line, then the readable type and name, the owner and how many accesses bind to it. */
    private fun hoverForField(field: FieldDefinition, className: String): Hover {
        val declaration = ".field " + (if (field.modifiers.isEmpty()) "" else field.modifiers.joinToString(" ") + " ") + field.name + ":" + field.type
        val usages = references.fieldFamily(className, field.name, field.type).usages.size
        return markdown(code(declaration) + "\n`${short(field.type)} ${field.name}` in `${Types.readableName(className)}` · ${count(usages, "reference")}")
    }

    /** Opcode documentation when the caret is on, or right after, the opcode of an instruction line. */
    private fun hoverForOpcodeKeyword(uri: String, position: Position): Hover? {
        val line = workspaceIndex.getLineContent(uri, position.line) ?: return null
        val scanner = InstructionScanner()
        if (!scanner.scan(line) || position.character !in scanner.opcodeStart..scanner.opcodeEnd) return null
        val info = DalvikOpcodeDatabase.lookup(scanner.opcode) ?: return null
        return markdown(info.toMarkdown())
    }

    /** [type] may be an array; the hover describes its element class. */
    fun hoverForClassReference(type: String): Hover? {
        if (Types.isPrimitive(type)) return hoverForPrimitiveType(type)
        val file = workspaceIndex.findClass(Types.className(type) ?: type)
        if (file != null) return hoverForClass(file.classDefinition)
        return markdown(code(type) + "\n`${Types.readableName(type)}` · ${elsewhere(Types.className(type) ?: type, null)}")
    }

    fun hoverForMethodReference(className: String, methodName: String, descriptor: String): Hover? {
        workspaceIndex.resolveMethod(className, methodName, descriptor)?.let { return hoverForMethod(it.method, it.file.classDefinition.name) }
        val close = descriptor.indexOf(')')
        val params = if (close < 0) emptyList() else Types.parseTypeStrings(descriptor.substring(0, close + 1))
        val returnType = if (close < 0 || close + 1 >= descriptor.length) "V" else descriptor.substring(close + 1)
        val signature = "${short(returnType)} $methodName(${params.joinToString(", ") { short(it) }})"
        val anchor = "#" + (if (methodName == "<init>") Types.simpleName(className) else methodName) + "(" + params.joinToString(",%20") { Types.readableName(it).replace('$', '.') } + ")"
        return markdown(code("$className->$methodName$descriptor") + "\n`$signature` in `${Types.readableName(className)}` · ${elsewhere(className, anchor)}")
    }

    fun hoverForFieldReference(className: String, fieldName: String, fieldType: String): Hover? {
        workspaceIndex.resolveField(className, fieldName, fieldType)?.let { return hoverForField(it.field, it.file.classDefinition.name) }
        return markdown(code("$className->$fieldName:$fieldType") + "\n`${short(fieldType)} $fieldName` in `${Types.readableName(className)}` · ${elsewhere(className, "#$fieldName")}")
    }

    /** What to say about a class the workspace does not define: a link to its reference page when it has one, else that it is not here. */
    private fun elsewhere(className: String, anchor: String?): String {
        val link = referenceUrl(className)
        return when {
            link != null -> "[Android reference]($link${anchor ?: ""})"
            Types.isSdkClass(className) -> "*Platform type*"
            else -> "*Not found in workspace*"
        }
    }

    /** `developer.android.com/reference/java/lang/String` for the platform and AndroidX classes it documents; null for arrays, primitives and everything else. */
    private fun referenceUrl(className: String): String? {
        if (!className.startsWith("L") || DOCUMENTED.none { className.startsWith(it) }) return null
        // the runtime's own annotations (Signature, MemberClasses, ...) are hidden API without a page; the optimization ones are documented
        if (className.startsWith("Ldalvik/annotation/") && !className.startsWith("Ldalvik/annotation/optimization/")) return null
        return "https://developer.android.com/reference/" + className.substring(1, className.length - 1).replace('$', '.')
    }

    /** `int` · 32-bit signed integer; `int[][]` · 2-dimensional array of `int` (32-bit signed integer) */
    private fun hoverForPrimitiveType(type: String): Hover {
        val dimensions = type.takeWhile { it == '[' }.length
        val base = type.trimStart('[')
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
        val name = Types.readableName(base)
        return markdown(when (dimensions) {
            0 -> "`$name` · $description"
            1 -> "`$name[]` · array of `$name` ($description)"
            else -> "`${Types.readableName(type)}` · $dimensions-dimensional array of `$name` ($description)"
        })
    }

    private fun markdown(text: String) = Hover(MarkupContent(MarkupKind.MARKDOWN, text))

    private fun code(smali: String) = "```smali\n$smali\n```"

    /** `java.lang.String[]` → `String[]`, `com.app.Outer$Inner` → `Inner`. */
    private fun short(type: String): String {
        val readable = Types.readableName(type)
        val bracket = readable.indexOf('[')
        val base = if (bracket < 0) readable else readable.substring(0, bracket)
        return base.substringAfterLast('.').substringAfterLast('$') + (if (bracket < 0) "" else readable.substring(bracket))
    }

    private fun count(n: Int, noun: String) = when (n) { 0 -> "no ${noun}s"; 1 -> "1 $noun"; else -> "$n ${noun}s" }

    /** [text] as a Markdown code span, fenced with one more backtick than its longest run of them. */
    private fun codeSpan(text: String): String {
        var longest = 0
        var run = 0
        for (c in text) { run = if (c == '`') run + 1 else 0; longest = maxOf(longest, run) }
        val fence = "`".repeat(longest + 1)
        return "$fence$text$fence"
    }

    private companion object {
        val DOCUMENTED = listOf("Ljava/", "Ljavax/", "Landroid/", "Landroidx/", "Ldalvik/", "Lorg/json/", "Lorg/w3c/", "Lorg/xml/", "Lorg/xmlpull/")
    }
}
