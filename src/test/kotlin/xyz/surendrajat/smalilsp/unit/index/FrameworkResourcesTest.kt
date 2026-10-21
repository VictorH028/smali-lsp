package xyz.surendrajat.smalilsp.unit.index

import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.FrameworkResources
import xyz.surendrajat.smalilsp.index.ResourceTable
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bundled `android.R` table: public framework ids by name. */
class FrameworkResourcesTest {

    @Test
    fun `names the public framework ids`() {
        assertTrue(FrameworkResources.size > 14_000, "${FrameworkResources.size} entries")
        assertEquals("attr/orientation", FrameworkResources.name(0x010100c4))
        assertEquals("attr/hint", FrameworkResources.name(0x01010150))
        assertEquals("attr/theme", FrameworkResources.name(0x01010000))
        assertEquals("id/text1", FrameworkResources.name(0x01020014))
        assertEquals("style/Theme", FrameworkResources.name(0x01030005))
        assertNull(FrameworkResources.name(0x0101ffff), "not a public id")
        assertNull(FrameworkResources.name(0x7f0a0012), "an app id")
    }

    @Test
    fun `the resource table answers framework ids as resources without a file`() {
        val table = ResourceTable()
        val hint = table[0x01010150]!!
        assertTrue(hint.isFramework)
        assertEquals("android:attr/hint", hint.qualifiedName)
        assertNull(table.valueOf(hint))
        assertNull(table.fileOf(hint))
        assertTrue(table[0x01010150] === hint, "built once")
        assertNull(table[0x7f0a0012], "no project loaded")
        assertEquals(0, table.size, "framework entries do not count as the project's")
        assertTrue(ResourceTable.isAnyResourceId(0x01010150) && ResourceTable.isAnyResourceId(0x7f0a0012) && !ResourceTable.isAnyResourceId(0x3e8) && !ResourceTable.isAnyResourceId(0x01000000))
    }
}
