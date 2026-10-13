package xyz.surendrajat.smalilsp.core

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import xyz.surendrajat.smalilsp.util.Types

/** What the caret is on, resolved from the token spans in the AST. See [SmaliFile.symbolAt]. */
sealed class Symbol {
    abstract val range: Range

    /** The class name in the `.class` directive. */
    data class ClassDecl(val classDef: ClassDefinition) : Symbol() {
        override val range: Range get() = classDef.nameSpan.toRange()
    }

    /** The method name in a `.method` directive. */
    data class MethodDecl(val method: MethodDefinition) : Symbol() {
        override val range: Range get() = method.nameSpan.toRange()
    }

    /** The field name in a `.field` directive. */
    data class FieldDecl(val fieldDef: FieldDefinition) : Symbol() {
        override val range: Range get() = fieldDef.nameSpan.toRange()
    }

    /**
     * A type token naming a class: `.super`, `.implements`, the owner before `->`, a type in a
     * descriptor or field type, a type operand, or a directive reference. [className] is the
     * element class when the token is an array type; [type] is the token as written.
     * [descriptorOf] is the invoke whose descriptor holds the token, if any.
     */
    data class ClassRef(
        val className: String,
        val type: String,
        override val range: Range,
        val descriptorOf: InvokeInstruction? = null
    ) : Symbol()

    /** A primitive or primitive-array token; [inside] is the member reference it belongs to, if any. */
    data class Primitive(val type: String, override val range: Range, val inside: Instruction? = null) : Symbol()

    /** The method name of an invoke target, or a spot in its descriptor that is not a type. [range] is the name. */
    data class MethodRef(val instruction: InvokeInstruction, override val range: Range) : Symbol()

    /** The field name of a field-access target. */
    data class FieldRef(val instruction: FieldAccessInstruction, override val range: Range) : Symbol()

    /** A `:label` definition line. */
    data class LabelDecl(val label: LabelDefinition, val method: MethodDefinition) : Symbol() {
        override val range: Range get() = label.range
    }

    /** A `:label` reference in a jump, `.catch`, switch table or `fill-array-data`. */
    data class LabelRef(val name: String, override val range: Range, val method: MethodDefinition) : Symbol()

    /** On a modelled instruction but not on one of its symbols: the opcode, a register, a string. */
    data class OnInstruction(val instruction: Instruction, val method: MethodDefinition) : Symbol() {
        override val range: Range get() = instruction.range
    }
}

/** The caret is inside the range or right after its last character. */
fun Range.touches(p: Position): Boolean =
    (p.line > start.line || (p.line == start.line && p.character >= start.character)) &&
        (p.line < end.line || (p.line == end.line && p.character <= end.character))

/** The symbol under the caret, or null when the caret is on nothing navigable. A caret right after a token still counts. */
fun SmaliFile.symbolAt(position: Position): Symbol? {
    val c = classDefinition
    if (c.nameSpan.touches(position)) return Symbol.ClassDecl(c)
    val superClass = c.superClass
    val superRange = c.superClassRange
    if (superClass != null && superRange != null && superRange.touches(position)) return Symbol.ClassRef(superClass, superClass, superRange)
    for ((iface, r) in c.interfaceRanges) if (r.touches(position)) return Symbol.ClassRef(iface, iface, r)

    for (f in fields) {
        if (f.nameSpan.touches(position)) return Symbol.FieldDecl(f)
        if (f.typeSpan.touches(position)) return typeSymbol(f.type, f.typeSpan.toRange(), null, null)
    }

    val method = methods.firstOrNull { it.range.touches(position) }
    if (method != null) {
        if (method.nameSpan.touches(position)) return Symbol.MethodDecl(method)
        if (method.descriptorSpan.touches(position)) return descriptorSymbol(method.descriptor, method.descriptorSpan, position, null)
        for (label in method.labels.values) if (label.range.touches(position)) return Symbol.LabelDecl(label, method)
        for (ref in method.labelRefs) if (ref.span.touches(position)) return Symbol.LabelRef(ref.name, ref.span.toRange(), method)
        for (instr in method.instructions) {
            if (!instr.range.touches(position)) continue
            return when (instr) {
                is InvokeInstruction -> {
                    val owner = instr.ownerSpan
                    val name = instr.nameSpan
                    val descriptor = instr.descriptorSpan
                    when {
                        owner != null && owner.touches(position) -> typeSymbol(instr.className, owner.toRange(), null, null)
                        name != null && name.touches(position) -> Symbol.MethodRef(instr, name.toRange())
                        descriptor != null && descriptor.touches(position) -> descriptorSymbol(instr.descriptor, descriptor, position, instr)
                        else -> Symbol.OnInstruction(instr, method)
                    }
                }
                is FieldAccessInstruction -> {
                    val owner = instr.ownerSpan
                    val name = instr.nameSpan
                    val type = instr.typeSpan
                    when {
                        owner != null && owner.touches(position) -> typeSymbol(instr.className, owner.toRange(), null, null)
                        name != null && name.touches(position) -> Symbol.FieldRef(instr, name.toRange())
                        type != null && type.touches(position) -> typeSymbol(instr.fieldType, type.toRange(), instr, null)
                        else -> Symbol.OnInstruction(instr, method)
                    }
                }
                is TypeInstruction -> {
                    val type = instr.typeSpan
                    if (type != null && type.touches(position)) typeSymbol(instr.className, type.toRange(), instr, null)
                    else Symbol.OnInstruction(instr, method)
                }
                is JumpInstruction, is ConstStringInstruction -> Symbol.OnInstruction(instr, method)
            }
        }
    }

    for (ref in typeRefs) if (ref.span.touches(position)) return Symbol.ClassRef(ref.className, ref.className, ref.span.toRange())
    return null
}

private fun typeSymbol(type: String, range: Range, inside: Instruction?, descriptorOf: InvokeInstruction?): Symbol {
    val className = Types.className(type)
    return if (className != null) Symbol.ClassRef(className, type, range, descriptorOf) else Symbol.Primitive(type, range, inside)
}

/** The type under the caret inside a descriptor; otherwise the method itself for an invoke, nothing for a declaration. */
private fun descriptorSymbol(descriptor: String, span: Span, position: Position, invoke: InvokeInstruction?): Symbol? {
    val type = Types.typeAtOffset(descriptor, position.character - span.start)
    if (type != null) {
        val range = Span(span.line, span.start + type.start, span.start + type.end).toRange()
        return typeSymbol(type.type, range, invoke, invoke)
    }
    val name = invoke?.nameSpan ?: return null
    return Symbol.MethodRef(invoke, name.toRange())
}
