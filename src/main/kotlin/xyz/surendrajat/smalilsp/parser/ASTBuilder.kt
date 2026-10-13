package xyz.surendrajat.smalilsp.parser

import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.parser.generated.SmaliParserBaseListener
import xyz.surendrajat.smalilsp.parser.generated.SmaliParser as GeneratedSmaliParser
import xyz.surendrajat.smalilsp.util.DescriptorParser
import xyz.surendrajat.smalilsp.util.StringPool

/**
 * Builds our clean data structures from the ANTLR parse tree.
 * 
 * Design: Keep it simple. Extract the raw text and parse the Smali-specific
 * formats ourselves. This is more reliable than trying to navigate the complex
 * ANTLR tree structure.
 *
 * Attached as a *parse* listener (`parser.addParseListener`), so the model is built
 * while the parser runs instead of by a second walk over the finished tree; every
 * callback is therefore an `exit` hook, where a node's children and stop token exist.
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
     * biggest parse-pipeline cost. Token offsets are UTF-16 string indices (see
     * [StringCharStream]), so a substring is exact. If the span holds whitespace or a
     * comment (hand-written smali may put spaces inside a signature), fall back to the
     * concatenation so the result stays identical to `getText()`.
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
    
    // Accumulated data
    private var className: String? = null
    private var classRange: Range? = null
    private val classModifiers = mutableSetOf<String>()
    private var superClass: String? = null
    private var superClassRange: Range? = null
    private val interfaces = mutableListOf<String>()
    private val interfaceRanges = mutableMapOf<String, Range>()
    private val methods = mutableListOf<MethodDefinition>()
    private val fields = mutableListOf<FieldDefinition>()
    
    // Declaration of the method whose body is being parsed (instructions attach to it)
    private class PendingMethod(
        val name: String,
        val descriptor: String,
        val modifiers: Set<String>,
        val parameters: List<Parameter>,
        val returnType: String
    )
    private var pendingMethod: PendingMethod? = null
    private val currentInstructions = mutableListOf<Instruction>()
    private val currentLabels = mutableMapOf<String, LabelDefinition>()
    
    override fun exitClassDirective(ctx: GeneratedSmaliParser.ClassDirectiveContext) {
        // Extract class name (intern for memory efficiency)
        className = ctx.className()?.src()?.let { StringPool.intern(it) }
        
        // Extract modifiers (intern common ones: public, private, static, final, etc.)
        ctx.classModifier().forEach { mod ->
            classModifiers.add(StringPool.intern(mod.src()))
        }
        
        // Create range for class definition
        classRange = ctx.toRange()
    }
    
    override fun exitSuperDirective(ctx: GeneratedSmaliParser.SuperDirectiveContext) {
        superClass = ctx.superName()?.src()?.let { StringPool.intern(it) }
        superClassRange = ctx.toRange()  // Capture .super directive range
    }
    
    override fun exitImplementsDirective(ctx: GeneratedSmaliParser.ImplementsDirectiveContext) {
        ctx.referenceType()?.src()?.let { interfaceName ->
            val internedName = StringPool.intern(interfaceName)
            interfaces.add(internedName)
            interfaceRanges[internedName] = ctx.toRange()  // Capture .implements directive range
        }
    }
    
    override fun exitFieldDirective(ctx: GeneratedSmaliParser.FieldDirectiveContext) {
        // Parse field name and type from fieldNameAndType
        val fieldNameAndType = ctx.fieldNameAndType()?.src() ?: return
        
        // Format is: name:type (e.g., myField:I)
        val parts = fieldNameAndType.split(":", limit = 2)
        if (parts.size != 2) return
        
        val fieldName = StringPool.intern(parts[0])
        val fieldType = StringPool.intern(parts[1])  // Many duplicates: I, Z, V, Ljava/lang/String;
        
        // Extract modifiers (intern common ones)
        val fieldModifiers = ctx.fieldModifier().map { StringPool.intern(it.src()) }.toSet()
        
        // Create field definition
        fields.add(FieldDefinition(
            name = fieldName,
            type = fieldType,
            range = ctx.toRange(),
            modifiers = fieldModifiers
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

        val sigText = ctx.methodSignature()?.src() ?: return

        // Format: methodName(params)returnType, e.g. <init>()V or getPath(FFFF)Landroid/graphics/Path;
        val openParen = sigText.indexOf('(')
        if (openParen <= 0) return
        val closeParen = sigText.indexOf(')', openParen)
        if (closeParen < 0) return

        val methodName = StringPool.intern(sigText.substring(0, openParen))
        val params = sigText.substring(openParen + 1, closeParen)
        val returnType = StringPool.intern(sigText.substring(closeParen + 1))  // Many duplicates: V, I, Z
        val modifiers = ctx.methodModifier().map { StringPool.intern(it.src()) }.toSet()

        pendingMethod = PendingMethod(
            name = methodName,
            descriptor = StringPool.intern("($params)$returnType"),
            modifiers = modifiers,
            parameters = parseParameters(params),
            returnType = returnType
        )
    }

    override fun exitMethodDirective(ctx: GeneratedSmaliParser.MethodDirectiveContext) {
        pendingMethod?.let { m ->
            methods.add(MethodDefinition(
                name = m.name,
                descriptor = m.descriptor,
                range = ctx.toRange(),
                modifiers = m.modifiers,
                parameters = m.parameters,
                returnType = m.returnType,
                instructions = currentInstructions.toList(),
                labels = currentLabels.toMap()
            ))
        }
        pendingMethod = null
        currentInstructions.clear()
        currentLabels.clear()
    }

    /**
     * Parse parameter types from descriptor string.
     * Delegates to DescriptorParser for the core parsing logic,
     * then interns strings via StringPool for memory efficiency.
     */
    private fun parseParameters(paramStr: String): List<Parameter> {
        if (paramStr.isEmpty()) return emptyList()
        return DescriptorParser.parseTypeStrings(paramStr).map { type ->
            Parameter(StringPool.intern(type), null)
        }
    }
    
    /**
     * Build the final SmaliFile after tree walking completes.
     */
    fun build(): SmaliFile? {
        if (className == null || classRange == null) {
            return null
        }
        
        return SmaliFile(
            uri = uri,
            classDefinition = ClassDefinition(
                name = className!!,
                range = classRange!!,
                modifiers = classModifiers.toSet(),
                superClass = superClass,
                interfaces = interfaces.toList(),
                superClassRange = superClassRange,
                interfaceRanges = interfaceRanges.toMap()
            ),
            methods = methods.toList(),
            fields = fields.toList()
        )
    }
    
    /**
     * Convert ANTLR context to our Range type.
     */
    private fun ParserRuleContext.toRange(): Range {
        val startToken = this.start
        val stopToken = this.stop ?: startToken
        
        return range(
            startToken.line - 1,  // LSP is 0-based
            startToken.charPositionInLine,
            stopToken.line - 1,
            stopToken.charPositionInLine + (stopToken.stopIndex - stopToken.startIndex + 1)
        )
    }
    
    // ========== Instruction Parsing ==========
    
    /**
     * Parse invoke-virtual instruction
     */
    override fun exitInvokeVirtualInstruction(ctx: GeneratedSmaliParser.InvokeVirtualInstructionContext) {
        parseInvokeInstruction("invoke-virtual", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    override fun exitInvokeVirtualRangeInstruction(ctx: GeneratedSmaliParser.InvokeVirtualRangeInstructionContext) {
        parseInvokeInstruction("invoke-virtual/range", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    /**
     * Parse invoke-super instruction
     */
    override fun exitInvokeSuperInstruction(ctx: GeneratedSmaliParser.InvokeSuperInstructionContext) {
        parseInvokeInstruction("invoke-super", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    override fun exitInvokeSuperRangeInstruction(ctx: GeneratedSmaliParser.InvokeSuperRangeInstructionContext) {
        parseInvokeInstruction("invoke-super/range", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    /**
     * Parse invoke-direct instruction
     */
    override fun exitInvokeDirectInstruction(ctx: GeneratedSmaliParser.InvokeDirectInstructionContext) {
        parseInvokeInstruction("invoke-direct", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    override fun exitInvokeDirectRangeInstruction(ctx: GeneratedSmaliParser.InvokeDirectRangeInstructionContext) {
        parseInvokeInstruction("invoke-direct/range", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    /**
     * Parse invoke-static instruction
     */
    override fun exitInvokeStaticInstruction(ctx: GeneratedSmaliParser.InvokeStaticInstructionContext) {
        parseInvokeInstruction("invoke-static", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    override fun exitInvokeStaticRangeInstruction(ctx: GeneratedSmaliParser.InvokeStaticRangeInstructionContext) {
        parseInvokeInstruction("invoke-static/range", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    /**
     * Parse invoke-interface instruction
     */
    override fun exitInvokeInterfaceInstruction(ctx: GeneratedSmaliParser.InvokeInterfaceInstructionContext) {
        parseInvokeInstruction("invoke-interface", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    override fun exitInvokeInterfaceRangeInstruction(ctx: GeneratedSmaliParser.InvokeInterfaceRangeInstructionContext) {
        parseInvokeInstruction("invoke-interface/range", ctx.methodInvocationTarget(), ctx.toRange())
    }
    
    /**
     * invoke-polymorphic targets a regular method reference followed by a call-site prototype.
     */
    override fun exitInvokePolymorphicInstruction(ctx: GeneratedSmaliParser.InvokePolymorphicInstructionContext) {
        parseInvokeInstruction("invoke-polymorphic", ctx.methodInvocationTarget(), ctx.toRange())
    }

    override fun exitInvokePolymorphicRangeInstruction(ctx: GeneratedSmaliParser.InvokePolymorphicRangeInstructionContext) {
        parseInvokeInstruction("invoke-polymorphic/range", ctx.methodInvocationTarget(), ctx.toRange())
    }

    /**
     * invoke-custom: the navigable symbol is the bootstrap method after `@`.
     */
    override fun exitInvokeCustomInstruction(ctx: GeneratedSmaliParser.InvokeCustomInstructionContext) {
        parseInvokeInstruction("invoke-custom", ctx.callSiteReference()?.methodReference(), ctx.toRange())
    }

    override fun exitInvokeCustomRangeInstruction(ctx: GeneratedSmaliParser.InvokeCustomRangeInstructionContext) {
        parseInvokeInstruction("invoke-custom/range", ctx.callSiteReference()?.methodReference(), ctx.toRange())
    }

    /**
     * const-method-handle references either a method (`invoke-static@...`) or a field (`instance-get@...`).
     */
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
        
        // Parse method signature
        val openParen = methodSig.indexOf('(')
        if (openParen < 0) return
        
        val methodName = methodSig.substring(0, openParen)
        val descriptor = methodSig.substring(openParen)
        
        currentInstructions.add(InvokeInstruction(
            opcode = opcode,
            className = className,
            methodName = methodName,
            descriptor = descriptor,
            range = range
        ))
    }
    
    /**
     * Parse iget instructions
     */
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
    
    /**
     * Parse iput instructions
     */
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
    
    /**
     * Parse sget instructions
     */
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
    
    /**
     * Parse sput instructions
     */
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
        
        // Parse field name and type
        val colonIndex = fieldPart.indexOf(':')
        if (colonIndex < 0) return
        
        val fieldName = fieldPart.substring(0, colonIndex)
        val fieldType = fieldPart.substring(colonIndex + 1)
        
        currentInstructions.add(FieldAccessInstruction(
            opcode = opcode,
            className = className,
            fieldName = fieldName,
            fieldType = fieldType,
            range = range
        ))
    }
    
    /**
     * Parse new-instance instruction
     */
    override fun exitNewInstanceInstruction(ctx: GeneratedSmaliParser.NewInstanceInstructionContext) {
        if (pendingMethod == null) return
        
        val className = ctx.newInstanceType()?.src() ?: return
        
        currentInstructions.add(TypeInstruction(
            opcode = "new-instance",
            className = className,
            range = ctx.toRange()
        ))
    }
    
    /**
     * Parse check-cast instruction
     */
    override fun exitCheckCastInstruction(ctx: GeneratedSmaliParser.CheckCastInstructionContext) {
        if (pendingMethod == null) return
        
        val className = ctx.checkCastType()?.src() ?: return
        
        currentInstructions.add(TypeInstruction(
            opcode = "check-cast",
            className = className,
            range = ctx.toRange()
        ))
    }
    
    /**
     * Parse instance-of instruction
     */
    override fun exitInstanceOfInstruction(ctx: GeneratedSmaliParser.InstanceOfInstructionContext) {
        if (pendingMethod == null) return
        
        val className = ctx.checkInstanceType()?.src() ?: return
        
        currentInstructions.add(TypeInstruction(
            opcode = "instance-of",
            className = className,
            range = ctx.toRange()
        ))
    }
    
    /**
     * Parse const-class instruction
     */
    override fun exitConstClass(ctx: GeneratedSmaliParser.ConstClassContext) {
        if (pendingMethod == null) return
        
        val className = ctx.referenceOrArrayType()?.src() ?: return
        
        currentInstructions.add(TypeInstruction(
            opcode = "const-class",
            className = className,
            range = ctx.toRange()
        ))
    }
    
    /**
     * Parse filled-new-array instruction
     * Format: filled-new-array {v0, v1, v2}, [Lcom/example/MyClass;
     */
    override fun exitFilledNewArrayInstruction(ctx: GeneratedSmaliParser.FilledNewArrayInstructionContext) {
        if (pendingMethod == null) return
        
        val className = ctx.arrayElementType()?.src() ?: return
        
        currentInstructions.add(TypeInstruction(
            opcode = "filled-new-array",
            className = className,
            range = ctx.toRange()
        ))
    }
    
    /**
     * Parse filled-new-array/range instruction
     * Format: filled-new-array/range {v0 .. v9}, [[Lt5/d;
     */
    override fun exitFilledNewArrayRangeInstruction(ctx: GeneratedSmaliParser.FilledNewArrayRangeInstructionContext) {
        if (pendingMethod == null) return
        
        val className = ctx.arrayElementType()?.src() ?: return
        
        currentInstructions.add(TypeInstruction(
            opcode = "filled-new-array/range",
            className = className,
            range = ctx.toRange()
        ))
    }
    
    /**
     * Parse new-array instruction
     * Format: new-array v1, v0, [I
     */
    override fun exitNewArrayInstruction(ctx: GeneratedSmaliParser.NewArrayInstructionContext) {
        if (pendingMethod == null) return
        
        val className = ctx.arrayElementType()?.src() ?: return
        
        currentInstructions.add(TypeInstruction(
            opcode = "new-array",
            className = className,
            range = ctx.toRange()
        ))
    }
    
    // ========== Label Parsing ==========

    // ========== String Constant Parsing ==========

    /**
     * Parse const-string instruction
     * Format: const-string v0, "string value"
     */
    override fun exitConstString(ctx: GeneratedSmaliParser.ConstStringContext) {
        if (pendingMethod == null) return

        val rawLiteral = ctx.stringLiteral()?.src() ?: return
        val value = rawLiteral.removeSurrounding("\"")

        currentInstructions.add(ConstStringInstruction(
            value = value,
            range = ctx.toRange()
        ))
    }

    /**
     * Parse const-string/jumbo instruction
     * Format: const-string/jumbo v0, "string value"
     */
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
    
    /**
     * Parse label definition (e.g., :cond_0)
     * lineLabel is the rule for labels in method body statements
     */
    override fun exitLineLabel(ctx: GeneratedSmaliParser.LineLabelContext) {
        if (pendingMethod == null) return
        
        val labelCtx = ctx.label() ?: return
        val labelName = labelCtx.labelName()?.src() ?: return
        
        currentLabels[labelName] = LabelDefinition(
            name = labelName,
            range = ctx.toRange()
        )
    }
    
    /**
     * Parse goto instruction
     */
    override fun exitGotoInstruction(ctx: GeneratedSmaliParser.GotoInstructionContext) {
        parseJumpInstruction("goto", ctx.label(), ctx.toRange())
    }
    
    override fun exitGoto16Instruction(ctx: GeneratedSmaliParser.Goto16InstructionContext) {
        parseJumpInstruction("goto/16", ctx.label(), ctx.toRange())
    }
    
    override fun exitGoto32Instruction(ctx: GeneratedSmaliParser.Goto32InstructionContext) {
        parseJumpInstruction("goto/32", ctx.label(), ctx.toRange())
    }
    
    /**
     * Parse if-*z instructions (single register tests)
     */
    override fun exitIfEqzInstruction(ctx: GeneratedSmaliParser.IfEqzInstructionContext) {
        parseJumpInstruction("if-eqz", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun exitIfNezInstruction(ctx: GeneratedSmaliParser.IfNezInstructionContext) {
        parseJumpInstruction("if-nez", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun exitIfLtzInstruction(ctx: GeneratedSmaliParser.IfLtzInstructionContext) {
        parseJumpInstruction("if-ltz", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun exitIfGezInstruction(ctx: GeneratedSmaliParser.IfGezInstructionContext) {
        parseJumpInstruction("if-gez", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun exitIfGtzInstruction(ctx: GeneratedSmaliParser.IfGtzInstructionContext) {
        parseJumpInstruction("if-gtz", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun exitIfLezInstruction(ctx: GeneratedSmaliParser.IfLezInstructionContext) {
        parseJumpInstruction("if-lez", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    /**
     * Parse if-* instructions (two register comparisons)
     */
    override fun exitIfEqInstruction(ctx: GeneratedSmaliParser.IfEqInstructionContext) {
        parseJumpInstruction("if-eq", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun exitIfNeInstruction(ctx: GeneratedSmaliParser.IfNeInstructionContext) {
        parseJumpInstruction("if-ne", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun exitIfLtInstruction(ctx: GeneratedSmaliParser.IfLtInstructionContext) {
        parseJumpInstruction("if-lt", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun exitIfGeInstruction(ctx: GeneratedSmaliParser.IfGeInstructionContext) {
        parseJumpInstruction("if-ge", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun exitIfGtInstruction(ctx: GeneratedSmaliParser.IfGtInstructionContext) {
        parseJumpInstruction("if-gt", ctx.ifLabel()?.label(), ctx.toRange())
    }
    
    override fun exitIfLeInstruction(ctx: GeneratedSmaliParser.IfLeInstructionContext) {
        parseJumpInstruction("if-le", ctx.ifLabel()?.label(), ctx.toRange())
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
        
        val labelName = labelCtx.labelName()?.src() ?: return
        val labelRange = labelCtx.toRange()
        
        currentInstructions.add(JumpInstruction(
            opcode = opcode,
            targetLabel = labelName,
            range = fullRange,
            labelRange = labelRange
        ))
    }
}
