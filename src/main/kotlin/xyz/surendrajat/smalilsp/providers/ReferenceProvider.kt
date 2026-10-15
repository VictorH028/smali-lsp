package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.util.Types

/** One declaration of a member: the file and the name token. */
data class MemberDeclaration(val file: SmaliFile, val nameSpan: Span, val isRoot: Boolean, val modifiers: Set<String> = emptySet())

/** How a call `Owner->member` finds its declaration. */
internal enum class Binding {
    /** In `Owner` itself: constructors, private methods. */
    OwnClass,
    /** In `Owner` or the nearest superclass declaring it, without dispatch: static methods, fields. */
    Inherited,
    /** As [Inherited], but a redeclaration in a subclass is an override of the same member. */
    Virtual
}

/**
 * A member's declarations (the root first, then the overrides of a virtual method) and the sites
 * that bind to them. [usages] walks the usage tables of every class in the family and is
 * computed on first use; [includes] answers for one owner without it, which is what a scan of a
 * single file needs.
 */
class MemberFamily internal constructor(
    val declarations: List<MemberDeclaration>,
    private val binding: Binding,
    private val roots: Set<String>,
    private val members: Set<String>,
    private val bindsTo: (owner: String) -> String?,
    private val usagesOf: (owner: String) -> List<Location>
) {
    /** Whether a reference whose static owner is [owner] binds to this member. */
    fun includes(owner: String): Boolean {
        if (owner in roots) return true
        if (binding == Binding.OwnClass || owner !in members) return false
        val target = bindsTo(owner) ?: return false
        return if (binding == Binding.Virtual) target in members else target in roots
    }

    /** Every site that binds to the member: the roots' own sites first, then those of the subclasses that inherit or override it. */
    val usages: List<Location> by lazy {
        val out = ArrayList<Location>()
        for (root in roots) out.addAll(usagesOf(root))
        if (binding != Binding.OwnClass) {
            for (owner in members) {
                if (owner in roots) continue
                val sites = usagesOf(owner)
                if (sites.isNotEmpty() && includes(owner)) out.addAll(sites)
            }
        }
        out
    }

    /** The usages, preceded when asked by every declaration's name token (root and overrides: the spots rename edits), where go-to-definition lands. */
    fun references(includeDeclaration: Boolean): List<Location> =
        if (includeDeclaration) declarations.map { Location(it.file.uri, it.nameSpan.toRange()) } + usages else usages

    internal companion object {
        /** The family of a member whose declaration is not in the workspace: the exact owner's sites, nothing else. */
        fun direct(owner: String, usagesOf: (owner: String) -> List<Location>) =
            MemberFamily(emptyList(), Binding.OwnClass, setOf(owner), emptySet(), { null }, usagesOf)
    }
}

/**
 * Find references. Classes: subclasses, implementors and every use as a type or owner.
 * Methods and fields: the declaration and every site that binds to it (see [MemberFamily]).
 * Labels: every reference in the method. Strings: every `const-string` of the same value. All
 * answered from the reverse indexes; the symbol under the caret comes from [SmaliFile.symbolAt].
 */
class ReferenceProvider(
    private val workspaceIndex: WorkspaceIndex
) {

    fun findReferences(
        uri: String,
        position: Position,
        includeDeclaration: Boolean = true,
        cancel: CancelChecker? = null
    ): List<Location> {
        val file = workspaceIndex.findFileByUri(uri) ?: return emptyList()
        return when (val symbol = file.symbolAt(position)) {
            null -> emptyList()
            is Symbol.ClassDecl -> findClassReferences(symbol.classDef.name, includeDeclaration, cancel)
            // A type inside an invoke's descriptor: the user is on a call, so answer with the call's references
            is Symbol.ClassRef -> symbol.descriptorOf?.let { methodReferences(it, includeDeclaration) }
                ?: findClassReferences(symbol.className, includeDeclaration, cancel)
            is Symbol.MethodDecl -> findMethodReferences(file.classDefinition.name, symbol.method.name, symbol.method.descriptor, includeDeclaration)
            is Symbol.FieldDecl -> findFieldReferences(file.classDefinition.name, symbol.fieldDef.name, symbol.fieldDef.type, includeDeclaration)
            is Symbol.MethodRef -> methodReferences(symbol.instruction, includeDeclaration)
            is Symbol.FieldRef -> fieldReferences(symbol.instruction, includeDeclaration)
            is Symbol.Primitive -> when (val inside = symbol.inside) {
                is InvokeInstruction -> methodReferences(inside, includeDeclaration)
                is FieldAccessInstruction -> fieldReferences(inside, includeDeclaration)
                else -> emptyList()
            }
            is Symbol.LabelDecl -> findLabelReferences(symbol.label.name, uri, symbol.method, includeDeclaration)
            is Symbol.LabelRef -> findLabelReferences(symbol.name, uri, symbol.method, includeDeclaration)
            // Anywhere else on an instruction (the opcode, a register): its primary symbol
            is Symbol.OnInstruction -> when (val instruction = symbol.instruction) {
                is InvokeInstruction -> methodReferences(instruction, includeDeclaration)
                is FieldAccessInstruction -> fieldReferences(instruction, includeDeclaration)
                is TypeInstruction -> findClassReferences(Types.elementClass(instruction.className), includeDeclaration, cancel)
                is JumpInstruction -> findLabelReferences(instruction.targetLabel, uri, symbol.method, includeDeclaration)
                is ConstStringInstruction -> workspaceIndex.findStringUsages(instruction.value)
            }
        }
    }

    /**
     * Declaration (if requested), `.super`/`.implements` lines of subclasses and implementors,
     * then every other use: instructions, field types, method signatures.
     */
    fun findClassReferences(className: String, includeDeclaration: Boolean, cancel: CancelChecker? = null): List<Location> {
        val locations = mutableListOf<Location>()

        if (includeDeclaration) {
            workspaceIndex.findClass(className)?.let { locations.add(Location(it.uri, it.classDefinition.nameSpan.toRange())) }
        }

        for (usageUri in workspaceIndex.findClassUsages(className)) {
            val usageFile = workspaceIndex.findFileByUri(usageUri) ?: continue
            val definition = usageFile.classDefinition
            if (definition.superClass == className) {
                locations.add(Location(usageFile.uri, definition.superClassRange ?: definition.range))
            }
            if (className in definition.interfaces) {
                locations.add(Location(usageFile.uri, definition.interfaceRanges[className] ?: definition.range))
            }
        }

        locations.addAll(workspaceIndex.findClassRefLocations(className, cancel))

        return locations.distinctBy { Triple(it.uri, it.range.start.line, it.range.start.character) }
    }

    private fun methodReferences(instruction: InvokeInstruction, includeDeclaration: Boolean): List<Location> =
        findMethodReferences(instruction.className, instruction.methodName, instruction.descriptor, includeDeclaration)

    private fun fieldReferences(instruction: FieldAccessInstruction, includeDeclaration: Boolean): List<Location> =
        findFieldReferences(instruction.className, instruction.fieldName, instruction.fieldType, includeDeclaration)

    /** References to the method a `className->methodName descriptor` reference resolves to: its declaration (when requested and in the workspace), then every call that binds to it. */
    fun findMethodReferences(className: String, methodName: String, descriptor: String, includeDeclaration: Boolean): List<Location> =
        methodFamily(className, methodName, descriptor).references(includeDeclaration)

    /** References to the field a reference resolves to: its declaration (when requested and in the workspace), then every access that binds to it. */
    fun findFieldReferences(className: String, fieldName: String, fieldType: String, includeDeclaration: Boolean): List<Location> =
        fieldFamily(className, fieldName, fieldType).references(includeDeclaration)

    /**
     * The method's family: its declaration and every call site that binds to it. A call through a
     * subclass binds to the inherited method unless the subclass redeclares it; the overrides of a
     * virtual method belong to the family too, since a call on the subclass dispatches to them,
     * and with [climb] the family starts at the topmost declarations the method overrides, so an
     * override and the interface method it implements are one member. Constructors and private
     * methods bind to their own class only. A method outside the workspace has no declaration
     * and only the exact owner's call sites.
     */
    fun methodFamily(className: String, methodName: String, descriptor: String, climb: Boolean = true): MemberFamily {
        val declaration = workspaceIndex.resolveMethod(className, methodName, descriptor)
            ?: return MemberFamily.direct(className) { owner -> workspaceIndex.findMethodUsages(owner, methodName, descriptor) }
        val method = declaration.method
        val binding = when {
            method.isVirtual -> Binding.Virtual
            "static" in method.modifiers -> Binding.Inherited
            else -> Binding.OwnClass
        }
        val roots = if (climb && binding == Binding.Virtual) workspaceIndex.rootMethodDeclarations(declaration, methodName, descriptor) else listOf(declaration)
        return family(
            roots = roots.map { MemberDeclaration(it.file, it.method.nameSpan, isRoot = true, modifiers = it.method.modifiers) },
            binding = binding,
            usagesOf = { owner -> workspaceIndex.findMethodUsages(owner, methodName, descriptor) },
            bindsTo = { owner -> workspaceIndex.resolveMethod(owner, methodName, descriptor)?.file?.classDefinition?.name },
            declaredIn = { file -> file.methods.firstOrNull { it.name == methodName && it.descriptor == descriptor }?.let { MemberDeclaration(file, it.nameSpan, isRoot = false, modifiers = it.modifiers) } }
        )
    }

    /** The field's family: its declaration and every access that binds to it, including through subclasses that inherit it without redeclaring it. */
    fun fieldFamily(className: String, fieldName: String, fieldType: String): MemberFamily {
        val declaration = workspaceIndex.resolveField(className, fieldName, fieldType)
            ?: return MemberFamily.direct(className) { owner -> workspaceIndex.findFieldUsages(owner, fieldName, fieldType) }
        return family(
            roots = listOf(MemberDeclaration(declaration.file, declaration.field.nameSpan, isRoot = true, modifiers = declaration.field.modifiers)),
            binding = Binding.Inherited,
            usagesOf = { owner -> workspaceIndex.findFieldUsages(owner, fieldName, fieldType) },
            bindsTo = { owner -> workspaceIndex.resolveField(owner, fieldName, fieldType)?.file?.classDefinition?.name },
            declaredIn = { null }
        )
    }

    private fun family(
        roots: List<MemberDeclaration>,
        binding: Binding,
        usagesOf: (owner: String) -> List<Location>,
        bindsTo: (owner: String) -> String?,
        declaredIn: (SmaliFile) -> MemberDeclaration?
    ): MemberFamily {
        val declarations = ArrayList(roots)
        val rootNames = roots.mapTo(LinkedHashSet()) { it.file.classDefinition.name }
        if (binding == Binding.OwnClass) return MemberFamily(declarations, binding, rootNames, emptySet(), bindsTo, usagesOf)

        val members = LinkedHashSet(rootNames)
        for (root in rootNames) members.addAll(workspaceIndex.getAllSubclasses(root))
        if (binding == Binding.Virtual) {
            for (owner in members) {
                if (owner in rootNames) continue
                workspaceIndex.findClass(owner)?.let(declaredIn)?.let { declarations.add(it) }
            }
        }
        return MemberFamily(declarations, binding, rootNames, members, bindsTo, usagesOf)
    }

    /** Labels are method-local: the definition (if requested) and every reference in [method]. */
    private fun findLabelReferences(
        labelName: String,
        uri: String,
        method: MethodDefinition,
        includeDeclaration: Boolean
    ): List<Location> {
        val locations = mutableListOf<Location>()
        if (includeDeclaration) {
            method.labels[labelName]?.let { locations.add(Location(uri, it.range)) }
        }
        for (ref in method.labelRefs) {
            if (ref.name == labelName) locations.add(Location(uri, ref.span.toRange()))
        }
        return locations
    }
}
