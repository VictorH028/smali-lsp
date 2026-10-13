package xyz.surendrajat.smalilsp.parser

import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.TerminalNode
import org.eclipse.lsp4j.Range
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.parser.generated.SmaliLexer as GeneratedSmaliLexer
import xyz.surendrajat.smalilsp.parser.generated.SmaliParser as GeneratedSmaliParser
import xyz.surendrajat.smalilsp.parser.generated.SmaliParserBaseListener
import xyz.surendrajat.smalilsp.util.StringPool
import xyz.surendrajat.smalilsp.util.Types

/**
 * Builds the [SmaliFile] model from the parse.
 *
 * Attached as a *parse* listener (`parser.addParseListener`), so the model is built while
 * the parser runs instead of by a second walk over the finished tree. Every rule callback
 * is therefore an `exit` hook, where a node's children and stop token exist; the few
 * `enter` hooks only maintain a depth counter.
 *
 * Text is sliced straight out of the source by token offset (see [src]); token offsets are
 * UTF-16 string indices (see [StringCharStream]), so spans are exact LSP columns.
 */
class ASTBuilder(
    private val uri: String,
    private val tokens: CommonTokenStream,
    private val source: String,
    private val strings: StringPool
) : SmaliParserBaseListener() {

    /**
     * Text of a parse-tree node, sliced straight out of the source.
     *
     * `ParserRuleContext.getText()` concatenates every leaf through a StringBuilder per
     * nesting level; for the ~1M invoke/field targets of a large APK that was the single
     * biggest parse-pipeline cost. If the span holds whitespace or a comment (hand-written
     * smali may put spaces inside a signature), fall back to the concatenation so the
     * result stays identical to `getText()`.
     */
    private fun ParserRuleContext.src(): String {
        val first = start ?: return text
        val last = stop ?: return text
        if (last.stopIndex < first.startIndex) return text
        val slice = source.substring(first.startIndex, last.stopIndex + 1)
        if (first === last) return slice
        for (ch in slice) {
            if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n' || ch == '#') return text
        }
        return slice
    }

    private val Token.length: Int get() = stopIndex - startIndex + 1

    private fun Token.toSpan(): Span = Span(line - 1, charPositionInLine, charPositionInLine + length)

    /** The node's span on its first line; a node that wraps lines ends after its first token. */
    private fun ParserRuleContext.toSpan(): Span {
        val first = start
        val last = stop ?: first
        val end = if (last.line == first.line) last.charPositionInLine + last.length else first.charPositionInLine + first.length
        return Span(first.line - 1, first.charPositionInLine, end)
    }

    /**
     * Column of the node's first token when the node is one contiguous run of [text] on a
     * single line, so that every part of [text] can be located by offset; -1 otherwise.
     */
    private fun ParserRuleContext.contiguousStart(text: String): Int {
        val first = start ?: return -1
        val last = stop ?: return -1
        if (last.line != first.line || last.stopIndex - first.startIndex + 1 != text.length) return -1
        return first.charPositionInLine
    }

    private val ParserRuleContext.startLine: Int get() = start.line - 1
    private val ParserRuleContext.startChar: Int get() = start.charPositionInLine
    private val ParserRuleContext.endLine: Int get() = (stop ?: start).line - 1
    private val ParserRuleContext.endChar: Int get() = (stop ?: start).let { it.charPositionInLine + it.length }

    private fun ParserRuleContext.toRange(): Range {
        val startToken = this.start
        val stopToken = this.stop ?: startToken
        return range(
            startToken.line - 1,  // LSP is 0-based
            startToken.charPositionInLine,
            stopToken.line - 1,
            stopToken.charPositionInLine + stopToken.length
        )
    }

    // Accumulated data
    private var className: String? = null
    private var classRange: Range? = null
    private var classNameSpan: Span? = null
    private val classModifiers = mutableSetOf<String>()
    private var superClass: String? = null
    private var superClassRange: Range? = null
    private val interfaces = mutableListOf<String>()
    private val interfaceRanges = mutableMapOf<String, Range>()
    private val methods = mutableListOf<MethodDefinition>()
    private val fields = mutableListOf<FieldDefinition>()
    private val typeRefs = mutableListOf<TypeRef>()

    // Declaration of the method whose body is being parsed (instructions attach to it)
    private class PendingMethod(
        val name: String,
        val descriptor: String,
        val modifiers: Set<String>,
        val parameters: List<Parameter>,
        val returnType: String,
        val nameSpan: Span?,
        val descriptorSpan: Span?
    )
    private var pendingMethod: PendingMethod? = null
    private val currentInstructions = mutableListOf<Instruction>()
    private val currentLabels = mutableMapOf<String, LabelDefinition>()
    private val currentDuplicateLabels = mutableListOf<LabelDefinition>()
    private val currentLabelRefs = mutableListOf<LabelRef>()

    /** > 0 while inside a directive whose class name tokens are recorded as [TypeRef]s. */
    private var typeRefDepth = 0

    override fun exitClassDirective(ctx: GeneratedSmaliParser.ClassDirectiveContext) {
        className = ctx.className()?.src()?.let { strings.intern(it) }
        classNameSpan = ctx.className()?.toSpan()
        ctx.classModifier().forEach { mod ->
            classModifiers.add(strings.intern(mod.src()))
        }
        classRange = ctx.toRange()
    }

    override fun exitSuperDirective(ctx: GeneratedSmaliParser.SuperDirectiveContext) {
        superClass = ctx.superName()?.src()?.let { strings.intern(it) }
        superClassRange = ctx.superName()?.toRange() ?: ctx.toRange()
    }

    override fun exitImplementsDirective(ctx: GeneratedSmaliParser.ImplementsDirectiveContext) {
        ctx.referenceType()?.let { type ->
            val internedName = strings.intern(type.src())
            interfaces.add(internedName)
            interfaceRanges[internedName] = type.toRange()
        }
    }

    override fun exitFieldDirective(ctx: GeneratedSmaliParser.FieldDirectiveContext) {
        val nameAndType = ctx.fieldNameAndType() ?: return
        val text = nameAndType.src()

        // Format is: name:type (e.g., myField:I)
        val parts = text.split(":", limit = 2)
        if (parts.size != 2) return

        val fieldName = strings.intern(parts[0])
        val fieldType = strings.intern(parts[1])  // Many duplicates: I, Z, V, Ljava/lang/String;
        val fieldModifiers = ctx.fieldModifier().map { strings.intern(it.src()) }.toSet()
        val range = ctx.toRange()

        val nameStart = nameAndType.start.toSpan()
        val colon = nameAndType.COLON()?.symbol
        val nameSpan = if (colon != null && colon.line == nameAndType.start.line) Span(nameStart.line, nameStart.start, colon.charPositionInLine) else null
        val typeSpan = nameAndType.anyType()?.toSpan()

        fields.add(FieldDefinition(
            name = fieldName,
            type = fieldType,
            range = range,
            modifiers = fieldModifiers,
            nameSpan = nameSpan ?: Span(range.start.line, range.start.character, range.start.character),
            typeSpan = typeSpan ?: Span(range.start.line, range.start.character, range.start.character)
        ))
    }

    /**
     * The declaration is complete before the body is parsed; capture it here and reset
     * the per-method accumulators. The definition itself is created in [exitMethodDirective]
     * once the full `.method` .. `.end method` range is known.
     */
    override fun exitMethodDeclaration(ctx: GeneratedSmaliParser.MethodDeclarationContext) {
        pendingMethod = null
        currentInstructions.clear()
        currentLabels.clear()
        currentDuplicateLabels.clear()
        currentLabelRefs.clear()

        val signature = ctx.methodSignature() ?: return
        val sigText = signature.src()

        // Format: methodName(params)returnType, e.g. <init>()V or getPath(FFFF)Landroid/graphics/Path;
        val openParen = sigText.indexOf('(')
        if (openParen <= 0) return
        val closeParen = sigText.indexOf(')', openParen)
        if (closeParen < 0) return

        val methodName = strings.intern(sigText.substring(0, openParen))
        val params = sigText.substring(openParen + 1, closeParen)
        val returnType = strings.intern(sigText.substring(closeParen + 1))  // Many duplicates: V, I, Z
        val modifiers = ctx.methodModifier().map { strings.intern(it.src()) }.toSet()

        val lparen = signature.LPAREN()?.symbol
        val first = signature.start
        val last = signature.stop
        val nameSpan = if (lparen != null && lparen.line == first.line) Span(first.line - 1, first.charPositionInLine, lparen.charPositionInLine) else null
        val descriptorSpan = if (lparen != null && last != null && last.line == lparen.line) Span(lparen.line - 1, lparen.charPositionInLine, last.charPositionInLine + last.length) else null

        pendingMethod = PendingMethod(
            name = methodName,
            descriptor = strings.intern("($params)$returnType"),
            modifiers = modifiers,
            parameters = parseParameters(params),
            returnType = returnType,
            nameSpan = nameSpan,
            descriptorSpan = descriptorSpan
        )
    }

    override fun exitMethodDirective(ctx: GeneratedSmaliParser.MethodDirectiveContext) {
        pendingMethod?.let { m ->
            val range = ctx.toRange()
            methods.add(MethodDefinition(
                name = m.name,
                descriptor = m.descriptor,
                range = range,
                modifiers = m.modifiers,
                parameters = m.parameters,
                returnType = m.returnType,
                instructions = currentInstructions.toList(),
                labels = currentLabels.toMap(),
                nameSpan = m.nameSpan ?: Span(range.start.line, range.start.character, range.start.character),
                descriptorSpan = m.descriptorSpan ?: Span(range.start.line, range.start.character, range.start.character),
                labelRefs = currentLabelRefs.toList(),
                duplicateLabels = currentDuplicateLabels.toList()
            ))
        }
        pendingMethod = null
        currentInstructions.clear()
        currentLabels.clear()
        currentDuplicateLabels.clear()
        currentLabelRefs.clear()
    }

    /**
     * Parse parameter types from descriptor string.
     * Delegates to Types for the core parsing logic,
     * then interns strings via StringPool for memory efficiency.
     */
    private fun parseParameters(paramStr: String): List<Parameter> {
        if (paramStr.isEmpty()) return emptyList()
        return Types.parseTypeStrings(paramStr).map { type ->
            Parameter(strings.intern(type), null)
        }
    }

    /**
     * Build the final SmaliFile after tree walking completes.
     */
    fun build(): SmaliFile? {
        val name = className ?: return null
        val range = classRange ?: return null

        return SmaliFile(
            uri = uri,
            classDefinition = ClassDefinition(
                name = name,
                range = range,
                modifiers = classModifiers.toSet(),
                superClass = superClass,
                interfaces = interfaces.toList(),
                superClassRange = superClassRange,
                interfaceRanges = interfaceRanges.toMap(),
                nameSpan = classNameSpan ?: Span(range.start.line, range.start.character, range.start.character)
            ),
            methods = methods.toList(),
            fields = fields.toList(),
            typeRefs = typeRefs.toList()
        )
    }

    // ========== Class references in directives ==========

    override fun enterAnnotationDirective(ctx: GeneratedSmaliParser.AnnotationDirectiveContext) { typeRefDepth++ }
    override fun exitAnnotationDirective(ctx: GeneratedSmaliParser.AnnotationDirectiveContext) { typeRefDepth-- }
    override fun enterLocalDirective(ctx: GeneratedSmaliParser.LocalDirectiveContext) { typeRefDepth++ }
    override fun exitLocalDirective(ctx: GeneratedSmaliParser.LocalDirectiveContext) { typeRefDepth-- }
    override fun enterMethodProto(ctx: GeneratedSmaliParser.MethodProtoContext) { typeRefDepth++ }
    override fun exitMethodProto(ctx: GeneratedSmaliParser.MethodProtoContext) { typeRefDepth-- }

    /**
     * Inside an annotation, `.local` or method prototype every class name is a reference: a
     * `QUALIFIED_TYPE_NAME` token, or the class at the end of a `COMPOUND_METHOD_ARG_LITERAL`
     * (`ILjava/lang/String;` lexes as one token).
     */
    override fun visitTerminal(node: TerminalNode) {
        if (typeRefDepth == 0) return
        val token = node.symbol
        when (token.type) {
            GeneratedSmaliLexer.QUALIFIED_TYPE_NAME -> typeRefs.add(TypeRef(strings.intern(node.text), token.toSpan()))
            GeneratedSmaliLexer.COMPOUND_METHOD_ARG_LITERAL -> {
                val span = token.toSpan()
                for (type in Types.parseTypeSequence(node.text)) {
                    val className = type.className ?: continue
                    typeRefs.add(TypeRef(strings.intern(className), Span(span.line, span.start + type.start, span.start + type.end)))
                }
            }
        }
    }

    override fun exitCatchDirective(ctx: GeneratedSmaliParser.CatchDirectiveContext) {
        ctx.catchExceptionType()?.let { typeRefs.add(TypeRef(strings.intern(it.src()), it.toSpan())) }
        addLabelRef(ctx.catchFromLabel()?.label())
        addLabelRef(ctx.catchToLabel()?.label())
        addLabelRef(ctx.catchGotoLabel()?.label())
    }

    override fun exitCatchAllDirective(ctx: GeneratedSmaliParser.CatchAllDirectiveContext) {
        addLabelRef(ctx.catchFromLabel()?.label())
        addLabelRef(ctx.catchToLabel()?.label())
        addLabelRef(ctx.catchGotoLabel()?.label())
    }

    override fun exitPackedSwitchDirectiveLabel(ctx: GeneratedSmaliParser.PackedSwitchDirectiveLabelContext) {
        addLabelRef(ctx.label())
    }

    override fun exitSparseSwitchDirectiveValue(ctx: GeneratedSmaliParser.SparseSwitchDirectiveValueContext) {
        addLabelRef(ctx.label())
    }

    override fun exitFillArrayDataInstruction(ctx: GeneratedSmaliParser.FillArrayDataInstructionContext) {
        addLabelRef(ctx.filledArrayDataLabel()?.label())
    }

    override fun exitPackedSwitchInstruction(ctx: GeneratedSmaliParser.PackedSwitchInstructionContext) {
        addLabelRef(ctx.packedSwitchLabel()?.label())
    }

    override fun exitSparseSwitchInstruction(ctx: GeneratedSmaliParser.SparseSwitchInstructionContext) {
        addLabelRef(ctx.sparseSwitchLabel()?.label())
    }

    private fun addLabelRef(labelCtx: GeneratedSmaliParser.LabelContext?) {
        if (pendingMethod == null || labelCtx == null) return
        val name = labelCtx.identifier()?.src() ?: return
        currentLabelRefs.add(LabelRef(strings.intern(name), labelCtx.toSpan()))
    }

    // ========== Instruction Parsing ==========

    override fun exitInvokeVirtualInstruction(ctx: GeneratedSmaliParser.InvokeVirtualInstructionContext) {
        addInvoke("invoke-virtual", ctx.methodInvocationTarget(), ctx)
    }

    override fun exitInvokeVirtualRangeInstruction(ctx: GeneratedSmaliParser.InvokeVirtualRangeInstructionContext) {
        addInvoke("invoke-virtual/range", ctx.methodInvocationTarget(), ctx)
    }

    override fun exitInvokeSuperInstruction(ctx: GeneratedSmaliParser.InvokeSuperInstructionContext) {
        addInvoke("invoke-super", ctx.methodInvocationTarget(), ctx)
    }

    override fun exitInvokeSuperRangeInstruction(ctx: GeneratedSmaliParser.InvokeSuperRangeInstructionContext) {
        addInvoke("invoke-super/range", ctx.methodInvocationTarget(), ctx)
    }

    override fun exitInvokeDirectInstruction(ctx: GeneratedSmaliParser.InvokeDirectInstructionContext) {
        addInvoke("invoke-direct", ctx.methodInvocationTarget(), ctx)
    }

    override fun exitInvokeDirectRangeInstruction(ctx: GeneratedSmaliParser.InvokeDirectRangeInstructionContext) {
        addInvoke("invoke-direct/range", ctx.methodInvocationTarget(), ctx)
    }

    override fun exitInvokeStaticInstruction(ctx: GeneratedSmaliParser.InvokeStaticInstructionContext) {
        addInvoke("invoke-static", ctx.methodInvocationTarget(), ctx)
    }

    override fun exitInvokeStaticRangeInstruction(ctx: GeneratedSmaliParser.InvokeStaticRangeInstructionContext) {
        addInvoke("invoke-static/range", ctx.methodInvocationTarget(), ctx)
    }

    override fun exitInvokeInterfaceInstruction(ctx: GeneratedSmaliParser.InvokeInterfaceInstructionContext) {
        addInvoke("invoke-interface", ctx.methodInvocationTarget(), ctx)
    }

    override fun exitInvokeInterfaceRangeInstruction(ctx: GeneratedSmaliParser.InvokeInterfaceRangeInstructionContext) {
        addInvoke("invoke-interface/range", ctx.methodInvocationTarget(), ctx)
    }

    /** invoke-polymorphic targets a regular method reference followed by a call-site prototype. */
    override fun exitInvokePolymorphicInstruction(ctx: GeneratedSmaliParser.InvokePolymorphicInstructionContext) {
        addInvoke("invoke-polymorphic", ctx.methodInvocationTarget(), ctx)
    }

    override fun exitInvokePolymorphicRangeInstruction(ctx: GeneratedSmaliParser.InvokePolymorphicRangeInstructionContext) {
        addInvoke("invoke-polymorphic/range", ctx.methodInvocationTarget(), ctx)
    }

    /** invoke-custom: the navigable symbol is the bootstrap method after `@`. */
    override fun exitInvokeCustomInstruction(ctx: GeneratedSmaliParser.InvokeCustomInstructionContext) {
        addInvoke("invoke-custom", ctx.callSiteReference()?.methodReference(), ctx)
    }

    override fun exitInvokeCustomRangeInstruction(ctx: GeneratedSmaliParser.InvokeCustomRangeInstructionContext) {
        addInvoke("invoke-custom/range", ctx.callSiteReference()?.methodReference(), ctx)
    }

    /** const-method-handle references either a method (`invoke-static@...`) or a field (`instance-get@...`). */
    override fun exitInvokeConstMethodHandleInstruction(ctx: GeneratedSmaliParser.InvokeConstMethodHandleInstructionContext) {
        val handle = ctx.methodHandleReference() ?: return
        handle.methodReference()?.let { addInvoke("const-method-handle", it, ctx) }
        handle.fieldInvocationTarget()?.let { addFieldAccess("const-method-handle", it, ctx) }
    }

    /**
     * Common helper to parse all invoke instructions.
     * [target] is any context whose text has the form `Lcom/Foo;->name(desc)ret`
     * (methodInvocationTarget or methodReference).
     */
    private fun addInvoke(
        opcode: String,
        target: ParserRuleContext?,
        ctx: ParserRuleContext
    ) {
        if (pendingMethod == null || target == null) return

        // Format: Lcom/example/MyClass;->myMethod(I)V
        val targetText = target.src()
        val arrowIndex = targetText.indexOf("->")
        if (arrowIndex < 0) return

        val className = targetText.substring(0, arrowIndex)
        val methodSig = targetText.substring(arrowIndex + 2)

        val openParen = methodSig.indexOf('(')
        if (openParen < 0) return

        // Owners, names and descriptors repeat across the workspace; interning them is most of the AST's memory
        currentInstructions.add(InvokeInstruction(
            opcode = opcode,
            className = strings.intern(className),
            methodName = strings.intern(methodSig.substring(0, openParen)),
            descriptor = strings.intern(methodSig.substring(openParen)),
            startLine = ctx.startLine, startChar = ctx.startChar, endLine = ctx.endLine, endChar = ctx.endChar,
            targetLine = target.start.line - 1,
            targetStart = target.contiguousStart(targetText)
        ))
    }

    override fun exitIgetInstruction(ctx: GeneratedSmaliParser.IgetInstructionContext) {
        addFieldAccess("iget", ctx.instanceField(), ctx)
    }

    override fun exitIgetWideInstruction(ctx: GeneratedSmaliParser.IgetWideInstructionContext) {
        addFieldAccess("iget-wide", ctx.instanceField(), ctx)
    }

    override fun exitIgetObjectInstruction(ctx: GeneratedSmaliParser.IgetObjectInstructionContext) {
        addFieldAccess("iget-object", ctx.instanceField(), ctx)
    }

    override fun exitIgetBooleanInstruction(ctx: GeneratedSmaliParser.IgetBooleanInstructionContext) {
        addFieldAccess("iget-boolean", ctx.instanceField(), ctx)
    }

    override fun exitIgetByteInstruction(ctx: GeneratedSmaliParser.IgetByteInstructionContext) {
        addFieldAccess("iget-byte", ctx.instanceField(), ctx)
    }

    override fun exitIgetCharInstruction(ctx: GeneratedSmaliParser.IgetCharInstructionContext) {
        addFieldAccess("iget-char", ctx.instanceField(), ctx)
    }

    override fun exitIgetShortInstruction(ctx: GeneratedSmaliParser.IgetShortInstructionContext) {
        addFieldAccess("iget-short", ctx.instanceField(), ctx)
    }

    override fun exitIputInstruction(ctx: GeneratedSmaliParser.IputInstructionContext) {
        addFieldAccess("iput", ctx.instanceField(), ctx)
    }

    override fun exitIputWideInstruction(ctx: GeneratedSmaliParser.IputWideInstructionContext) {
        addFieldAccess("iput-wide", ctx.instanceField(), ctx)
    }

    override fun exitIputObjectInstruction(ctx: GeneratedSmaliParser.IputObjectInstructionContext) {
        addFieldAccess("iput-object", ctx.instanceField(), ctx)
    }

    override fun exitIputBooleanInstruction(ctx: GeneratedSmaliParser.IputBooleanInstructionContext) {
        addFieldAccess("iput-boolean", ctx.instanceField(), ctx)
    }

    override fun exitIputByteInstruction(ctx: GeneratedSmaliParser.IputByteInstructionContext) {
        addFieldAccess("iput-byte", ctx.instanceField(), ctx)
    }

    override fun exitIputCharInstruction(ctx: GeneratedSmaliParser.IputCharInstructionContext) {
        addFieldAccess("iput-char", ctx.instanceField(), ctx)
    }

    override fun exitIputShortInstruction(ctx: GeneratedSmaliParser.IputShortInstructionContext) {
        addFieldAccess("iput-short", ctx.instanceField(), ctx)
    }

    override fun exitSGetInstruction(ctx: GeneratedSmaliParser.SGetInstructionContext) {
        addFieldAccess("sget", ctx.fieldInvocationTarget(), ctx)
    }

    override fun exitSGetWideInstruction(ctx: GeneratedSmaliParser.SGetWideInstructionContext) {
        addFieldAccess("sget-wide", ctx.fieldInvocationTarget(), ctx)
    }

    override fun exitSGetObjectInstruction(ctx: GeneratedSmaliParser.SGetObjectInstructionContext) {
        addFieldAccess("sget-object", ctx.fieldInvocationTarget(), ctx)
    }

    override fun exitSGetBooleanInstruction(ctx: GeneratedSmaliParser.SGetBooleanInstructionContext) {
        addFieldAccess("sget-boolean", ctx.fieldInvocationTarget(), ctx)
    }

    override fun exitSGetByteInstruction(ctx: GeneratedSmaliParser.SGetByteInstructionContext) {
        addFieldAccess("sget-byte", ctx.fieldInvocationTarget(), ctx)
    }

    override fun exitSGetCharInstruction(ctx: GeneratedSmaliParser.SGetCharInstructionContext) {
        addFieldAccess("sget-char", ctx.fieldInvocationTarget(), ctx)
    }

    override fun exitSGetShortInstruction(ctx: GeneratedSmaliParser.SGetShortInstructionContext) {
        addFieldAccess("sget-short", ctx.fieldInvocationTarget(), ctx)
    }

    override fun exitSPutInstruction(ctx: GeneratedSmaliParser.SPutInstructionContext) {
        addFieldAccess("sput", ctx.fieldInvocationTarget(), ctx)
    }

    override fun exitSPutWideInstruction(ctx: GeneratedSmaliParser.SPutWideInstructionContext) {
        addFieldAccess("sput-wide", ctx.fieldInvocationTarget(), ctx)
    }

    override fun exitSPutObjectInstruction(ctx: GeneratedSmaliParser.SPutObjectInstructionContext) {
        addFieldAccess("sput-object", ctx.fieldInvocationTarget(), ctx)
    }

    override fun exitSPutBooleanInstruction(ctx: GeneratedSmaliParser.SPutBooleanInstructionContext) {
        addFieldAccess("sput-boolean", ctx.fieldInvocationTarget(), ctx)
    }

    override fun exitSPutByteInstruction(ctx: GeneratedSmaliParser.SPutByteInstructionContext) {
        addFieldAccess("sput-byte", ctx.fieldInvocationTarget(), ctx)
    }

    override fun exitSPutCharInstruction(ctx: GeneratedSmaliParser.SPutCharInstructionContext) {
        addFieldAccess("sput-char", ctx.fieldInvocationTarget(), ctx)
    }

    override fun exitSPutShortInstruction(ctx: GeneratedSmaliParser.SPutShortInstructionContext) {
        addFieldAccess("sput-short", ctx.fieldInvocationTarget(), ctx)
    }

    /**
     * Common helper to parse field access instructions
     */
    private fun addFieldAccess(
        opcode: String,
        fieldCtx: ParserRuleContext?,
        ctx: ParserRuleContext
    ) {
        if (pendingMethod == null || fieldCtx == null) return

        // Format: Lcom/example/MyClass;->myField:I
        val fieldText = fieldCtx.src()
        val arrowIndex = fieldText.indexOf("->")
        if (arrowIndex < 0) return

        val className = fieldText.substring(0, arrowIndex)
        val fieldPart = fieldText.substring(arrowIndex + 2)

        val colonIndex = fieldPart.indexOf(':')
        if (colonIndex < 0) return

        currentInstructions.add(FieldAccessInstruction(
            opcode = opcode,
            className = strings.intern(className),
            fieldName = strings.intern(fieldPart.substring(0, colonIndex)),
            fieldType = strings.intern(fieldPart.substring(colonIndex + 1)),
            startLine = ctx.startLine, startChar = ctx.startChar, endLine = ctx.endLine, endChar = ctx.endChar,
            targetLine = fieldCtx.start.line - 1,
            targetStart = fieldCtx.contiguousStart(fieldText)
        ))
    }

    override fun exitNewInstanceInstruction(ctx: GeneratedSmaliParser.NewInstanceInstructionContext) {
        addTypeInstruction("new-instance", ctx.newInstanceType(), ctx)
    }

    override fun exitCheckCastInstruction(ctx: GeneratedSmaliParser.CheckCastInstructionContext) {
        addTypeInstruction("check-cast", ctx.checkCastType(), ctx)
    }

    override fun exitInstanceOfInstruction(ctx: GeneratedSmaliParser.InstanceOfInstructionContext) {
        addTypeInstruction("instance-of", ctx.checkInstanceType(), ctx)
    }

    override fun exitConstClass(ctx: GeneratedSmaliParser.ConstClassContext) {
        addTypeInstruction("const-class", ctx.referenceOrArrayType(), ctx)
    }

    /** filled-new-array {v0, v1, v2}, [Lcom/example/MyClass; */
    override fun exitFilledNewArrayInstruction(ctx: GeneratedSmaliParser.FilledNewArrayInstructionContext) {
        addTypeInstruction("filled-new-array", ctx.arrayElementType(), ctx)
    }

    /** filled-new-array/range {v0 .. v9}, [[Lt5/d; */
    override fun exitFilledNewArrayRangeInstruction(ctx: GeneratedSmaliParser.FilledNewArrayRangeInstructionContext) {
        addTypeInstruction("filled-new-array/range", ctx.arrayElementType(), ctx)
    }

    /** new-array v1, v0, [I */
    override fun exitNewArrayInstruction(ctx: GeneratedSmaliParser.NewArrayInstructionContext) {
        addTypeInstruction("new-array", ctx.arrayElementType(), ctx)
    }

    private fun addTypeInstruction(opcode: String, typeCtx: ParserRuleContext?, ctx: ParserRuleContext) {
        if (pendingMethod == null || typeCtx == null) return
        val className = typeCtx.src()
        currentInstructions.add(TypeInstruction(
            opcode = opcode,
            className = strings.intern(className),
            startLine = ctx.startLine, startChar = ctx.startChar, endLine = ctx.endLine, endChar = ctx.endChar,
            typeLine = typeCtx.start.line - 1,
            typeStart = typeCtx.contiguousStart(className)
        ))
    }

    // ========== String Constant Parsing ==========

    /** const-string v0, "string value" */
    override fun exitConstString(ctx: GeneratedSmaliParser.ConstStringContext) {
        if (pendingMethod == null) return

        val rawLiteral = ctx.stringLiteral()?.src() ?: return
        val value = rawLiteral.removeSurrounding("\"")

        currentInstructions.add(ConstStringInstruction(value, ctx.startLine, ctx.startChar, ctx.endLine, ctx.endChar))
    }

    /** const-string/jumbo v0, "string value" */
    override fun exitConstStringJumbo(ctx: GeneratedSmaliParser.ConstStringJumboContext) {
        if (pendingMethod == null) return

        val rawLiteral = ctx.stringLiteral()?.src() ?: return
        val value = rawLiteral.removeSurrounding("\"")

        currentInstructions.add(ConstStringInstruction(value, ctx.startLine, ctx.startChar, ctx.endLine, ctx.endChar))
    }

    // ========== Label Parsing ==========

    /** Label definition (e.g., :cond_0); lineLabel is the rule for labels in method body statements. */
    override fun exitLineLabel(ctx: GeneratedSmaliParser.LineLabelContext) {
        if (pendingMethod == null) return

        val labelCtx = ctx.label() ?: return
        val labelName = strings.intern(labelCtx.identifier()?.src() ?: return)

        val definition = LabelDefinition(labelName, ctx.startLine, ctx.startChar, ctx.endChar)
        if (currentLabels.putIfAbsent(labelName, definition) != null) currentDuplicateLabels.add(definition)
    }

    override fun exitGotoInstruction(ctx: GeneratedSmaliParser.GotoInstructionContext) {
        addJump("goto", ctx.label(), ctx)
    }

    override fun exitGoto16Instruction(ctx: GeneratedSmaliParser.Goto16InstructionContext) {
        addJump("goto/16", ctx.label(), ctx)
    }

    override fun exitGoto32Instruction(ctx: GeneratedSmaliParser.Goto32InstructionContext) {
        addJump("goto/32", ctx.label(), ctx)
    }

    override fun exitIfEqzInstruction(ctx: GeneratedSmaliParser.IfEqzInstructionContext) {
        addJump("if-eqz", ctx.label(), ctx)
    }

    override fun exitIfNezInstruction(ctx: GeneratedSmaliParser.IfNezInstructionContext) {
        addJump("if-nez", ctx.label(), ctx)
    }

    override fun exitIfLtzInstruction(ctx: GeneratedSmaliParser.IfLtzInstructionContext) {
        addJump("if-ltz", ctx.label(), ctx)
    }

    override fun exitIfGezInstruction(ctx: GeneratedSmaliParser.IfGezInstructionContext) {
        addJump("if-gez", ctx.label(), ctx)
    }

    override fun exitIfGtzInstruction(ctx: GeneratedSmaliParser.IfGtzInstructionContext) {
        addJump("if-gtz", ctx.label(), ctx)
    }

    override fun exitIfLezInstruction(ctx: GeneratedSmaliParser.IfLezInstructionContext) {
        addJump("if-lez", ctx.label(), ctx)
    }

    override fun exitIfEqInstruction(ctx: GeneratedSmaliParser.IfEqInstructionContext) {
        addJump("if-eq", ctx.label(), ctx)
    }

    override fun exitIfNeInstruction(ctx: GeneratedSmaliParser.IfNeInstructionContext) {
        addJump("if-ne", ctx.label(), ctx)
    }

    override fun exitIfLtInstruction(ctx: GeneratedSmaliParser.IfLtInstructionContext) {
        addJump("if-lt", ctx.label(), ctx)
    }

    override fun exitIfGeInstruction(ctx: GeneratedSmaliParser.IfGeInstructionContext) {
        addJump("if-ge", ctx.label(), ctx)
    }

    override fun exitIfGtInstruction(ctx: GeneratedSmaliParser.IfGtInstructionContext) {
        addJump("if-gt", ctx.label(), ctx)
    }

    override fun exitIfLeInstruction(ctx: GeneratedSmaliParser.IfLeInstructionContext) {
        addJump("if-le", ctx.label(), ctx)
    }

    /**
     * Common helper to parse jump instructions
     */
    private fun addJump(
        opcode: String,
        labelCtx: GeneratedSmaliParser.LabelContext?,
        ctx: ParserRuleContext
    ) {
        if (pendingMethod == null || labelCtx == null) return

        val labelName = strings.intern(labelCtx.identifier()?.src() ?: return)
        val label = labelCtx.toSpan()

        currentInstructions.add(JumpInstruction(
            opcode = opcode,
            targetLabel = labelName,
            startLine = ctx.startLine, startChar = ctx.startChar, endLine = ctx.endLine, endChar = ctx.endChar,
            labelLine = label.line, labelStart = label.start, labelEnd = label.end
        ))
        currentLabelRefs.add(LabelRef(labelName, label))
    }
}
