package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.*
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.util.Types

/**
 * Provides document outline/symbols in IDEs.
 * 
 * Shows hierarchical view:
 * - Class
 *   - Methods
 *   - Fields
 * 
 * Enables quick navigation in Explorer sidebar.
 */
class DocumentSymbolProvider {
    
    /**
     * Provide document symbols for a parsed smali file.
     */
    fun provideDocumentSymbols(file: SmaliFile): List<DocumentSymbol> {
        val classSymbol = DocumentSymbol()
        
        // Class symbol
        classSymbol.name = Types.readableName(file.classDefinition.name)
        classSymbol.kind = SymbolKind.Class
        classSymbol.range = file.classDefinition.range
        classSymbol.selectionRange = file.classDefinition.range
        
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
            methodSymbol.selectionRange = method.range
            
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
            fieldSymbol.selectionRange = field.range
            
            // Detail: type
            val typeClass = Types.className(field.type)
            fieldSymbol.detail = if (typeClass != null) {
                Types.readableName(typeClass)
            } else {
                field.type
            }
            
            children.add(fieldSymbol)
        }
        
        classSymbol.children = children
        
        return listOf(classSymbol)
    }
}
