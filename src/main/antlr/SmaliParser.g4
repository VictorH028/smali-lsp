//MIT License
//
//Copyright (c) 2018 psygate
//
//Permission is hereby granted, free of charge, to any person obtaining a copy
//of this software and associated documentation files (the "Software"), to deal
//in the Software without restriction, including without limitation the rights
//to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
//copies of the Software, and to permit persons to whom the Software is
//furnished to do so, subject to the following conditions:
//
//The above copyright notice and this permission notice shall be included in all
//copies or substantial portions of the Software.
//
//THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
//IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
//FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
//AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
//LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
//OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
//SOFTWARE.

parser grammar SmaliParser;

options {
	tokenVocab=SmaliLexer;
}

// Helpers

stringLiteral:                  STRING_LITERAL;
numericLiteral:                 SUB? (DECIMAL_LITERAL | HEX_LITERAL | OCT_LITERAL | BINARY_LITERAL | FLOAT_LITERAL | HEX_FLOAT_LITERAL | FLOAT_SPECIAL);

// A member or label name is a single token. DEX SimpleName admits anything the lexer might
// otherwise classify as a keyword, type, literal or opcode (e.g. Kotlin's `NaN` companion fields,
// obfuscated methods named `move`), so every such token is accepted here. Single-token on purpose:
// a repeated form would swallow the instruction that follows a `:label` line.
identifier:
    IDENTIFIER
    | VOID_TYPE
    | BOOLEAN_TYPE
    | BYTE_TYPE
    | SHORT_TYPE
    | CHAR_TYPE
    | INT_TYPE
    | LONG_TYPE
    | FLOAT_TYPE
    | DOUBLE_TYPE
    | CONSTRUCTOR
    | ANNOTATION
    | BRIDGE
    | NATIVE
    | VARARGS
    | SYNTHETIC
    | PUBLIC
    | PRIVATE
    | PROTECTED
    | FINAL
    | STATIC
    | ABSTRACT
    | ENUM
    | INTERFACE
    | TRANSIENT
    | VOLATILE
    | STRICTFP
    | SYNCHRONIZED
    | DECLARED_SYNCHRONIZED
    | literalKeyword
    | opcodeKeyword
    | DECIMAL_LITERAL
    | HEX_LITERAL
    | OCT_LITERAL
    | BINARY_LITERAL
    ;

literalKeyword:             FLOAT_SPECIAL | BOOL_LITERAL | NULL_LITERAL;

// Every OP_* token of the lexer, in lexer order. GrammarConsistencyTest checks that this list
// stays complete when opcodes are added to SmaliLexer.g4.
opcodeKeyword:
    OP_NOP | OP_MOVE | OP_MOVE_FROM16 | OP_MOVE_16 | OP_MOVE_WIDE | OP_MOVE_WIDE_FROM16
    | OP_MOVE_WIDE_16 | OP_MOVE_OBJECT | OP_MOVE_OBJECT_FROM16 | OP_MOVE_OBJECT_16 | OP_MOVE_RESULT | OP_MOVE_RESULT_WIDE
    | OP_MOVE_RESULT_OBJECT | OP_MOVE_EXCEPTION | OP_RETURN_VOID | OP_RETURN | OP_RETURN_WIDE | OP_RETURN_OBJECT
    | OP_CONST_4 | OP_CONST_16 | OP_CONST | OP_CONST_HIGH16 | OP_CONST_WIDE_16 | OP_CONST_WIDE_32
    | OP_CONST_WIDE | OP_CONST_WIDE_HIGH16 | OP_CONST_STRING | OP_CONST_STRING_JUMBO | OP_CONST_CLASS | OP_MONITOR_ENTER
    | OP_MONITOR_EXIT | OP_CHECK_CAST | OP_INSTANCE_OF | OP_ARRAY_LENGTH | OP_NEW_INSTANCE | OP_NEW_ARRAY
    | OP_FILLED_NEW_ARRAY | OP_FILLED_NEW_ARRAY_RANGE | OP_FILL_ARRAY_DATA | OP_THROW | OP_GOTO | OP_GOTO_16
    | OP_GOTO_32 | OP_CMPL_FLOAT | OP_CMPG_FLOAT | OP_CMPL_DOUBLE | OP_CMPG_DOUBLE | OP_CMP_LONG
    | OP_IF_EQ | OP_IF_NE | OP_IF_LT | OP_IF_GE | OP_IF_GT | OP_IF_LE
    | OP_IF_EQZ | OP_IF_NEZ | OP_IF_LTZ | OP_IF_GEZ | OP_IF_GTZ | OP_IF_LEZ
    | OP_AGET | OP_AGET_WIDE | OP_AGET_OBJECT | OP_AGET_BOOLEAN | OP_AGET_BYTE | OP_AGET_CHAR
    | OP_AGET_SHORT | OP_APUT | OP_APUT_WIDE | OP_APUT_OBJECT | OP_APUT_BOOLEAN | OP_APUT_BYTE
    | OP_APUT_CHAR | OP_APUT_SHORT | OP_IGET | OP_IGET_WIDE | OP_IGET_OBJECT | OP_IGET_BOOLEAN
    | OP_IGET_BYTE | OP_IGET_CHAR | OP_IGET_SHORT | OP_IPUT | OP_IPUT_WIDE | OP_IPUT_OBJECT
    | OP_IPUT_BOOLEAN | OP_IPUT_BYTE | OP_IPUT_CHAR | OP_IPUT_SHORT | OP_SGET | OP_SGET_WIDE
    | OP_SGET_OBJECT | OP_SGET_BOOLEAN | OP_SGET_BYTE | OP_SGET_CHAR | OP_SGET_SHORT | OP_SPUT
    | OP_SPUT_WIDE | OP_SPUT_OBJECT | OP_SPUT_BOOLEAN | OP_SPUT_BYTE | OP_SPUT_CHAR | OP_SPUT_SHORT
    | OP_INVOKE_VIRTUAL | OP_INVOKE_SUPER | OP_INVOKE_DIRECT | OP_INVOKE_STATIC | OP_INVOKE_INTERFACE | OP_INVOKE_VIRTUAL_RANGE
    | OP_INVOKE_SUPER_RANGE | OP_INVOKE_DIRECT_RANGE | OP_INVOKE_STATIC_RANGE | OP_INVOKE_INTERFACE_RANGE | OP_NEG_INT | OP_NOT_INT
    | OP_NEG_LONG | OP_NOT_LONG | OP_NEG_FLOAT | OP_NEG_DOUBLE | OP_INT_TO_LONG | OP_INT_TO_FLOAT
    | OP_INT_TO_DOUBLE | OP_LONG_TO_INT | OP_LONG_TO_FLOAT | OP_LONG_TO_DOUBLE | OP_FLOAT_TO_INT | OP_FLOAT_TO_LONG
    | OP_FLOAT_TO_DOUBLE | OP_DOUBLE_TO_INT | OP_DOUBLE_TO_LONG | OP_DOUBLE_TO_FLOAT | OP_INT_TO_BYTE | OP_INT_TO_CHAR
    | OP_INT_TO_SHORT | OP_ADD_INT | OP_SUB_INT | OP_MUL_INT | OP_DIV_INT | OP_REM_INT
    | OP_AND_INT | OP_OR_INT | OP_XOR_INT | OP_SHL_INT | OP_SHR_INT | OP_USHR_INT
    | OP_ADD_LONG | OP_SUB_LONG | OP_MUL_LONG | OP_DIV_LONG | OP_REM_LONG | OP_AND_LONG
    | OP_OR_LONG | OP_XOR_LONG | OP_SHL_LONG | OP_SHR_LONG | OP_USHR_LONG | OP_ADD_FLOAT
    | OP_SUB_FLOAT | OP_MUL_FLOAT | OP_DIV_FLOAT | OP_REM_FLOAT | OP_ADD_DOUBLE | OP_SUB_DOUBLE
    | OP_MUL_DOUBLE | OP_DIV_DOUBLE | OP_REM_DOUBLE | OP_ADD_INT_2ADDR | OP_SUB_INT_2ADDR | OP_MUL_INT_2ADDR
    | OP_DIV_INT_2ADDR | OP_REM_INT_2ADDR | OP_AND_INT_2ADDR | OP_OR_INT_2ADDR | OP_XOR_INT_2ADDR | OP_SHL_INT_2ADDR
    | OP_SHR_INT_2ADDR | OP_USHR_INT_2ADDR | OP_ADD_LONG_2ADDR | OP_SUB_LONG_2ADDR | OP_MUL_LONG_2ADDR | OP_DIV_LONG_2ADDR
    | OP_REM_LONG_2ADDR | OP_AND_LONG_2ADDR | OP_OR_LONG_2ADDR | OP_XOR_LONG_2ADDR | OP_SHL_LONG_2ADDR | OP_SHR_LONG_2ADDR
    | OP_USHR_LONG_2ADDR | OP_ADD_FLOAT_2ADDR | OP_SUB_FLOAT_2ADDR | OP_MUL_FLOAT_2ADDR | OP_DIV_FLOAT_2ADDR | OP_REM_FLOAT_2ADDR
    | OP_ADD_DOUBLE_2ADDR | OP_SUB_DOUBLE_2ADDR | OP_MUL_DOUBLE_2ADDR | OP_DIV_DOUBLE_2ADDR | OP_REM_DOUBLE_2ADDR | OP_ADD_INT_LIT16
    | OP_RSUB_INT | OP_MUL_INT_LIT16 | OP_DIV_INT_LIT16 | OP_REM_INT_LIT16 | OP_AND_INT_LIT16 | OP_OR_INT_LIT16
    | OP_XOR_INT_LIT16 | OP_ADD_INT_LIT8 | OP_RSUB_INT_LIT8 | OP_MUL_INT_LIT8 | OP_DIV_INT_LIT8 | OP_REM_INT_LIT8
    | OP_AND_INT_LIT8 | OP_OR_INT_LIT8 | OP_XOR_INT_LIT8 | OP_SHL_INT_LIT8 | OP_SHR_INT_LIT8 | OP_USHR_INT_LIT8
    | OP_INVOKE_POLYMORPHIC | OP_INVOKE_POLYMORPHIC_RANGE | OP_INVOKE_CUSTOM | OP_INVOKE_CUSTOM_RANGE | OP_CONST_METHOD_HANDLE | OP_CONST_METHOD_TYPE
    | OP_PACKED_SWITCH | OP_SPARSE_SWITCH
    ;

// Types
referenceType:                  QUALIFIED_TYPE_NAME;
primitiveType:
    BOOLEAN_TYPE | BYTE_TYPE | SHORT_TYPE | CHAR_TYPE | INT_TYPE | LONG_TYPE | FLOAT_TYPE | DOUBLE_TYPE
    ;

methodParameterLiteral:     (COMPOUND_METHOD_ARG_LITERAL | IDENTIFIER);     // strings like IIS

arrayType:                  LBRACK (QUALIFIED_TYPE_NAME | primitiveType | arrayType | methodParameterLiteral);

referenceOrArrayType:       QUALIFIED_TYPE_NAME | arrayType;

nonVoidType:                QUALIFIED_TYPE_NAME | primitiveType | arrayType;

anyType:                    QUALIFIED_TYPE_NAME | primitiveType | arrayType | VOID_TYPE;

nullLiteral:                NULL_LITERAL;

booleanLiteral:             BOOL_LITERAL;

charLiteral:                CHAR_LITERAL;

assignableValue:            anyType | stringLiteral | charLiteral | numericLiteral | nullLiteral | booleanLiteral;

// Modifiers

classModifier:
    PUBLIC
    | PRIVATE
    | PROTECTED
    | FINAL
    | ANNOTATION
    | SYNTHETIC
    | STATIC
    | ABSTRACT
    | ENUM
    | INTERFACE
    ;

methodModifier:
    PUBLIC
    | PRIVATE
    | PROTECTED
    | FINAL
    | SYNTHETIC
    | STATIC
    | ABSTRACT
    | CONSTRUCTOR
    | BRIDGE
    | DECLARED_SYNCHRONIZED
    | SYNCHRONIZED
    | STRICTFP
    | VARARGS
    | NATIVE
    ;

fieldModifier:
    PUBLIC
    | PRIVATE
    | PROTECTED
    | FINAL
    | SYNTHETIC
    | STATIC
    | TRANSIENT
    | VOLATILE
    | ENUM
    ;

label:                          COLON identifier;

// Instructions

registerRange:                  LBRACE IDENTIFIER DOT DOT IDENTIFIER RBRACE;

registerList:                   LBRACE (IDENTIFIER (COMMA IDENTIFIER)*)? RBRACE;

gotoInstruction:                OP_GOTO label;

goto16Instruction:              OP_GOTO_16 label;

goto32Instruction:              OP_GOTO_32 label;

moveResultInstruction:          OP_MOVE_RESULT IDENTIFIER;

moveResultWideInstruction:      OP_MOVE_RESULT_WIDE IDENTIFIER;

moveResultObjectInstruction:    OP_MOVE_RESULT_OBJECT IDENTIFIER;

moveExceptionInstruction:       OP_MOVE_EXCEPTION IDENTIFIER;

returnInstruction:              OP_RETURN IDENTIFIER;

returnWideInstruction:          OP_RETURN_WIDE IDENTIFIER;

returnObjectInstruction:        OP_RETURN_OBJECT IDENTIFIER;

monitorEnterInstruction:        OP_MONITOR_ENTER IDENTIFIER;

monitorExitInstruction:         OP_MONITOR_EXIT IDENTIFIER;

throwInstruction:               OP_THROW IDENTIFIER;

returnVoidInstruction:          OP_RETURN_VOID;

nopInstruction:                 OP_NOP;

moveInstruction:                OP_MOVE IDENTIFIER COMMA IDENTIFIER;

moveFrom16Instruction:          OP_MOVE_FROM16 IDENTIFIER COMMA IDENTIFIER;

move16Instruction:              OP_MOVE_16 IDENTIFIER COMMA IDENTIFIER;

moveWideInstruction:            OP_MOVE_WIDE IDENTIFIER COMMA IDENTIFIER;

moveWideFrom16Instruction:      OP_MOVE_WIDE_FROM16 IDENTIFIER COMMA IDENTIFIER;

moveWide16Instruction:          OP_MOVE_WIDE_16 IDENTIFIER COMMA IDENTIFIER;

moveObjectInstruction:          OP_MOVE_OBJECT IDENTIFIER COMMA IDENTIFIER;

moveObjectFrom16Instruction:    OP_MOVE_OBJECT_FROM16 IDENTIFIER COMMA IDENTIFIER;

moveObject16Instruction:        OP_MOVE_OBJECT_16 IDENTIFIER COMMA IDENTIFIER;

constInstruction:               OP_CONST IDENTIFIER COMMA numericLiteral;

const4Instruction:              OP_CONST_4 IDENTIFIER COMMA numericLiteral;

const16Instruction:             OP_CONST_16 IDENTIFIER COMMA numericLiteral;

constHigh16Instruction:         OP_CONST_HIGH16 IDENTIFIER COMMA numericLiteral;

constWide16Instruction:         OP_CONST_WIDE_16 IDENTIFIER COMMA numericLiteral;

constWide32Instruction:         OP_CONST_WIDE_32 IDENTIFIER COMMA numericLiteral;

constWideInstruction:           OP_CONST_WIDE IDENTIFIER COMMA numericLiteral;

constWideHigh16Instruction:     OP_CONST_WIDE_HIGH16 IDENTIFIER COMMA numericLiteral;

constString:                    OP_CONST_STRING IDENTIFIER COMMA stringLiteral;

constStringJumbo:               OP_CONST_STRING_JUMBO IDENTIFIER COMMA stringLiteral;

constClass:                     OP_CONST_CLASS IDENTIFIER COMMA referenceOrArrayType;

sGetInstruction:                OP_SGET IDENTIFIER COMMA fieldInvocationTarget;

sGetWideInstruction:            OP_SGET_WIDE IDENTIFIER COMMA fieldInvocationTarget;

sGetObjectInstruction:          OP_SGET_OBJECT IDENTIFIER COMMA fieldInvocationTarget;

sGetBooleanInstruction:         OP_SGET_BOOLEAN IDENTIFIER COMMA fieldInvocationTarget;

sGetByteInstruction:            OP_SGET_BYTE IDENTIFIER COMMA fieldInvocationTarget;

sGetCharInstruction:            OP_SGET_CHAR IDENTIFIER COMMA fieldInvocationTarget;

sGetShortInstruction:           OP_SGET_SHORT IDENTIFIER COMMA fieldInvocationTarget;

sPutInstruction:                OP_SPUT IDENTIFIER COMMA fieldInvocationTarget;

sPutWideInstruction:            OP_SPUT_WIDE IDENTIFIER COMMA fieldInvocationTarget;

sPutObjectInstruction:          OP_SPUT_OBJECT IDENTIFIER COMMA fieldInvocationTarget;

sPutBooleanInstruction:         OP_SPUT_BOOLEAN IDENTIFIER COMMA fieldInvocationTarget;

sPutByteInstruction:            OP_SPUT_BYTE IDENTIFIER COMMA fieldInvocationTarget;

sPutCharInstruction:            OP_SPUT_CHAR IDENTIFIER COMMA fieldInvocationTarget;

sPutShortInstruction:           OP_SPUT_SHORT IDENTIFIER COMMA fieldInvocationTarget;

invokeVirtualInstruction:       OP_INVOKE_VIRTUAL registerList COMMA methodInvocationTarget;

invokeSuperInstruction:         OP_INVOKE_SUPER registerList COMMA methodInvocationTarget;

invokeDirectInstruction:        OP_INVOKE_DIRECT registerList COMMA methodInvocationTarget;

invokeStaticInstruction:        OP_INVOKE_STATIC registerList COMMA methodInvocationTarget;

invokeInterfaceInstruction:     OP_INVOKE_INTERFACE registerList COMMA methodInvocationTarget;

invokeVirtualRangeInstruction:  OP_INVOKE_VIRTUAL_RANGE registerRange COMMA methodInvocationTarget;

invokeSuperRangeInstruction:    OP_INVOKE_SUPER_RANGE registerRange COMMA methodInvocationTarget;

invokeDirectRangeInstruction:   OP_INVOKE_DIRECT_RANGE registerRange COMMA methodInvocationTarget;

invokeStaticRangeInstruction:   OP_INVOKE_STATIC_RANGE registerRange COMMA methodInvocationTarget;

invokeInterfaceRangeInstruction:OP_INVOKE_INTERFACE_RANGE registerRange COMMA methodInvocationTarget;

intToLongInstruction:           OP_INT_TO_LONG IDENTIFIER COMMA IDENTIFIER;

intToFloatInstruction:          OP_INT_TO_FLOAT IDENTIFIER COMMA IDENTIFIER;

intToDoubleInstruction:         OP_INT_TO_DOUBLE IDENTIFIER COMMA IDENTIFIER;

longToIntInstruction:           OP_LONG_TO_INT IDENTIFIER COMMA IDENTIFIER;

longToFloatInstruction:         OP_LONG_TO_FLOAT IDENTIFIER COMMA IDENTIFIER;

longToDoubleInstruction:        OP_LONG_TO_DOUBLE IDENTIFIER COMMA IDENTIFIER;

floatToIntInstruction:          OP_FLOAT_TO_INT IDENTIFIER COMMA IDENTIFIER;

floatToLongInstruction:         OP_FLOAT_TO_LONG IDENTIFIER COMMA IDENTIFIER;

floatToDoubleInstruction:       OP_FLOAT_TO_DOUBLE IDENTIFIER COMMA IDENTIFIER;

doubleToIntInstruction:         OP_DOUBLE_TO_INT IDENTIFIER COMMA IDENTIFIER;

doubleToLongInstruction:        OP_DOUBLE_TO_LONG IDENTIFIER COMMA IDENTIFIER;

doubleToFloatInstruction:       OP_DOUBLE_TO_FLOAT IDENTIFIER COMMA IDENTIFIER;

intToByteInstruction:           OP_INT_TO_BYTE IDENTIFIER COMMA IDENTIFIER;

intToCharInstruction:           OP_INT_TO_CHAR IDENTIFIER COMMA IDENTIFIER;

intToShortInstruction:          OP_INT_TO_SHORT IDENTIFIER COMMA IDENTIFIER;

ifEqzInstruction:               OP_IF_EQZ IDENTIFIER COMMA label;

ifNezInstruction:               OP_IF_NEZ IDENTIFIER COMMA label;

ifLtzInstruction:               OP_IF_LTZ IDENTIFIER COMMA label;

ifGezInstruction:               OP_IF_GEZ IDENTIFIER COMMA label;

ifGtzInstruction:               OP_IF_GTZ IDENTIFIER COMMA label;

ifLezInstruction:               OP_IF_LEZ IDENTIFIER COMMA label;

negIntInstruction:              OP_NEG_INT IDENTIFIER COMMA IDENTIFIER;

notIntInstruction:              OP_NOT_INT IDENTIFIER COMMA IDENTIFIER;

negLongInstruction:             OP_NEG_LONG IDENTIFIER COMMA IDENTIFIER;

notLongInstruction:             OP_NOT_LONG IDENTIFIER COMMA IDENTIFIER;

negFloatInstruction:            OP_NEG_FLOAT IDENTIFIER COMMA IDENTIFIER;

negDoubleInstruction:           OP_NEG_DOUBLE IDENTIFIER COMMA IDENTIFIER;

ifEqInstruction:                OP_IF_EQ IDENTIFIER COMMA IDENTIFIER COMMA label;

ifNeInstruction:                OP_IF_NE IDENTIFIER COMMA IDENTIFIER COMMA label;

ifLtInstruction:                OP_IF_LT IDENTIFIER COMMA IDENTIFIER COMMA label;

ifGeInstruction:                OP_IF_GE IDENTIFIER COMMA IDENTIFIER COMMA label;

ifGtInstruction:                OP_IF_GT IDENTIFIER COMMA IDENTIFIER COMMA label;

ifLeInstruction:                OP_IF_LE IDENTIFIER COMMA IDENTIFIER COMMA label;

addInt2addrInstruction:         OP_ADD_INT_2ADDR IDENTIFIER COMMA IDENTIFIER;

subInt2addrInstruction:         OP_SUB_INT_2ADDR IDENTIFIER COMMA IDENTIFIER;

mulInt2addrInstruction:         OP_MUL_INT_2ADDR IDENTIFIER COMMA IDENTIFIER;

divInt2addrInstruction:         OP_DIV_INT_2ADDR IDENTIFIER COMMA IDENTIFIER;

remInt2addrInstruction:         OP_REM_INT_2ADDR IDENTIFIER COMMA IDENTIFIER;

andInt2addrInstruction:         OP_AND_INT_2ADDR IDENTIFIER COMMA IDENTIFIER;

orInt2addrInstruction:          OP_OR_INT_2ADDR IDENTIFIER COMMA IDENTIFIER;

xorInt2addrInstruction:         OP_XOR_INT_2ADDR IDENTIFIER COMMA IDENTIFIER;

shlInt2addrInstruction:         OP_SHL_INT_2ADDR IDENTIFIER COMMA IDENTIFIER;

shrInt2addrInstruction:         OP_SHR_INT_2ADDR IDENTIFIER COMMA IDENTIFIER;

ushrInt2addrInstruction:        OP_USHR_INT_2ADDR IDENTIFIER COMMA IDENTIFIER;

addLong2addrInstruction:        OP_ADD_LONG_2ADDR IDENTIFIER COMMA IDENTIFIER;

subLong2addrInstruction:        OP_SUB_LONG_2ADDR IDENTIFIER COMMA IDENTIFIER;

mulLong2addrInstruction:        OP_MUL_LONG_2ADDR IDENTIFIER COMMA IDENTIFIER;

divLong2addrInstruction:        OP_DIV_LONG_2ADDR IDENTIFIER COMMA IDENTIFIER;

remLong2addrInstruction:        OP_REM_LONG_2ADDR IDENTIFIER COMMA IDENTIFIER;

andLong2addrInstruction:        OP_AND_LONG_2ADDR IDENTIFIER COMMA IDENTIFIER;

orLong2addrInstruction:         OP_OR_LONG_2ADDR IDENTIFIER COMMA IDENTIFIER;

xorLong2addrInstruction:        OP_XOR_LONG_2ADDR IDENTIFIER COMMA IDENTIFIER;

shlLong2addrInstruction:        OP_SHL_LONG_2ADDR IDENTIFIER COMMA IDENTIFIER;

shrLong2addrInstruction:        OP_SHR_LONG_2ADDR IDENTIFIER COMMA IDENTIFIER;

ushrLong2addrInstruction:       OP_USHR_LONG_2ADDR IDENTIFIER COMMA IDENTIFIER;

addFloat2addrInstruction:       OP_ADD_FLOAT_2ADDR IDENTIFIER COMMA IDENTIFIER;

subFloat2addrInstruction:       OP_SUB_FLOAT_2ADDR IDENTIFIER COMMA IDENTIFIER;

mulFloat2addrInstruction:       OP_MUL_FLOAT_2ADDR IDENTIFIER COMMA IDENTIFIER;

divFloat2addrInstruction:       OP_DIV_FLOAT_2ADDR IDENTIFIER COMMA IDENTIFIER;

remFloat2addrInstruction:       OP_REM_FLOAT_2ADDR IDENTIFIER COMMA IDENTIFIER;

addDouble2addrInstruction:      OP_ADD_DOUBLE_2ADDR IDENTIFIER COMMA IDENTIFIER;

subDouble2addrInstruction:      OP_SUB_DOUBLE_2ADDR IDENTIFIER COMMA IDENTIFIER;

mulDouble2addrInstruction:      OP_MUL_DOUBLE_2ADDR IDENTIFIER COMMA IDENTIFIER;

divDouble2addrInstruction:      OP_DIV_DOUBLE_2ADDR IDENTIFIER COMMA IDENTIFIER;

remDouble2addrInstruction:      OP_REM_DOUBLE_2ADDR IDENTIFIER COMMA IDENTIFIER;

cmplFloatInstruction:           OP_CMPL_FLOAT IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

cmpgFloatInstruction:           OP_CMPG_FLOAT IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

cmplDoubleInstruction:          OP_CMPL_DOUBLE IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

cmpgDoubleInstruction:          OP_CMPG_DOUBLE IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

cmpLongInstruction:             OP_CMP_LONG IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

instanceField:                  fieldInvocationTarget;


agetInstruction:                OP_AGET IDENTIFIER COMMA IDENTIFIER COMMA (IDENTIFIER | numericLiteral);

agetWideInstruction:            OP_AGET_WIDE IDENTIFIER COMMA IDENTIFIER COMMA (IDENTIFIER | numericLiteral);

agetObjectInstruction:          OP_AGET_OBJECT IDENTIFIER COMMA IDENTIFIER COMMA (IDENTIFIER | numericLiteral);

agetBooleanInstruction:         OP_AGET_BOOLEAN IDENTIFIER COMMA IDENTIFIER COMMA (IDENTIFIER | numericLiteral);

agetByteInstruction:            OP_AGET_BYTE IDENTIFIER COMMA IDENTIFIER COMMA (IDENTIFIER | numericLiteral);

agetCharInstruction:            OP_AGET_CHAR IDENTIFIER COMMA IDENTIFIER COMMA (IDENTIFIER | numericLiteral);

agetShortInstruction:           OP_AGET_SHORT IDENTIFIER COMMA IDENTIFIER COMMA (IDENTIFIER | numericLiteral);


aputInstruction:                OP_APUT IDENTIFIER COMMA IDENTIFIER COMMA (IDENTIFIER | numericLiteral);

aputWideInstruction:            OP_APUT_WIDE IDENTIFIER COMMA IDENTIFIER COMMA (IDENTIFIER | numericLiteral);

aputObjectInstruction:          OP_APUT_OBJECT IDENTIFIER COMMA IDENTIFIER COMMA (IDENTIFIER | numericLiteral);

aputBooleanInstruction:         OP_APUT_BOOLEAN IDENTIFIER COMMA IDENTIFIER COMMA (IDENTIFIER | numericLiteral);

aputByteInstruction:            OP_APUT_BYTE IDENTIFIER COMMA IDENTIFIER COMMA (IDENTIFIER | numericLiteral);

aputCharInstruction:            OP_APUT_CHAR IDENTIFIER COMMA IDENTIFIER COMMA (IDENTIFIER | numericLiteral);

aputShortInstruction:           OP_APUT_SHORT IDENTIFIER COMMA IDENTIFIER COMMA (IDENTIFIER | numericLiteral);


igetInstruction:                OP_IGET IDENTIFIER COMMA IDENTIFIER COMMA instanceField;

igetWideInstruction:            OP_IGET_WIDE IDENTIFIER COMMA IDENTIFIER COMMA instanceField;

igetObjectInstruction:          OP_IGET_OBJECT IDENTIFIER COMMA IDENTIFIER COMMA instanceField;

igetBooleanInstruction:         OP_IGET_BOOLEAN IDENTIFIER COMMA IDENTIFIER COMMA instanceField;

igetByteInstruction:            OP_IGET_BYTE IDENTIFIER COMMA IDENTIFIER COMMA instanceField;

igetCharInstruction:            OP_IGET_CHAR IDENTIFIER COMMA IDENTIFIER COMMA instanceField;

igetShortInstruction:           OP_IGET_SHORT IDENTIFIER COMMA IDENTIFIER COMMA instanceField;


iputInstruction:                OP_IPUT IDENTIFIER COMMA IDENTIFIER COMMA instanceField;

iputWideInstruction:            OP_IPUT_WIDE IDENTIFIER COMMA IDENTIFIER COMMA instanceField;

iputObjectInstruction:          OP_IPUT_OBJECT IDENTIFIER COMMA IDENTIFIER COMMA instanceField;

iputBooleanInstruction:         OP_IPUT_BOOLEAN IDENTIFIER COMMA IDENTIFIER COMMA instanceField;

iputByteInstruction:            OP_IPUT_BYTE IDENTIFIER COMMA IDENTIFIER COMMA instanceField;

iputCharInstruction:            OP_IPUT_CHAR IDENTIFIER COMMA IDENTIFIER COMMA instanceField;

iputShortInstruction:           OP_IPUT_SHORT IDENTIFIER COMMA IDENTIFIER COMMA instanceField;


addIntInstruction:              OP_ADD_INT IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

subIntInstruction:              OP_SUB_INT IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

mulIntInstruction:              OP_MUL_INT IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

divIntInstruction:              OP_DIV_INT IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

remIntInstruction:              OP_REM_INT IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

andIntInstruction:              OP_AND_INT IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

orIntInstruction:               OP_OR_INT IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

xorIntInstruction:              OP_XOR_INT IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

shlIntInstruction:              OP_SHL_INT IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

shrIntInstruction:              OP_SHR_INT IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

ushrIntInstruction:             OP_USHR_INT IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

// rsub-int can take either a register or a literal (Fix for BUG-003)
rsubIntInstruction:             OP_RSUB_INT IDENTIFIER COMMA IDENTIFIER COMMA (IDENTIFIER | numericLiteral);

addLongInstruction:             OP_ADD_LONG IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

subLongInstruction:             OP_SUB_LONG IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

mulLongInstruction:             OP_MUL_LONG IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

divLongInstruction:             OP_DIV_LONG IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

remLongInstruction:             OP_REM_LONG IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

andLongInstruction:             OP_AND_LONG IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

orLongInstruction:              OP_OR_LONG IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

xorLongInstruction:             OP_XOR_LONG IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

shlLongInstruction:             OP_SHL_LONG IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

shrLongInstruction:             OP_SHR_LONG IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

ushrLongInstruction:            OP_USHR_LONG IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

addFloatInstruction:            OP_ADD_FLOAT IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

subFloatInstruction:            OP_SUB_FLOAT IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

mulFloatInstruction:            OP_MUL_FLOAT IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

divFloatInstruction:            OP_DIV_FLOAT IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

remFloatInstruction:            OP_REM_FLOAT IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

addDoubleInstruction:           OP_ADD_DOUBLE IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

subDoubleInstruction:           OP_SUB_DOUBLE IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

mulDoubleInstruction:           OP_MUL_DOUBLE IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

divDoubleInstruction:           OP_DIV_DOUBLE IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

remDoubleInstruction:           OP_REM_DOUBLE IDENTIFIER COMMA IDENTIFIER COMMA IDENTIFIER;

addIntLit16Instruction:         OP_ADD_INT_LIT16 IDENTIFIER COMMA IDENTIFIER COMMA numericLiteral;

mulIntLit16Instruction:         OP_MUL_INT_LIT16 IDENTIFIER COMMA IDENTIFIER COMMA numericLiteral;

divIntLit16Instruction:         OP_DIV_INT_LIT16 IDENTIFIER COMMA IDENTIFIER COMMA numericLiteral;

remIntLit16Instruction:         OP_REM_INT_LIT16 IDENTIFIER COMMA IDENTIFIER COMMA numericLiteral;

andIntLit16Instruction:         OP_AND_INT_LIT16 IDENTIFIER COMMA IDENTIFIER COMMA numericLiteral;

orIntLit16Instruction:          OP_OR_INT_LIT16 IDENTIFIER COMMA IDENTIFIER COMMA numericLiteral;

xorIntLit16Instruction:         OP_XOR_INT_LIT16 IDENTIFIER COMMA IDENTIFIER COMMA numericLiteral;


addIntLit8Instruction:          OP_ADD_INT_LIT8 IDENTIFIER COMMA IDENTIFIER COMMA numericLiteral;

rsubIntLit8Instruction:         OP_RSUB_INT_LIT8 IDENTIFIER COMMA IDENTIFIER COMMA numericLiteral;

mulIntLit8Instruction:          OP_MUL_INT_LIT8 IDENTIFIER COMMA IDENTIFIER COMMA numericLiteral;

divIntLit8Instruction:          OP_DIV_INT_LIT8 IDENTIFIER COMMA IDENTIFIER COMMA numericLiteral;

remIntLit8Instruction:          OP_REM_INT_LIT8 IDENTIFIER COMMA IDENTIFIER COMMA numericLiteral;

andIntLit8Instruction:          OP_AND_INT_LIT8 IDENTIFIER COMMA IDENTIFIER COMMA numericLiteral;

orIntLit8Instruction:           OP_OR_INT_LIT8 IDENTIFIER COMMA IDENTIFIER COMMA numericLiteral;

xorIntLit8Instruction:          OP_XOR_INT_LIT8 IDENTIFIER COMMA IDENTIFIER COMMA numericLiteral;

shlIntLit8Instruction:          OP_SHL_INT_LIT8 IDENTIFIER COMMA IDENTIFIER COMMA numericLiteral;

shrIntLit8Instruction:          OP_SHR_INT_LIT8 IDENTIFIER COMMA IDENTIFIER COMMA numericLiteral;

ushrIntLit8Instruction:         OP_USHR_INT_LIT8 IDENTIFIER COMMA IDENTIFIER COMMA numericLiteral;

newInstanceType:                referenceType;

newInstanceInstruction:         OP_NEW_INSTANCE IDENTIFIER COMMA newInstanceType;

checkCastType:                  referenceOrArrayType;

checkCastInstruction:           OP_CHECK_CAST IDENTIFIER COMMA checkCastType;

arrayLengthInstruction:         OP_ARRAY_LENGTH IDENTIFIER COMMA IDENTIFIER;

arrayElementType:               nonVoidType;

arrayElementRegisterRange:      registerRange;

arrayElementRegisters:          registerList;

filledNewArrayRangeInstruction:            OP_FILLED_NEW_ARRAY_RANGE arrayElementRegisterRange COMMA arrayElementType;

filledNewArrayInstruction:                 OP_FILLED_NEW_ARRAY arrayElementRegisters COMMA arrayElementType;

filledArrayDataLabel:           label;

fillArrayDataInstruction:                  OP_FILL_ARRAY_DATA IDENTIFIER COMMA filledArrayDataLabel;

checkInstanceType:              nonVoidType;

instanceOfInstruction:          OP_INSTANCE_OF IDENTIFIER COMMA IDENTIFIER COMMA checkInstanceType;

newArrayInstruction:            OP_NEW_ARRAY IDENTIFIER COMMA IDENTIFIER COMMA arrayElementType;

packedSwitchLabel:              label;

sparseSwitchLabel:              label;

packedSwitchInstruction:        OP_PACKED_SWITCH IDENTIFIER COMMA packedSwitchLabel;

sparseSwitchInstruction:        OP_SPARSE_SWITCH IDENTIFIER COMMA sparseSwitchLabel;

// Method prototype without a name, e.g. `(ILjava/lang/String;)V` (invoke-polymorphic, const-method-type, call sites).
methodProto:                LPAREN (nonVoidType | methodParameterLiteral)* RPAREN anyType;

// `invoke-static@Lcom/Foo;->bar()V`, `instance-get@Lcom/Foo;->IDENTIFIER:I`. The kind is one of
// invoke-static/instance/constructor/direct/interface or static/instance-get/put; all lex as
// identifiers or opcode tokens, so `identifier` covers them.
methodHandleKind:           identifier;

methodHandleReference:      methodHandleKind AT (methodReference | fieldInvocationTarget);

callSiteName:               identifier;

callSiteExtraArgument:
    numericLiteral
    | stringLiteral
    | charLiteral
    | booleanLiteral
    | nullLiteral
    | enumFieldReference
    | methodHandleReference
    | methodProto
    | methodReference
    | fieldInvocationTarget
    | anyType
    ;

// `call_site_0("name", (I)V, extra...)@Lcom/Bsm;->bootstrap(...)Ljava/lang/invoke/CallSite;`
callSiteReference:          callSiteName LPAREN stringLiteral COMMA methodProto (COMMA callSiteExtraArgument)* RPAREN AT methodReference;

invokePolymorphicInstruction:   OP_INVOKE_POLYMORPHIC registerList COMMA methodInvocationTarget COMMA methodProto;

invokePolymorphicRangeInstruction:  OP_INVOKE_POLYMORPHIC_RANGE registerRange COMMA methodInvocationTarget COMMA methodProto;

invokeCustomInstruction:        OP_INVOKE_CUSTOM registerList COMMA callSiteReference;

invokeCustomRangeInstruction:   OP_INVOKE_CUSTOM_RANGE registerRange COMMA callSiteReference;

invokeConstMethodHandleInstruction: OP_CONST_METHOD_HANDLE IDENTIFIER COMMA methodHandleReference;

invokeConstMethodTypeInstruction:   OP_CONST_METHOD_TYPE IDENTIFIER COMMA methodProto;

methodInvocationTarget:         (QUALIFIED_TYPE_NAME | arrayType) ARROW methodSignature;

fieldInvocationTarget:          (QUALIFIED_TYPE_NAME | arrayType) ARROW fieldNameAndType;

// Fields and methods

fieldNameAndType:           SUB? identifier COLON anyType;

fieldDirective:             FIELD_DIRECTIVE fieldModifier* fieldNameAndType (ASSIGN assignableValue)? fieldBody? FIELD_END_DIRECTIVE?;

fieldBody:                  annotationDirective+;

className:                  referenceType;

classDirective:             CLASS_DIRECTIVE classModifier* className;

superName:                  referenceType;

superDirective:             SUPER_DIRECTIVE superName;

sourceName:                 stringLiteral;

sourceDirective:            SOURCE_DIRECTIVE sourceName;

methodSignature:            (SUB? identifier | LT identifier GT) LPAREN (nonVoidType | methodParameterLiteral)* RPAREN anyType;

methodDeclaration:          methodModifier* methodSignature;

annotationScope:            IDENTIFIER;

annotationType:             referenceType;

enumFieldReference:         ENUM_DIRECTIVE fieldInvocationTarget;

methodReference:            (QUALIFIED_TYPE_NAME | arrayType) ARROW methodSignature;

subannotation:              SUBANNOTATION_DIRECTIVE annotationType annotationField* SUBANNOTATION_END_DIRECTIVE;

annotationFieldValue:       (assignableValue | methodReference | referenceType | enumFieldReference | subannotation | annotationDirective);

annotationValueScoped:      LBRACE (annotationFieldValue (COMMA annotationFieldValue)*)? RBRACE;

annotationField:            SUB? identifier ASSIGN (annotationFieldValue|annotationValueScoped);

annotationDirective:        ANNOTATION_DIRECTIVE annotationScope annotationType annotationField* ANNOTATION_END_DIRECTIVE;

locaDirectiveVariableName:  stringLiteral | nullLiteral;

localDirectiveType:         nonVoidType;

localDirectiveGenericHint:  stringLiteral;

// `.local v0`, `.local v0, "name":T`, `.local v0, null:T, "signature"`
localDirective:             LOCAL_DIRECTIVE IDENTIFIER (COMMA locaDirectiveVariableName (COLON localDirectiveType)? (COMMA localDirectiveGenericHint)?)?;

localEndDirective:          LOCAL_END_DIRECTIVE IDENTIFIER;

localRestartDirective:      RESTART_LOCAL_DIRECTIVE IDENTIFIER;

prologueDirective:          PROLOGUE_DIRECTIVE;

epilogueDirective:          EPILOGUE_DIRECTIVE;

lineLabel:                  label;

// Every statement of a method body is a direct alternative: no instruction/binaryInstruction/
// methodBodyStatement wrappers, which cost three rule contexts per instruction.
methodBody:
    (
        registersDirective
        | localsDirective
        | paramDirective
        | lineDirective
        | prologueDirective
        | epilogueDirective
        | invokePolymorphicInstruction
        | invokePolymorphicRangeInstruction
        | invokeCustomInstruction
        | invokeCustomRangeInstruction
        | invokeConstMethodHandleInstruction
        | invokeConstMethodTypeInstruction
        | instanceOfInstruction
        | newArrayInstruction
        | agetInstruction
        | agetWideInstruction
        | agetObjectInstruction
        | agetBooleanInstruction
        | agetByteInstruction
        | agetCharInstruction
        | agetShortInstruction
        | aputInstruction
        | aputWideInstruction
        | aputObjectInstruction
        | aputBooleanInstruction
        | aputByteInstruction
        | aputCharInstruction
        | aputShortInstruction
        | igetInstruction
        | igetWideInstruction
        | igetObjectInstruction
        | igetBooleanInstruction
        | igetByteInstruction
        | igetCharInstruction
        | igetShortInstruction
        | iputInstruction
        | iputWideInstruction
        | iputObjectInstruction
        | iputBooleanInstruction
        | iputByteInstruction
        | iputCharInstruction
        | iputShortInstruction
        | addIntInstruction
        | subIntInstruction
        | mulIntInstruction
        | divIntInstruction
        | remIntInstruction
        | andIntInstruction
        | orIntInstruction
        | xorIntInstruction
        | shlIntInstruction
        | shrIntInstruction
        | ushrIntInstruction
        | rsubIntInstruction
        | addLongInstruction
        | subLongInstruction
        | mulLongInstruction
        | divLongInstruction
        | remLongInstruction
        | andLongInstruction
        | orLongInstruction
        | xorLongInstruction
        | shlLongInstruction
        | shrLongInstruction
        | ushrLongInstruction
        | addFloatInstruction
        | subFloatInstruction
        | mulFloatInstruction
        | divFloatInstruction
        | remFloatInstruction
        | addDoubleInstruction
        | subDoubleInstruction
        | mulDoubleInstruction
        | divDoubleInstruction
        | remDoubleInstruction
        | addIntLit16Instruction
        | mulIntLit16Instruction
        | divIntLit16Instruction
        | remIntLit16Instruction
        | andIntLit16Instruction
        | orIntLit16Instruction
        | xorIntLit16Instruction
        | addIntLit8Instruction
        | rsubIntLit8Instruction
        | mulIntLit8Instruction
        | divIntLit8Instruction
        | remIntLit8Instruction
        | andIntLit8Instruction
        | orIntLit8Instruction
        | xorIntLit8Instruction
        | shlIntLit8Instruction
        | shrIntLit8Instruction
        | ushrIntLit8Instruction
        | filledNewArrayRangeInstruction
        | filledNewArrayInstruction
        | fillArrayDataInstruction
        | arrayLengthInstruction
        | packedSwitchInstruction
        | sparseSwitchInstruction
        | newInstanceInstruction
        | checkCastInstruction
        | moveInstruction
        | moveFrom16Instruction
        | move16Instruction
        | moveWideInstruction
        | moveWideFrom16Instruction
        | moveWide16Instruction
        | moveObjectInstruction
        | moveObjectFrom16Instruction
        | moveObject16Instruction
        | constInstruction
        | const4Instruction
        | const16Instruction
        | constHigh16Instruction
        | constWide16Instruction
        | constWide32Instruction
        | constWideInstruction
        | constWideHigh16Instruction
        | constString
        | constStringJumbo
        | constClass
        | sGetInstruction
        | sGetWideInstruction
        | sGetObjectInstruction
        | sGetBooleanInstruction
        | sGetByteInstruction
        | sGetCharInstruction
        | sGetShortInstruction
        | sPutInstruction
        | sPutWideInstruction
        | sPutObjectInstruction
        | sPutBooleanInstruction
        | sPutByteInstruction
        | sPutCharInstruction
        | sPutShortInstruction
        | invokeVirtualInstruction
        | invokeSuperInstruction
        | invokeDirectInstruction
        | invokeStaticInstruction
        | invokeInterfaceInstruction
        | invokeVirtualRangeInstruction
        | invokeSuperRangeInstruction
        | invokeDirectRangeInstruction
        | invokeStaticRangeInstruction
        | invokeInterfaceRangeInstruction
        | intToLongInstruction
        | intToFloatInstruction
        | intToDoubleInstruction
        | longToIntInstruction
        | longToFloatInstruction
        | longToDoubleInstruction
        | floatToIntInstruction
        | floatToLongInstruction
        | floatToDoubleInstruction
        | doubleToIntInstruction
        | doubleToLongInstruction
        | doubleToFloatInstruction
        | intToByteInstruction
        | intToCharInstruction
        | intToShortInstruction
        | ifEqzInstruction
        | ifNezInstruction
        | ifLtzInstruction
        | ifGezInstruction
        | ifGtzInstruction
        | ifLezInstruction
        | negIntInstruction
        | notIntInstruction
        | negLongInstruction
        | notLongInstruction
        | negFloatInstruction
        | negDoubleInstruction
        | ifEqInstruction
        | ifNeInstruction
        | ifLtInstruction
        | ifGeInstruction
        | ifGtInstruction
        | ifLeInstruction
        | addInt2addrInstruction
        | subInt2addrInstruction
        | mulInt2addrInstruction
        | divInt2addrInstruction
        | remInt2addrInstruction
        | andInt2addrInstruction
        | orInt2addrInstruction
        | xorInt2addrInstruction
        | shlInt2addrInstruction
        | shrInt2addrInstruction
        | ushrInt2addrInstruction
        | addLong2addrInstruction
        | subLong2addrInstruction
        | mulLong2addrInstruction
        | divLong2addrInstruction
        | remLong2addrInstruction
        | andLong2addrInstruction
        | orLong2addrInstruction
        | xorLong2addrInstruction
        | shlLong2addrInstruction
        | shrLong2addrInstruction
        | ushrLong2addrInstruction
        | addFloat2addrInstruction
        | subFloat2addrInstruction
        | mulFloat2addrInstruction
        | divFloat2addrInstruction
        | remFloat2addrInstruction
        | addDouble2addrInstruction
        | subDouble2addrInstruction
        | mulDouble2addrInstruction
        | divDouble2addrInstruction
        | remDouble2addrInstruction
        | cmplFloatInstruction
        | cmpgFloatInstruction
        | cmplDoubleInstruction
        | cmpgDoubleInstruction
        | cmpLongInstruction
        | returnVoidInstruction
        | nopInstruction
        | gotoInstruction
        | goto16Instruction
        | goto32Instruction
        | moveResultInstruction
        | moveResultWideInstruction
        | moveResultObjectInstruction
        | moveExceptionInstruction
        | returnInstruction
        | returnWideInstruction
        | returnObjectInstruction
        | monitorEnterInstruction
        | monitorExitInstruction
        | throwInstruction
        | lineLabel
        | catchDirective
        | catchAllDirective
        | annotationDirective
        | localDirective
        | localEndDirective
        | localRestartDirective
        | packedSwitchDirective
        | arrayDataDirective
        | sparseSwitchDirective
    )+
    ;

packedSwitchIdent:          numericLiteral;

packedSwitchDirectiveLabel: label;

packedSwitchDirectiveLabels:packedSwitchDirectiveLabel+;

packedSwitchDirective:      PACKED_SWITCH_DIRECTIVE packedSwitchIdent packedSwitchDirectiveLabels? PACKED_SWITCH_END_DIRECTIVE;

methodDirective:            METHOD_DIRECTIVE methodDeclaration methodBody? METHOD_END_DIRECTIVE;

registersDirective:         REGISTERS_DIRECTIVE numericLiteral;

localsDirective:            LOCALS_DIRECTIVE numericLiteral;

paramName:                  stringLiteral;

paramAnnotations:           annotationDirective* PARAM_END_DIRECTIVE;

// `.param p1`, `.param p1, "name"`, either optionally followed by annotations and `.end param`.
paramDirective:             PARAM_DIRECTIVE IDENTIFIER (COMMA paramName)? paramAnnotations?;

lineDirective:              LINE_DIRECTIVE numericLiteral;

catchFromLabel:             label;

catchToLabel:               label;

catchGotoLabel:             label;

catchExceptionType:         referenceType;

catchDirective:             CATCH_DIRECTIVE catchExceptionType LBRACE catchFromLabel DOT DOT catchToLabel RBRACE catchGotoLabel;

catchAllDirective:          CATCHALL_DIRECTIVE LBRACE catchFromLabel DOT DOT catchToLabel RBRACE catchGotoLabel;

arrayDataDirective:         ARRAY_DATA_DIRECTIVE numericLiteral arrayDataEntry* ARRAY_DATA_END_DIRECTIVE;

arrayDataEntry:             numericLiteral;

sparseSwitchDirectiveValue: numericLiteral ARROW label;

sparseSwitchDirective:      SPARSE_SWITCH_DIRECTIVE sparseSwitchDirectiveValue* SPARSE_SWITCH_END_DIRECTIVE;

implementsDirective:        IMPLEMENTS_DIRECTIVE referenceType;

// EOF is mandatory: without it the parser would stop silently at the first construct it
// cannot match and drop the rest of the file without reporting a syntax error.
parse:
    (classDirective | superDirective | sourceDirective | implementsDirective | fieldDirective | methodDirective | annotationDirective)+ EOF
    ;