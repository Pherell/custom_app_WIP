package com.dji.recreate2.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telemetry held while the C2 link is down.
 *
 * `publishTelemetry` used to return immediately when disconnected, so every frame sent during an
 * outage was lost and the C2 track had an unexplained gap. These tests pin the two properties that
 * make a replay useful: it must be bounded, and it must come back in order.
 */
class TelemetryBufferTest {

    @Test
    fun `frames are held while the link is down`() {
        val buffer = TelemetryBuffer(capacity = 10)
        assertTrue(buffer.isEmpty)

        buffer.add("a")
        buffer.add("b")

        assertEquals(2, buffer.size)
        assertFalse(buffer.isEmpty)
    }

    @Test
    fun `the buffer is bounded and drops the oldest`() {
        // THE ALTERNATIVE would be an unbounded queue that grows until the app dies. A tactical
        // track wants recent data more than complete data.
        val buffer = TelemetryBuffer(capacity = 3)
        listOf("1", "2", "3", "4", "5").forEach { buffer.add(it) }

        assertEquals(3, buffer.size)
        assertEquals(2L, buffer.droppedCount)

        // The three NEWEST survived.
        assertEquals(listOf("3", "4", "5"), buffer.drain(10).map { it.payload })
    }

    @Test
    fun `add reports whether anything was dropped`() {
        val buffer = TelemetryBuffer(capacity = 2)
        assertTrue(buffer.add("1"))
        assertTrue(buffer.add("2"))
        assertFalse("the third add must report the loss", buffer.add("3"))
    }

    @Test
    fun `draining returns oldest first`() {
        // Newest-first would draw the track backwards on any consumer that appends as it receives.
        val buffer = TelemetryBuffer(capacity = 10)
        listOf("1", "2", "3", "4").forEach { buffer.add(it) }

        assertEquals(listOf("1", "2", "3", "4"), buffer.drain(10).map { it.payload })
    }

    @Test
    fun `draining takes at most the batch size and leaves the rest in order`() {
        val buffer = TelemetryBuffer(capacity = 10)
        listOf("1", "2", "3", "4", "5").forEach { buffer.add(it) }

        assertEquals(listOf("1", "2"), buffer.drain(2).map { it.payload })
        assertEquals(3, buffer.size)
        assertEquals(listOf("3", "4", "5"), buffer.drain(10).map { it.payload })
    }

    @Test
    fun `draining an empty buffer is harmless`() {
        val buffer = TelemetryBuffer(capacity = 10)
        assertTrue(buffer.drain(10).isEmpty())
        assertTrue(buffer.drain(0).isEmpty())
    }

    @Test
    fun `a failed send goes back to the front in order`() {
        // The replay must survive a mid-batch failure without shuffling the track.
        val buffer = TelemetryBuffer(capacity = 10)
        listOf("1", "2", "3", "4").forEach { buffer.add(it) }

        val batch = buffer.drain(2)          // 1, 2
        buffer.requeueFront(batch)

        assertEquals(listOf("1", "2", "3", "4"), buffer.drain(10).map { it.payload })
    }

    @Test
    fun `requeue cannot overflow the buffer`() {
        val buffer = TelemetryBuffer(capacity = 3)
        listOf("1", "2", "3").forEach { buffer.add(it) }
        val batch = buffer.drain(3)
        listOf("a", "b", "c").forEach { buffer.add(it) }

        buffer.requeueFront(batch)

        assertEquals(3, buffer.size)
        assertTrue("the requeue must be counted as a loss", buffer.droppedCount >= 3L)
    }

    @Test
    fun `the payload is preserved exactly`() {
        // Replayed frames keep their ORIGINAL timestamps so the server plots the real track.
        // Any rewriting here would move the aircraft.
        val buffer = TelemetryBuffer(capacity = 4)
        val frame = """{"drone_id":"D1","timestamp":1690000000123,"location":{"latitude":-6.2}}"""
        buffer.add(frame)

        assertEquals(frame, buffer.drain(1).single().payload)
    }

    @Test
    fun `clearing resets the loss count too`() {
        val buffer = TelemetryBuffer(capacity = 1)
        listOf("1", "2", "3").forEach { buffer.add(it) }
        assertTrue(buffer.droppedCount > 0)

        buffer.clear()

        assertTrue(buffer.isEmpty)
        assertEquals(0L, buffer.droppedCount)
    }

    @Test
    fun `a zero capacity buffer holds nothing and says so`() {
        val buffer = TelemetryBuffer(capacity = 0)
        assertFalse(buffer.add("1"))
        assertTrue(buffer.isEmpty)
        assertEquals(1L, buffer.droppedCount)
    }
}
