package com.dji.recreate2.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Log rotation.
 *
 * The in-app log was a `StringBuffer` capped at 5000 characters that silently deleted its oldest
 * half and died with the process, so two force-closes during the 2026-08-09 audit left nothing to
 * diagnose from. The rotation order is the part worth pinning: get it wrong and a rename lands on
 * a file that still exists, which loses data instead of preserving it.
 */
class FlightLogTest {

    @Test
    fun `the oldest file is deleted, not renamed`() {
        // If the oldest were renamed it would collide with a file that still exists and the
        // rotation would either fail or overwrite.
        val plan = FlightLog.rotationPlan(maxFiles = 5)

        assertEquals("flight.log.4", plan.first().first)
        assertNull("the oldest must be deleted", plan.first().second)
    }

    @Test
    fun `rotation runs oldest to newest`() {
        val plan = FlightLog.rotationPlan(maxFiles = 5)

        assertEquals(
            listOf(
                "flight.log.4" to null,
                "flight.log.3" to "flight.log.4",
                "flight.log.2" to "flight.log.3",
                "flight.log.1" to "flight.log.2",
                "flight.log" to "flight.log.1"
            ),
            plan
        )
    }

    @Test
    fun `the active file always becomes dot one`() {
        for (max in 2..8) {
            val plan = FlightLog.rotationPlan(maxFiles = max)
            assertEquals("maxFiles=$max", "flight.log" to "flight.log.1", plan.last())
        }
    }

    @Test
    fun `running the plan loses only the oldest file`() {
        // Simulate the rotation over a fake filesystem. This is the property that matters: after
        // one rotation every generation has shifted down by one and exactly one is gone. Reversing
        // the plan order would clobber files that had not moved yet, silently losing the middle.
        val fs = mutableMapOf(
            "flight.log" to "newest",
            "flight.log.1" to "gen1",
            "flight.log.2" to "gen2",
            "flight.log.3" to "gen3",
            "flight.log.4" to "oldest"
        )

        for ((from, to) in FlightLog.rotationPlan(maxFiles = 5)) {
            val content = fs.remove(from) ?: continue
            if (to != null) fs[to] = content
        }

        assertEquals("newest", fs["flight.log.1"])
        assertEquals("gen1", fs["flight.log.2"])
        assertEquals("gen2", fs["flight.log.3"])
        assertEquals("gen3", fs["flight.log.4"])
        assertNull("the active slot is free for the new file", fs["flight.log"])
        assertTrue("only the oldest may be lost", "oldest" !in fs.values)
        assertEquals(4, fs.size)
    }

    @Test
    fun `repeated rotations never grow the file set`() {
        val fs = mutableMapOf("flight.log" to "0")
        repeat(20) { round ->
            for ((from, to) in FlightLog.rotationPlan(maxFiles = 5)) {
                val content = fs.remove(from) ?: continue
                if (to != null) fs[to] = content
            }
            fs["flight.log"] = "${round + 1}"
            assertTrue("round $round kept ${fs.size} files", fs.size <= 5)
        }
    }

    @Test
    fun `the plan keeps exactly the requested number of files`() {
        // One delete plus (maxFiles - 1) renames.
        assertEquals(5, FlightLog.rotationPlan(maxFiles = 5).size)
        assertEquals(3, FlightLog.rotationPlan(maxFiles = 3).size)
    }

    @Test
    fun `a single file configuration just truncates`() {
        val plan = FlightLog.rotationPlan(maxFiles = 1)
        assertEquals(listOf("flight.log" to null), plan)
    }

    @Test
    fun `the limits are big enough to outlast a session`() {
        // A bench session that fills the buffer in minutes would defeat the point. Half a
        // megabyte per file across five files is hours of flight logging.
        assertTrue(FlightLog.MAX_FILE_BYTES >= 128L * 1024)
        assertTrue(FlightLog.MAX_FILES >= 2)
    }

    @Test
    fun `appending without init does not throw`() {
        // A logger that can bring down the app is worse than no logger. This runs on whatever
        // thread happened to call it, including during a crash.
        FlightLog.append("before init")
        assertEquals("", FlightLog.tail())
    }
}
