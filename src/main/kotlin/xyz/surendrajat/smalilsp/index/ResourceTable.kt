package xyz.surendrajat.smalilsp.index

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One `<public type="id" name="login" id="0x7f0a0012" />` entry of an apktool project's `res/values/public.xml`. */
class Resource(val id: Int, val type: String, val name: String, val uri: String, val line: Int, val start: Int, val end: Int) {
    /** `id/login`, the way `R.id.login` reads in smali hovers. */
    val qualifiedName: String get() = "$type/$name"

    val location: Location get() = Location(uri, Range(Position(line, start), Position(line, end)))
}

/**
 * Resource ids by value. A decompiled app spells every layout, id, string and drawable as a
 * `0x7fXXXXXX` literal; apktool writes their names to `res/values/public.xml`, which is read
 * once per workspace root and again when the file changes. Thread-safe.
 */
class ResourceTable {
    private val byId = ConcurrentHashMap<Int, Resource>()

    val size: Int get() = byId.size

    operator fun get(id: Int): Resource? = byId[id]

    /** Loads the entries of [publicXml], replacing what an earlier read of the same file recorded; returns how many. */
    fun load(publicXml: File): Int {
        val uri = publicXml.toPath().toUri().toString()
        byId.values.removeIf { it.uri == uri }
        if (!publicXml.isFile) return 0
        var count = 0
        publicXml.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { lineNumber, line ->
                val entry = parse(line, uri, lineNumber) ?: return@forEachIndexed
                byId[entry.id] = entry
                count++
            }
        }
        return count
    }

    /** Forgets every entry read from files under [root]. */
    fun removeUnder(root: File) {
        val prefix = root.toPath().toUri().toString()
        byId.values.removeIf { it.uri.startsWith(prefix) }
    }

    private fun parse(line: String, uri: String, lineNumber: Int): Resource? {
        val tag = line.indexOf("<public ")
        if (tag < 0) return null
        val type = attribute(line, "type", tag) ?: return null
        val nameStart = line.indexOf("name=\"", tag)
        val name = attribute(line, "name", tag) ?: return null
        val idText = attribute(line, "id", tag) ?: return null
        val id = idText.removePrefix("0x").removePrefix("0X").toLongOrNull(16)?.toInt() ?: return null
        val start = nameStart + "name=\"".length
        return Resource(id, type, name, uri, lineNumber, start, start + name.length)
    }

    private fun attribute(line: String, attribute: String, from: Int): String? {
        val key = "$attribute=\""
        val at = line.indexOf(key, from)
        if (at < 0) return null
        val valueStart = at + key.length
        val valueEnd = line.indexOf('"', valueStart)
        return if (valueEnd < 0) null else line.substring(valueStart, valueEnd)
    }

    companion object {
        /** The ids an app's own resources use: package 0x7f. */
        fun isResourceId(value: Long): Boolean = value in 0x7f000000L..0x7fffffffL

        /** The `public.xml` of an apktool project rooted at [root], whether or not it exists. */
        fun publicXml(root: File): File = File(root, "res/values/public.xml")
    }
}
