package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import xyz.surendrajat.smalilsp.core.InstructionText
import xyz.surendrajat.smalilsp.core.Literals
import xyz.surendrajat.smalilsp.index.Resource
import xyz.surendrajat.smalilsp.index.ResourceTable
import xyz.surendrajat.smalilsp.index.WorkspaceIndex

/** Resource ids in code (any literal [Literals] finds), named by the project's `public.xml` or the framework table. */
object ResourceLiterals {

    /** The resource the literal under the caret names, if any. */
    fun at(index: WorkspaceIndex, uri: String, position: Position): Resource? {
        val line = index.getLineContent(uri, position.line) ?: return null
        return resourceOf(index, line)?.takeIf { (_, literalStart, literalEnd) -> position.character in literalStart..literalEnd }?.first
    }

    /** The resource a line's literal names, with the literal's columns. */
    private fun resourceOf(index: WorkspaceIndex, line: String): Triple<Resource, Int, Int>? {
        val literal = Literals.find(line)?.token ?: return null
        val value = InstructionText.literalValue(literal.text) ?: return null
        if (!ResourceTable.isAnyResourceId(value)) return null
        val resource = index.resources[value.toInt()] ?: return null
        return Triple(resource, literal.start, literal.end)
    }

    fun hover(index: WorkspaceIndex, resource: Resource): Hover {
        if (resource.isFramework) {
            return Hover(MarkupContent(MarkupKind.MARKDOWN, "**Resource:** `${resource.qualifiedName}` (`0x${Integer.toHexString(resource.id)}`)\n\n*Android framework resource* - `android.R.${resource.type}.${resource.name}`"))
        }
        val value = index.resources.valueOf(resource)
        val file = if (value == null) index.resources.fileOf(resource) else null
        val md = buildString {
            append("**Resource:** `${resource.qualifiedName}` (`0x${Integer.toHexString(resource.id)}`)\n\n")
            if (value != null) append("**Value:** ${shown(resource.type, value.value, 200)}\n\n")
            when {
                value != null -> append("*Defined in* `${relative(resource, value.uri)}`")
                file != null -> append("*File* `res/${resource.type}/${file.name}`")
                else -> append("*From* `res/values/public.xml`")
            }
        }
        return Hover(MarkupContent(MarkupKind.MARKDOWN, md))
    }

    /** The value's line, else the resource's file, else its `public.xml` entry; nothing for a framework resource. */
    fun definition(index: WorkspaceIndex, resource: Resource): List<Location> {
        if (resource.isFramework) return emptyList()
        index.resources.valueOf(resource)?.let { return listOf(it.location) }
        index.resources.fileOf(resource)?.let { return listOf(Location(it.toPath().toUri().toString(), Range(Position(0, 0), Position(0, 0)))) }
        return listOf(resource.location)
    }

    /** The value when there is one (name in the tooltip), else the name. */
    fun hint(index: WorkspaceIndex, resource: Resource): InlayHints.Hint {
        val value = index.resources.valueOf(resource)?.value ?: return InlayHints.Hint(resource.qualifiedName, null)
        val tooltip = if (value.length > HINT_LIMIT) "${resource.qualifiedName} = ${shown(resource.type, value, 500)}" else resource.qualifiedName
        return InlayHints.Hint(shown(resource.type, value, HINT_LIMIT), tooltip)
    }

    private fun shown(type: String, value: String, limit: Int): String {
        val text = if (value.length > limit) value.substring(0, limit - 1) + "…" else value
        return if (type == "string") "\"$text\"" else text
    }

    private fun relative(resource: Resource, uri: String): String = "res/values/" + uri.substringAfterLast('/')

    private const val HINT_LIMIT = 40
}
