package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.*
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.util.Types

/** Document outline: the class with its methods and fields, name tokens as selection ranges. */
class DocumentSymbolProvider {
    
    /** From `.class` to the end of the last member: a parent's range must contain its children, or an editor cannot tell which symbol the cursor is in. */
    private fun enclosingRange(file: SmaliFile): Range {
        var endLine = file.classDefinition.endLine
        var endChar = file.classDefinition.endChar
        fun extend(line: Int, char: Int) { if (line > endLine || (line == endLine && char > endChar)) { endLine = line; endChar = char } }
        for (method in file.methods) extend(method.endLine, method.endChar)
        for (field in file.fields) extend(field.endLine, field.endChar)
        return Range(Position(file.classDefinition.startLine, file.classDefinition.startChar), Position(endLine, endChar))
    }

    /**
     * Provide document symbols for a parsed smali file.
     */
    fun provideDocumentSymbols(file: SmaliFile): List<DocumentSymbol> {
        val classSymbol = DocumentSymbol()
        
        // Class symbol
        classSymbol.name = Types.readableName(file.classDefinition.name)
        classSymbol.kind = SymbolKind.Class
        classSymbol.range = enclosingRange(file)
        classSymbol.selectionRange = file.classDefinition.nameSpan.toRange()
        
        // Add detail (modifiers, superclass)
        val details = mutableListOf<String>()
        if (file.classDefinition.modifiers.isNotEmpty()) {
            details.add(file.classDefinition.modifiers.joinToString(" "))
        }
        file.classDefinition.superClass?.let {
            details.add("extends ${Types.readableName(it)}")
        }
        if (details.isNotEmpty()) {
            classSymbol.detail = details.joinToString(", ")
        }
        
        // Children: methods and fields
        val children = mutableListOf<DocumentSymbol>()
        
        // Add methods
        file.methods.forEach { method ->
            val methodSymbol = DocumentSymbol()
            methodSymbol.name = method.name
            methodSymbol.kind = if (method.name == "<init>" || method.name == "<clinit>") {
                SymbolKind.Constructor
            } else {
                SymbolKind.Method
            }
            methodSymbol.range = method.range
            methodSymbol.selectionRange = method.nameSpan.toRange()
            
            // Detail: signature
            methodSymbol.detail = method.descriptor
            
            children.add(methodSymbol)
        }
        
        // Add fields
        file.fields.forEach { field ->
            val fieldSymbol = DocumentSymbol()
            fieldSymbol.name = field.name
            fieldSymbol.kind = SymbolKind.Field
            fieldSymbol.range = field.range
            fieldSymbol.selectionRange = field.nameSpan.toRange()
            
            // Detail: the type as Java would write it (`int`, `byte[]`, `java.lang.String[]`)
            fieldSymbol.detail = Types.readableName(field.type)
            
            children.add(fieldSymbol)
        }
        
        classSymbol.children = children
        
        return listOf(classSymbol)
    }
}
