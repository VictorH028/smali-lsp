package xyz.surendrajat.smalilsp.core

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.util.RandomAccess

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
    val typeRefs: List<TypeRef> = emptyList(),
    /** Directive blocks an editor can fold: annotations, switch tables, array data. */
    val blocks: List<Block> = emptyList()
)

/** The lines of a directive block, from its opening directive to its `.end`. */
data class Block(val startLine: Int, val endLine: Int)

/** A file's blocks as one int array behind the `List` interface: a large app has half a million. */
class BlockList private constructor(private val lines: IntArray) : AbstractList<Block>(), RandomAccess {
    override val size: Int get() = lines.size / 2
    override fun get(index: Int): Block = Block(lines[2 * index], lines[2 * index + 1])

    companion object {
        fun of(blocks: List<Block>): List<Block> =
            if (blocks.isEmpty()) emptyList() else BlockList(IntArray(blocks.size * 2) { i -> if (i % 2 == 0) blocks[i / 2].startLine else blocks[i / 2].endLine })
    }
}

/**
 * Declarations keep their positions as ints and build the LSP [Range] and the token [Span]s on
 * demand, as instructions do: a `Range` is three objects and a `Span` one, and a large app has
 * a million declarations and token spans. Secondary constructors take the objects, for callers
 * that build definitions by hand.
 */
data class ClassDefinition(
    val name: String,  // e.g., "Lcom/example/MyClass;"
    val startLine: Int,
    val startChar: Int,
    val endLine: Int,
    val endChar: Int,
    val modifiers: Set<String>,  // public, final, etc.
    val superClass: String?,  // null for Object
    val interfaces: List<String>,
    /** The type token of the `.super` directive. */
    val superClassRange: Range? = null,
    /** Interface name to the type token of its `.implements` directive. */
    val interfaceRanges: Map<String, Range> = emptyMap(),
    /** The class name token of the `.class` directive. */
    val nameLine: Int = startLine,
    val nameStart: Int = startChar,
    val nameEnd: Int = startChar
) {
    constructor(
        name: String,
        range: Range,
        modifiers: Set<String>,
        superClass: String?,
        interfaces: List<String>,
        superClassRange: Range? = null,
        interfaceRanges: Map<String, Range> = emptyMap(),
        nameSpan: Span = Span(range.start.line, range.start.character, range.start.character)
    ) : this(name, range.start.line, range.start.character, range.end.line, range.end.character, modifiers, superClass, interfaces, superClassRange, interfaceRanges, nameSpan.line, nameSpan.start, nameSpan.end)

    val range: Range get() = range(startLine, startChar, endLine, endChar)
    val nameSpan: Span get() = Span(nameLine, nameStart, nameEnd)
}

data class MethodDefinition(
    val name: String,  // e.g., "myMethod" or "<init>"
    val descriptor: String,  // e.g., "(Ljava/lang/String;)V"
    val startLine: Int,
    val startChar: Int,
    val endLine: Int,
    val endChar: Int,
    val modifiers: Set<String>,
    val parameters: List<Parameter>,
    val returnType: String,
    val instructions: List<Instruction> = emptyList(),  // Instruction-level AST
    val labels: Map<String, LabelDefinition> = emptyMap(),  // Label definitions (name -> position)
    /** The method name in the `.method` line. */
    val nameLine: Int = startLine,
    val nameStart: Int = startChar,
    val nameEnd: Int = startChar,
    /** The `(params)return` part of the `.method` line. */
    val descriptorLine: Int = startLine,
    val descriptorStart: Int = startChar,
    val descriptorEnd: Int = startChar,
    /** Every label reference in the body: jumps, `.catch` blocks, switch tables, `fill-array-data`. */
    val labelRefs: List<LabelRef> = emptyList(),
    /** Definitions of a label name that was already defined in the method; [labels] keeps the first. */
    val duplicateLabels: List<LabelDefinition> = emptyList()
) {
    constructor(
        name: String,
        descriptor: String,
        range: Range,
        modifiers: Set<String>,
        parameters: List<Parameter>,
        returnType: String,
        instructions: List<Instruction> = emptyList(),
        labels: Map<String, LabelDefinition> = emptyMap(),
        nameSpan: Span = Span(range.start.line, range.start.character, range.start.character),
        descriptorSpan: Span = Span(range.start.line, range.start.character, range.start.character),
        labelRefs: List<LabelRef> = emptyList(),
        duplicateLabels: List<LabelDefinition> = emptyList()
    ) : this(
        name, descriptor, range.start.line, range.start.character, range.end.line, range.end.character, modifiers, parameters, returnType, instructions, labels,
        nameSpan.line, nameSpan.start, nameSpan.end, descriptorSpan.line, descriptorSpan.start, descriptorSpan.end, labelRefs, duplicateLabels
    )

    val range: Range get() = range(startLine, startChar, endLine, endChar)
    val nameSpan: Span get() = Span(nameLine, nameStart, nameEnd)
    val descriptorSpan: Span get() = Span(descriptorLine, descriptorStart, descriptorEnd)

    /** The caret is inside the method's range or right after its last character. */
    fun touches(position: Position): Boolean =
        (position.line > startLine || (position.line == startLine && position.character >= startChar)) &&
            (position.line < endLine || (position.line == endLine && position.character <= endChar))

    /** `<init>` or `<clinit>`: bound to its own class, never inherited or overridden. */
    val isConstructor: Boolean get() = name == "<init>" || name == "<clinit>"

    /** Dispatched at run time: not a constructor, not private, not static. */
    val isVirtual: Boolean get() = !isConstructor && "private" !in modifiers && "static" !in modifiers
}

data class Parameter(
    val type: String,
    val name: String?  // Can be null in smali
)

/** A method's parameters as an array of type strings behind the `List` interface; baksmali leaves the names to `.param` directives, so they are null. */
class ParameterList private constructor(private val types: Array<String>) : AbstractList<Parameter>(), RandomAccess {
    override val size: Int get() = types.size
    override fun get(index: Int): Parameter = Parameter(types[index], null)

    companion object {
        fun of(types: List<String>): List<Parameter> = if (types.isEmpty()) emptyList() else ParameterList(types.toTypedArray())
    }
}

data class FieldDefinition(
    val name: String,
    val type: String,
    val startLine: Int,
    val startChar: Int,
    val endLine: Int,
    val endChar: Int,
    val modifiers: Set<String>,
    /** The field name in the `.field` line. */
    val nameLine: Int = startLine,
    val nameStart: Int = startChar,
    val nameEnd: Int = startChar,
    /** The type after the colon in the `.field` line. */
    val typeLine: Int = startLine,
    val typeStart: Int = startChar,
    val typeEnd: Int = startChar
) {
    constructor(
        name: String,
        type: String,
        range: Range,
        modifiers: Set<String>,
        nameSpan: Span = Span(range.start.line, range.start.character, range.start.character),
        typeSpan: Span = Span(range.start.line, range.start.character, range.start.character)
    ) : this(name, type, range.start.line, range.start.character, range.end.line, range.end.character, modifiers, nameSpan.line, nameSpan.start, nameSpan.end, typeSpan.line, typeSpan.start, typeSpan.end)

    val range: Range get() = range(startLine, startChar, endLine, endChar)
    val nameSpan: Span get() = Span(nameLine, nameStart, nameEnd)
    val typeSpan: Span get() = Span(typeLine, typeStart, typeEnd)
}

/** A class name token inside a directive, e.g. the exception type of a `.catch`. */
data class TypeRef(val className: String, val line: Int, val start: Int, val end: Int) {
    constructor(className: String, span: Span) : this(className, span.line, span.start, span.end)

    val span: Span get() = Span(line, start, end)
}

/** A `:label` reference; [span] covers the colon and the name. */
data class LabelRef(val name: String, val line: Int, val start: Int, val end: Int) {
    constructor(name: String, span: Span) : this(name, span.line, span.start, span.end)

    val span: Span get() = Span(line, start, end)

    /** The name without its colon. */
    val nameSpan: Span get() = Span(line, start + 1, end)
}

/**
 * Instructions that name a symbol: invoke, field access, type operands, jumps, `const-string`.
 * Arithmetic, move and return instructions reference registers only and are not kept.
 *
 * Every instruction holds its range as four ints and builds the LSP [Range] on demand: a
 * large app has millions of instructions, and the three objects of a stored `Range` were the
 * biggest part of the AST after the strings.
 */
sealed class Instruction {
    abstract val startLine: Int
    abstract val startChar: Int
    abstract val endLine: Int
    abstract val endChar: Int
    val range: Range get() = range(startLine, startChar, endLine, endChar)
}

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
    override val startLine: Int,
    override val startChar: Int,
    override val endLine: Int,
    override val endChar: Int,
    val targetLine: Int = -1,
    val targetStart: Int = -1
) : Instruction() {
    constructor(opcode: String, className: String, methodName: String, descriptor: String, range: Range, targetLine: Int = -1, targetStart: Int = -1) :
        this(opcode, className, methodName, descriptor, range.start.line, range.start.character, range.end.line, range.end.character, targetLine, targetStart)

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
    override val startLine: Int,
    override val startChar: Int,
    override val endLine: Int,
    override val endChar: Int,
    val targetLine: Int = -1,
    val targetStart: Int = -1
) : Instruction() {
    constructor(opcode: String, className: String, fieldName: String, fieldType: String, range: Range, targetLine: Int = -1, targetStart: Int = -1) :
        this(opcode, className, fieldName, fieldType, range.start.line, range.start.character, range.end.line, range.end.character, targetLine, targetStart)

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
    override val startLine: Int,
    override val startChar: Int,
    override val endLine: Int,
    override val endChar: Int,
    val typeLine: Int = -1,
    val typeStart: Int = -1
) : Instruction() {
    constructor(opcode: String, className: String, range: Range, typeLine: Int = -1, typeStart: Int = -1) :
        this(opcode, className, range.start.line, range.start.character, range.end.line, range.end.character, typeLine, typeStart)

    val typeSpan: Span? get() = if (typeStart < 0) null else Span(typeLine, typeStart, typeStart + className.length)
}

/**
 * Jump instruction: goto, if-*, packed-switch, sparse-switch
 * References a label within the same method; [labelRange] is the `:label` token.
 */
data class JumpInstruction(
    val opcode: String,  // goto, if-eqz, if-nez, if-eq, etc.
    val targetLabel: String,  // Label name without colon (e.g., "cond_0")
    override val startLine: Int,
    override val startChar: Int,
    override val endLine: Int,
    override val endChar: Int,
    val labelLine: Int,
    val labelStart: Int,
    val labelEnd: Int
) : Instruction() {
    constructor(opcode: String, targetLabel: String, range: Range, labelRange: Range) :
        this(opcode, targetLabel, range.start.line, range.start.character, range.end.line, range.end.character, labelRange.start.line, labelRange.start.character, labelRange.end.character)

    val labelRange: Range get() = range(labelLine, labelStart, labelLine, labelEnd)
}

/**
 * String constant: const-string, const-string/jumbo
 */
data class ConstStringInstruction(
    val value: String,   // The string literal value (without surrounding quotes)
    override val startLine: Int,
    override val startChar: Int,
    override val endLine: Int,
    override val endChar: Int
) : Instruction() {
    constructor(value: String, range: Range) : this(value, range.start.line, range.start.character, range.end.line, range.end.character)
}

/**
 * Label definition at a specific position in a method; [range] covers `:name`.
 */
data class LabelDefinition(
    val name: String,  // Without colon (e.g., "cond_0")
    val line: Int,
    val start: Int,
    val end: Int
) {
    constructor(name: String, range: Range) : this(name, range.start.line, range.start.character, range.end.character)

    val range: Range get() = range(line, start, line, end)
}

/** The method whose lines contain [line], if any; methods are in file order. */
fun SmaliFile.methodAt(line: Int): MethodDefinition? {
    var low = 0
    var high = methods.size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val method = methods[mid]
        when {
            line < method.startLine -> high = mid - 1
            line > method.endLine -> low = mid + 1
            else -> return method
        }
    }
    return null
}

/**
 * Helper to create Range objects
 */
fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int): Range {
    return Range(
        Position(startLine, startChar),
        Position(endLine, endChar)
    )
}
