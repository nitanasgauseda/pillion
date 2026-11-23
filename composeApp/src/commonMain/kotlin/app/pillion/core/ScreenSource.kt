package app.pillion.core

/**
 * Produces 480x240 JPEG frames of the screen. Pull-based: the engine asks for the most recent
 * frame at its own send rate. The engine depends on this abstraction (DIP); platforms provide it
 * (Android MediaProjection, iOS ReplayKit).
 */
interface ScreenSource {
    fun start()
    /** The most recent screen as a 480x240 JPEG, or null if no frame is available yet. */
    fun latestFrame(): ByteArray?
    fun stop()

    /**
     * Resize output frames to the dash's native size once the session resolves it from the head
     * unit — NaviLite reveals it via the CCU part number at handshake time. Default no-op for
     * sources whose size is fixed elsewhere.
     */
    fun resizeOutput(width: Int, height: Int) {}

    /**
     * A counter that changes whenever [latestFrame] would return different content, or
     * [UNKNOWN_SEQUENCE] when the source can't tell (the engine then sends every frame, as before).
     * The engine reads this *before* [latestFrame], so an implementation must publish a new frame no
     * later than its new sequence number (e.g. swap one immutable holder object).
     */
    fun frameSequence(): Long = UNKNOWN_SEQUENCE

    /** Runtime JPEG quality for adaptive quality. Default: unsupported, ignored. */
    fun setQuality(quality: Int) {}

    companion object {
        const val UNKNOWN_SEQUENCE: Long = -1L
    }
}
