package app.pillion.core

import app.pillion.protocol.NaviLiteCodec
import app.pillion.protocol.ServiceType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * A simulated CCU: answers the handshake on every connection and ACKs each IMAGE after [ackDelayMs]
 * (standing in for Bluetooth transfer + dash render time). Records every image it receives.
 */
private class SimulatedDash(
    @Volatile var ackDelayMs: Long = 20,
    private val dropAfterImages: Int = Int.MAX_VALUE,
) : ByteChannel {
    val images = CopyOnWriteArrayList<Long>() // receive timestamps, across all connections
    @Volatile var openCount = 0
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r).apply { isDaemon = true } }
    @Volatile private var inbound = LinkedBlockingQueue<ByteArray>()
    @Volatile private var pending: ByteArray? = null
    @Volatile private var pendingPos = 0
    @Volatile private var closed = true
    @Volatile private var imagesThisConnection = 0

    override fun open() {
        openCount++
        inbound = LinkedBlockingQueue()
        pending = null
        imagesThisConnection = 0
        closed = false
        inbound.put(NaviLiteCodec.build(6, ServiceType.ESN_UPDATE, 0, byteArrayOf()))
        inbound.put(NaviLiteCodec.build(6, ServiceType.SEC_DATA, 1, ByteArray(16) { (it + 1).toByte() }))
    }

    override fun write(bytes: ByteArray) {
        if (closed) throw java.io.IOException("closed")
        if (NaviLiteCodec.serviceTypeAt(bytes, 0) != ServiceType.IMAGE) return
        val payload = NaviLiteCodec.payloadAt(bytes, 0)
        images += System.currentTimeMillis()
        imagesThisConnection++
        if (imagesThisConnection >= dropAfterImages) {
            close() // the dash terminates the link mid-stream
            return
        }
        val ack = NaviLiteCodec.build(6, ServiceType.IMAGE_ACK, 0, byteArrayOf(payload[1], payload[2]))
        val queue = inbound
        timer.schedule({ queue.put(ack) }, ackDelayMs, TimeUnit.MILLISECONDS)
    }

    override fun read(buffer: ByteArray): Int {
        while (true) {
            if (closed) return -1
            val p = pending
            if (p != null && pendingPos < p.size) {
                val n = minOf(buffer.size, p.size - pendingPos)
                p.copyInto(buffer, 0, pendingPos, pendingPos + n)
                pendingPos += n
                return n
            }
            pending = inbound.poll(10, TimeUnit.MILLISECONDS) ?: continue
            pendingPos = 0
        }
    }

    override fun close() { closed = true }

    fun shutdown() { closed = true; timer.shutdownNow() }

    fun imagesBetween(fromMs: Long, toMs: Long) = images.count { it in fromMs until toMs }
}

/** A screen whose content never changes once captured (e.g. stopped at a red light). */
private class StaticScreen(private val knowsSequence: Boolean = true) : ScreenSource {
    val qualities = CopyOnWriteArrayList<Int>()
    override fun start() {}
    override fun latestFrame(): ByteArray = JPEG
    override fun stop() {}
    override fun frameSequence(): Long = if (knowsSequence) 1L else ScreenSource.UNKNOWN_SEQUENCE
    override fun setQuality(quality: Int) { qualities += quality }
}

/** A screen that changes continuously (a moving map). */
private class MovingScreen : ScreenSource {
    val qualities = CopyOnWriteArrayList<Int>()
    override fun start() {}
    override fun latestFrame(): ByteArray = JPEG
    override fun stop() {}
    override fun frameSequence(): Long = System.nanoTime()
    override fun setQuality(quality: Int) { qualities += quality }
}

private val JPEG = ByteArray(2000) { it.toByte() }

private fun waitFor(maxMs: Long = 5000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + maxMs
    while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    return condition()
}

class MirrorStreamingTest {
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest fun tearDown() { cleanups.forEach { runCatching(it) } }

    private fun run(dash: SimulatedDash, screen: ScreenSource, maxFps: Int = 15, adaptiveCeiling: Int? = null): MirrorEngine {
        val job = Job()
        val engine = MirrorEngine(dash, screen, maxFps, adaptiveQualityCeiling = adaptiveCeiling)
        cleanups += { engine.stop(); job.cancel(); dash.shutdown() }
        engine.start(CoroutineScope(job))
        assertTrue(waitFor { dash.images.isNotEmpty() }, "no image reached the dash")
        return engine
    }

    @Test
    fun moving_screen_streams_at_the_fps_cap() {
        val dash = SimulatedDash(ackDelayMs = 20)
        run(dash, MovingScreen(), maxFps = 15)
        val from = System.currentTimeMillis()
        Thread.sleep(2000)
        val sent = dash.imagesBetween(from, from + 2000)
        assertTrue(sent in 24..32, "expected ~30 frames in 2 s at a 15 fps cap, got $sent")
    }

    @Test
    fun static_screen_is_not_resent_at_full_rate_but_keeps_a_slow_refresh() {
        val dash = SimulatedDash(ackDelayMs = 20)
        run(dash, StaticScreen(), maxFps = 15)
        val from = System.currentTimeMillis()
        Thread.sleep(2000)
        val sent = dash.imagesBetween(from, from + 2000)
        assertTrue(sent in 3..6, "expected a ~500 ms refresh (about 4 frames in 2 s), got $sent")
    }

    @Test
    fun sources_without_sequence_keep_the_old_send_every_frame_behaviour() {
        val dash = SimulatedDash(ackDelayMs = 20)
        run(dash, StaticScreen(knowsSequence = false), maxFps = 15)
        val from = System.currentTimeMillis()
        Thread.sleep(2000)
        val sent = dash.imagesBetween(from, from + 2000)
        assertTrue(sent >= 24, "unknown-sequence source must still stream at the cap, got $sent")
    }

    @Test
    fun reconnect_always_resends_the_current_frame_even_if_unchanged() {
        val dash = SimulatedDash(ackDelayMs = 20, dropAfterImages = 2)
        run(dash, StaticScreen(), maxFps = 15)
        // 1st connection: image #1 ACKed, image #2 (the 500 ms refresh) kills the link.
        assertTrue(waitFor(8000) { dash.openCount >= 2 && dash.images.size >= 3 },
            "after a drop the unchanged frame must be sent on the new connection (opens=${dash.openCount}, images=${dash.images.size})")
    }

    @Test
    fun adaptive_quality_steps_down_on_a_slow_link_but_stops_when_it_does_not_help() {
        // ACK time here is fixed (not size-dependent), like a dash that is slow to render.
        val dash = SimulatedDash(ackDelayMs = 150)
        val screen = MovingScreen()
        run(dash, screen, maxFps = 15, adaptiveCeiling = 40)
        assertTrue(waitFor(4000) { screen.qualities.isNotEmpty() }, "quality never adapted")
        Thread.sleep(3000)
        assertEquals(listOf(35), screen.qualities.toList(), "one probe step, then hold")
    }

    @Test
    fun adaptive_quality_is_off_unless_requested() {
        val dash = SimulatedDash(ackDelayMs = 150)
        val screen = MovingScreen()
        run(dash, screen, maxFps = 15)
        Thread.sleep(2500)
        assertFalse(screen.qualities.isNotEmpty(), "quality changed without adaptive mode: ${screen.qualities}")
    }
}
