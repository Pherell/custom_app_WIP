package com.dji.recreate2.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tile address building.
 *
 * The important test here is the AXIS ORDER. ArcGIS serves `/{z}/{y}/{x}`; tileserver-gl and OSM
 * serve `/{z}/{x}/{y}`. The map used to hardcode ArcGIS's order, so pointing it at the air-gapped
 * tile server by changing only the host would have drawn correct tiles in the wrong places - a map
 * that still looks like a map, which an operator would trust. That is worse than a blank map.
 */
class TileTemplateTest {

    @Test
    fun `the ArcGIS template keeps y before x`() {
        val url = TileTemplate.build(TileTemplate.ARCGIS_SATELLITE, zoom = 14, x = 100, y = 200)
        assertTrue("got: $url", url!!.endsWith("/14/200/100"))
    }

    @Test
    fun `a standard XYZ template keeps x before y`() {
        val url = TileTemplate.build(TileTemplate.LOCAL_TILESERVER, zoom = 14, x = 100, y = 200)
        assertTrue("got: $url", url!!.endsWith("/14/100/200.png"))
    }

    @Test
    fun `the two orders differ for the same tile`() {
        // THE TRAP, stated directly. If these ever match, the substitution has stopped honouring
        // the template and one of the two servers is being addressed wrongly.
        val arcgis = TileTemplate.build(TileTemplate.ARCGIS_SATELLITE, 14, 100, 200)!!
        val xyz = TileTemplate.build(TileTemplate.LOCAL_TILESERVER, 14, 100, 200)!!

        assertTrue(arcgis.endsWith("/200/100"))
        assertTrue(xyz.endsWith("/100/200.png"))
    }

    @Test
    fun `every placeholder is substituted`() {
        val url = TileTemplate.build("http://h/{z}/{x}/{y}.png", 3, 4, 5)
        assertEquals("http://h/3/4/5.png", url)
        assertFalse(url!!.contains("{"))
    }

    @Test
    fun `a repeated placeholder is substituted everywhere`() {
        assertEquals("a/7/7/1/2", TileTemplate.build("a/{z}/{z}/{x}/{y}", 7, 1, 2))
    }

    @Test
    fun `an incomplete template is refused`() {
        // The caller must fall back to the default rather than request a malformed address once
        // per tile, forever.
        assertNull(TileTemplate.build("http://h/{z}/{x}", 1, 2, 3))
        assertNull(TileTemplate.build("http://h/{z}/{y}", 1, 2, 3))
        assertNull(TileTemplate.build("http://h/no/placeholders", 1, 2, 3))
        assertNull(TileTemplate.build("", 1, 2, 3))
    }

    @Test
    fun `validity matches what build accepts`() {
        assertTrue(TileTemplate.isValid(TileTemplate.ARCGIS_SATELLITE))
        assertTrue(TileTemplate.isValid(TileTemplate.LOCAL_TILESERVER))
        assertFalse(TileTemplate.isValid("http://h/{z}/{x}"))
        assertFalse(TileTemplate.isValid(""))
    }

    @Test
    fun `zoom zero is a single tile and each level doubles`() {
        assertEquals(1, TileTemplate.tilesPerAxis(0))
        assertEquals(2, TileTemplate.tilesPerAxis(1))
        assertEquals(1024, TileTemplate.tilesPerAxis(10))
        assertEquals(1 shl 20, TileTemplate.tilesPerAxis(20))
    }

    @Test
    fun `an absurd zoom cannot overflow the tile count`() {
        // A pre-cache sweep multiplies this by itself. An unbounded shift would go negative and
        // the sweep would either do nothing or run away.
        assertTrue(TileTemplate.tilesPerAxis(99) > 0)
        assertTrue(TileTemplate.tilesPerAxis(-5) > 0)
    }
}
