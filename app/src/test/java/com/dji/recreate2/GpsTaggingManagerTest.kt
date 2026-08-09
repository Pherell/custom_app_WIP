package com.dji.recreate2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinate test that stops the application from closing.
 *
 * Telemetry starts at NaN and stays there until the aircraft has a satellite lock. The tag buttons
 * used to test `droneLat == 0.0`, which a NaN passes, so the NaN reached `JSONObject.put`, which
 * refuses it. Nothing on that path had a catch block, and the application closed.
 */
class GpsTaggingManagerTest {

    @Test
    fun `a normal coordinate can be tagged`() {
        assertTrue(GpsTaggingManager.isTaggable(-6.2088, 106.8456))
        assertTrue(GpsTaggingManager.isTaggable(0.0, 0.0))
        assertTrue(GpsTaggingManager.isTaggable(-90.0, 180.0))
        assertTrue(GpsTaggingManager.isTaggable(90.0, -180.0))
    }

    @Test
    fun `a NaN coordinate is refused`() {
        // THE CRASH. This is the state of droneLat and droneLon before a satellite lock.
        assertFalse(GpsTaggingManager.isTaggable(Double.NaN, Double.NaN))
        assertFalse(GpsTaggingManager.isTaggable(Double.NaN, 106.8456))
        assertFalse(GpsTaggingManager.isTaggable(-6.2088, Double.NaN))
    }

    @Test
    fun `an infinite coordinate is refused`() {
        // org.json refuses an infinite value for the same reason it refuses a NaN.
        assertFalse(GpsTaggingManager.isTaggable(Double.POSITIVE_INFINITY, 0.0))
        assertFalse(GpsTaggingManager.isTaggable(0.0, Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `a coordinate off the Earth is refused`() {
        assertFalse(GpsTaggingManager.isTaggable(91.0, 0.0))
        assertFalse(GpsTaggingManager.isTaggable(-91.0, 0.0))
        assertFalse(GpsTaggingManager.isTaggable(0.0, 181.0))
        assertFalse(GpsTaggingManager.isTaggable(0.0, -181.0))
    }

    @Test
    fun `the old zero test does not find a NaN`() {
        // Why the guard had to change. This is the expression the two dialog buttons used, and it
        // lets the value that closes the application straight through.
        val droneLat = Double.NaN
        val droneLon = Double.NaN

        val oldGuardBlocks = droneLat == 0.0 && droneLon == 0.0
        assertFalse("the old guard never blocked a NaN", oldGuardBlocks)

        assertFalse("the new guard must block it", GpsTaggingManager.isTaggable(droneLat, droneLon))
    }
}
