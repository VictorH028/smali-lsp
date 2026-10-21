package xyz.surendrajat.smalilsp.unit

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.Options
import xyz.surendrajat.smalilsp.parseArgs

class MainArgsTest {

    @Test
    fun `command and options in any order`() {
        assertEquals(Options("lsp", false, null), parseArgs(arrayOf("lsp")))
        assertEquals(Options("mcp", true, null), parseArgs(arrayOf("--verbose", "mcp")))
        assertEquals(Options("lsp", true, "/tmp/smali.log"), parseArgs(arrayOf("lsp", "--log-file", "/tmp/smali.log", "--verbose")))
        assertEquals(Options("help", false, null), parseArgs(emptyArray()))
        assertEquals(Options("--version", false, null), parseArgs(arrayOf("--version")))
    }

    @Test
    fun `malformed options are rejected`() {
        assertNull(parseArgs(arrayOf("lsp", "--log-file")), "a path is required")
        assertNull(parseArgs(arrayOf("lsp", "--log-file", "--verbose")), "an option is not a path")
        assertNull(parseArgs(arrayOf("lsp", "extra")), "only one command")
    }
}
