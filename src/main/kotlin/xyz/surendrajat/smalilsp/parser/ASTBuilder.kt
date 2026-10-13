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
    private val source: String
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
    private val currentLabelRefs = mutableListOf<LabelRef>()

    /** > 0 while inside a directive whose class name tokens are recorded as [TypeRef]s. */
    private var typeRefDepth = 0

    override fun exitClassDirective(ctx: GeneratedSmaliParser.ClassDirectiveContext) {
        className = ctx.className()?.src()?.let { StringPool.intern(it) }
        classNameSpan = ctx.className()?.toSpan()
        ctx.classModifier().forEach { mod ->
            classModifiers.add(StringPool.intern(mod.src()))
        }
        classRange = ctx.toRange()
    }

    override fun exitSuperDirective(ctx: GeneratedSmaliParser.SuperDirectiveContext) {
        superClass = ctx.superName()?.src()?.let { StringPool.intern(it) }
        superClassRange = ctx.superName()?.toRange() ?: ctx.toRange()
    }

    override fun exitImplementsDirective(ctx: GeneratedSmaliParser.ImplementsDirectiveContext) {
        ctx.referenceType()?.let { type ->
            val internedName = StringPool.intern(type.src())
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

        val fieldName = StringPool.intern(parts[0])
        val fieldType = StringPool.intern(parts[1])  // Many duplicates: I, Z, V, Ljava/lang/String;
        val fieldModifiers = ctx.fieldModifier().map { StringPool.intern(it.src()) }.toSet()
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
        currentLabelRefs.clear()

        val signature = ctx.methodSignature() ?: return
        val sigText = signature.src()

        // Format: methodName(params)returnType, e.g. <init>()V or getPath(FFFF)Landroid/graphics/Path;
        val openParen = sigText.indexOf('(')
        if (openParen <= 0) return
        val closeParen = sigText.indexOf(')', openParen)
        if (closeParen < 0) return

        val methodName = StringPool.intern(sigText.substring(0, openParen))
        val params = sigText.substring(openParen + 1, closeParen)
        val returnType = StringPool.intern(sigText.substring(closeParen + 1))  // Many duplicates: V, I, Z
        val modifiers = ctx.methodModifier().map { StringPool.intern(it.src()) }.toSet()

        val lparen = signature.LPAREN()?.symbol
        val first = signature.start
        val last = signature.stop
        val nameSpan = if (lparen != null && lparen.line == first.line) Span(first.line - 1, first.charPositionInLine, lparen.charPositionInLine) else null
        val descriptorSpan = if (lparen != null && last != null && last.line == lparen.line) Span(lparen.line - 1, lparen.charPositionInLine, last.charPositionInLine + last.length) else null

        pendingMethod = PendingMethod(
            name = methodName,
            descriptor = StringPool.intern("($params)$returnType"),
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
                labelRefs = currentLabelRefs.toList()
            ))
        }
        pendingMethod = null
        currentInstructions.clear()
        currentLabels.clear()
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
            Parameter(StringPool.intern(type), null)
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

    /** Inside an annotation, `.local` or method prototype every class name token is a reference. */
    override fun visitTerminal(node: TerminalNode) {
        if (typeRefDepth > 0 && node.symbol.type == GeneratedSmaliLexer.QUALIFIED_TYPE_NAME) {
            typeRefs.add(TypeRef(StringPool.intern(node.text), node.symbol.toSpan()))
        }
    }

    override fun exitCatchDirective(ctx: GeneratedSmaliParser.CatchDirectiveContext) {
        ctx.catchExceptionType()?.let { typeRefs.add(TypeRef(StringPool.intern(it.src()), it.toSpan())) }
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
        currentLabelRefs.add(LabelRef(StringPool.intern(name), labelCtx.toSpan()))
    }

    // ========== Instruction Parsing ==========

    override fun exitInvokeVirtualInstruction(ctx: GeneratedSmaliParser.InvokeVirtualInstructionContext) {
        parseInvokeInstruction("invoke-virtual", ctx.methodInvocationTarget(), ctx.toRange())
    }

    override fun exitInvokeVirtualRangeInstruction(ctx: GeneratedSmaliParser.InvokeVirtualRangeInstructionContext) {
        parseInvokeInstruction("invoke-virtual/range", ctx.methodInvocationTarget(), ctx.toRange())
    }

    override fun exitInvokeSuperInstruction(ctx: GeneratedSmaliParser.InvokeSuperInstructionContext) {
        parseInvokeInstruction("invoke-super", ctx.methodInvocationTarget(), ctx.toRange())
    }

    override fun exitInvokeSuperRangeInstruction(ctx: GeneratedSmaliParser.InvokeSuperRangeInstructionContext) {
        parseInvokeInstruction("invoke-super/range", ctx.methodInvocationTarget(), ctx.toRange())
    }

    override fun exitInvokeDirectInstruction(ctx: GeneratedSmaliParser.InvokeDirectInstructionContext) {
        parseInvokeInstruction("invoke-direct", ctx.methodInvocationTarget(), ctx.toRange())
    }

    override fun exitInvokeDirectRangeInstruction(ctx: GeneratedSmaliParser.InvokeDirectRangeInstructionContext) {
        parseInvokeInstruction("invoke-direct/range", ctx.methodInvocationTarget(), ctx.toRange())
    }

    override fun exitInvokeStaticInstruction(ctx: GeneratedSmaliParser.InvokeStaticInstructionContext) {
        parseInvokeInstruction("invoke-static", ctx.methodInvocationTarget(), ctx.toRange())
    }

    override fun exitInvokeStaticRangeInstruction(ctx: GeneratedSmaliParser.InvokeStaticRangeInstructionContext) {
        parseInvokeInstruction("invoke-static/range", ctx.methodInvocationTarget(), ctx.toRange())
    }

    override fun exitInvokeInterfaceInstruction(ctx: GeneratedSmaliParser.InvokeInterfaceInstructionContext) {
        parseInvokeInstruction("invoke-interface", ctx.methodInvocationTarget(), ctx.toRange())
    }

    override fun exitInvokeInterfaceRangeInstruction(ctx: GeneratedSmaliParser.InvokeInterfaceRangeInstructionContext) {
        parseInvokeInstruction("invoke-interface/range", ctx.methodInvocationTarget(), ctx.toRange())
    }

    /** invoke-polymorphic targets a regular method reference followed by a call-site prototype. */
    override fun exitInvokePolymorphicInstruction(ctx: GeneratedSmaliParser.InvokePolymorphicInstructionContext) {
        parseInvokeInstruction("invoke-polymorphic", ctx.methodInvocationTarget(), ctx.toRange())
    }

    override fun exitInvokePolymorphicRangeInstruction(ctx: GeneratedSmaliParser.InvokePolymorphicRangeInstructionContext) {
        parseInvokeInstruction("invoke-polymorphic/range", ctx.methodInvocationTarget(), ctx.toRange())
    }

    /** invoke-custom: the navigable symbol is the bootstrap method after `@`. */
    override fun exitInvokeCustomInstruction(ctx: GeneratedSmaliParser.InvokeCustomInstructionContext) {
        parseInvokeInstruction("invoke-custom", ctx.callSiteReference()?.methodReference(), ctx.toRange())
    }

    override fun exitInvokeCustomRangeInstruction(ctx: GeneratedSmaliParser.InvokeCustomRangeInstructionContext) {
        parseInvokeInstruction("invoke-custom/range", ctx.callSiteReference()?.methodReference(), ctx.toRange())
    }

    /** const-method-handle references either a method (`invoke-static@...`) or a field (`instance-get@...`). */
    override fun exitInvokeConstMethodHandleInstruction(ctx: GeneratedSmaliParser.InvokeConstMethodHandleInstructionContext) {
        val handle = ctx.methodHandleReference() ?: return
        handle.methodReference()?.let { parseInvokeInstruction("const-method-handle", it, ctx.toRange()) }
        handle.fieldInvocationTarget()?.let { parseFieldAccessInstruction("const-method-handle", it, ctx.toRange()) }
    }

    /**
     * Common helper to parse all invoke instructions.
     * [target] is any context whose text has the form `Lcom/Foo;->name(desc)ret`
     * (methodInvocationTarget or methodReference).
     */
    private fun parseInvokeInstruction(
        opcode: String,
        target: ParserRuleContext?,
        range: Range
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
            className = StringPool.intern(className),
            methodName = StringPool.intern(methodSig.substring(0, openParen)),
            descriptor = StringPool.intern(methodSig.substring(openParen)),
            range = range,
            targetLine = target.start.line - 1,
            targetStart = target.contiguousStart(targetText)
        ))
    }

    override fun exitIgetInstruction(ctx: GeneratedSmaliParser.IgetInstructionContext) {
        parseFieldAccessInstruction("iget", ctx.instanceField(), ctx.toRange())
    }

    override fun exitIgetWideInstruction(ctx: GeneratedSmaliParser.IgetWideInstructionContext) {
        parseFieldAccessInstruction("iget-wide", ctx.instanceField(), ctx.toRange())
    }

    override fun exitIgetObjectInstruction(ctx: GeneratedSmaliParser.IgetObjectInstructionContext) {
        parseFieldAccessInstruction("iget-object", ctx.instanceField(), ctx.toRange())
    }

    override fun exitIgetBooleanInstruction(ctx: GeneratedSmaliParser.IgetBooleanInstructionContext) {
        parseFieldAccessInstruction("iget-boolean", ctx.instanceField(), ctx.toRange())
    }

    override fun exitIgetByteInstruction(ctx: GeneratedSmaliParser.IgetByteInstructionContext) {
        parseFieldAccessInstruction("iget-byte", ctx.instanceField(), ctx.toRange())
    }

    override fun exitIgetCharInstruction(ctx: GeneratedSmaliParser.IgetCharInstructionContext) {
        parseFieldAccessInstruction("iget-char", ctx.instanceField(), ctx.toRange())
    }

    override fun exitIgetShortInstruction(ctx: GeneratedSmaliParser.IgetShortInstructionContext) {
        parseFieldAccessInstruction("iget-short", ctx.instanceField(), ctx.toRange())
    }

    override fun exitIputInstruction(ctx: GeneratedSmaliParser.IputInstructionContext) {
        parseFieldAccessInstruction("iput", ctx.instanceField(), ctx.toRange())
    }

    override fun exitIputWideInstruction(ctx: GeneratedSmaliParser.IputWideInstructionContext) {
        parseFieldAccessInstruction("iput-wide", ctx.instanceField(), ctx.toRange())
    }

    override fun exitIputObjectInstruction(ctx: GeneratedSmaliParser.IputObjectInstructionContext) {
        parseFieldAccessInstruction("iput-object", ctx.instanceField(), ctx.toRange())
    }

    override fun exitIputBooleanInstruction(ctx: GeneratedSmaliParser.IputBooleanInstructionContext) {
        parseFieldAccessInstruction("iput-boolean", ctx.instanceField(), ctx.toRange())
    }

    override fun exitIputByteInstruction(ctx: GeneratedSmaliParser.IputByteInstructionContext) {
        parseFieldAccessInstruction("iput-byte", ctx.instanceField(), ctx.toRange())
    }

    override fun exitIputCharInstruction(ctx: GeneratedSmaliParser.IputCharInstructionContext) {
        parseFieldAccessInstruction("iput-char", ctx.instanceField(), ctx.toRange())
    }

    override fun exitIputShortInstruction(ctx: GeneratedSmaliParser.IputShortInstructionContext) {
        parseFieldAccessInstruction("iput-short", ctx.instanceField(), ctx.toRange())
    }

    override fun exitSGetInstruction(ctx: GeneratedSmaliParser.SGetInstructionContext) {
        parseFieldAccessInstruction("sget", ctx.fieldInvocationTarget(), ctx.toRange())
    }

    override fun exitSGetWideInstruction(ctx: GeneratedSmaliParser.SGetWideInstructionContext) {
        parseFieldAccessInstruction("sget-wide", ctx.fieldInvocationTarget(), ctx.toRange())
    }

    override fun exitSGetObjectInstruction(ctx: GeneratedSmaliParser.SGetObjectInstructionContext) {
        parseFieldAccessInstruction("sget-object", ctx.fieldInvocationTarget(), ctx.toRange())
    }

    override fun exitSGetBooleanInstruction(ctx: GeneratedSmaliParser.SGetBooleanInstructionContext) {
        parseFieldAccessInstruction("sget-boolean", ctx.fieldInvocationTarget(), ctx.toRange())
    }

    override fun exitSGetByteInstruction(ctx: GeneratedSmaliParser.SGetByteInstructionContext) {
        parseFieldAccessInstruction("sget-byte", ctx.fieldInvocationTarget(), ctx.toRange())
    }

    override fun exitSGetCharInstruction(ctx: GeneratedSmaliParser.SGetCharInstructionContext) {
        parseFieldAccessInstruction("sget-char", ctx.fieldInvocationTarget(), ctx.toRange())
    }

    override fun exitSGetShortInstruction(ctx: GeneratedSmaliParser.SGetShortInstructionContext) {
        parseFieldAccessInstruction("sget-short", ctx.fieldInvocationTarget(), ctx.toRange())
    }

    override fun exitSPutInstruction(ctx: GeneratedSmaliParser.SPutInstructionContext) {
        parseFieldAccessInstruction("sput", ctx.fieldInvocationTarget(), ctx.toRange())
    }

    override fun exitSPutWideInstruction(ctx: GeneratedSmaliParser.SPutWideInstructionContext) {
        parseFieldAccessInstruction("sput-wide", ctx.fieldInvocationTarget(), ctx.toRange())
    }

    override fun exitSPutObjectInstruction(ctx: GeneratedSmaliParser.SPutObjectInstructionContext) {
        parseFieldAccessInstruction("sput-object", ctx.fieldInvocationTarget(), ctx.toRange())
    }

    override fun exitSPutBooleanInstruction(ctx: GeneratedSmaliParser.SPutBooleanInstructionContext) {
        parseFieldAccessInstruction("sput-boolean", ctx.fieldInvocationTarget(), ctx.toRange())
    }

    override fun exitSPutByteInstruction(ctx: GeneratedSmaliParser.SPutByteInstructionContext) {
        parseFieldAccessInstruction("sput-byte", ctx.fieldInvocationTarget(), ctx.toRange())
    }

    override fun exitSPutCharInstruction(ctx: GeneratedSmaliParser.SPutCharInstructionContext) {
        parseFieldAccessInstruction("sput-char", ctx.fieldInvocationTarget(), ctx.toRange())
    }

    override fun exitSPutShortInstruction(ctx: GeneratedSmaliParser.SPutShortInstructionContext) {
        parseFieldAccessInstruction("sput-short", ctx.fieldInvocationTarget(), ctx.toRange())
    }

    /**
     * Common helper to parse field access instructions
     */
    private fun parseFieldAccessInstruction(
        opcode: String,
        fieldCtx: ParserRuleContext?,
        range: Range
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
            className = StringPool.intern(className),
            fieldName = StringPool.intern(fieldPart.substring(0, colonIndex)),
            fieldType = StringPool.intern(fieldPart.substring(colonIndex + 1)),
            range = range,
            targetLine = fieldCtx.start.line - 1,
            targetStart = fieldCtx.contiguousStart(fieldText)
        ))
    }

    override fun exitNewInstanceInstruction(ctx: GeneratedSmaliParser.NewInstanceInstructionContext) {
        addTypeInstruction("new-instance", ctx.newInstanceType(), ctx.toRange())
    }

    override fun exitCheckCastInstruction(ctx: GeneratedSmaliParser.CheckCastInstructionContext) {
        addTypeInstruction("check-cast", ctx.checkCastType(), ctx.toRange())
    }

    override fun exitInstanceOfInstruction(ctx: GeneratedSmaliParser.InstanceOfInstructionContext) {
        addTypeInstruction("instance-of", ctx.checkInstanceType(), ctx.toRange())
    }

    override fun exitConstClass(ctx: GeneratedSmaliParser.ConstClassContext) {
        addTypeInstruction("const-class", ctx.referenceOrArrayType(), ctx.toRange())
    }

    /** filled-new-array {v0, v1, v2}, [Lcom/example/MyClass; */
    override fun exitFilledNewArrayInstruction(ctx: GeneratedSmaliParser.FilledNewArrayInstructionContext) {
        addTypeInstruction("filled-new-array", ctx.arrayElementType(), ctx.toRange())
    }

    /** filled-new-array/range {v0 .. v9}, [[Lt5/d; */
    override fun exitFilledNewArrayRangeInstruction(ctx: GeneratedSmaliParser.FilledNewArrayRangeInstructionContext) {
        addTypeInstruction("filled-new-array/range", ctx.arrayElementType(), ctx.toRange())
    }

    /** new-array v1, v0, [I */
    override fun exitNewArrayInstruction(ctx: GeneratedSmaliParser.NewArrayInstructionContext) {
        addTypeInstruction("new-array", ctx.arrayElementType(), ctx.toRange())
    }

    private fun addTypeInstruction(opcode: String, typeCtx: ParserRuleContext?, range: Range) {
        if (pendingMethod == null || typeCtx == null) return
        val className = typeCtx.src()
        currentInstructions.add(TypeInstruction(
            opcode = opcode,
            className = StringPool.intern(className),
            range = range,
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

        currentInstructions.add(ConstStringInstruction(
            value = value,
            range = ctx.toRange()
        ))
    }

    /** const-string/jumbo v0, "string value" */
    override fun exitConstStringJumbo(ctx: GeneratedSmaliParser.ConstStringJumboContext) {
        if (pendingMethod == null) return

        val rawLiteral = ctx.stringLiteral()?.src() ?: return
        val value = rawLiteral.removeSurrounding("\"")

        currentInstructions.add(ConstStringInstruction(
            value = value,
            range = ctx.toRange()
        ))
    }

    // ========== Label Parsing ==========

    /** Label definition (e.g., :cond_0); lineLabel is the rule for labels in method body statements. */
    override fun exitLineLabel(ctx: GeneratedSmaliParser.LineLabelContext) {
        if (pendingMethod == null) return

        val labelCtx = ctx.label() ?: return
        val labelName = StringPool.intern(labelCtx.identifier()?.src() ?: return)

        currentLabels[labelName] = LabelDefinition(
            name = labelName,
            range = ctx.toRange()
        )
    }

    override fun exitGotoInstruction(ctx: GeneratedSmaliParser.GotoInstructionContext) {
        parseJumpInstruction("goto", ctx.label(), ctx.toRange())
    }

    override fun exitGoto16Instruction(ctx: GeneratedSmaliParser.Goto16InstructionContext) {
        parseJumpInstruction("goto/16", ctx.label(), ctx.toRange())
    }

    override fun exitGoto32Instruction(ctx: GeneratedSmaliParser.Goto32InstructionContext) {
        parseJumpInstruction("goto/32", ctx.label(), ctx.toRange())
    }

    override fun exitIfEqzInstruction(ctx: GeneratedSmaliParser.IfEqzInstructionContext) {
        parseJumpInstruction("if-eqz", ctx.label(), ctx.toRange())
    }

    override fun exitIfNezInstruction(ctx: GeneratedSmaliParser.IfNezInstructionContext) {
        parseJumpInstruction("if-nez", ctx.label(), ctx.toRange())
    }

    override fun exitIfLtzInstruction(ctx: GeneratedSmaliParser.IfLtzInstructionContext) {
        parseJumpInstruction("if-ltz", ctx.label(), ctx.toRange())
    }

    override fun exitIfGezInstruction(ctx: GeneratedSmaliParser.IfGezInstructionContext) {
        parseJumpInstruction("if-gez", ctx.label(), ctx.toRange())
    }

    override fun exitIfGtzInstruction(ctx: GeneratedSmaliParser.IfGtzInstructionContext) {
        parseJumpInstruction("if-gtz", ctx.label(), ctx.toRange())
    }

    override fun exitIfLezInstruction(ctx: GeneratedSmaliParser.IfLezInstructionContext) {
        parseJumpInstruction("if-lez", ctx.label(), ctx.toRange())
    }

    override fun exitIfEqInstruction(ctx: GeneratedSmaliParser.IfEqInstructionContext) {
        parseJumpInstruction("if-eq", ctx.label(), ctx.toRange())
    }

    override fun exitIfNeInstruction(ctx: GeneratedSmaliParser.IfNeInstructionContext) {
        parseJumpInstruction("if-ne", ctx.label(), ctx.toRange())
    }

    override fun exitIfLtInstruction(ctx: GeneratedSmaliParser.IfLtInstructionContext) {
        parseJumpInstruction("if-lt", ctx.label(), ctx.toRange())
    }

    override fun exitIfGeInstruction(ctx: GeneratedSmaliParser.IfGeInstructionContext) {
        parseJumpInstruction("if-ge", ctx.label(), ctx.toRange())
    }

    override fun exitIfGtInstruction(ctx: GeneratedSmaliParser.IfGtInstructionContext) {
        parseJumpInstruction("if-gt", ctx.label(), ctx.toRange())
    }

    override fun exitIfLeInstruction(ctx: GeneratedSmaliParser.IfLeInstructionContext) {
        parseJumpInstruction("if-le", ctx.label(), ctx.toRange())
    }

    /**
     * Common helper to parse jump instructions
     */
    private fun parseJumpInstruction(
        opcode: String,
        labelCtx: GeneratedSmaliParser.LabelContext?,
        fullRange: Range
    ) {
        if (pendingMethod == null || labelCtx == null) return

        val labelName = StringPool.intern(labelCtx.identifier()?.src() ?: return)

        currentInstructions.add(JumpInstruction(
            opcode = opcode,
            targetLabel = labelName,
            range = fullRange,
            labelRange = labelCtx.toRange()
        ))
        currentLabelRefs.add(LabelRef(labelName, labelCtx.toSpan()))
    }
}
