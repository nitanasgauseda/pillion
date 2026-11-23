package app.pillion

import app.pillion.core.AdaptiveQuality
import app.pillion.core.ByteChannel
import app.pillion.core.FrameReader
import app.pillion.core.ReconnectBackoff
import app.pillion.protocol.NaviLiteCodec
import app.pillion.protocol.ServiceType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Delivers [data] in fixed-size chunks, like a Bluetooth socket handing over partial frames. */
private class ChunkedChannel(private val data: ByteArray, private val chunk: Int) : ByteChannel {
    private var pos = 0
    override fun open() {}
    override fun close() {}
    override fun write(bytes: ByteArray) {}
    override fun read(buffer: ByteArray): Int {
        if (pos >= data.size) return -1
        val n = minOf(chunk, buffer.size, data.size - pos)
        data.copyInto(buffer, 0, pos, pos + n)
        pos += n
        return n
    }
}

class StreamTuningTest {

    @Test
    fun adaptive_quality_steps_down_when_link_bound_and_never_below_floor() {
        val q = AdaptiveQuality(ceiling = 40, targetIntervalMs = 66)
        assertEquals(15, q.floor)
        assertEquals(35, q.onWindow(avgAckMs = 120, frames = 8))
        assertEquals(30, q.onWindow(avgAckMs = 120, frames = 8))
        repeat(10) { q.onWindow(avgAckMs = 200, frames = 5) }
        assertEquals(15, q.current)
        assertNull(q.onWindow(avgAckMs = 200, frames = 5), "already at floor")
    }

    @Test
    fun adaptive_quality_recovers_to_but_not_above_ceiling() {
        val q = AdaptiveQuality(ceiling = 40, targetIntervalMs = 66)
        q.onWindow(120, 8); q.onWindow(120, 8)
        assertEquals(30, q.current)
        assertEquals(35, q.onWindow(avgAckMs = 30, frames = 15))
        assertEquals(40, q.onWindow(avgAckMs = 30, frames = 15))
        assertNull(q.onWindow(avgAckMs = 30, frames = 15), "already at ceiling")
        assertEquals(40, q.current)
    }

    @Test
    fun adaptive_quality_holds_inside_hysteresis_band_and_ignores_idle_windows() {
        val q = AdaptiveQuality(ceiling = 40, targetIntervalMs = 66)
        assertNull(q.onWindow(avgAckMs = 66, frames = 15)) // on target
        assertNull(q.onWindow(avgAckMs = 500, frames = 2)) // static screen: too few ACKs to judge
        assertEquals(40, q.current)
    }

    @Test
    fun adaptive_quality_with_low_ceiling_never_exceeds_user_setting() {
        val q = AdaptiveQuality(ceiling = 10, targetIntervalMs = 66)
        assertEquals(10, q.floor)
        assertNull(q.onWindow(avgAckMs = 500, frames = 10))
        assertNull(q.onWindow(avgAckMs = 1, frames = 10))
        assertEquals(10, q.current)
    }

    @Test
    fun adaptive_quality_is_inert_without_fps_cap() {
        val q = AdaptiveQuality(ceiling = 40, targetIntervalMs = 0)
        assertNull(q.onWindow(avgAckMs = 1000, frames = 10))
    }

    @Test
    fun reconnect_backoff_grows_then_caps() {
        assertEquals(1500, ReconnectBackoff.delayMs(1))
        assertEquals(3000, ReconnectBackoff.delayMs(2))
        assertEquals(5000, ReconnectBackoff.delayMs(3))
        assertEquals(5000, ReconnectBackoff.delayMs(50))
        assertEquals(1500, ReconnectBackoff.delayMs(0))
    }

    @Test
    fun frame_reader_reassembles_frames_split_across_tiny_reads_and_skips_garbage() {
        val big = ByteArray(40_000) { (it * 31).toByte() } // forces buffer growth past 16 KB
        val frames = listOf(
            NaviLiteCodec.build(6, ServiceType.IMAGE_ACK, 0, byteArrayOf(1, 0)),
            NaviLiteCodec.build(6, ServiceType.SEC_DATA, 1, big),
            NaviLiteCodec.build(6, ServiceType.IMAGE_ACK, 0, byteArrayOf(2, 0)),
        )
        val wire = byteArrayOf(0x11, 0x6e, 0x22) + frames[0] + byteArrayOf(0x6e, 0x41) + frames[1] + frames[2]
        for (chunk in listOf(1, 3, 7, 4096)) {
            val reader = FrameReader(ChunkedChannel(wire, chunk))
            val a = reader.next()
            assertEquals(ServiceType.IMAGE_ACK, a.serviceType)
            assertContentEquals(byteArrayOf(1, 0), a.payload)
            val b = reader.next()
            assertEquals(ServiceType.SEC_DATA, b.serviceType)
            assertContentEquals(big, b.payload, "chunk=$chunk")
            val c = reader.next()
            assertContentEquals(byteArrayOf(2, 0), c.payload)
        }
    }

    @Test
    fun frame_reader_handles_many_frames_without_unbounded_buffer_growth() {
        val one = NaviLiteCodec.build(6, ServiceType.IMAGE_ACK, 0, byteArrayOf(7, 0))
        val count = 5000
        val wire = ByteArray(one.size * count)
        for (i in 0 until count) one.copyInto(wire, i * one.size)
        val reader = FrameReader(ChunkedChannel(wire, 1000))
        var n = 0
        repeat(count) { if (reader.next().serviceType == ServiceType.IMAGE_ACK) n++ }
        assertEquals(count, n)
        assertTrue(runCatching { reader.next() }.isFailure, "closed channel must surface as an error")
    }
}
