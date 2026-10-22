package xyz.surendrajat.smalilsp.unit.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.surendrajat.smalilsp.util.SourceFiles
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The byte reader gives exactly what a UTF-8 read gave, for every kind of content a smali file can hold. */
class SourceFilesTest {

    @TempDir
    lateinit var dir: File

    private fun check(name: String, bytes: ByteArray): String {
        val file = File(dir, name).apply { writeBytes(bytes) }
        val read = SourceFiles.read(file)
        assertEquals(file.readText(Charsets.UTF_8), read, name)
        assertEquals(String(bytes, Charsets.UTF_8), read, "$name against String(bytes, UTF_8)")
        return read
    }

    @Test
    fun `ascii, the common case, takes the latin-1 path and is identical`() {
        val text = ".class public Lcom/A;\n.super Ljava/lang/Object;\n    const-string v0, \"caf\\u00e9\"\r\n    # tab\there\n"
        assertEquals(text, check("ascii.smali", text.toByteArray(Charsets.US_ASCII)))
        assertEquals("", check("empty.smali", ByteArray(0)))
        assertTrue(check("bytes.smali", ByteArray(128) { it.toByte() }).length == 128, "every 7-bit byte is one char")
    }

    @Test
    fun `multi-byte utf-8 in strings, names, comments and astral characters`() {
        val text = ".class public L包/类;\n.source \"Übersicht.java\"\n    const-string v0, \"こんにちは 😀 🇯🇵\"\n    # naïve façade – ‘quotes’\n    :ñ_label\n"
        val read = check("utf8.smali", text.toByteArray(Charsets.UTF_8))
        assertEquals(text, read)
        assertTrue(read.indexOf("😀") > 0 && read.codePointCount(0, read.length) < read.length, "astral characters stay as surrogate pairs, as UTF-16 columns need")
    }

    @Test
    fun `a byte order mark, latin-1 bytes that are not utf-8, and malformed sequences decode as before`() {
        check("bom.smali", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + ".class public Lcom/B;\n".toByteArray())
        check("latin1.smali", ".source \"caf".toByteArray() + byteArrayOf(0xE9.toByte()) + ".java\"\n".toByteArray())
        check("truncated.smali", "x".toByteArray() + byteArrayOf(0xE2.toByte(), 0x82.toByte()) + "\n".toByteArray())
        check("overlong.smali", byteArrayOf(0xC0.toByte(), 0x80.toByte(), 0xFF.toByte(), 0xFE.toByte()) + " end\n".toByteArray())
        check("lone-continuation.smali", byteArrayOf(0x80.toByte(), 0xBF.toByte()) + "\n".toByteArray())
        check("surrogate-bytes.smali", byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()) + "\n".toByteArray())
    }

    @Test
    fun `every byte value alone and in pairs decodes as before`() {
        val all = ByteArray(256) { it.toByte() }
        check("all-bytes.smali", all)
        val pairs = ByteArray(256 * 256 * 2)
        for (a in 0 until 256) for (b in 0 until 256) { val i = (a * 256 + b) * 2; pairs[i] = a.toByte(); pairs[i + 1] = b.toByte() }
        check("all-pairs.smali", pairs)
    }
}
