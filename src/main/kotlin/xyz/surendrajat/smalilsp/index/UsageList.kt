package xyz.surendrajat.smalilsp.index

import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * The sites that use one symbol, packed five ints per site: file id, the file's generation
 * when the site was added, line, start column, end column. A site whose file has moved on to
 * a newer generation is stale; readers skip it and [compact] drops it.
 */
internal class UsageList {
    private var data = IntArray(STRIDE * 2)
    private var size = 0

    @Synchronized
    fun add(fileId: Int, generation: Int, line: Int, start: Int, end: Int) {
        if (size + STRIDE > data.size) data = data.copyOf(data.size * 2)
        data[size] = fileId
        data[size + 1] = generation
        data[size + 2] = line
        data[size + 3] = start
        data[size + 4] = end
        size += STRIDE
    }

    /** A copy of the packed entries, stale ones included. */
    @Synchronized
    fun snapshot(): IntArray = data.copyOf(size)

    /** Drops every stale entry; returns how many were dropped. */
    @Synchronized
    fun compact(generation: AtomicIntegerArray): Int {
        var write = 0
        var read = 0
        var dropped = 0
        while (read < size) {
            val fileId = data[read]
            if (fileId < generation.length() && generation.get(fileId) == data[read + 1]) {
                if (write != read) System.arraycopy(data, read, data, write, STRIDE)
                write += STRIDE
            } else {
                dropped++
            }
            read += STRIDE
        }
        size = write
        if (data.size > 2 * size + STRIDE * 2) data = data.copyOf(size + STRIDE * 2)
        return dropped
    }

    companion object {
        const val STRIDE = 5
    }
}
