package com.dji.recreate2.telemetry

/**
 * Holds telemetry while the C2 link is down, so a reconnect backfills the track instead of
 * leaving a hole in it.
 *
 * `publishTelemetry` is QoS 0 with no queue: every frame sent while the broker was unreachable
 * was simply lost, and nothing could recover it afterwards. For a flight record that is a gap
 * with no explanation.
 *
 * **Bounded by COUNT, dropping oldest first.** A tactical track wants recent data more than
 * complete data - if the link has been down long enough to overflow, the newest minutes are the
 * ones worth having. An unbounded queue would instead grow until the app died.
 *
 * Pure state: no Paho, no Android. Testable without a broker.
 */
class TelemetryBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        /** About 2 minutes at 10 Hz. */
        const val DEFAULT_CAPACITY = 1200
    }

    /** One held frame. [payload] is the exact JSON that would have been sent. */
    data class Frame(val payload: String)

    private val frames = ArrayDeque<Frame>()

    /** Frames waiting to be sent. */
    @get:Synchronized
    val size: Int get() = frames.size

    @get:Synchronized
    val isEmpty: Boolean get() = frames.isEmpty()

    /** Total frames dropped for want of room, since the last [clear]. Reported so a gap is known. */
    @Volatile
    var droppedCount: Long = 0L
        private set

    /**
     * Holds a frame, discarding the oldest if the buffer is full.
     *
     * @return true when nothing had to be dropped.
     */
    @Synchronized
    fun add(payload: String): Boolean {
        if (capacity <= 0) {
            droppedCount++
            return false
        }
        var dropped = false
        while (frames.size >= capacity) {
            frames.removeFirst()
            droppedCount++
            dropped = true
        }
        frames.addLast(Frame(payload))
        return !dropped
    }

    /**
     * Takes up to [max] frames, **oldest first**, and removes them.
     *
     * Oldest-first matters: replaying newest-first would draw the track backwards on any consumer
     * that appends as it receives.
     */
    @Synchronized
    fun drain(max: Int): List<Frame> {
        if (max <= 0 || frames.isEmpty()) return emptyList()
        val taken = ArrayList<Frame>(minOf(max, frames.size))
        var n = 0
        while (n < max && frames.isNotEmpty()) {
            taken.add(frames.removeFirst())
            n++
        }
        return taken
    }

    /** Puts frames back at the FRONT when a send failed, so order survives the failure. */
    @Synchronized
    fun requeueFront(returned: List<Frame>) {
        for (frame in returned.asReversed()) {
            if (frames.size >= capacity) {
                droppedCount++
                continue
            }
            frames.addFirst(frame)
        }
    }

    @Synchronized
    fun clear() {
        frames.clear()
        droppedCount = 0L
    }
}
