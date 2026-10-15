package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import xyz.surendrajat.smalilsp.core.InstructionScanner
import xyz.surendrajat.smalilsp.core.InstructionText
import xyz.surendrajat.smalilsp.index.Resource
import xyz.surendrajat.smalilsp.index.ResourceTable
import xyz.surendrajat.smalilsp.index.WorkspaceIndex

/**
 * Resource ids where they appear in code: the literal of a `const`, `const/high16` or
 * `const/16` whose value the workspace's `public.xml` names. Literals are not in the AST, so
 * the line is decoded on demand, as registers are. Hover shows the name and the value the
 * values files give it, definition opens the value's line (or the file the resource is), and
 * [InlayHints] puts name and value after the literal.
 */
object ResourceLiterals {

    /** The resource the literal under the caret names, if any. */
    fun at(index: WorkspaceIndex, uri: String, position: Position): Resource? {
        if (index.resources.size == 0) return null
        val line = index.getLineContent(uri, position.line) ?: return null
        return resourceOf(index, line)?.takeIf { (_, literalStart, literalEnd) -> position.character in literalStart..literalEnd }?.first
    }

    /** The resource a line's literal names, with the literal's columns. */
    private fun resourceOf(index: WorkspaceIndex, line: String): Triple<Resource, Int, Int>? {
        val scanner = InstructionScanner()
        if (!scanner.scan(line)) return null
        val literal = scanner.literal ?: return null
        val value = InstructionText.literalValue(literal.text) ?: return null
        if (!ResourceTable.isResourceId(value)) return null
        val resource = index.resources[value.toInt()] ?: return null
        return Triple(resource, literal.start, literal.end)
    }

    fun hover(index: WorkspaceIndex, resource: Resource): Hover {
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

    /** Where the resource is defined: its value's line, else the file it is, else its `public.xml` entry. */
    fun definition(index: WorkspaceIndex, resource: Resource): List<Location> {
        index.resources.valueOf(resource)?.let { return listOf(it.location) }
        index.resources.fileOf(resource)?.let { return listOf(Location(it.toPath().toUri().toString(), Range(Position(0, 0), Position(0, 0)))) }
        return listOf(resource.location)
    }

    /** The inlay hint for a literal naming [resource]: `name` or `name = value`, the whole value as the tooltip when it was cut short. */
    fun hint(index: WorkspaceIndex, resource: Resource): InlayHints.Hint {
        val value = index.resources.valueOf(resource) ?: return InlayHints.Hint(resource.qualifiedName, null)
        return InlayHints.Hint("${resource.qualifiedName} = ${shown(resource.type, value.value, 40)}", value.value.takeIf { it.length > 40 })
    }

    private fun shown(type: String, value: String, limit: Int): String {
        val text = if (value.length > limit) value.substring(0, limit - 1) + "…" else value
        return if (type == "string") "\"$text\"" else text
    }

    private fun relative(resource: Resource, uri: String): String = "res/values/" + uri.substringAfterLast('/')
}
