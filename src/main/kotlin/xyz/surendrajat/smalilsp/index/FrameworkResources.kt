package xyz.surendrajat.smalilsp.index

/**
 * The Android framework's public resource names (`android.R`: package `0x01`), read once from
 * the bundled `android-public.txt`, which `test-tmp/probes/gen_android_public.py` writes from
 * the `public.xml` of the framework apktool ships. A decompiled app spells `android:attr/hint`
 * as `0x1010150`; public ids never change between releases, so one table names them all.
 */
object FrameworkResources {
    private val table: Pair<IntArray, Array<String>> by lazy { load() }

    /** How many ids the table names. */
    val size: Int get() = table.first.size

    /** `type/name` of a public framework id, null for anything else. */
    fun name(id: Int): String? {
        if (id ushr 24 != 0x01) return null
        val (ids, names) = table
        val at = ids.binarySearch(id)
        return if (at >= 0) names[at] else null
    }

    /** The `0x01TTEEEE` shape of a framework id (type byte at least 1). */
    fun isFrameworkId(value: Long): Boolean = value in 0x01010000L..0x01ffffffL

    private fun load(): Pair<IntArray, Array<String>> {
        val ids = ArrayList<Int>(16_000)
        val names = ArrayList<String>(16_000)
        var typeId = -1
        var type = ""
        FrameworkResources::class.java.getResourceAsStream("/android-public.txt")?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                if (line[0] == '[') {
                    val space = line.indexOf(' ')
                    typeId = line.substring(1, space).toInt(16)
                    type = line.substring(space + 1, line.length - 1)
                    continue
                }
                val space = line.indexOf(' ')
                if (space < 0 || typeId < 0) continue
                ids.add((0x01 shl 24) or (typeId shl 16) or line.substring(0, space).toInt(16))
                names.add("$type/${line.substring(space + 1)}")
            }
        }
        // the file is written in id order; sort anyway so a hand edit cannot break the search
        val order = ids.indices.sortedBy { ids[it] }
        return IntArray(ids.size) { ids[order[it]] } to Array(names.size) { names[order[it]] }
    }
}
