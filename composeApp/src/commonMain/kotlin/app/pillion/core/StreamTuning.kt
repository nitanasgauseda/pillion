package app.pillion.core

/**
 * Adapts JPEG quality to the Bluetooth link, AIMD-style with a hysteresis band.
 *
 * The user's quality setting is the [ceiling]. The engine reports how long each one-second window's
 * IMAGE_ACKs took on average; if the link can't keep up with the fps cap (ACKs slower than the
 * send interval) quality steps down, and once there's clear headroom again it steps back up — never
 * above the ceiling and never below [floor], so a slow-rendering dash can't push the picture to mush.
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

    /**
     * Feed one measurement window. Returns the new quality if it changed, else null.
     * Windows with fewer than [MIN_FRAMES] ACKs (idle/static screen, timeouts) carry no signal.
     */
    fun onWindow(avgAckMs: Long, frames: Int): Int? {
        if (targetIntervalMs <= 0L || frames < MIN_FRAMES) return null
        val next = when {
            avgAckMs * 100 > targetIntervalMs * SLOW_PERCENT -> maxOf(floor, current - STEP)
            avgAckMs * 100 < targetIntervalMs * FAST_PERCENT -> minOf(ceiling, current + STEP)
            else -> current
        }
        if (next == current) return null
        current = next
        return next
    }

    companion object {
        const val MIN_QUALITY = 15
        const val MAX_DROP = 25
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
