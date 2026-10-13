package xyz.surendrajat.smalilsp.core

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/** One token's place in a file: UTF-16 columns [start, end) on a single line. */
data class Span(val line: Int, val start: Int, val end: Int) {
    val length: Int get() = end - start

    fun toRange(): Range = Range(Position(line, start), Position(line, end))

    /** The caret is on one of the token's characters. */
    fun covers(position: Position): Boolean =
        position.line == line && position.character >= start && position.character < end

    /** The caret is on the token or right after its last character. */
    fun touches(position: Position): Boolean =
        position.line == line && position.character >= start && position.character <= end
}

data class SmaliFile(
    val uri: String,
    val classDefinition: ClassDefinition,
    val fields: List<FieldDefinition>,
    val methods: List<MethodDefinition>,
    /** Class references in directives: annotations, `.catch`, `.local`, method prototypes. */
    val typeRefs: List<TypeRef> = emptyList()
)

data class ClassDefinition(
    val name: String,  // e.g., "Lcom/example/MyClass;"
    val range: Range,
    val modifiers: Set<String>,  // public, final, etc.
    val superClass: String?,  // null for Object
    val interfaces: List<String>,
    /** The type token of the `.super` directive. */
    val superClassRange: Range? = null,
    /** Interface name to the type token of its `.implements` directive. */
    val interfaceRanges: Map<String, Range> = emptyMap(),
    /** The class name token of the `.class` directive. */
    val nameSpan: Span = Span(range.start.line, range.start.character, range.start.character)
)

data class MethodDefinition(
    val name: String,  // e.g., "myMethod" or "<init>"
    val descriptor: String,  // e.g., "(Ljava/lang/String;)V"
    val range: Range,
    val modifiers: Set<String>,
    val parameters: List<Parameter>,
    val returnType: String,
    val instructions: List<Instruction> = emptyList(),  // Instruction-level AST
    val labels: Map<String, LabelDefinition> = emptyMap(),  // Label definitions (name -> position)
    /** The method name in the `.method` line. */
    val nameSpan: Span = Span(range.start.line, range.start.character, range.start.character),
    /** The `(params)return` part of the `.method` line. */
    val descriptorSpan: Span = Span(range.start.line, range.start.character, range.start.character),
    /** Every label reference in the body: jumps, `.catch` blocks, switch tables, `fill-array-data`. */
    val labelRefs: List<LabelRef> = emptyList()
)

data class Parameter(
    val type: String,
    val name: String?  // Can be null in smali
)

data class FieldDefinition(
    val name: String,
    val type: String,
    val range: Range,
    val modifiers: Set<String>,
    /** The field name in the `.field` line. */
    val nameSpan: Span = Span(range.start.line, range.start.character, range.start.character),
    /** The type after the colon in the `.field` line. */
    val typeSpan: Span = Span(range.start.line, range.start.character, range.start.character)
)

/** A class name token inside a directive, e.g. the exception type of a `.catch`. */
data class TypeRef(val className: String, val span: Span)

/** A `:label` reference; [span] covers the colon and the name. */
data class LabelRef(val name: String, val span: Span) {
    /** The name without its colon. */
    val nameSpan: Span get() = Span(span.line, span.start + 1, span.end)
}

/**
 * Instruction-level AST for navigation.
 * Only critical instructions that reference symbols (for goto definition).
 */
sealed class Instruction(
    open val range: Range
)

/**
 * Method invocation: invoke-virtual, invoke-static, invoke-direct, invoke-interface
 *
 * [targetLine]/[targetStart] locate the `Lowner;->name(desc)ret` text when it is one
 * contiguous run on one line (always, for baksmali output); -1 when it is not.
 */
data class InvokeInstruction(
    val opcode: String,  // invoke-virtual, invoke-static, etc.
    val className: String,  // Lcom/example/MyClass;
    val methodName: String,
    val descriptor: String,
    override val range: Range,
    val targetLine: Int = -1,
    val targetStart: Int = -1
) : Instruction(range) {
    val ownerSpan: Span? get() = if (targetStart < 0) null else Span(targetLine, targetStart, targetStart + className.length)
    val nameSpan: Span? get() = ownerSpan?.let { Span(targetLine, it.end + 2, it.end + 2 + methodName.length) }
    val descriptorSpan: Span? get() = nameSpan?.let { Span(targetLine, it.end, it.end + descriptor.length) }
}

/**
 * Field access: iget, iput, sget, sput
 *
 * [targetLine]/[targetStart] locate the `Lowner;->name:type` text, as for [InvokeInstruction].
 */
data class FieldAccessInstruction(
    val opcode: String,  // iget, iput, sget, sput
    val className: String,
    val fieldName: String,
    val fieldType: String,
    override val range: Range,
    val targetLine: Int = -1,
    val targetStart: Int = -1
) : Instruction(range) {
    val ownerSpan: Span? get() = if (targetStart < 0) null else Span(targetLine, targetStart, targetStart + className.length)
    val nameSpan: Span? get() = ownerSpan?.let { Span(targetLine, it.end + 2, it.end + 2 + fieldName.length) }
    val typeSpan: Span? get() = nameSpan?.let { Span(targetLine, it.end + 1, it.end + 1 + fieldType.length) }
}

/**
 * Type reference: new-instance, check-cast, instance-of, const-class
 *
 * [typeLine]/[typeStart] locate the type operand (brackets included), -1 when unknown.
 */
data class TypeInstruction(
    val opcode: String,  // new-instance, check-cast, instance-of, const-class
    val className: String,
    override val range: Range,
    val typeLine: Int = -1,
    val typeStart: Int = -1
) : Instruction(range) {
    val typeSpan: Span? get() = if (typeStart < 0) null else Span(typeLine, typeStart, typeStart + className.length)
}

/**
 * Jump instruction: goto, if-*, packed-switch, sparse-switch
 * References a label within the same method.
 */
data class JumpInstruction(
    val opcode: String,  // goto, if-eqz, if-nez, if-eq, etc.
    val targetLabel: String,  // Label name without colon (e.g., "cond_0")
    override val range: Range,
    val labelRange: Range  // Range of just the label reference for precise navigation
) : Instruction(range)

/**
 * String constant: const-string, const-string/jumbo
 */
data class ConstStringInstruction(
    val value: String,   // The string literal value (without surrounding quotes)
    override val range: Range
) : Instruction(range)

/**
 * Label definition at a specific position in a method.
 */
data class LabelDefinition(
    val name: String,  // Without colon (e.g., "cond_0")
    val range: Range   // Full range including colon (e.g., ":cond_0")
)

/**
 * Helper to create Range objects
 */
fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int): Range {
    return Range(
        Position(startLine, startChar),
        Position(endLine, endChar)
    )
}
