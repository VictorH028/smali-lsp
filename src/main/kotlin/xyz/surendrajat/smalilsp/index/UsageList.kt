package xyz.surendrajat.smalilsp.index

import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * The sites that use one symbol, packed four ints per site: file id, line, start column, end
 * column. A file gets a fresh id each time it is indexed and the old id is retired, so a site
 * is stale exactly when its file id is no longer alive; readers skip it and [compact] drops it.
 */
internal class UsageList {
    private var data = EMPTY
    private var size = 0

    @Synchronized
    fun add(fileId: Int, line: Int, start: Int, end: Int) {
        if (size + STRIDE > data.size) data = data.copyOf(maxOf(size + STRIDE * 2, size + size / 2))
        data[size] = fileId
        data[size + 1] = line
        data[size + 2] = start
        data[size + 3] = end
        size += STRIDE
    }

    /** Drops the spare capacity; called once the initial scan is over, when most lists will never grow again. */
    @Synchronized
    fun trim() {
        if (data.size != size) data = if (size == 0) EMPTY else data.copyOf(size)
    }

    /** A copy of the packed entries, stale ones included. */
    @Synchronized
    fun snapshot(): IntArray = data.copyOf(size)

    /** Drops every entry whose file id is not [alive] (non-zero); returns how many were dropped. */
    @Synchronized
    fun compact(alive: AtomicIntegerArray): Int {
        var write = 0
        var read = 0
        var dropped = 0
        while (read < size) {
            val fileId = data[read]
            if (fileId < alive.length() && alive.get(fileId) != 0) {
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
        const val STRIDE = 4
        private val EMPTY = IntArray(0)
    }
}
