package com.dji.recreate2.diag

import java.io.File

/**
 * A flight log that survives the process.
 *
 * The in-app log was a `StringBuffer` capped at 5000 characters that deleted its oldest half
 * without saying so and died with the process. Two defects found during the 2026-08-09 audit
 * force-closed the app and left nothing to diagnose from, and `BENCH_TEST_CHECKLIST.md` sends the
 * operator to that same log - which will have discarded the start of a long session by the time
 * they read it.
 *
 * Appends to disk and rotates. The in-memory view stays for the LOG tab, but it is no longer the
 * only copy.
 *
 * The rotation arithmetic is separated from the file work so it can be tested without a device.
 */
object FlightLog {

    /** Rotate once the active file passes this. */
    const val MAX_FILE_BYTES = 512L * 1024

    /** Files kept, newest first, including the active one. */
    const val MAX_FILES = 5

    private const val ACTIVE_NAME = "flight.log"

    @Volatile private var dir: File? = null
    private val lock = Any()

    /** @param logDir where to write. Use the app's own files directory - no permission needed. */
    fun init(logDir: File) {
        synchronized(lock) {
            if (!logDir.exists()) logDir.mkdirs()
            dir = logDir
        }
    }

    fun activeFile(): File? = dir?.let { File(it, ACTIVE_NAME) }

    /**
     * Appends one line. Never throws: a logger that can bring down the app is worse than no
     * logger, and this runs on whatever thread happened to call it.
     */
    fun append(line: String) {
        val target = activeFile() ?: return
        synchronized(lock) {
            try {
                if (target.exists() && target.length() >= MAX_FILE_BYTES) rotate()
                target.appendText(line.trimEnd() + "\n")
            } catch (e: Exception) {
                // Deliberately swallowed. Nothing useful to do, and throwing here would take
                // down whatever was being logged about.
            }
        }
    }

    /** Every log file, newest first. */
    fun files(): List<File> {
        val d = dir ?: return emptyList()
        return (d.listFiles { f -> f.isFile && f.name.startsWith(ACTIVE_NAME) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
    }

    /** The tail of the active file, for the LOG tab. */
    fun tail(maxChars: Int = 20_000): String {
        val target = activeFile() ?: return ""
        return try {
            if (!target.exists()) "" else {
                val text = target.readText()
                if (text.length <= maxChars) text else text.substring(text.length - maxChars)
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * The rename steps of a rotation, in the order they must happen.
     *
     * `to == null` means delete. The order matters: the oldest goes first, so no rename ever
     * lands on a file that still exists.
     *
     * With [MAX_FILES] = 5 this is:
     * `flight.log.4` deleted, `.3`->`.4`, `.2`->`.3`, `.1`->`.2`, `flight.log`->`.1`.
     *
     * [rotate] executes exactly this list, so what the tests check is what runs.
     */
    fun rotationPlan(maxFiles: Int = MAX_FILES): List<Pair<String, String?>> {
        if (maxFiles <= 1) return listOf(ACTIVE_NAME to null)

        val plan = mutableListOf<Pair<String, String?>>()
        plan.add("$ACTIVE_NAME.${maxFiles - 1}" to null)
        for (i in (maxFiles - 2) downTo 1) {
            plan.add("$ACTIVE_NAME.$i" to "$ACTIVE_NAME.${i + 1}")
        }
        plan.add(ACTIVE_NAME to "$ACTIVE_NAME.1")
        return plan
    }

    private fun rotate() {
        val d = dir ?: return
        for ((from, to) in rotationPlan()) {
            val source = File(d, from)
            if (!source.exists()) continue
            if (to == null) source.delete() else source.renameTo(File(d, to))
        }
    }
}
