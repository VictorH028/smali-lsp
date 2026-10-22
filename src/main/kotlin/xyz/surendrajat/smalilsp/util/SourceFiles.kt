package xyz.surendrajat.smalilsp.util

import java.io.File
import java.nio.file.Files

/** Reading smali files: baksmali writes ASCII (non-ASCII goes out as `\u` escapes), so the usual file decodes as Latin-1, a copy instead of a UTF-8 decode; anything else takes the UTF-8 path. */
object SourceFiles {
    fun read(file: File): String {
        val bytes = Files.readAllBytes(file.toPath())
        for (b in bytes) if (b < 0) return String(bytes, Charsets.UTF_8)
        return String(bytes, Charsets.ISO_8859_1)
    }
}
