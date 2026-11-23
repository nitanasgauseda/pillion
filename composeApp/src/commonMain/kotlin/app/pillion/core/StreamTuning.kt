package app.pillion.core

/**
 * Adapts JPEG quality to the Bluetooth link, AIMD-style with a hysteresis band.
 *
 * The user's quality setting is the [ceiling]. The engine reports how long each one-second window's
 * IMAGE_ACKs took on average; if the link can't keep up with the fps cap (ACKs slower than the
 * send interval) quality steps down, and once there's clear headroom again it steps back up — never
 * above the ceiling and never below [floor].
 *
 * A step down must pay off: if the ACK time didn't drop by at least [MIN_GAIN_PERCENT] after the
 * previous step, the bottleneck isn't frame size (e.g. a dash that is slow to render), so quality
 * holds instead of sliding to the floor for no fps gain. Any step up re-arms stepping down.
 *
 * Pure logic (no clock, no I/O) so it is unit-tested directly.
 */
class AdaptiveQuality(
    val ceiling: Int,
    private val targetIntervalMs: Long,
    val floor: Int = minOf(ceiling, maxOf(MIN_QUALITY, ceiling - MAX_DROP)),
) {
    var current: Int = ceiling
        private set

    /** ACK time of the window that triggered the last step down, or null if the last move wasn't down. */
    private var ackBeforeLastDrop: Long? = null
    /** Set when a step down didn't reduce ACK time: no further drops until there's headroom again. */
    private var dropsIneffective = false

    /**
     * Feed one measurement window. Returns the new quality if it changed, else null.
     * Windows with fewer than [MIN_FRAMES] ACKs (idle/static screen, timeouts) carry no signal.
     */
    fun onWindow(avgAckMs: Long, frames: Int): Int? {
        if (targetIntervalMs <= 0L || frames < MIN_FRAMES) return null
        val slow = avgAckMs * 100 > targetIntervalMs * SLOW_PERCENT
        val fast = avgAckMs * 100 < targetIntervalMs * FAST_PERCENT
        val before = ackBeforeLastDrop
        if (slow && before != null && avgAckMs * 100 > before * (100 - MIN_GAIN_PERCENT)) {
            dropsIneffective = true // the last drop bought (almost) nothing
        }
        val next = when {
            slow && !dropsIneffective -> maxOf(floor, current - STEP)
            fast -> minOf(ceiling, current + STEP)
            else -> current
        }
        if (fast) dropsIneffective = false
        ackBeforeLastDrop = if (next < current) avgAckMs else if (next > current) null else ackBeforeLastDrop
        if (next == current) return null
        current = next
        return next
    }

    companion object {
        const val MIN_QUALITY = 25
        const val MAX_DROP = 20
        /** A step down must cut the average ACK time by at least this much to justify the next one. */
        const val MIN_GAIN_PERCENT = 5L
        const val STEP = 5
        const val MIN_FRAMES = 3
        /** ACK slower than 115 % of the send interval → link-bound → step down. */
        const val SLOW_PERCENT = 115L
        /** ACK faster than 75 % of the send interval → headroom → step up. */
        const val FAST_PERCENT = 75L
    }
}

/** Reconnect delays: 1.5 s, 3 s, then 5 s while the dash stays unreachable. Reset after a handshake. */
object ReconnectBackoff {
    const val BASE_MS = 1500L
    const val MAX_MS = 5000L

    /** @param attempt 1 for the first retry after a failure. */
    fun delayMs(attempt: Int): Long {
        if (attempt <= 1) return BASE_MS
        var d = BASE_MS
        repeat(minOf(attempt - 1, 8)) { d = minOf(MAX_MS, d * 2) }
        return d
    }
}
