package xyz.surendrajat.smalilsp.index

/**
 * Names of the framework's public resource ids (`android.R`, `0x01…`), from the bundled
 * `android-public.txt` (`./gradlew generateAndroidPublic`). Public ids never change between
 * releases, so one table serves every app.
 */
object FrameworkResources {
    private val table: Pair<IntArray, Array<String>> by lazy { load() }

    val size: Int get() = table.first.size

    /** `type/name`, or null when [id] is not a public framework id. */
    fun name(id: Int): String? {
        if (id ushr 24 != 0x01) return null
        val (ids, names) = table
        val at = ids.binarySearch(id)
        return if (at >= 0) names[at] else null
    }

    /** `0x01TTEEEE`. */
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
        // written in id order; sorted anyway so a hand edit cannot break the binary search
        val order = ids.indices.sortedBy { ids[it] }
        return IntArray(ids.size) { ids[order[it]] } to Array(names.size) { names[order[it]] }
    }
}
