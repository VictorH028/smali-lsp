package xyz.surendrajat.smalilsp.shared

import org.eclipse.lsp4j.Position
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser

/** Parses [source], indexes it under [uri] and makes it the open document text, as didOpen would. */
fun WorkspaceIndex.openSource(uri: String, source: String): SmaliFile {
    val file = SmaliParser().parse(uri, source) ?: error("failed to parse $uri")
    indexFile(file)
    setDocumentContent(uri, source)
    return file
}

/**
 * Position of [needle] plus [offset] on the first line containing [marker]; with [after], the
 * search starts after that text on the line. Lets a test say "on `Widget` in the `.method` line".
 */
fun String.positionOf(marker: String, needle: String, offset: Int = 0, after: String? = null): Position {
    val lines = lines()
    val line = lines.indexOfFirst { it.contains(marker) }
    require(line >= 0) { "no line contains $marker" }
    val from = lines[line].indexOf(needle, if (after == null) 0 else lines[line].indexOf(after) + after.length)
    require(from >= 0) { "no $needle on line $line" }
    return Position(line, from + offset)
}
