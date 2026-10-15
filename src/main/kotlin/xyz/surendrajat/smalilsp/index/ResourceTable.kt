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

    /** The `public.xml` entry. */
    val location: Location get() = Location(uri, Range(Position(line, start), Position(line, end)))
}

/** The value a `res/values/<name>.xml` file gives a resource (`<string name="app_name">My App</string>`), at its line. */
class ResourceValue(val value: String, val uri: String, val line: Int, val start: Int, val end: Int) {
    val location: Location get() = Location(uri, Range(Position(line, start), Position(line, end)))
}

/**
 * Resource ids by value. A decompiled app spells every layout, id, string and drawable as a
 * `0x7fXXXXXX` literal; apktool writes their names to `res/values/public.xml`, which is read
 * once per workspace root and again when the file changes, along with the values the default
 * `res/values/<name>.xml` files give strings, colors, dimensions, integers and booleans. Thread-safe.
 */
class ResourceTable {
    private val byId = ConcurrentHashMap<Int, Resource>()
    private val values = ConcurrentHashMap<String, ResourceValue>()   // "type/name"
    private val roots = ConcurrentHashMap<String, File>()              // public.xml uri → project root

    val size: Int get() = byId.size

    operator fun get(id: Int): Resource? = byId[id]

    /** The value a values file gives [resource], when it has one (strings, colors, dimensions, integers, booleans). */
    fun valueOf(resource: Resource): ResourceValue? = values[resource.qualifiedName]

    /** The file a file-backed resource is (`res/layout/activity_main.xml`, `res/drawable/icon.png`), when it exists. */
    fun fileOf(resource: Resource): File? {
        val root = roots[resource.uri] ?: return null
        val dir = File(root, "res/${resource.type}")
        val entries = dir.listFiles() ?: return null
        return entries.firstOrNull { it.isFile && (it.name == resource.name || it.name.startsWith(resource.name + ".")) }
    }

    /**
     * Loads the entries of [publicXml] and the values of the files beside it, replacing what an
     * earlier read of the same project recorded; returns how many ids. A missing file loads nothing.
     */
    fun load(publicXml: File): Int {
        val uri = publicXml.toPath().toUri().toString()
        val root = publicXml.parentFile?.parentFile?.parentFile
        forget(uri)
        if (!publicXml.isFile || root == null) return 0
        roots[uri] = root
        var count = 0
        publicXml.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { lineNumber, line ->
                val entry = parsePublic(line, uri, lineNumber) ?: return@forEachIndexed
                byId[entry.id] = entry
                count++
            }
        }
        val valuesDir = publicXml.parentFile
        for (file in valuesDir.listFiles() ?: emptyArray()) {
            if (file.isFile && file.extension == "xml" && file.name != "public.xml") loadValues(file)
        }
        return count
    }

    /** Forgets every entry read from the project rooted at [root]. */
    fun removeUnder(root: File) {
        val prefix = root.toPath().toUri().toString()
        byId.values.removeIf { it.uri.startsWith(prefix) }
        values.values.removeIf { it.uri.startsWith(prefix) }
        roots.keys.removeIf { it.startsWith(prefix) }
    }

    private fun forget(publicXmlUri: String) {
        byId.values.removeIf { it.uri == publicXmlUri }
        val valuesPrefix = publicXmlUri.removeSuffix("public.xml")
        values.values.removeIf { it.uri.startsWith(valuesPrefix) }
        roots.remove(publicXmlUri)
    }

    private fun parsePublic(line: String, uri: String, lineNumber: Int): Resource? {
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

    /** `<string name="x">value</string>`, `<color name="x">#ff3366</color>`, `<item type="dimen" name="x">4dp</item>`, one per line. */
    private fun loadValues(file: File) {
        val uri = file.toPath().toUri().toString()
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { lineNumber, line ->
                val tagStart = line.indexOf('<')
                if (tagStart < 0 || tagStart + 1 >= line.length || !line[tagStart + 1].isLetter()) return@forEachIndexed
                val tagEnd = line.indexOf(' ', tagStart)
                if (tagEnd < 0) return@forEachIndexed
                val tag = line.substring(tagStart + 1, tagEnd)
                val type = if (tag == "item") attribute(line, "type", tagStart) ?: return@forEachIndexed else tag
                if (type !in VALUE_TYPES) return@forEachIndexed
                val name = attribute(line, "name", tagStart) ?: return@forEachIndexed
                val open = line.indexOf('>', tagEnd)
                if (open < 0 || line[open - 1] == '/') return@forEachIndexed
                val close = line.indexOf("</", open)
                val raw = if (close >= 0) line.substring(open + 1, close) else line.substring(open + 1).trimEnd()
                val nameStart = line.indexOf("name=\"", tagStart) + "name=\"".length
                values["$type/$name"] = ResourceValue(unescape(raw), uri, lineNumber, nameStart, nameStart + name.length)
            }
        }
    }

    private fun attribute(line: String, attribute: String, from: Int): String? {
        val key = "$attribute=\""
        val at = line.indexOf(key, from)
        if (at < 0) return null
        val valueStart = at + key.length
        val valueEnd = line.indexOf('"', valueStart)
        return if (valueEnd < 0) null else line.substring(valueStart, valueEnd)
    }

    private fun unescape(text: String): String =
        if (text.indexOf('&') < 0 && text.indexOf('\\') < 0) text
        else text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("\\'", "'").replace("\\\"", "\"")

    companion object {
        private val VALUE_TYPES = setOf("string", "color", "dimen", "integer", "bool", "fraction")

        /** The ids an app's own resources use: package 0x7f. */
        fun isResourceId(value: Long): Boolean = value in 0x7f000000L..0x7fffffffL

        /** The `public.xml` of an apktool project rooted at [root], whether or not it exists. */
        fun publicXml(root: File): File = File(root, "res/values/public.xml")
    }
}
