package xyz.surendrajat.smalilsp.parser

/**
 * What follows each opcode, as the assembler reads it: `R` a register, `LIST` a `{v0, v1}`
 * register list, `RANGE` a `{v0 .. v3}` register range, then the reference, literal, label or
 * type operand. Derived from the smali grammar: every one of the 224 opcodes has exactly one shape.
 */
enum class OperandShape {
    NONE,
    R,
    RR,
    RRR,
    RR_LITERAL,
    RR_REGISTER_OR_LITERAL,
    R_LITERAL,
    R_STRING,
    R_FIELD,
    RR_FIELD,
    LABEL,
    R_LABEL,
    RR_LABEL,
    LIST_METHOD,
    RANGE_METHOD,
    LIST_METHOD_PROTO,
    RANGE_METHOD_PROTO,
    LIST_CALL_SITE,
    RANGE_CALL_SITE,
    LIST_TYPE,
    RANGE_TYPE,
    R_REFERENCE_TYPE,
    R_REFERENCE_OR_ARRAY_TYPE,
    RR_NON_VOID_TYPE,
    R_METHOD_HANDLE,
    R_PROTO,
}

/** Opcode name to its operand shape; a name that is not here is not an instruction. */
object InstructionShapes {
    private val shapes: Map<String, OperandShape> = HashMap<String, OperandShape>(512).apply {
        for (op in listOf("nop", "return-void")) put(op, OperandShape.NONE)
        for (op in listOf("throw", "return", "move-result", "return-wide", "monitor-exit", "monitor-enter")) put(op, OperandShape.R)
        for (op in listOf("return-object", "move-exception", "move-result-wide", "move-result-object")) put(op, OperandShape.R)
        for (op in listOf("move", "move/16", "neg-int", "not-int", "neg-long", "not-long")) put(op, OperandShape.RR)
        for (op in listOf("move-wide", "neg-float", "neg-double", "int-to-byte", "int-to-char", "int-to-long")) put(op, OperandShape.RR)
        for (op in listOf("long-to-int", "move-object", "move/from16", "array-length", "float-to-int", "int-to-float")) put(op, OperandShape.RR)
        for (op in listOf("int-to-short", "move-wide/16", "or-int/2addr", "add-int/2addr", "and-int/2addr", "div-int/2addr")) put(op, OperandShape.RR)
        for (op in listOf("double-to-int", "float-to-long", "int-to-double", "long-to-float", "mul-int/2addr", "or-long/2addr")) put(op, OperandShape.RR)
        for (op in listOf("rem-int/2addr", "shl-int/2addr", "shr-int/2addr", "sub-int/2addr", "xor-int/2addr", "add-long/2addr")) put(op, OperandShape.RR)
        for (op in listOf("and-long/2addr", "div-long/2addr", "double-to-long", "long-to-double", "move-object/16", "mul-long/2addr")) put(op, OperandShape.RR)
        for (op in listOf("rem-long/2addr", "shl-long/2addr", "shr-long/2addr", "sub-long/2addr", "ushr-int/2addr", "xor-long/2addr")) put(op, OperandShape.RR)
        for (op in listOf("add-float/2addr", "div-float/2addr", "double-to-float", "float-to-double", "mul-float/2addr", "rem-float/2addr")) put(op, OperandShape.RR)
        for (op in listOf("sub-float/2addr", "ushr-long/2addr", "add-double/2addr", "div-double/2addr", "move-wide/from16", "mul-double/2addr")) put(op, OperandShape.RR)
        for (op in listOf("rem-double/2addr", "sub-double/2addr", "move-object/from16")) put(op, OperandShape.RR)
        for (op in listOf("or-int", "add-int", "and-int", "div-int", "mul-int", "or-long")) put(op, OperandShape.RRR)
        for (op in listOf("rem-int", "shl-int", "shr-int", "sub-int", "xor-int", "add-long")) put(op, OperandShape.RRR)
        for (op in listOf("and-long", "cmp-long", "div-long", "mul-long", "rem-long", "shl-long")) put(op, OperandShape.RRR)
        for (op in listOf("shr-long", "sub-long", "ushr-int", "xor-long", "add-float", "div-float")) put(op, OperandShape.RRR)
        for (op in listOf("mul-float", "rem-float", "sub-float", "ushr-long", "add-double", "cmpg-float")) put(op, OperandShape.RRR)
        for (op in listOf("cmpl-float", "div-double", "mul-double", "rem-double", "sub-double", "cmpg-double")) put(op, OperandShape.RRR)
        for (op in listOf("cmpl-double")) put(op, OperandShape.RRR)
        for (op in listOf("or-int/lit8", "add-int/lit8", "and-int/lit8", "div-int/lit8", "mul-int/lit8", "or-int/lit16")) put(op, OperandShape.RR_LITERAL)
        for (op in listOf("rem-int/lit8", "shl-int/lit8", "shr-int/lit8", "xor-int/lit8", "add-int/lit16", "and-int/lit16")) put(op, OperandShape.RR_LITERAL)
        for (op in listOf("div-int/lit16", "mul-int/lit16", "rem-int/lit16", "rsub-int/lit8", "ushr-int/lit8", "xor-int/lit16")) put(op, OperandShape.RR_LITERAL)
        for (op in listOf("aget", "aput", "rsub-int", "aget-byte", "aget-char", "aget-wide")) put(op, OperandShape.RR_REGISTER_OR_LITERAL)
        for (op in listOf("aput-byte", "aput-char", "aput-wide", "aget-short", "aput-short", "aget-object")) put(op, OperandShape.RR_REGISTER_OR_LITERAL)
        for (op in listOf("aput-object", "aget-boolean", "aput-boolean")) put(op, OperandShape.RR_REGISTER_OR_LITERAL)
        for (op in listOf("const", "const/4", "const/16", "const-wide", "const/high16", "const-wide/16")) put(op, OperandShape.R_LITERAL)
        for (op in listOf("const-wide/32", "const-wide/high16")) put(op, OperandShape.R_LITERAL)
        for (op in listOf("const-string", "const-string/jumbo")) put(op, OperandShape.R_STRING)
        for (op in listOf("sget", "sput", "sget-byte", "sget-char", "sget-wide", "sput-byte")) put(op, OperandShape.R_FIELD)
        for (op in listOf("sput-char", "sput-wide", "sget-short", "sput-short", "sget-object", "sput-object")) put(op, OperandShape.R_FIELD)
        for (op in listOf("sget-boolean", "sput-boolean")) put(op, OperandShape.R_FIELD)
        for (op in listOf("iget", "iput", "iget-byte", "iget-char", "iget-wide", "iput-byte")) put(op, OperandShape.RR_FIELD)
        for (op in listOf("iput-char", "iput-wide", "iget-short", "iput-short", "iget-object", "iput-object")) put(op, OperandShape.RR_FIELD)
        for (op in listOf("iget-boolean", "iput-boolean")) put(op, OperandShape.RR_FIELD)
        for (op in listOf("goto", "goto/16", "goto/32")) put(op, OperandShape.LABEL)
        for (op in listOf("if-eqz", "if-gez", "if-gtz", "if-lez", "if-ltz", "if-nez")) put(op, OperandShape.R_LABEL)
        for (op in listOf("packed-switch", "sparse-switch", "fill-array-data")) put(op, OperandShape.R_LABEL)
        for (op in listOf("if-eq", "if-ge", "if-gt", "if-le", "if-lt", "if-ne")) put(op, OperandShape.RR_LABEL)
        for (op in listOf("invoke-super", "invoke-direct", "invoke-static", "invoke-virtual", "invoke-interface")) put(op, OperandShape.LIST_METHOD)
        for (op in listOf("invoke-super/range", "invoke-direct/range", "invoke-static/range", "invoke-virtual/range", "invoke-interface/range")) put(op, OperandShape.RANGE_METHOD)
        for (op in listOf("invoke-polymorphic")) put(op, OperandShape.LIST_METHOD_PROTO)
        for (op in listOf("invoke-polymorphic/range")) put(op, OperandShape.RANGE_METHOD_PROTO)
        for (op in listOf("invoke-custom")) put(op, OperandShape.LIST_CALL_SITE)
        for (op in listOf("invoke-custom/range")) put(op, OperandShape.RANGE_CALL_SITE)
        for (op in listOf("filled-new-array")) put(op, OperandShape.LIST_TYPE)
        for (op in listOf("filled-new-array/range")) put(op, OperandShape.RANGE_TYPE)
        for (op in listOf("new-instance")) put(op, OperandShape.R_REFERENCE_TYPE)
        for (op in listOf("check-cast", "const-class")) put(op, OperandShape.R_REFERENCE_OR_ARRAY_TYPE)
        for (op in listOf("new-array", "instance-of")) put(op, OperandShape.RR_NON_VOID_TYPE)
        for (op in listOf("const-method-handle")) put(op, OperandShape.R_METHOD_HANDLE)
        for (op in listOf("const-method-type")) put(op, OperandShape.R_PROTO)
    }

    operator fun get(opcode: String): OperandShape? = shapes[opcode]

    val opcodes: Set<String> get() = shapes.keys
}
