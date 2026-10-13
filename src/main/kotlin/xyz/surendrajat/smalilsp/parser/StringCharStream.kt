package xyz.surendrajat.smalilsp.parser

import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.IntStream
import org.antlr.v4.runtime.misc.Interval

/**
 * A [CharStream] over a [String] in UTF-16 code units.
 *
 * ANTLR's `CharStreams.fromString` indexes by Unicode code point, so token offsets and
 * `charPositionInLine` drift from the [String] (and from LSP, whose positions are UTF-16
 * code units) after any supplementary character on a line. This stream keeps every index a
 * plain [String] index: token start/stop map straight onto the source text and columns are
 * what the editor expects. The lexer handles surrogate pairs explicitly.
 */
internal class StringCharStream(
    private val text: String,
    private val name: String = IntStream.UNKNOWN_SOURCE_NAME,
) : CharStream {

    private var position = 0

    override fun consume() {
        check(position < text.length) { "cannot consume EOF" }
        position++
    }

    override fun LA(i: Int): Int {
        if (i == 0) return 0
        val index = if (i < 0) position + i else position + i - 1
        return if (index < 0 || index >= text.length) IntStream.EOF else text[index].code
    }

    override fun mark(): Int = -1

    override fun release(marker: Int) {}

    override fun index(): Int = position

    override fun seek(index: Int) {
        position = index.coerceIn(0, text.length)
    }

    override fun size(): Int = text.length

    override fun getSourceName(): String = name

    override fun getText(interval: Interval): String {
        val start = interval.a
        val stop = minOf(interval.b, text.length - 1)
        return if (start > stop) "" else text.substring(start, stop + 1)
    }

    override fun toString(): String = text
}
